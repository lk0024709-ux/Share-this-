package com.sharethis.app.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sharethis.app.core.engine.FastTransferEngine
import com.sharethis.app.data.models.DevicePeer
import com.sharethis.app.data.models.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * Dual-band hotspot + join coordinator.
 *
 * Strategy per API level:
 *  - API 26+: [WifiManager.startLocalOnlyHotspot] — the only hotspot API
 *    available to non-system apps. On 5 GHz-capable hardware the framework
 *    prefers 5 GHz, which is where 80+ MB/s links come from. (Note:
 *    [android.net.wifi.SoftApConfiguration] band selection via TetheringManager
 *    requires system privileges and is NOT usable by Play-store apps, so we
 *    probe capability + measure the live frequency instead of pretending.)
 *  - API 21-25: best-effort legacy AP via the documented
 *    `setWifiApEnabled` path with graceful manual-setup fallback.
 *
 * Join path:
 *  - API 29+: [WifiNetworkSpecifier] + requestNetwork (no location sideline)
 *  - API 21-28: classic [WifiConfiguration] add/enable network.
 */
class NetworkBandManager(private val context: Context) {

    sealed interface HotspotResult {
        data class Started(val config: NetworkConfig) : HotspotResult
        data class Failed(val reason: String) : HotspotResult
    }

    sealed interface JoinResult {
        data object Connected : JoinResult
        data class Failed(val reason: String) : JoinResult
    }

    companion object {
        const val HOTSPOT_TIMEOUT_MS = 30_000L
        const val JOIN_TIMEOUT_MS = 30_000L
        const val DEFAULT_AP_IP = "192.168.43.1"

        private const val FREQ_5GHZ_MIN = 4900
        private const val FREQ_5GHZ_MAX = 5900
    }

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var joinCallback: ConnectivityManager.NetworkCallback? = null

    // ------------------------------------------------------------- capabilities

    @Suppress("DEPRECATION")
    fun is5GHzSupported(): Boolean {
        return try {
            wifiManager?.is5GHzBandSupported == true
        } catch (_: Exception) {
            false
        }
    }

    /** Live radio frequency in MHz, or null when not associated. */
    fun currentFrequencyMhz(): Int? {
        return try {
            val wifi = wifiManager ?: return null
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo ?: return null
            val frequency = info.frequency
            if (frequency > 0) frequency else null
        } catch (_: Exception) {
            null
        }
    }

    fun currentBand(): String {
        val frequency = currentFrequencyMhz() ?: return DevicePeer.BAND_UNKNOWN
        return if (frequency in FREQ_5GHZ_MIN..FREQ_5GHZ_MAX) {
            DevicePeer.BAND_5GHZ
        } else {
            DevicePeer.BAND_2_4GHZ
        }
    }

    /** Human summary like "5 GHz · 80+ MB/s capable" for the UI badge. */
    fun bandSummary(): String {
        val live = currentBand()
        val capable = is5GHzSupported()
        return when {
            live == DevicePeer.BAND_5GHZ -> "5 GHz · high-throughput link"
            live == DevicePeer.BAND_2_4GHZ && capable -> "2.4 GHz · 5 GHz capable device"
            live == DevicePeer.BAND_2_4GHZ -> "2.4 GHz link"
            capable -> "5 GHz capable"
            else -> "Band unknown"
        }
    }

    fun deviceName(): String = try {
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    } catch (_: Exception) {
        "Android"
    }

    // ---------------------------------------------------------------- hotspot

    /**
     * Starts a local-only hotspot as the receiver. Returns SSID/passphrase/IP
     * that the sender needs (transported via QR / PIN broadcast / Bluetooth).
     */
    suspend fun startHotspot(
        prefer5Ghz: Boolean = true,
        port: Int = FastTransferEngine.DEFAULT_TCP_PORT
    ): HotspotResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startLocalOnlyHotspot(port)
        } else {
            startLegacyHotspot(port)
        }
    }

    private suspend fun startLocalOnlyHotspot(port: Int): HotspotResult {
        val wifi = wifiManager ?: return HotspotResult.Failed("Wi-Fi service unavailable")
        // Local-only hotspots need the radio on.
        try {
            @Suppress("DEPRECATION")
            if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
        } catch (_: Exception) { /* some OEMs ignore this; LOH still works */
        }

        return withTimeoutOrNull(HOTSPOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        if (reservation == null) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    HotspotResult.Failed("Hotspot returned no reservation")
                                )
                            }
                            return
                        }
                        hotspotReservation = reservation
                        val (ssid, passphrase) = hotspotCredentials(reservation)
                        val config = NetworkConfig(
                            ssid = ssid,
                            passphrase = passphrase,
                            ipAddress = findApIpAddress(),
                            port = port,
                            band = if (is5GHzSupported()) DevicePeer.BAND_5GHZ else DevicePeer.BAND_2_4GHZ,
                            deviceName = deviceName()
                        )
                        if (continuation.isActive) {
                            continuation.resume(HotspotResult.Started(config))
                        }
                    }

                    override fun onStopped() {
                        hotspotReservation = null
                    }

                    override fun onFailed(reason: Int) {
                        if (continuation.isActive) {
                            continuation.resume(
                                HotspotResult.Failed(hotspotFailureMessage(reason))
                            )
                        }
                    }
                }
                continuation.invokeOnCancellation { stopHotspot() }
                try {
                    wifi.startLocalOnlyHotspot(callback, handler)
                } catch (e: SecurityException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            HotspotResult.Failed(
                                "Missing nearby-devices permission: ${e.message}"
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(
                            HotspotResult.Failed(e.message ?: "Could not start hotspot")
                        )
                    }
                }
            }
        } ?: HotspotResult.Failed("Hotspot start timed out — enable Location services and retry")
    }

    private fun hotspotCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation
    ): Pair<String, String> {
        // API 30+: SoftApConfiguration (authoritative when present).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val softAp = reservation.softApConfiguration
                val ssid = softAp?.ssid
                val passphrase = softAp?.passphrase
                if (!ssid.isNullOrEmpty()) return ssid to (passphrase ?: "")
            } catch (_: Exception) { /* fall through */
            }
        }
        // API 26-29: WifiConfiguration (deprecated on 29+ but still populated).
        try {
            @Suppress("DEPRECATION")
            val legacy = reservation.wifiConfiguration
            val ssid = legacy?.SSID?.trim('"')
            val passphrase = legacy?.preSharedKey?.trim('"')
            if (!ssid.isNullOrEmpty()) return ssid to (passphrase ?: "")
        } catch (_: Exception) { /* fall through */
        }
        return "" to ""
    }

    private fun hotspotFailureMessage(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "Hotspot disallowed on this device — use manual Wi-Fi join + PIN instead"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Wi-Fi is busy in another mode — toggle Wi-Fi off/on and retry"
        else -> "Hotspot failed (code $reason). Enable Location services and retry"
    }

    /**
     * API 21-25 fallback. Uses the long-standing `setWifiApEnabled` path;
     * wrapped in try/catch because OEMs vary wildly here.
     */
    private suspend fun startLegacyHotspot(port: Int): HotspotResult =
        withContext(Dispatchers.IO) {
            try {
                val wifi = wifiManager ?: return@withContext HotspotResult.Failed("Wi-Fi unavailable")
                @Suppress("DEPRECATION")
                wifi.isWifiEnabled = false
                val ssid = "ShareThis-${(1000..9999).random()}"
                val passphrase = (10000000..99999999).random().toString()
                @Suppress("DEPRECATION")
                val apConfig = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$passphrase\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                }
                val method = wifi.javaClass.getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                val started = method.invoke(wifi, apConfig, true) as? Boolean == true
                if (!started) {
                    return@withContext HotspotResult.Failed(
                        "System blocked legacy hotspot — enable a hotspot manually, then share via PIN"
                    )
                }
                HotspotResult.Started(
                    NetworkConfig(
                        ssid = ssid,
                        passphrase = passphrase,
                        ipAddress = DEFAULT_AP_IP,
                        port = port,
                        band = DevicePeer.BAND_2_4GHZ,
                        deviceName = deviceName()
                    )
                )
            } catch (e: Exception) {
                HotspotResult.Failed(
                    "Legacy hotspot unavailable (${e.message}). " +
                        "Enable a hotspot manually in Settings, then use PIN pairing"
                )
            }
        }

    fun stopHotspot() {
        try {
            hotspotReservation?.close()
        } catch (_: Exception) { /* ignore */
        }
        hotspotReservation = null
        // Best-effort legacy teardown (API 21-25).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                val wifi = wifiManager ?: return
                val method = wifi.javaClass.getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(wifi, null, false)
            } catch (_: Exception) { /* ignore */
            }
        }
    }

    /** Local-only hotspots don't publish their gateway IP — probe interfaces. */
    fun findApIpAddress(): String {
        try {
            val candidates = ArrayList<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return DEFAULT_AP_IP
            for (iface in interfaces) {
                try {
                    if (!iface.isUp || iface.isLoopback) continue
                } catch (_: Exception) {
                    continue
                }
                for (address in iface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        candidates.add(address.hostAddress ?: continue)
                    }
                }
            }
            // Local-only hotspots overwhelmingly use 192.168.43.1.
            candidates.firstOrNull { it == DEFAULT_AP_IP }?.let { return it }
            candidates.firstOrNull { it.startsWith("192.168.") }?.let { return it }
            candidates.firstOrNull { it.startsWith("10.") || it.startsWith("172.") }?.let { return it }
            candidates.firstOrNull()?.let { return it }
        } catch (_: Exception) { /* fall through */
        }
        return DEFAULT_AP_IP
    }

    // ------------------------------------------------------------------- join

    /**
     * Connects the sender to the receiver's hotspot. Must be called after the
     * pairing handshake produced a [NetworkConfig] (QR / Bluetooth payload).
     */
    suspend fun connectToHotspot(config: NetworkConfig): JoinResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                joinViaSpecifier(config)
            } else {
                joinViaConfiguration(config)
            }
        }

    private suspend fun joinViaSpecifier(config: NetworkConfig): JoinResult {
        val manager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return JoinResult.Failed("Connectivity service unavailable")
        return withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val specifier = try {
                    val builder = WifiNetworkSpecifier.Builder()
                        .setSsid(config.ssid)
                    if (!config.isOpen) {
                        if (config.security == NetworkConfig.SECURITY_WPA3 &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ) {
                            builder.setWpa3Passphrase(config.passphrase)
                        } else {
                            builder.setWpa2Passphrase(config.passphrase)
                        }
                    }
                    builder.build()
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(JoinResult.Failed("Bad network spec: ${e.message}"))
                    }
                    return@suspendCancellableCoroutine
                }
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Keep the callback registered for the session duration —
                        // unregistering drops the requested network on API 29+.
                        joinCallback = this
                        if (continuation.isActive) continuation.resume(JoinResult.Connected)
                    }

                    override fun onUnavailable() {
                        if (continuation.isActive) {
                            continuation.resume(
                                JoinResult.Failed("Could not join ${config.ssid} — move closer and retry")
                            )
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    try {
                        manager.unregisterNetworkCallback(callback)
                    } catch (_: Exception) { /* ignore */
                    }
                }
                try {
                    manager.requestNetwork(request, callback)
                } catch (e: SecurityException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            JoinResult.Failed("Missing nearby-devices permission: ${e.message}")
                        )
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(JoinResult.Failed(e.message ?: "Join failed"))
                    }
                }
            }
        } ?: JoinResult.Failed("Join timed out — approve the system Wi-Fi prompt and retry")
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun joinViaConfiguration(config: NetworkConfig): JoinResult =
        withContext(Dispatchers.IO) {
            val wifi = wifiManager ?: return@withContext JoinResult.Failed("Wi-Fi unavailable")
            try {
                if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
                val wifiConfig = WifiConfiguration().apply {
                    SSID = "\"${config.ssid}\""
                    if (config.isOpen) {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    } else {
                        preSharedKey = "\"${config.passphrase}\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                    }
                    status = WifiConfiguration.Status.ENABLED
                }
                val networkId = wifi.addNetwork(wifiConfig)
                if (networkId == -1) {
                    return@withContext JoinResult.Failed("System rejected the network — join it manually in Settings")
                }
                wifi.disconnect()
                wifi.enableNetwork(networkId, true)
                wifi.reconnect()

                val deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val current = wifi.connectionInfo?.ssid?.trim('"')
                    if (current == config.ssid) return@withContext JoinResult.Connected
                    kotlinx.coroutines.delay(500L)
                }
                JoinResult.Failed("Could not join ${config.ssid} — check the password and retry")
            } catch (e: SecurityException) {
                JoinResult.Failed("Missing location permission: ${e.message}")
            } catch (e: Exception) {
                JoinResult.Failed(e.message ?: "Join failed")
            }
        }

    /** Releases a specifier-requested network (API 29+). Safe to call anytime. */
    fun disconnectFromHotspot() {
        val callback = joinCallback ?: return
        joinCallback = null
        try {
            val manager =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) { /* ignore */
        }
    }
}
