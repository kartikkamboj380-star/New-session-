package com.multispace.manager.core

/**
 * Lifecycle status of a MultiSpace session. Persisted in Room as a string.
 */
enum class SessionStatus {
    CREATED,
    ACTIVE,
    IDLE,
    RECOVERING,
    CLOSED,
    ERROR
}

/**
 * What kind of work a session performs.
 *
 * WEB  -> isolated WebView container running in its own OS process.
 * APP  -> bound to an imported, device-installed APK; launched through the
 *         Android platform (optionally in an isolated profile when available).
 */
enum class SessionKind {
    WEB,
    APP
}

/**
 * Explicit, testable transition rules for session lifecycle. Every status change
 * in the app must go through this machine so invalid states cannot be persisted.
 */
object SessionStateMachine {

    private val allowed: Map<SessionStatus, Set<SessionStatus>> = mapOf(
        SessionStatus.CREATED to setOf(
            SessionStatus.ACTIVE, SessionStatus.CLOSED, SessionStatus.ERROR
        ),
        SessionStatus.ACTIVE to setOf(
            SessionStatus.IDLE, SessionStatus.RECOVERING, SessionStatus.CLOSED, SessionStatus.ERROR
        ),
        SessionStatus.IDLE to setOf(
            SessionStatus.ACTIVE, SessionStatus.CLOSED, SessionStatus.ERROR
        ),
        SessionStatus.RECOVERING to setOf(
            SessionStatus.ACTIVE, SessionStatus.IDLE, SessionStatus.CLOSED, SessionStatus.ERROR
        ),
        SessionStatus.ERROR to setOf(
            SessionStatus.ACTIVE, SessionStatus.CLOSED
        ),
        SessionStatus.CLOSED to emptySet()
    )

    fun canTransition(from: SessionStatus, to: SessionStatus): Boolean =
        allowed[from]?.contains(to) == true

    /** Returns the new status or throws — callers must never persist an invalid state. */
    fun transition(from: SessionStatus, to: SessionStatus): SessionStatus {
        require(canTransition(from, to)) {
            "Illegal session transition: $from -> $to"
        }
        return to
    }
}
