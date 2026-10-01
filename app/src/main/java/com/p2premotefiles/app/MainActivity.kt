package com.p2premotefiles.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2premotefiles.app.data.preferences.ThemeMode
import com.p2premotefiles.app.ui.AppRoot
import com.p2premotefiles.app.ui.RemoteFilesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RemoteFilesApplication).container
        setContent {
            val themeMode = container.preferences.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            ).value
            RemoteFilesTheme(themeMode = themeMode) {
                AppRoot(container = container)
            }
        }
    }
}
