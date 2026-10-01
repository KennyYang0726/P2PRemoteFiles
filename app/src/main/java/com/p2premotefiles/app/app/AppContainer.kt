package com.p2premotefiles.app.app

import android.content.Context
import com.p2premotefiles.app.data.filesystem.DirectFileBackend
import com.p2premotefiles.app.data.filesystem.HybridFileSystemBackend
import com.p2premotefiles.app.data.filesystem.RootFileBackend
import com.p2premotefiles.app.data.filesystem.ShizukuFileBackend
import com.p2premotefiles.app.data.filesystem.StorageVolumeRepository
import com.p2premotefiles.app.data.filesystem.ThumbnailGenerator
import com.p2premotefiles.app.data.preferences.AppPreferences
import com.p2premotefiles.app.network.lan.LanDiscoveryManager
import com.p2premotefiles.app.network.lan.LanRemoteServer
import com.p2premotefiles.app.network.vps.VpsConnectionManager
import com.p2premotefiles.app.peer.PeerRepository
import com.p2premotefiles.app.remote.RemoteConnectionResolver
import com.p2premotefiles.app.root.RootServiceBridge
import com.p2premotefiles.app.shizuku.ShizukuBridge
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val preferences = AppPreferences(appContext)
    val peers = PeerRepository(appContext)
    val identity = DeviceIdentityRepository()
    val shizukuBridge = ShizukuBridge(appContext)
    val rootServiceBridge = RootServiceBridge(appContext)
    val lanDiscovery = LanDiscoveryManager(appContext)

    private val directBackend = DirectFileBackend()
    private val shizukuBackend = ShizukuFileBackend(shizukuBridge)
    private val rootBackend = RootFileBackend(rootServiceBridge)
    val fileSystem = HybridFileSystemBackend(directBackend, shizukuBackend, rootBackend)
    private val volumes = StorageVolumeRepository(appContext)
    private val thumbnails = ThumbnailGenerator(fileSystem)

    val lanRemoteServer = LanRemoteServer(
        identity = identity,
        backend = fileSystem,
        volumes = volumes,
        thumbnails = thumbnails,
        peers = peers,
    )

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val vpsConnection = VpsConnectionManager(
        identity = identity,
        httpClient = httpClient,
        lanServer = lanRemoteServer,
        peers = peers,
    )

    val connectionResolver = RemoteConnectionResolver(
        identity = identity,
        vps = vpsConnection,
        httpClient = httpClient,
    )
}
