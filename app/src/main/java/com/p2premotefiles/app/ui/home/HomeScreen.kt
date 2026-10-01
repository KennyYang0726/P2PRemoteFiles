package com.p2premotefiles.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.p2premotefiles.app.R
import com.p2premotefiles.app.network.lan.LanDiscoveryManager
import com.p2premotefiles.app.network.lan.LanPeer
import com.p2premotefiles.app.network.lan.LanRemoteServer
import com.p2premotefiles.app.network.vps.VpsConnectionManager
import com.p2premotefiles.app.peer.PeerRepository
import com.p2premotefiles.app.peer.RecentConnection
import com.p2premotefiles.app.permission.PermissionSnapshot
import com.p2premotefiles.app.shizuku.ShizukuBridge
import com.p2premotefiles.app.ui.PermissionActions
import com.p2premotefiles.app.util.DisplayFormatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    deviceId: String,
    permissions: PermissionSnapshot,
    permissionActions: PermissionActions,
    shizukuState: ShizukuBridge.State,
    lanPeers: List<LanPeer>,
    lanState: LanDiscoveryManager.State,
    vpsState: VpsConnectionManager.State,
    connecting: Boolean,
    connectionMessage: Int?,
    recentConnections: List<RecentConnection>,
    activeIncomingSessions: List<LanRemoteServer.ActiveSession>,
    onConnectRemote: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onDisconnectIncoming: (String) -> Unit,
    onRequestShizuku: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onScanLan: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var remoteId by remember { mutableStateOf("") }
    var infoMessage by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.my_device),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (deviceId.isBlank()) stringResource(R.string.loading) else DisplayFormatters.deviceId(deviceId),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = stringResource(R.string.device_online),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            when (vpsState.status) {
                                VpsConnectionManager.Status.ONLINE -> R.string.vps_status_online
                                VpsConnectionManager.Status.CONNECTING -> R.string.vps_status_connecting
                                VpsConnectionManager.Status.ID_CONFLICT -> R.string.vps_status_id_conflict
                                VpsConnectionManager.Status.ERROR -> R.string.vps_status_error
                                VpsConnectionManager.Status.DISCONNECTED -> R.string.vps_status_disconnected
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = remoteId,
                        onValueChange = { value -> remoteId = value.filter(Char::isDigit).take(9) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.device_id_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(
                        onClick = {
                            infoMessage = null
                            onConnectRemote(remoteId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting && remoteId.length == 9 && remoteId != deviceId,
                    ) {
                        Text(stringResource(if (connecting) R.string.lan_connecting else R.string.connect))
                    }
                    (connectionMessage ?: infoMessage)?.let { messageRes ->
                        Text(
                            text = stringResource(messageRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            PermissionSummaryCard(
                permissions = permissions,
                shizukuState = shizukuState,
                onStorage = permissionActions.requestStorage,
                onLocalNetwork = permissionActions.requestLocalNetwork,
                onNotifications = permissionActions.requestNotifications,
                onRequestShizuku = onRequestShizuku,
                onRefreshShizuku = onRefreshShizuku,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.local_network_devices),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(
                            onClick = onScanLan,
                            enabled = permissions.localNetworkGranted,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.scan_local_network),
                            )
                        }
                    }

                    when {
                        !permissions.localNetworkGranted -> Text(
                            text = stringResource(R.string.lan_permission_required),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        lanState.error -> Text(
                            text = stringResource(R.string.lan_discovery_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )

                        lanPeers.isEmpty() -> Text(
                            text = stringResource(
                                if (lanState.scanning) R.string.lan_scanning_no_devices else R.string.lan_no_devices,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> lanPeers.forEach { peer ->
                            LanPeerButton(
                                peer = peer,
                                onClick = {
                                    remoteId = peer.deviceId
                                    infoMessage = null
                                    onConnectRemote(peer.deviceId)
                                },
                            )
                        }
                    }
                }
            }

            if (activeIncomingSessions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.active_connections), style = MaterialTheme.typography.titleMedium)
                        activeIncomingSessions.forEach { session ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(DisplayFormatters.deviceId(session.deviceId), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(
                                            if (session.route == LanRemoteServer.SessionRoute.LAN_DIRECT) {
                                                R.string.connection_route_lan
                                            } else {
                                                R.string.connection_route_vps
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onDisconnectIncoming(session.sessionId) }) {
                                    Icon(
                                        imageVector = Icons.Default.LinkOff,
                                        contentDescription = stringResource(R.string.disconnect_device),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.recent_connections), style = MaterialTheme.typography.titleMedium)
                    if (recentConnections.isEmpty()) {
                        Text(
                            stringResource(R.string.no_recent_connections),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        recentConnections.take(6).forEach { recent ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        remoteId = recent.deviceId
                                        onConnectRemote(recent.deviceId)
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !connecting,
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(DisplayFormatters.deviceId(recent.deviceId), fontWeight = FontWeight.SemiBold)
                                        Text(
                                            stringResource(
                                                if (recent.route == PeerRepository.ROUTE_LAN) {
                                                    R.string.connection_route_lan
                                                } else {
                                                    R.string.connection_route_vps
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        DisplayFormatters.dateTime(context, recent.connectedAt).takeIf { it.isNotBlank() }?.let { time ->
                                            Text(
                                                time,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { onRemoveRecent(recent.deviceId) },
                                    enabled = !connecting,
                                ) {
                                    Surface(
                                        modifier = Modifier.size(26.dp),
                                        shape = CircleShape,
                                        color = Color(0xFFD32F2F),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Remove,
                                                contentDescription = stringResource(R.string.remove_recent_connection),
                                                tint = Color.White,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LanPeerButton(
    peer: LanPeer,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = DisplayFormatters.deviceId(peer.deviceId),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.lan_direct_available),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionSummaryCard(
    permissions: PermissionSnapshot,
    shizukuState: ShizukuBridge.State,
    onStorage: () -> Unit,
    onLocalNetwork: () -> Unit,
    onNotifications: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRefreshShizuku: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.permission_setup_title), style = MaterialTheme.typography.titleMedium)

            PermissionRow(
                title = stringResource(R.string.permission_all_files),
                granted = permissions.storageGranted,
                onAction = onStorage,
            )
            PermissionRow(
                title = stringResource(R.string.permission_local_network),
                granted = permissions.localNetworkGranted,
                onAction = onLocalNetwork,
            )
            PermissionRow(
                title = stringResource(R.string.permission_notification),
                granted = permissions.notificationGranted,
                onAction = onNotifications,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.shizuku), fontWeight = FontWeight.Medium)
                    Text(
                        text = when {
                            !shizukuState.binderAvailable -> stringResource(R.string.shizuku_not_running)
                            !shizukuState.supported -> stringResource(R.string.shizuku_unsupported)
                            shizukuState.permissionGranted -> stringResource(R.string.shizuku_running_granted)
                            else -> stringResource(R.string.shizuku_running_not_granted)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (shizukuState.binderAvailable && shizukuState.supported && !shizukuState.permissionGranted) {
                    OutlinedButton(onClick = onRequestShizuku) {
                        Text(stringResource(R.string.permission_request))
                    }
                } else {
                    IconButton(onClick = onRefreshShizuku) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.shizuku_refresh))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                text = stringResource(if (granted) R.string.permission_granted else R.string.permission_denied),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onAction) {
                Text(stringResource(R.string.permission_request))
            }
        }
    }
}
