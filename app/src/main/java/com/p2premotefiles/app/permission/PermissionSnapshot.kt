package com.p2premotefiles.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

data class PermissionSnapshot(
    val storageGranted: Boolean,
    val localNetworkGranted: Boolean,
    val notificationGranted: Boolean,
) {
    companion object {
        fun read(context: Context): PermissionSnapshot {
            val storageGranted = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
                else -> {
                    val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    val write = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    } else {
                        true
                    }
                    read && write
                }
            }

            val localGranted = if (Build.VERSION.SDK_INT >= 37) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            return PermissionSnapshot(storageGranted, localGranted, notificationGranted)
        }
    }
}
