package com.p2premotefiles.app.peer

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 遠端Peer的可授權能力。 */
data class PeerCapabilities(
    val browse: Boolean = true,
    val download: Boolean = true,
    val upload: Boolean = true,
    val manage: Boolean = true,
    val delete: Boolean = true,
) {
    companion object {
        val FULL = PeerCapabilities()
    }
}

data class TrustedPeer(
    val deviceId: String,
    val fingerprint: String,
    val capabilities: PeerCapabilities = PeerCapabilities.FULL,
    val unattended: Boolean = false,
    val lockedAccess: Boolean = false,
)

data class RecentConnection(
    val deviceId: String,
    val fingerprint: String,
    val route: String,
    val connectedAt: Long,
)

class PeerRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _trustedPeers = MutableStateFlow(loadTrusted())
    val trustedPeers: StateFlow<List<TrustedPeer>> = _trustedPeers.asStateFlow()

    private val _recentConnections = MutableStateFlow(loadRecent())
    val recentConnections: StateFlow<List<RecentConnection>> = _recentConnections.asStateFlow()

    fun findTrusted(deviceId: String, fingerprint: String): TrustedPeer? = synchronized(lock) {
        _trustedPeers.value.firstOrNull {
            it.deviceId == deviceId && it.fingerprint.equals(fingerprint, ignoreCase = true)
        }
    }

    fun trustPeer(deviceId: String, fingerprint: String): TrustedPeer = synchronized(lock) {
        val existing = _trustedPeers.value.firstOrNull { it.deviceId == deviceId }
        val nextPeer = if (existing != null && existing.fingerprint.equals(fingerprint, ignoreCase = true)) {
            existing
        } else {
            TrustedPeer(deviceId = deviceId, fingerprint = fingerprint)
        }
        val next = (_trustedPeers.value.filterNot { it.deviceId == deviceId } + nextPeer)
            .sortedBy { it.deviceId }
        saveTrusted(next)
        nextPeer
    }

    fun updatePeer(peer: TrustedPeer) = synchronized(lock) {
        val normalized = peer.copy(
            unattended = peer.unattended,
            lockedAccess = peer.lockedAccess && peer.unattended,
        )
        val next = (_trustedPeers.value.filterNot { it.deviceId == normalized.deviceId } + normalized)
            .sortedBy { it.deviceId }
        saveTrusted(next)
    }

    fun removeTrusted(deviceId: String) = synchronized(lock) {
        saveTrusted(_trustedPeers.value.filterNot { it.deviceId == deviceId })
    }

    fun hasUnattendedPeers(): Boolean = _trustedPeers.value.any { it.unattended }

    /**
     * 無人值守只在本次開機已完成第一次解鎖時生效；目前鎖屏則還需要lockedAccess。
     */
    fun autoAcceptPeer(deviceId: String, fingerprint: String): TrustedPeer? {
        val peer = findTrusted(deviceId, fingerprint) ?: return null
        if (!peer.unattended) return null
        val userManager = appContext.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked != true) return null
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true && !peer.lockedAccess) return null
        return peer
    }

    fun recordRecent(deviceId: String, fingerprint: String, route: String) = synchronized(lock) {
        val item = RecentConnection(
            deviceId = deviceId,
            fingerprint = fingerprint,
            route = route,
            connectedAt = System.currentTimeMillis(),
        )
        val next = (listOf(item) + _recentConnections.value.filterNot { it.deviceId == deviceId })
            .take(MAX_RECENTS)
        saveRecent(next)
    }

    fun removeRecent(deviceId: String) = synchronized(lock) {
        saveRecent(_recentConnections.value.filterNot { it.deviceId == deviceId })
    }

    private fun saveTrusted(items: List<TrustedPeer>) {
        _trustedPeers.value = items
        val array = JSONArray()
        items.forEach { peer ->
            array.put(
                JSONObject()
                    .put("deviceId", peer.deviceId)
                    .put("fingerprint", peer.fingerprint)
                    .put("browse", peer.capabilities.browse)
                    .put("download", peer.capabilities.download)
                    .put("upload", peer.capabilities.upload)
                    .put("manage", peer.capabilities.manage)
                    .put("delete", peer.capabilities.delete)
                    .put("unattended", peer.unattended)
                    .put("lockedAccess", peer.lockedAccess),
            )
        }
        prefs.edit().putString(KEY_TRUSTED, array.toString()).apply()
    }

    private fun saveRecent(items: List<RecentConnection>) {
        _recentConnections.value = items
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("deviceId", item.deviceId)
                    .put("fingerprint", item.fingerprint)
                    .put("route", item.route)
                    .put("connectedAt", item.connectedAt),
            )
        }
        prefs.edit().putString(KEY_RECENT, array.toString()).apply()
    }

    private fun loadTrusted(): List<TrustedPeer> = runCatching {
        val raw = prefs.getString(KEY_TRUSTED, null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TrustedPeer(
                        deviceId = item.getString("deviceId"),
                        fingerprint = item.getString("fingerprint"),
                        capabilities = PeerCapabilities(
                            browse = item.optBoolean("browse", true),
                            download = item.optBoolean("download", true),
                            upload = item.optBoolean("upload", true),
                            manage = item.optBoolean("manage", true),
                            delete = item.optBoolean("delete", true),
                        ),
                        unattended = item.optBoolean("unattended", false),
                        lockedAccess = item.optBoolean("lockedAccess", false),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun loadRecent(): List<RecentConnection> = runCatching {
        val raw = prefs.getString(KEY_RECENT, null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RecentConnection(
                        deviceId = item.getString("deviceId"),
                        fingerprint = item.optString("fingerprint"),
                        route = item.optString("route", ROUTE_UNKNOWN),
                        connectedAt = item.optLong("connectedAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.connectedAt }.take(MAX_RECENTS)
    }.getOrDefault(emptyList())

    companion object {
        const val ROUTE_LAN = "LAN_DIRECT"
        const val ROUTE_VPS = "VPS_RELAY"
        const val ROUTE_UNKNOWN = "UNKNOWN"

        private const val PREFS_NAME = "peer_access"
        private const val KEY_TRUSTED = "trusted_peers"
        private const val KEY_RECENT = "recent_connections"
        private const val MAX_RECENTS = 12
    }
}
