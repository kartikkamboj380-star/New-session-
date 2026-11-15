package com.multispace.manager.core

/**
 * Decides the real status of a session after the app restarted.
 *
 * A session persisted as ACTIVE may have died with the process. Recovery is
 * verified against an actual liveness check (are any of this app's WebView
 * slot processes still running for this session?); nothing is guessed.
 */
object SessionRecoveryPolicy {

    fun resolve(
        persisted: SessionStatus,
        processAlive: Boolean
    ): SessionStatus = when (persisted) {
        SessionStatus.ACTIVE, SessionStatus.RECOVERING ->
            if (processAlive) SessionStatus.ACTIVE else SessionStatus.IDLE
        SessionStatus.CREATED -> SessionStatus.CREATED
        SessionStatus.IDLE -> SessionStatus.IDLE
        SessionStatus.ERROR -> SessionStatus.ERROR
        SessionStatus.CLOSED -> SessionStatus.CLOSED
    }

    /** Statuses a persisted session can legitimately survive an app restart in. */
    fun needsRecovery(status: SessionStatus): Boolean =
        status == SessionStatus.ACTIVE || status == SessionStatus.RECOVERING
}
