package com.p2premotefiles.app.network.vps

import android.os.SystemClock
import com.p2premotefiles.app.BuildConfig
import com.p2premotefiles.app.app.DeviceIdentityRepository
import com.p2premotefiles.app.network.lan.LanCrypto
import com.p2premotefiles.app.network.lan.LanRemoteServer
import com.p2premotefiles.app.peer.PeerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 只處理裝置註冊、在線狀態、簽章連線要求與VPS Relay協調，不碰Filesystem指令。
 * 前景與無人值守Foreground Service共用同一條WSS。
 */
class VpsConnectionManager(
    private val identity: DeviceIdentityRepository,
    private val httpClient: OkHttpClient,
    private val lanServer: LanRemoteServer,
    private val peers: PeerRepository,
) {
    enum class Status { DISCONNECTED, CONNECTING, ONLINE, ERROR, ID_CONFLICT }
    data class State(val status: Status = Status.DISCONNECTED)

    data class IncomingRequest(
        val requestId: String,
        val deviceId: String,
        val fingerprint: String,
        val identityKey: String,
        val nonce: String,
    )

    data class RelayOffer(
        val sessionId: String,
        val token: String,
        val targetDeviceId: String,
        val targetFingerprint: String,
    )

    private data class ControllerRequest(
        val targetId: String,
        val nonce: String,
        val deferred: CompletableDeferred<RelayOffer>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _incomingRequests = MutableStateFlow<List<IncomingRequest>>(emptyList())
    val incomingRequests: StateFlow<List<IncomingRequest>> = _incomingRequests.asStateFlow()

    private val controllerRequests = ConcurrentHashMap<String, ControllerRequest>()
    private val receivedRequests = ConcurrentHashMap<String, IncomingRequest>()
    private val approvedRequests = ConcurrentHashMap<String, IncomingRequest>()
    private val incomingLock = Any()

    @Volatile private var deviceId: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var retryDelayMs = 1_000L
    @Volatile private var lastPongElapsedMs = 0L
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    @Synchronized
    fun start(id: String) {
        if (id.isBlank()) return
        if (deviceId == id && socket != null) return
        stop()
        deviceId = id
        openSocket(id)
    }

    @Synchronized
    fun stop() {
        deviceId = null
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        lastPongElapsedMs = 0L
        val oldSocket = socket
        socket = null
        runCatching { oldSocket?.close(1000, null) }
        controllerRequests.values.forEach { it.deferred.completeExceptionally(IOException("Signaling stopped")) }
        controllerRequests.clear()
        receivedRequests.clear()
        approvedRequests.clear()
        synchronized(incomingLock) { _incomingRequests.value = emptyList() }
        _state.value = State(Status.DISCONNECTED)
    }

    suspend fun requestRelay(targetId: String): RelayOffer {
        val ownId = deviceId ?: throw VpsConnectionException.Offline
        withTimeout(12_000L) { state.first { it.status == Status.ONLINE } }
        val requestId = UUID.randomUUID().toString()
        val nonce = LanCrypto.encode(LanCrypto.randomNonce())
        val pending = ControllerRequest(targetId, nonce, CompletableDeferred())
        controllerRequests[requestId] = pending
        val payload = VpsProtocol.connectPayload(requestId, ownId, targetId, nonce)
        val message = JSONObject()
            .put("type", "connect_request")
            .put("requestId", requestId)
            .put("targetId", targetId)
            .put("nonce", nonce)
            .put("signature", LanCrypto.encode(identity.sign(payload)))
        try {
            if (!send(message)) throw VpsConnectionException.Offline
            return withTimeout(75_000L) { pending.deferred.await() }
        } finally {
            controllerRequests.remove(requestId)
        }
    }

    fun respond(requestId: String, accepted: Boolean) {
        val request = receivedRequests.remove(requestId) ?: return
        removeIncoming(requestId)
        val ownId = deviceId ?: return
        val responseNonce = LanCrypto.encode(LanCrypto.randomNonce())
        val payload = VpsProtocol.responsePayload(
            request.requestId,
            request.deviceId,
            ownId,
            accepted,
            request.nonce,
            responseNonce,
        )
        if (accepted) approvedRequests[requestId] = request
        val sent = send(
            JSONObject()
                .put("type", "connect_response")
                .put("requestId", requestId)
                .put("accepted", accepted)
                .put("responseNonce", responseNonce)
                .put("signature", LanCrypto.encode(identity.sign(payload))),
        )
        if (!sent) approvedRequests.remove(requestId)
    }

    @Synchronized
    private fun openSocket(id: String) {
        if (deviceId != id) return
        _state.value = State(Status.CONNECTING)
        val request = Request.Builder().url(BuildConfig.VPS_SIGNALING_URL).build()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket || deviceId != id) return
                runCatching { handleMessage(JSONObject(text), id) }
                    .onFailure { webSocket.close(1008, "Protocol error") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnected(webSocket, id)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnected(webSocket, id)
            }
        })
    }

    private fun handleMessage(message: JSONObject, ownId: String) {
        when (message.optString("type")) {
            "challenge" -> {
                val nonce = message.getString("nonce")
                val key = identity.publicKeyEncodedBase64()
                send(
                    JSONObject()
                        .put("type", "hello")
                        .put("version", VpsProtocol.VERSION)
                        .put("deviceId", ownId)
                        .put("identityKey", key)
                        .put("signature", LanCrypto.encode(identity.sign(VpsProtocol.registerPayload(ownId, key, nonce)))),
                )
            }
            "hello_ok" -> {
                retryDelayMs = 1_000L
                lastPongElapsedMs = SystemClock.elapsedRealtime()
                _state.value = State(Status.ONLINE)
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (true) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        val currentSocket = socket ?: break
                        val elapsedSincePong = SystemClock.elapsedRealtime() - lastPongElapsedMs
                        if (elapsedSincePong > HEARTBEAT_TIMEOUT_MS) {
                            currentSocket.cancel()
                            break
                        }
                        if (!send(JSONObject().put("type", "ping"))) {
                            currentSocket.cancel()
                            break
                        }
                    }
                }
            }
            "hello_error" -> {
                if (message.optString("reason") == "id_conflict") {
                    _state.value = State(Status.ID_CONFLICT)
                    deviceId = null // 不以相同ID繼續無限重試；使用者須先重新配置ID。
                } else {
                    _state.value = State(Status.ERROR)
                }
                socket?.close(1008, "Registration rejected")
            }
            "incoming_connect" -> onIncomingConnect(message, ownId)
            "connect_result" -> onConnectResult(message, ownId)
            "relay_offer" -> onTargetRelayOffer(message, ownId)
            "pong" -> lastPongElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun onIncomingConnect(message: JSONObject, ownId: String) {
        val requestId = message.getString("requestId")
        val sourceId = message.getString("sourceId")
        val keyEncoded = message.getString("sourceIdentityKey")
        val nonce = message.getString("nonce")
        if (message.optString("targetId") != ownId || sourceId == ownId) return
        val sourceKey = identity.decodePublicKey(keyEncoded)
        val signature = LanCrypto.decode(message.getString("signature"))
        val payload = VpsProtocol.connectPayload(requestId, sourceId, ownId, nonce)
        if (!identity.verify(sourceKey, payload, signature)) return
        val request = IncomingRequest(requestId, sourceId, identity.fingerprint(sourceKey), keyEncoded, nonce)
        receivedRequests[requestId] = request
        if (peers.autoAcceptPeer(sourceId, request.fingerprint) != null) {
            respond(requestId, true)
        } else {
            synchronized(incomingLock) { _incomingRequests.value = _incomingRequests.value + request }
        }
    }

    private fun onConnectResult(message: JSONObject, ownId: String) {
        val requestId = message.getString("requestId")
        val request = controllerRequests[requestId] ?: return
        when (message.optString("status")) {
            "offline" -> request.deferred.completeExceptionally(VpsConnectionException.TargetOffline)
            "rejected" -> request.deferred.completeExceptionally(VpsConnectionException.Rejected)
            "busy" -> request.deferred.completeExceptionally(VpsConnectionException.Busy)
            "accepted" -> {
                try {
                    val targetId = message.getString("targetId")
                    val responseNonce = message.getString("responseNonce")
                    val requestNonce = message.getString("requestNonce")
                    require(targetId == request.targetId && requestNonce == request.nonce)
                    val targetKey = identity.decodePublicKey(message.getString("targetIdentityKey"))
                    val signature = LanCrypto.decode(message.getString("targetSignature"))
                    val payload = VpsProtocol.responsePayload(
                        requestId, ownId, targetId, true, requestNonce, responseNonce,
                    )
                    require(identity.verify(targetKey, payload, signature))
                    request.deferred.complete(
                        RelayOffer(
                            sessionId = message.getString("sessionId"),
                            token = message.getString("token"),
                            targetDeviceId = targetId,
                            targetFingerprint = identity.fingerprint(targetKey),
                        ),
                    )
                } catch (error: Throwable) {
                    request.deferred.completeExceptionally(VpsConnectionException.IdentityMismatch)
                }
            }
            else -> request.deferred.completeExceptionally(VpsConnectionException.Failed)
        }
    }

    private fun onTargetRelayOffer(message: JSONObject, ownId: String) {
        val requestId = message.getString("requestId")
        val approved = approvedRequests.remove(requestId) ?: return
        if (message.getString("sourceId") != approved.deviceId) return
        val sessionId = message.getString("sessionId")
        val token = message.getString("token")
        scope.launch {
            runCatching {
                val connection = WebSocketByteDuplexConnection.connect(
                    httpClient, sessionId, "target", token,
                )
                if (deviceId == ownId) {
                    lanServer.acceptExternalConnection(
                        connection,
                        ownId,
                        LanRemoteServer.ExpectedPeer(approved.deviceId, approved.fingerprint),
                    )
                } else {
                    connection.close()
                }
            }
        }
    }

    @Synchronized
    private fun onDisconnected(webSocket: WebSocket, id: String) {
        if (webSocket !== socket) return
        socket = null
        heartbeatJob?.cancel()
        lastPongElapsedMs = 0L
        controllerRequests.values.forEach { it.deferred.completeExceptionally(VpsConnectionException.Offline) }
        controllerRequests.clear()
        receivedRequests.clear()
        approvedRequests.clear()
        synchronized(incomingLock) { _incomingRequests.value = emptyList() }
        if (deviceId == id) {
            _state.value = State(Status.ERROR)
            reconnectJob?.cancel()
            val backoff = retryDelayMs
            retryDelayMs = (retryDelayMs * 2L).coerceAtMost(30_000L)
            reconnectJob = scope.launch {
                delay(backoff)
                synchronized(this@VpsConnectionManager) {
                    if (deviceId == id && socket == null) openSocket(id)
                }
            }
        }
    }

    private fun send(message: JSONObject): Boolean = socket?.send(message.toString()) == true

    private fun removeIncoming(requestId: String) {
        synchronized(incomingLock) {
            _incomingRequests.value = _incomingRequests.value.filterNot { it.requestId == requestId }
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        const val HEARTBEAT_TIMEOUT_MS = 70_000L
    }
}

sealed class VpsConnectionException(message: String) : IOException(message) {
    data object Offline : VpsConnectionException("vps_offline")
    data object TargetOffline : VpsConnectionException("target_offline")
    data object Rejected : VpsConnectionException("rejected")
    data object Busy : VpsConnectionException("busy")
    data object IdentityMismatch : VpsConnectionException("identity_mismatch")
    data object Failed : VpsConnectionException("failed")
}
