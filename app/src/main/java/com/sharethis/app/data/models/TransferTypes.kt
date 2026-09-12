package com.sharethis.app.data.models

/**
 * Centralized transfer error taxonomy (pure JVM — the UI maps these to
 * human-readable strings, the engine maps exceptions to these categories).
 *
 * Every error carries: a user-facing message, whether a retry makes sense,
 * and a technical tag for structured logging. Raw stack traces are never
 * shown to users.
 */
enum class ErrorCategory(
    val userMessage: String,
    val retryable: Boolean
) {
    NETWORK_UNAVAILABLE("No network connection available. Check Wi-Fi or hotspot and try again.", true),
    PAIRING_TIMEOUT("Pairing timed out. Make sure both devices are nearby and try again.", true),
    AUTHENTICATION_FAILED("Pairing failed — the PIN or session did not match. Start a fresh session on the receiver.", false),
    PERMISSION_DENIED("A required permission was denied. You can grant it or use another pairing method.", false),
    STORAGE_ACCESS_FAILED("Could not access storage. The file location may be unavailable.", true),
    REMOTE_DISCONNECTED("The other device disconnected.", true),
    TRANSFER_CORRUPTED("Data was corrupted in transit. The transfer will be resumed or retried.", true),
    INSUFFICIENT_STORAGE("Not enough storage space to receive these files.", false),
    FILE_NOT_FOUND("A selected file is no longer available on the sender.", false),
    UNSUPPORTED_PROTOCOL("These devices run incompatible ShareThis versions. Update ShareThis on both devices.", false),
    HOTSPOT_UNAVAILABLE("Could not start a hotspot automatically. Enable it manually in Settings and continue.", false),
    BLUETOOTH_UNAVAILABLE("Bluetooth is unavailable or turned off.", false),
    VERIFICATION_FAILED("File verification failed — the received file does not match the original.", false),
    CANCELLED_BY_USER("Transfer cancelled.", false),
    STORAGE_UNAVAILABLE("Storage became unavailable during the transfer.", true),
    UNKNOWN_ERROR("Something went wrong. Please try again.", true)
}

/** What to do when the destination already contains a file with the same name. */
enum class DuplicatePolicy {
    ASK,          // query the user via callback (falls back to KEEP_BOTH on timeout)
    KEEP_BOTH,    // auto-rename "photo (1).jpg" — the safe default
    REPLACE,      // overwrite the existing file
    SKIP          // do not write this file, mark it skipped
}

/**
 * Connection endpoint produced by every pairing transport
 * (QR / Bluetooth / PIN / manual entry).
 */
data class ConnectTarget(
    val host: String,
    val port: Int,
    val sessionId: Long,
    val transferId: Int = 0,
    val receiverPublicKey: ByteArray? = null, // pre-shared via QR/Bluetooth (authenticated OOB)
    val receiverChallenge: ByteArray? = null,
    val authMode: String = AuthMode.PIN,
    val pin: String = "",                     // from the pairing payload or user entry
    val transportLabel: String = "Wi-Fi"
) {
    object AuthMode {
        const val PIN = "pin"
        const val PRE_SHARED_KEY = "oob" // QR / Bluetooth: receiver key pre-shared
    }
}
