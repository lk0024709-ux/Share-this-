package com.sharethis.app.data.enums

/**
 * Lifecycle of a file transfer session, shared by the socket engine,
 * pairing flows and the UI layer.
 */
enum class TransferState {
    IDLE,
    CONNECTING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    ERROR,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == ERROR || this == CANCELLED

    val isActive: Boolean
        get() = this == CONNECTING || this == TRANSFERRING || this == VERIFYING
}
