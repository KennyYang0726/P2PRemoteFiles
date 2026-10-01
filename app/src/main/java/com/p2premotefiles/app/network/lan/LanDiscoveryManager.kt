package com.p2premotefiles.app.network.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class LanDiscoveryManager(context: Context) {
    data class State(
        val advertising: Boolean = false,
        val scanning: Boolean = false,
        val error: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val peerMap = ConcurrentHashMap<String, LanPeer>()
    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var currentDeviceId: String? = null
    private var currentFingerprint: String? = null
    private var currentServicePort: Int? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val pendingResolutions = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var restartDiscoveryAfterStop = false

    @Synchronized
    fun start(deviceId: String, fingerprint: String, servicePort: Int) {
        if (deviceId.isBlank() || servicePort !in 1..65535) return
        if (
            currentDeviceId == deviceId &&
            currentFingerprint == fingerprint &&
            currentServicePort == servicePort &&
            registrationListener != null
        ) {
            if (discoveryListener == null) startDiscoveryInternal(clearPeers = false)
            return
        }

        stop()
        currentDeviceId = deviceId
        currentFingerprint = fingerprint
        currentServicePort = servicePort
        startAdvertising(deviceId, fingerprint, servicePort)
        startDiscoveryInternal(clearPeers = true)
    }

    @Synchronized
    fun refreshDiscovery() {
        if (currentDeviceId == null) return
        peerMap.clear()
        publishPeers()
        pendingResolutions.clear()

        val listener = discoveryListener
        if (listener == null) {
            startDiscoveryInternal(clearPeers = false)
            return
        }

        restartDiscoveryAfterStop = true
        runCatching { nsdManager.stopServiceDiscovery(listener) }
            .onFailure {
                restartDiscoveryAfterStop = false
                discoveryListener = null
                startDiscoveryInternal(clearPeers = false)
            }
    }

    @Synchronized
    fun stop() {
        stopDiscoveryInternal()
        stopAdvertisingInternal()
        multicastLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        multicastLock = null
        currentDeviceId = null
        currentFingerprint = null
        currentServicePort = null
        peerMap.clear()
        pendingResolutions.clear()
        resolving = false
        restartDiscoveryAfterStop = false
        publishPeers()
        _state.value = State()
    }

    private fun startAdvertising(deviceId: String, fingerprint: String, servicePort: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${LanProtocol.SERVICE_NAME_PREFIX}$deviceId"
            serviceType = LanProtocol.SERVICE_TYPE
            port = servicePort
            setAttribute(LanProtocol.ATTRIBUTE_DEVICE_ID, deviceId)
            setAttribute(LanProtocol.ATTRIBUTE_PROTOCOL_VERSION, LanProtocol.VERSION.toString())
            setAttribute(
                LanProtocol.ATTRIBUTE_FINGERPRINT,
                fingerprint.take(LanProtocol.FINGERPRINT_HINT_LENGTH),
            )
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                registrationListener = null
                _state.value = _state.value.copy(advertising = false, error = true)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                _state.value = _state.value.copy(error = true)
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                _state.value = _state.value.copy(advertising = true, error = false)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                _state.value = _state.value.copy(advertising = false)
            }
        }
        registrationListener = listener

        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            registrationListener = null
            _state.value = _state.value.copy(advertising = false, error = true)
        }
    }

    private fun startDiscoveryInternal(clearPeers: Boolean) {
        if (discoveryListener != null) return
        if (clearPeers) {
            peerMap.clear()
            publishPeers()
        }
        acquireMulticastLockIfNeeded()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                discoveryListener = null
                restartDiscoveryAfterStop = false
                _state.value = _state.value.copy(scanning = false, error = true)
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _state.value = _state.value.copy(scanning = false, error = true)
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                _state.value = _state.value.copy(scanning = true, error = false)
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                discoveryListener = null
                _state.value = _state.value.copy(scanning = false)
                if (restartDiscoveryAfterStop) {
                    restartDiscoveryAfterStop = false
                    startDiscoveryInternal(clearPeers = false)
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != LanProtocol.SERVICE_TYPE) return
                enqueueResolution(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peerMap.remove(serviceInfo.serviceName)
                pendingResolutions.removeAll { it.serviceName == serviceInfo.serviceName }
                publishPeers()
            }
        }
        discoveryListener = listener

        runCatching {
            nsdManager.discoverServices(LanProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discoveryListener = null
            _state.value = _state.value.copy(scanning = false, error = true)
        }
    }

    private fun enqueueResolution(serviceInfo: NsdServiceInfo) {
        if (pendingResolutions.any { it.serviceName == serviceInfo.serviceName }) return
        if (peerMap.containsKey(serviceInfo.serviceName)) return
        pendingResolutions.addLast(serviceInfo)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val serviceInfo = pendingResolutions.removeFirstOrNull() ?: return
        resolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                finishResolution()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                try {
                    val attributes = resolved.attributes
                    val deviceId = attributes[LanProtocol.ATTRIBUTE_DEVICE_ID]
                        ?.toString(StandardCharsets.UTF_8)
                        ?.takeIf { it.isNotBlank() }
                        ?: resolved.serviceName.removePrefix(LanProtocol.SERVICE_NAME_PREFIX)
                    if (deviceId.isBlank() || deviceId == currentDeviceId) return

                    val hostAddress = if (Build.VERSION.SDK_INT >= 34) {
                        resolved.hostAddresses.firstOrNull()?.hostAddress ?: resolved.host?.hostAddress
                    } else {
                        resolved.host?.hostAddress
                    } ?: return

                    val version = attributes[LanProtocol.ATTRIBUTE_PROTOCOL_VERSION]
                        ?.toString(StandardCharsets.UTF_8)
                        ?.toIntOrNull()
                        ?: 1
                    val fingerprintHint = attributes[LanProtocol.ATTRIBUTE_FINGERPRINT]
                        ?.toString(StandardCharsets.UTF_8)
                        ?.takeIf { it.isNotBlank() }

                    peerMap[resolved.serviceName] = LanPeer(
                        serviceName = resolved.serviceName,
                        deviceId = deviceId,
                        host = hostAddress,
                        port = resolved.port,
                        protocolVersion = version,
                        fingerprintHint = fingerprintHint,
                    )
                    publishPeers()
                } finally {
                    finishResolution()
                }
            }
        }

        runCatching {
            nsdManager.resolveService(serviceInfo, resolveListener)
        }.onFailure {
            finishResolution()
        }
    }

    private fun finishResolution() {
        resolving = false
        resolveNext()
    }

    private fun stopDiscoveryInternal() {
        restartDiscoveryAfterStop = false
        pendingResolutions.clear()
        resolving = false
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        _state.value = _state.value.copy(scanning = false)
    }

    private fun stopAdvertisingInternal() {
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching { nsdManager.unregisterService(listener) }
        _state.value = _state.value.copy(advertising = false)
    }

    private fun acquireMulticastLockIfNeeded() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun publishPeers() {
        _peers.value = peerMap.values
            .sortedWith(compareBy<LanPeer> { it.deviceId }.thenBy { it.host }.thenBy { it.port })
    }

    companion object {
        private const val MULTICAST_LOCK_TAG = "P2PRemoteFiles:LanDiscovery"
    }
}
