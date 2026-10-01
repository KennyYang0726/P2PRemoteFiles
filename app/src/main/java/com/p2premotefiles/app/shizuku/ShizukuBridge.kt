package com.p2premotefiles.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.p2premotefiles.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {
    data class State(
        val binderAvailable: Boolean = false,
        val supported: Boolean = true,
        val permissionGranted: Boolean = false,
        val uid: Int? = null,
    ) {
        val ready: Boolean get() = binderAvailable && supported && permissionGranted
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _service = MutableStateFlow<IPrivilegedFileService?>(null)
    private var binding = false

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedFileService::class.java.name),
    )
        .processNameSuffix("privileged_file")
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)
        .daemon(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            _service.value = binder?.let(IPrivilegedFileService.Stub::asInterface)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            _service.value = null
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
        if (_state.value.ready) bindIfNeeded()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binding = false
        _service.value = null
        refresh()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refresh()
        if (_state.value.ready) bindIfNeeded()
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
        if (_state.value.ready) bindIfNeeded()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    fun refresh() {
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) {
            _state.value = State(binderAvailable = false)
            return
        }

        val supported = runCatching { !Shizuku.isPreV11() }.getOrDefault(false)
        val granted = supported && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = if (granted) runCatching { Shizuku.getUid() }.getOrNull() else null
        _state.value = State(
            binderAvailable = true,
            supported = supported,
            permissionGranted = granted,
            uid = uid,
        )
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        refresh()
        val current = _state.value
        if (!current.binderAvailable || !current.supported || current.permissionGranted) return
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    suspend fun service(): IPrivilegedFileService? {
        refresh()
        if (!_state.value.ready) return null
        _service.value?.let { return it }
        bindIfNeeded()
        return withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
            _service.filterNotNull().first()
        }
    }

    private fun bindIfNeeded() {
        if (!_state.value.ready || _service.value != null || binding) return
        binding = true
        runCatching {
            Shizuku.bindUserService(serviceArgs, connection)
        }.onFailure {
            binding = false
            _service.value = null
        }
    }

    companion object {
        const val REQUEST_CODE = 1001
        private const val SERVICE_BIND_TIMEOUT_MS = 5_000L
    }
}
