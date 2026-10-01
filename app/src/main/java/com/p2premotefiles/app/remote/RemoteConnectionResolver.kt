package com.p2premotefiles.app.remote

import com.p2premotefiles.app.app.DeviceIdentityRepository
import com.p2premotefiles.app.network.lan.LanPeer
import com.p2premotefiles.app.network.vps.VpsConnectionManager
import com.p2premotefiles.app.network.vps.WebSocketByteDuplexConnection
import okhttp3.OkHttpClient

enum class ConnectionRoute {
    LAN_DIRECT,
    VPS_RELAY,
}

data class ResolvedRemoteConnection(
    val client: LanRemoteFileClient,
    val route: ConnectionRoute,
    val remoteDeviceId: String,
)

class RemoteConnectionResolver(
    private val identity: DeviceIdentityRepository,
    private val vps: VpsConnectionManager,
    private val httpClient: OkHttpClient,
) {
    suspend fun connect(
        targetDeviceId: String,
        localDeviceId: String,
        lanPeers: List<LanPeer>,
    ): ResolvedRemoteConnection {
        val lanPeer = lanPeers.firstOrNull { it.deviceId == targetDeviceId }
        if (lanPeer != null) {
            try {
                val client = LanRemoteFileClient.connect(lanPeer, localDeviceId, identity)
                return ResolvedRemoteConnection(client, ConnectionRoute.LAN_DIRECT, targetDeviceId)
            } catch (error: LanConnectionException) {
                // 找到裝置但無法到達（例如Wi-Fi剛切換）時才Fallback到VPS。
                // 身分不符、協定不符或使用者拒絕都不可偷偷改走另一條路。
                if (error !is LanConnectionException.Unreachable) throw error
            }
        }

        val offer = vps.requestRelay(targetDeviceId)
        val tunnel = WebSocketByteDuplexConnection.connect(
            client = httpClient,
            sessionId = offer.sessionId,
            role = "controller",
            token = offer.token,
        )
        val client = LanRemoteFileClient.connect(
            connection = tunnel,
            localDeviceId = localDeviceId,
            identity = identity,
            expectedDeviceId = offer.targetDeviceId,
            expectedFingerprint = offer.targetFingerprint,
        )
        return ResolvedRemoteConnection(client, ConnectionRoute.VPS_RELAY, targetDeviceId)
    }
}
