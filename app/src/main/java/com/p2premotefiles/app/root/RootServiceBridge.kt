package com.p2premotefiles.app.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.p2premotefiles.app.shizuku.IPrivilegedFileService
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 對libsu RootService的單次綁定橋接。
 *
 * 第一次需要root能力時才會嘗試啟動RootService；成功後Binder會被重複使用。
 * 若裝置沒有root或使用者拒絕，該次APP生命週期不會在每個檔案操作重複要求su。
 */
class RootServiceBridge(context: Context) {
    private val appContext = context.applicationContext
    private val serviceFlow = MutableStateFlow<IPrivilegedFileService?>(null)

    @Volatile
    private var bindAttempted = false

    @Volatile
    private var binding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            serviceFlow.value = service?.let(IPrivilegedFileService.Stub::asInterface)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            bindAttempted = false
            serviceFlow.value = null
        }

        override fun onBindingDied(name: ComponentName?) {
            binding = false
            bindAttempted = false
            serviceFlow.value = null
        }

        override fun onNullBinding(name: ComponentName?) {
            binding = false
            serviceFlow.value = null
        }
    }

    suspend fun service(): IPrivilegedFileService? {
        serviceFlow.value?.let { return it }
        if (bindAttempted) return null

        bindAttempted = true
        binding = true
        val intent = Intent(appContext, RootFileService::class.java)
        val started = withContext(Dispatchers.Main.immediate) {
            runCatching {
                RootService.bind(intent, connection)
                true
            }.getOrDefault(false)
        }

        if (!started) {
            binding = false
            return null
        }

        val service = withTimeoutOrNull(ROOT_BIND_TIMEOUT_MS) {
            serviceFlow.filterNotNull().first()
        }
        if (service == null) binding = false
        return service
    }

    fun isConnected(): Boolean = serviceFlow.value != null

    companion object {
        private const val ROOT_BIND_TIMEOUT_MS = 1_500L
    }
}
