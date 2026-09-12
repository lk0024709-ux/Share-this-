package com.sharethis.app.data.enums

/**
 * Lifecycle of a transfer session, shared by the socket engine, pairing
 * flows and the UI layer.
 *
 * v2 adds the full connection state machine vocabulary: pairing/discovery
 * states, authentication/negotiation, reconnect+resume, pause, and the
 * specific failure states required for actionable error recovery.
 */
enum class TransferState {
    IDLE,
    DISCOVERING,
    PAIRING,
    CONNECTING,
    AUTHENTICATING,
    NEGOTIATING,
    CONNECTED,
    TRANSFERRING,
    RECONNECTING,
    VERIFYING,
    PAUSED,
    COMPLETED,
    ERROR,
    PAIRING_FAILED,
    CONNECTION_FAILED,
    AUTHENTICATION_FAILED,
    TRANSFER_FAILED,
    VERIFICATION_FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == ERROR || this == PAIRING_FAILED ||
            this == CONNECTION_FAILED || this == AUTHENTICATION_FAILED ||
            this == TRANSFER_FAILED || this == VERIFICATION_FAILED || this == CANCELLED

    val isFailure: Boolean
        get() = this == ERROR || this == PAIRING_FAILED || this == CONNECTION_FAILED ||
            this == AUTHENTICATION_FAILED || this == TRANSFER_FAILED ||
            this == VERIFICATION_FAILED

    val isActive: Boolean
        get() = this == DISCOVERING || this == PAIRING || this == CONNECTING ||
            this == AUTHENTICATING || this == NEGOTIATING || this == CONNECTED ||
            this == TRANSFERRING || this == RECONNECTING || this == VERIFYING || this == PAUSED
}
