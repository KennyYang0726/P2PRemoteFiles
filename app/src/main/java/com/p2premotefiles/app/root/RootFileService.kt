package com.p2premotefiles.app.root

import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.p2premotefiles.app.shizuku.PrivilegedFileService
import com.topjohnwu.superuser.ipc.RootService

/**
 * libsu會用su只負責啟動這個root process；後續檔案操作全部走Binder，
 * 不維持可輸入命令的root shell，也不會每個操作重新呼叫su。
 */
@Keep
class RootFileService : RootService() {
    private val binder by lazy { PrivilegedFileService() }

    override fun onBind(intent: Intent): IBinder = binder
}
