package com.p2premotefiles.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.appDataStore by preferencesDataStore(name = "app_preferences")

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

class AppPreferences(private val context: Context) {
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val thumbnailsKey = booleanPreferencesKey("image_thumbnails")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val deviceId: Flow<String?> = context.appDataStore.data.map { it[deviceIdKey] }
    val thumbnailsEnabled: Flow<Boolean> = context.appDataStore.data.map { it[thumbnailsKey] ?: true }
    val themeMode: Flow<ThemeMode> = context.appDataStore.data.map { preferences ->
        preferences[themeModeKey]
            ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    suspend fun ensureDeviceId(): String {
        var current: String? = null
        context.appDataStore.edit { preferences ->
            current = preferences[deviceIdKey]
            if (current == null) {
                current = generateDeviceId()
                preferences[deviceIdKey] = current!!
            }
        }
        return requireNotNull(current)
    }

    /** 只有VPS確認ID已由其他金鑰註冊時才重新分配；一般啟動必須保留原ID。 */
    suspend fun regenerateDeviceId(): String {
        val id = generateDeviceId()
        context.appDataStore.edit { it[deviceIdKey] = id }
        return id
    }

    suspend fun setThumbnailsEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[thumbnailsKey] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appDataStore.edit { it[themeModeKey] = mode.name }
    }

    private fun generateDeviceId(): String {
        val value = 100_000_000 + SecureRandom().nextInt(900_000_000)
        return value.toString()
    }
}
