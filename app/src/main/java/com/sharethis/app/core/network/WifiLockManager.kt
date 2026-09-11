package com.sharethis.app.core.network

import android.content.Context
import android.net.wifi.WifiManager

/**
 * High-performance Wi-Fi lock coordinator.
 *
 * Holds [WifiManager.WIFI_MODE_FULL_HIGH_PERF] for the duration of a transfer
 * so OEM power savers can't throttle the radio/CPU mid-stream (a common cause
 * of 5 GHz links collapsing to 2.4 GHz-class speeds when the screen is off).
 *
 * Reference-counted and thread-safe: nested acquire/release pairs from the
 * pairing + transfer layers collapse into a single lock.
 */
class WifiLockManager(context: Context) {

    private val appContext = context.applicationContext
    private var wifiLock: WifiManager.WifiLock? = null
    private var refCount = 0

    @Synchronized
    fun acquire(tag: String = "ShareThis:Transfer") {
        if (wifiLock == null) {
            val wifi =
                appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
                .apply { setReferenceCounted(false) }
        }
        refCount++
        try {
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (_: Exception) {
            // Missing WAKE_LOCK or OEM restriction — transfer still works,
            // just without the throttling guard.
        }
    }

    @Synchronized
    fun release() {
        if (refCount > 0) refCount--
        if (refCount == 0) {
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (_: Exception) { /* ignore */
            }
            wifiLock = null
        }
    }

    @Synchronized
    fun forceRelease() {
        refCount = 0
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) { /* ignore */
        }
        wifiLock = null
    }

    val isHeld: Boolean
        @Synchronized get() = wifiLock?.isHeld == true
}
