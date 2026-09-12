package com.sharethis.app.core.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Dynamic API-level runtime permission resolver.
 *
 * Only *dangerous* permissions are ever requested at runtime; normal
 * permissions (CHANGE_WIFI_STATE, WAKE_LOCK, …) are manifest-declared and
 * auto-granted. Storage Access Framework Uris need no storage permission at
 * all, so READ/WRITE_EXTERNAL_STORAGE are requested solely for the legacy
 * receive path (API ≤ 28) and direct file:// sends.
 */
object PermissionManager {

    /** Permissions needed to start a hotspot / scan Wi-Fi (receiver + join). */
    fun wifiPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Permissions for the Bluetooth handshake (both directions). */
    fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    fun cameraPermission(): Array<String> = arrayOf(Manifest.permission.CAMERA)

    /**
     * Runtime notification permission (API 33+) for the transfer foreground
     * service notification. Below 33 no runtime grant is needed; if denied
     * on 33+ the service still runs — the progress notification is simply
     * not shown (no dead end).
     */
    fun notificationPermission(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** Only used for legacy (API ≤ 28) receive-to-Downloads. */
    fun legacyWritePermission(): Array<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            emptyArray()
        }

    fun missingPermissions(context: Context, permissions: Array<String>): Array<String> {
        if (permissions.isEmpty()) return emptyArray()
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    fun hasAll(context: Context, permissions: Array<String>): Boolean =
        missingPermissions(context, permissions).isEmpty()

    fun openAppSettings(activity: Activity) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            activity.startActivity(intent)
        } catch (_: Exception) { /* ignore */
        }
    }
}
