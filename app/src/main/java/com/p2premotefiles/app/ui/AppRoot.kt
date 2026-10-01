package com.p2premotefiles.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2premotefiles.app.R
import com.p2premotefiles.app.app.AppContainer
import com.p2premotefiles.app.data.preferences.ThemeMode
import com.p2premotefiles.app.permission.PermissionSnapshot
import com.p2premotefiles.app.peer.PeerRepository
import com.p2premotefiles.app.service.RemoteAccessService
import com.p2premotefiles.app.remote.LanConnectionException
import com.p2premotefiles.app.remote.ConnectionRoute
import com.p2premotefiles.app.network.vps.VpsConnectionException
import com.p2premotefiles.app.remote.RemoteFileClient
import com.p2premotefiles.app.ui.browser.RemoteBrowserScreen
import com.p2premotefiles.app.ui.home.HomeScreen
import com.p2premotefiles.app.ui.settings.SettingsScreen
import com.p2premotefiles.app.util.DisplayFormatters
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class AppScreen {
    HOME,
    BROWSER,
    SETTINGS,
}

data class PermissionActions(
    val requestStorage: () -> Unit,
    val requestLocalNetwork: () -> Unit,
    val requestNotifications: () -> Unit,
)

@Composable
fun AppRoot(container: AppContainer) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isForeground by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val permissionState = remember(permissionRefresh) { PermissionSnapshot.read(context) }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRefresh++ }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefresh++ }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefresh++ }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionRefresh++ }

    val requestStorage: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val packageUri = Uri.parse("package:${context.packageName}")
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            runCatching { allFilesLauncher.launch(appIntent) }
                .onFailure { allFilesLauncher.launch(fallbackIntent) }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    val permissionActions = PermissionActions(
        requestStorage = requestStorage as () -> Unit,
        requestLocalNetwork = {
            if (Build.VERSION.SDK_INT >= 37) {
                localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        },
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isForeground = true
                Lifecycle.Event.ON_STOP -> isForeground = false
                Lifecycle.Event.ON_RESUME -> {
                    permissionRefresh++
                    container.shizukuBridge.refresh()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var deviceId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        deviceId = container.preferences.ensureDeviceId()
    }

    val shizukuState by container.shizukuBridge.state.collectAsStateWithLifecycle()
    val thumbnailsEnabled by container.preferences.thumbnailsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val themeMode by container.preferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val lanPeers by container.lanDiscovery.peers.collectAsStateWithLifecycle()
    val lanState by container.lanDiscovery.state.collectAsStateWithLifecycle()
    val incomingRequests by container.lanRemoteServer.incomingRequests.collectAsStateWithLifecycle()
    val vpsIncomingRequests by container.vpsConnection.incomingRequests.collectAsStateWithLifecycle()
    val vpsState by container.vpsConnection.state.collectAsStateWithLifecycle()
    val trustedPeers by container.peers.trustedPeers.collectAsStateWithLifecycle()
    val recentConnections by container.peers.recentConnections.collectAsStateWithLifecycle()
    val activeIncomingSessions by container.lanRemoteServer.activeSessions.collectAsStateWithLifecycle()
    val unattendedEnabled = trustedPeers.any { it.unattended }
    val unattendedServiceEnabled = unattendedEnabled && permissionState.notificationGranted
    var idConflictRetries by remember { mutableIntStateOf(0) }
    LaunchedEffect(vpsState.status) {
        if (vpsState.status == com.p2premotefiles.app.network.vps.VpsConnectionManager.Status.ID_CONFLICT &&
            idConflictRetries < 3
        ) {
            idConflictRetries++
            deviceId = container.preferences.regenerateDeviceId()
        }
    }

    DisposableEffect(deviceId, permissionState.localNetworkGranted, isForeground, unattendedServiceEnabled) {
        if ((isForeground || unattendedServiceEnabled) && deviceId.isNotBlank() && permissionState.localNetworkGranted) {
            val port = runCatching { container.lanRemoteServer.start(deviceId) }.getOrNull()
            if (port != null) {
                container.lanDiscovery.start(
                    deviceId = deviceId,
                    fingerprint = container.identity.publicKeyFingerprint(),
                    servicePort = port,
                )
            }
        } else {
            container.lanDiscovery.stop()
            container.lanRemoteServer.stopLanListener()
        }
        onDispose {
            if (!unattendedServiceEnabled) {
                container.lanDiscovery.stop()
                container.lanRemoteServer.stopLanListener()
            }
        }
    }

    // 前景或明確開啟無人值守時維持WSS。
    DisposableEffect(deviceId, isForeground, unattendedServiceEnabled) {
        if ((isForeground || unattendedServiceEnabled) && deviceId.isNotBlank()) {
            container.vpsConnection.start(deviceId)
        } else {
            container.vpsConnection.stop()
            container.lanRemoteServer.stop()
        }
        onDispose {
            if (!unattendedServiceEnabled) {
                container.vpsConnection.stop()
                container.lanRemoteServer.stop()
            }
        }
    }

    LaunchedEffect(unattendedServiceEnabled) {
        if (unattendedServiceEnabled) {
            RemoteAccessService.start(context)
        } else {
            RemoteAccessService.stop(context)
        }
    }

    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var settingsReturnScreen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var activeClient by remember { mutableStateOf<RemoteFileClient?>(null) }
    var activeRemoteDeviceId by remember { mutableStateOf<String?>(null) }
    var activeRoute by remember { mutableStateOf<ConnectionRoute?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<Int?>(null) }
    var connectJob by remember { mutableStateOf<Job?>(null) }

    fun closeBrowser() {
        activeClient?.close()
        activeClient = null
        activeRemoteDeviceId = null
        activeRoute = null
        screen = AppScreen.HOME
    }

    fun connectTo(targetId: String) {
        connectionMessage = null
        if (connecting || deviceId.isBlank() || targetId.length != 9 || targetId == deviceId) return
        connecting = true
        connectJob = appScope.launch {
            try {
                val result = container.connectionResolver.connect(
                    targetDeviceId = targetId,
                    localDeviceId = deviceId,
                    lanPeers = lanPeers,
                )
                activeClient?.close()
                activeClient = result.client
                activeRemoteDeviceId = result.remoteDeviceId
                activeRoute = result.route
                container.peers.recordRecent(
                    deviceId = result.remoteDeviceId,
                    fingerprint = result.client.remoteFingerprint,
                    route = if (result.route == ConnectionRoute.LAN_DIRECT) PeerRepository.ROUTE_LAN else PeerRepository.ROUTE_VPS,
                )
                connectionMessage = null
                screen = AppScreen.BROWSER
            } catch (error: LanConnectionException) {
                connectionMessage = when (error) {
                    LanConnectionException.Rejected -> R.string.lan_connection_rejected
                    LanConnectionException.IdentityMismatch -> R.string.lan_connection_identity_mismatch
                    LanConnectionException.ProtocolMismatch -> R.string.lan_connection_protocol_mismatch
                    LanConnectionException.HandshakeFailed -> R.string.lan_connection_handshake_failed
                    is LanConnectionException.Unreachable -> R.string.remote_connection_failed
                }
            } catch (error: VpsConnectionException) {
                connectionMessage = when (error) {
                    VpsConnectionException.Offline -> R.string.vps_unavailable
                    VpsConnectionException.TargetOffline -> R.string.vps_target_offline
                    VpsConnectionException.Rejected -> R.string.lan_connection_rejected
                    VpsConnectionException.Busy -> R.string.vps_target_busy
                    VpsConnectionException.IdentityMismatch -> R.string.lan_connection_identity_mismatch
                    VpsConnectionException.Failed -> R.string.remote_connection_failed
                }
            } catch (_: Throwable) {
                connectionMessage = R.string.remote_connection_failed
            } finally {
                connecting = false
                connectJob = null
            }
        }
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            deviceId = deviceId,
            permissions = permissionState,
            permissionActions = permissionActions,
            shizukuState = shizukuState,
            lanPeers = lanPeers,
            lanState = lanState,
            vpsState = vpsState,
            connecting = connecting,
            connectionMessage = connectionMessage,
            recentConnections = recentConnections,
            activeIncomingSessions = activeIncomingSessions,
            onConnectRemote = ::connectTo,
            onRemoveRecent = container.peers::removeRecent,
            onDisconnectIncoming = container.lanRemoteServer::disconnectSession,
            onRequestShizuku = container.shizukuBridge::requestPermission,
            onRefreshShizuku = container.shizukuBridge::refresh,
            onScanLan = container.lanDiscovery::refreshDiscovery,
            onOpenSettings = {
                settingsReturnScreen = AppScreen.HOME
                screen = AppScreen.SETTINGS
            },
        )

        AppScreen.BROWSER -> {
            val client = activeClient
            if (client == null) {
                LaunchedEffect(Unit) { screen = AppScreen.HOME }
            } else {
                RemoteBrowserScreen(
                    client = client,
                    sessionLabel = activeRemoteDeviceId?.let { remoteId ->
                        stringResource(
                            if (activeRoute == ConnectionRoute.VPS_RELAY) R.string.vps_session_label
                            else R.string.lan_session_label,
                            DisplayFormatters.deviceId(remoteId),
                        )
                    } ?: stringResource(R.string.remote_session_title),
                    thumbnailsEnabled = thumbnailsEnabled,
                    onExit = ::closeBrowser,
                    onOpenSettings = {
                        settingsReturnScreen = AppScreen.BROWSER
                        screen = AppScreen.SETTINGS
                    },
                )
            }
        }

        AppScreen.SETTINGS -> {
            BackHandler { screen = settingsReturnScreen }
            SettingsScreen(
                permissions = permissionState,
                permissionActions = permissionActions,
                shizukuState = shizukuState,
                onRequestShizuku = container.shizukuBridge::requestPermission,
                onRefreshShizuku = container.shizukuBridge::refresh,
                thumbnailsEnabled = thumbnailsEnabled,
                onThumbnailsChanged = { enabled ->
                    appScope.launch {
                        container.preferences.setThumbnailsEnabled(enabled)
                    }
                },
                themeMode = themeMode,
                onThemeModeChanged = { mode ->
                    appScope.launch {
                        container.preferences.setThemeMode(mode)
                    }
                },
                trustedPeers = trustedPeers,
                notificationGranted = permissionState.notificationGranted,
                onTrustedPeerChanged = { peer ->
                    container.peers.updatePeer(peer)
                    if (peer.unattended && !permissionState.notificationGranted) {
                        permissionActions.requestNotifications()
                    }
                },
                onRemoveTrustedPeer = container.peers::removeTrusted,
                onBack = { screen = settingsReturnScreen },
            )
        }
    }

    incomingRequests.firstOrNull()?.let { request ->
        var rememberPeer by remember(request.requestId) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { container.lanRemoteServer.respond(request.requestId, false) },
            title = { Text(stringResource(R.string.incoming_connection_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        stringResource(
                            R.string.incoming_connection_message,
                            DisplayFormatters.deviceId(request.deviceId),
                            request.fingerprint.take(16),
                        ),
                    )
                    Row(
                        modifier = Modifier.clickable { rememberPeer = !rememberPeer },
                    ) {
                        Checkbox(checked = rememberPeer, onCheckedChange = { rememberPeer = it })
                        Text(stringResource(R.string.trust_this_device))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (rememberPeer) container.peers.trustPeer(request.deviceId, request.fingerprint)
                    container.lanRemoteServer.respond(request.requestId, true)
                }) {
                    Text(stringResource(R.string.incoming_connection_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { container.lanRemoteServer.respond(request.requestId, false) }) {
                    Text(stringResource(R.string.incoming_connection_reject))
                }
            },
        )
    }

    if (incomingRequests.isEmpty()) {
        vpsIncomingRequests.firstOrNull()?.let { request ->
            var rememberPeer by remember(request.requestId) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { container.vpsConnection.respond(request.requestId, false) },
                title = { Text(stringResource(R.string.incoming_connection_title)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            stringResource(
                                R.string.vps_incoming_connection_message,
                                DisplayFormatters.deviceId(request.deviceId),
                                request.fingerprint.take(16),
                            ),
                        )
                        Row(modifier = Modifier.clickable { rememberPeer = !rememberPeer }) {
                            Checkbox(checked = rememberPeer, onCheckedChange = { rememberPeer = it })
                            Text(stringResource(R.string.trust_this_device))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (rememberPeer) container.peers.trustPeer(request.deviceId, request.fingerprint)
                        container.vpsConnection.respond(request.requestId, true)
                    }) {
                        Text(stringResource(R.string.incoming_connection_accept))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { container.vpsConnection.respond(request.requestId, false) }) {
                        Text(stringResource(R.string.incoming_connection_reject))
                    }
                },
            )
        }
    }

    if (connecting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.connection_waiting_title)) },
            text = { Text(stringResource(R.string.connection_waiting_message)) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    connectJob?.cancel()
                    connectJob = null
                    connecting = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    var showInitialPermissionDialog by rememberSaveable { mutableStateOf(!permissionState.storageGranted) }
    if (showInitialPermissionDialog && !permissionState.storageGranted) {
        AlertDialog(
            onDismissRequest = { showInitialPermissionDialog = false },
            title = { Text(stringResource(R.string.permission_setup_title)) },
            text = { Text(stringResource(R.string.permission_setup_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInitialPermissionDialog = false
                        permissionActions.requestStorage()
                    },
                ) {
                    Text(stringResource(R.string.permission_dialog_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showInitialPermissionDialog = false }) {
                    Text(stringResource(R.string.permission_dialog_later))
                }
            },
        )
    }
}
