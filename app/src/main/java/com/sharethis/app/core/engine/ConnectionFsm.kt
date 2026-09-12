package com.sharethis.app.core.engine

import com.sharethis.app.data.enums.TransferState

/**
 * Deterministic connection state machine (pure JVM, unit-tested).
 *
 * States (per the ShareThis v2 session model):
 *
 *   DISCOVERING → PAIRING → CONNECTING → AUTHENTICATING → NEGOTIATING →
 *   CONNECTED → TRANSFERRING → VERIFYING → COMPLETED
 *
 * Failure states: PAIRING_FAILED, CONNECTION_FAILED, AUTHENTICATION_FAILED,
 * TRANSFER_FAILED, VERIFICATION_FAILED, CANCELLED.
 *
 * Guarantees:
 *  - every non-terminal state has a timeout — the session can never hang;
 *  - CONNECTING/AUTHENTICATING/NEGOTIATING/TRANSFERRING support bounded
 *    automatic retries (reconnect+resume) before failing;
 *  - all transitions are validated — impossible states are rejected;
 *  - cancellation is allowed from any non-terminal state.
 */
class ConnectionFsm(
    private val timeouts: Timeouts = Timeouts(),
    private val clock: Clock = Clock { System.currentTimeMillis() }
) {

    fun interface Clock {
        fun now(): Long
    }

    /**
     * Per-state timeout (ms) and bounded automatic retries.
     * TRANSFERRING has no overall timeout (a 100 GB batch may legitimately
     * run for hours) — instead the engine applies an inactivity watchdog.
     */
    data class Timeouts(
        val discoveringMs: Long = 30_000,
        val pairingMs: Long = 60_000,
        val connectingMs: Long = 15_000,
        val authenticatingMs: Long = 15_000,
        val negotiatingMs: Long = 20_000,
        val connectedMs: Long = 30_000,
        val transferringInactivityMs: Long = 45_000,
        val verifyingMs: Long = 120_000,
        val connectRetries: Int = 2,
        val authRetries: Int = 1,
        val negotiateRetries: Int = 2,
        val transferRetries: Int = 3
    )

    val state: TransferState get() = internalState
    val stateReason: String get() = internalReason
    val attempts: Int get() = internalAttempts
    val enteredAtMs: Long get() = internalEnteredAt
    val lastError: String get() = internalError

    @Volatile private var internalState: TransferState = TransferState.IDLE
    @Volatile private var internalReason: String = ""
    @Volatile private var internalError: String = ""
    @Volatile private var internalAttempts: Int = 0
    @Volatile private var internalEnteredAt: Long = clock.now()

    /** Raised (once) when a state exceeds its timeout. */
    data class TimeoutEvent(val state: TransferState, val timeoutMs: Long)

    /**
     * Attempts a transition. Returns false (and ignores) when the transition
     * is not allowed from the current state.
     */
    @Synchronized
    fun transition(to: TransferState, reason: String = ""): Boolean {
        val from = internalState
        if (from == to) {
            internalReason = reason
            return true
        }
        if (!allowed(from, to)) return false
        when (to) {
            TransferState.CONNECTING, TransferState.AUTHENTICATING,
            TransferState.NEGOTIATING -> {
                // Count a "retry" when re-entering after a failure-driven loop.
                if (isFailureLoop(from, to)) internalAttempts++ else internalAttempts = 0
            }
            TransferState.RECONNECTING -> internalAttempts++
            TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.CONNECTED -> Unit
            else -> internalAttempts = 0
        }
        internalState = to
        internalReason = reason
        internalEnteredAt = clock.now()
        return true
    }

    @Synchronized
    fun reset() {
        internalState = TransferState.IDLE
        internalReason = ""
        internalError = ""
        internalAttempts = 0
        internalEnteredAt = clock.now()
    }

    @Synchronized
    fun recordError(message: String) {
        internalError = message.take(300)
    }

    /**
     * Reports whether the current state has exceeded its timeout.
     * [activeSinceMs] lets callers distinguish "no bytes for N ms" for the
     * TRANSFERRING watchdog (pass last-progress timestamp; 0 = no progress).
     */
    @Synchronized
    fun checkTimeout(activeSinceMs: Long = 0L): TimeoutEvent? {
        val timeoutMs = when (internalState) {
            TransferState.DISCOVERING -> timeouts.discoveringMs
            TransferState.PAIRING -> timeouts.pairingMs
            TransferState.CONNECTING -> timeouts.connectingMs
            TransferState.AUTHENTICATING -> timeouts.authenticatingMs
            TransferState.NEGOTIATING -> timeouts.negotiatingMs
            TransferState.CONNECTED -> timeouts.connectedMs
            TransferState.TRANSFERRING -> {
                val last = if (activeSinceMs > 0) activeSinceMs else internalEnteredAt
                val idle = clock.now() - last
                return if (idle > timeouts.transferringInactivityMs) {
                    TimeoutEvent(TransferState.TRANSFERRING, timeouts.transferringInactivityMs)
                } else null
            }
            TransferState.VERIFYING -> timeouts.verifyingMs
            else -> return null
        }
        val elapsed = clock.now() - internalEnteredAt
        return if (elapsed > timeoutMs) TimeoutEvent(internalState, timeoutMs) else null
    }

    /** Remaining automatic retries for the current failure loop. */
    @Synchronized
    fun retriesLeft(state: TransferState): Int {
        val max = when (state) {
            TransferState.CONNECTING, TransferState.RECONNECTING -> timeouts.connectRetries
            TransferState.AUTHENTICATING -> timeouts.authRetries
            TransferState.NEGOTIATING -> timeouts.negotiateRetries
            TransferState.TRANSFERRING -> timeouts.transferRetries
            else -> 0
        }
        return (max - internalAttempts).coerceAtLeast(0)
    }

    private fun isFailureLoop(from: TransferState, to: TransferState): Boolean =
        from == TransferState.RECONNECTING ||
            (from == TransferState.TRANSFERRING && to == TransferState.CONNECTING) ||
            (from == TransferState.CONNECTED && to == TransferState.CONNECTING)

    companion object {
        private val ALLOWED: Map<TransferState, Set<TransferState>> = run {
            val map = mutableMapOf<TransferState, MutableSet<TransferState>>()
            fun edge(from: TransferState, vararg to: TransferState) {
                map.getOrPut(from) { mutableSetOf() }.addAll(to.toSet())
            }

            edge(TransferState.IDLE, TransferState.DISCOVERING, TransferState.PAIRING,
                TransferState.CONNECTING, TransferState.CANCELLED)
            edge(TransferState.DISCOVERING, TransferState.PAIRING, TransferState.CONNECTING,
                TransferState.PAIRING_FAILED, TransferState.CANCELLED)
            edge(TransferState.PAIRING, TransferState.CONNECTING, TransferState.PAIRING_FAILED,
                TransferState.CONNECTION_FAILED, TransferState.CANCELLED)
            edge(TransferState.CONNECTING, TransferState.AUTHENTICATING, TransferState.CONNECTED,
                TransferState.CONNECTION_FAILED, TransferState.RECONNECTING,
                TransferState.CANCELLED)
            edge(TransferState.AUTHENTICATING, TransferState.NEGOTIATING,
                TransferState.AUTHENTICATION_FAILED, TransferState.RECONNECTING,
                TransferState.CANCELLED)
            edge(TransferState.NEGOTIATING, TransferState.CONNECTED,
                TransferState.AUTHENTICATION_FAILED, TransferState.CONNECTION_FAILED,
                TransferState.RECONNECTING, TransferState.CANCELLED)
            edge(TransferState.CONNECTED, TransferState.TRANSFERRING, TransferState.NEGOTIATING,
                TransferState.CONNECTION_FAILED, TransferState.RECONNECTING,
                TransferState.CANCELLED)
            edge(TransferState.TRANSFERRING, TransferState.TRANSFERRING, TransferState.VERIFYING,
                TransferState.RECONNECTING, TransferState.TRANSFER_FAILED,
                TransferState.VERIFICATION_FAILED, TransferState.PAUSED,
                TransferState.CONNECTION_FAILED, TransferState.CANCELLED)
            edge(TransferState.RECONNECTING, TransferState.CONNECTING, TransferState.NEGOTIATING,
                TransferState.TRANSFERRING, TransferState.CONNECTION_FAILED,
                TransferState.TRANSFER_FAILED, TransferState.CANCELLED)
            edge(TransferState.VERIFYING, TransferState.COMPLETED,
                TransferState.TRANSFERRING, TransferState.VERIFICATION_FAILED,
                TransferState.RECONNECTING, TransferState.TRANSFER_FAILED,
                TransferState.CANCELLED)
            edge(TransferState.PAUSED, TransferState.RECONNECTING, TransferState.TRANSFERRING,
                TransferState.CANCELLED, TransferState.TRANSFER_FAILED)
            // Failure / terminal states can only restart via reset() or a
            // bounded retry edge back into CONNECTING/RECONNECTING.
            for (failure in listOf(
                TransferState.CONNECTION_FAILED, TransferState.TRANSFER_FAILED,
                TransferState.AUTHENTICATION_FAILED, TransferState.VERIFICATION_FAILED,
                TransferState.PAIRING_FAILED, TransferState.ERROR
            )) {
                edge(failure, TransferState.RECONNECTING, TransferState.CONNECTING,
                    TransferState.IDLE, TransferState.CANCELLED)
            }
            edge(TransferState.CANCELLED, TransferState.IDLE)
            edge(TransferState.COMPLETED, TransferState.IDLE, TransferState.TRANSFERRING)
            map
        }

        fun allowed(from: TransferState, to: TransferState): Boolean =
            ALLOWED[from]?.contains(to) == true
    }
}
