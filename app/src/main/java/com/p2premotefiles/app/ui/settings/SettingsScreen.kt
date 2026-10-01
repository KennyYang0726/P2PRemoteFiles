package com.p2premotefiles.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2premotefiles.app.R
import com.p2premotefiles.app.data.preferences.ThemeMode
import com.p2premotefiles.app.permission.PermissionSnapshot
import com.p2premotefiles.app.peer.TrustedPeer
import com.p2premotefiles.app.shizuku.ShizukuBridge
import com.p2premotefiles.app.ui.PermissionActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    permissions: PermissionSnapshot,
    permissionActions: PermissionActions,
    shizukuState: ShizukuBridge.State,
    onRequestShizuku: () -> Unit,
    onRefreshShizuku: () -> Unit,
    thumbnailsEnabled: Boolean,
    onThumbnailsChanged: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    trustedPeers: List<TrustedPeer>,
    notificationGranted: Boolean,
    onTrustedPeerChanged: (TrustedPeer) -> Unit,
    onRemoveTrustedPeer: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
                    ThemeModeSpinner(
                        current = themeMode,
                        onSelect = onThemeModeChanged,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings_file_display), style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_thumbnails), fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.settings_thumbnails_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = thumbnailsEnabled,
                            onCheckedChange = onThumbnailsChanged,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings_permissions), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_all_files_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsPermissionRow(
                        title = stringResource(R.string.permission_all_files),
                        granted = permissions.storageGranted,
                        onRequest = permissionActions.requestStorage,
                    )
                    SettingsPermissionRow(
                        title = stringResource(R.string.permission_local_network),
                        granted = permissions.localNetworkGranted,
                        onRequest = permissionActions.requestLocalNetwork,
                    )
                    SettingsPermissionRow(
                        title = stringResource(R.string.permission_notification),
                        granted = permissions.notificationGranted,
                        onRequest = permissionActions.requestNotifications,
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
                            shizukuState.uid?.let { uid ->
                                Text(
                                    stringResource(R.string.shizuku_uid_format, uid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (shizukuState.binderAvailable && shizukuState.supported && !shizukuState.permissionGranted) {
                            OutlinedButton(onClick = onRequestShizuku) {
                                Text(stringResource(R.string.permission_request))
                            }
                        } else {
                            IconButton(onClick = onRefreshShizuku) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.shizuku_refresh),
                                )
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.trusted_devices), style = MaterialTheme.typography.titleMedium)
                    if (trustedPeers.isEmpty()) {
                        Text(
                            stringResource(R.string.no_trusted_devices),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        trustedPeers.forEach { peer ->
                            TrustedPeerEditor(
                                peer = peer,
                                notificationGranted = notificationGranted,
                                onChanged = onTrustedPeerChanged,
                                onRemove = { onRemoveTrustedPeer(peer.deviceId) },
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.settings_connection), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_connection_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrustedPeerEditor(
    peer: TrustedPeer,
    notificationGranted: Boolean,
    onChanged: (TrustedPeer) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(peer.deviceId.chunked(3).joinToString(" "), fontWeight = FontWeight.SemiBold)
            Text(
                peer.fingerprint.take(20),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PeerSwitchRow(stringResource(R.string.peer_permission_browse), peer.capabilities.browse) {
                onChanged(peer.copy(capabilities = peer.capabilities.copy(browse = it)))
            }
            PeerSwitchRow(stringResource(R.string.peer_permission_download), peer.capabilities.download) {
                onChanged(peer.copy(capabilities = peer.capabilities.copy(download = it)))
            }
            PeerSwitchRow(stringResource(R.string.peer_permission_upload), peer.capabilities.upload) {
                onChanged(peer.copy(capabilities = peer.capabilities.copy(upload = it)))
            }
            PeerSwitchRow(stringResource(R.string.peer_permission_manage), peer.capabilities.manage) {
                onChanged(peer.copy(capabilities = peer.capabilities.copy(manage = it)))
            }
            PeerSwitchRow(stringResource(R.string.peer_permission_delete), peer.capabilities.delete) {
                onChanged(peer.copy(capabilities = peer.capabilities.copy(delete = it)))
            }
            PeerSwitchRow(stringResource(R.string.peer_permission_unattended), peer.unattended) { enabled ->
                onChanged(peer.copy(unattended = enabled, lockedAccess = if (enabled) peer.lockedAccess else false))
            }
            PeerSwitchRow(
                label = stringResource(R.string.peer_permission_locked_access),
                checked = peer.lockedAccess,
                enabled = peer.unattended,
            ) { enabled ->
                onChanged(peer.copy(lockedAccess = enabled))
            }
            if (peer.unattended && !notificationGranted) {
                Text(
                    stringResource(R.string.unattended_notification_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onRemove) {
                Text(stringResource(R.string.remove_trusted_device))
            }
        }
    }
}

@Composable
private fun PeerSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun ThemeModeSpinner(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = when (current) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.settings_theme_mode),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_theme_mode_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(currentLabel),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.settings_theme_mode),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> R.string.theme_mode_system
                        ThemeMode.LIGHT -> R.string.theme_mode_light
                        ThemeMode.DARK -> R.string.theme_mode_dark
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = {
                            Icon(Icons.Default.Palette, contentDescription = null)
                        },
                        onClick = {
                            expanded = false
                            onSelect(mode)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPermissionRow(
    title: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                stringResource(if (granted) R.string.permission_granted else R.string.permission_denied),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onRequest) {
                Text(stringResource(R.string.permission_request))
            }
        }
    }
}
