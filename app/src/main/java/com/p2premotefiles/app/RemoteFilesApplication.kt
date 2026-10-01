package com.p2premotefiles.app

import android.app.Application
import com.p2premotefiles.app.app.AppContainer

class RemoteFilesApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.identity.ensureKey()
        container.shizukuBridge.start()
    }
}
