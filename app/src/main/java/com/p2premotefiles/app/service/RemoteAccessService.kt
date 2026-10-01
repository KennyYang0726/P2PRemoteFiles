package com.p2premotefiles.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.p2premotefiles.app.MainActivity
import com.p2premotefiles.app.R
import com.p2premotefiles.app.RemoteFilesApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 使用者明確為至少一台受信任Peer開啟「無人值守」後才啟動。
 * 以connectedDevice前景服務維持VPS Presence與LAN listener，讓熄螢幕/切到背景後仍可被連線。
 */
class RemoteAccessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val container by lazy { (application as RemoteFilesApplication).container }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.remote_access_notification_title))
            .setContentText(getString(R.string.remote_access_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!container.peers.hasUnattendedPeers()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startJob?.cancel()
        startJob = scope.launch {
            val deviceId = container.preferences.ensureDeviceId()
            container.vpsConnection.start(deviceId)
            if (hasLocalNetworkPermission()) {
                val port = runCatching { container.lanRemoteServer.start(deviceId) }.getOrNull()
                if (port != null) {
                    container.lanDiscovery.start(
                        deviceId = deviceId,
                        fingerprint = container.identity.publicKeyFingerprint(),
                        servicePort = port,
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        startJob?.cancel()
        if (!container.peers.hasUnattendedPeers()) {
            container.lanDiscovery.stop()
            container.lanRemoteServer.stop()
            container.vpsConnection.stop()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.remote_access_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.remote_access_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "remote_access"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteAccessService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteAccessService::class.java))
        }
    }
}
