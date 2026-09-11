package com.sharethis.app.core.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Legacy WRITE_SETTINGS coordinator (API 23–25).
 *
 * Only the legacy hotspot toggle path on Android 6.0–7.1 ever consults this.
 * Android 8+ uses startLocalOnlyHotspot() which needs no system-settings
 * grant, and the WRITE_SETTINGS permission is inert there.
 */
object SystemSettingsHelper {

    /**
     * True when the device is in the legacy window AND the grant is missing.
     */
    fun needsWriteSettingsGrant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.M..Build.VERSION_CODES.N_MR1) {
            return false
        }
        return try {
            !Settings.System.canWrite(context)
        } catch (_: Exception) {
            false
        }
    }

    fun canWrite(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            Settings.System.canWrite(context)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Opens the system "modify settings" grant screen. The caller should
     * re-check [canWrite] in onResume — there is no result callback.
     */
    fun requestWriteSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
            } catch (_: Exception) { /* no handler; nothing more to do */
            }
        }
    }
}
