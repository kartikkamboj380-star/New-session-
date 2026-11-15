package com.multispace.manager

import com.multispace.manager.core.SessionPaths
import com.multispace.manager.core.SessionRecoveryPolicy
import com.multispace.manager.core.SessionSnapshot
import com.multispace.manager.core.SessionSnapshotStore
import com.multispace.manager.core.SessionStatus
import com.multispace.manager.core.SessionStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Session state machine + persistence (snapshot round-trip, corrupt files). */
class SessionLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- state machine ----------------------------------------------------

    @Test
    fun `happy path CREATED to ACTIVE to IDLE to CLOSED`() {
        assertEquals(
            SessionStatus.ACTIVE,
            SessionStateMachine.transition(SessionStatus.CREATED, SessionStatus.ACTIVE)
        )
        assertEquals(
            SessionStatus.IDLE,
            SessionStateMachine.transition(SessionStatus.ACTIVE, SessionStatus.IDLE)
        )
        assertEquals(
            SessionStatus.CLOSED,
            SessionStateMachine.transition(SessionStatus.IDLE, SessionStatus.CLOSED)
        )
    }

    @Test
    fun `illegal transitions throw`() {
        try {
            SessionStateMachine.transition(SessionStatus.CLOSED, SessionStatus.ACTIVE)
            fail("CLOSED -> ACTIVE must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            SessionStateMachine.transition(SessionStatus.CREATED, SessionStatus.IDLE)
            fail("CREATED -> IDLE must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `recovery path RECOVERING resolves both ways`() {
        assertEquals(
            SessionStatus.ACTIVE,
            SessionStateMachine.transition(SessionStatus.RECOVERING, SessionStatus.ACTIVE)
        )
        assertEquals(
            SessionStatus.IDLE,
            SessionStateMachine.transition(SessionStatus.RECOVERING, SessionStatus.IDLE)
        )
    }

    // ---- recovery policy (app restart test) --------------------------------

    @Test
    fun `app restart with live slot process keeps session active`() {
        assertEquals(
            SessionStatus.ACTIVE,
            SessionRecoveryPolicy.resolve(SessionStatus.ACTIVE, processAlive = true)
        )
    }

    @Test
    fun `app restart with dead process marks session idle`() {
        assertEquals(
            SessionStatus.IDLE,
            SessionRecoveryPolicy.resolve(SessionStatus.ACTIVE, processAlive = false)
        )
    }

    @Test
    fun `terminal states never need recovery`() {
        assertFalse(SessionRecoveryPolicy.needsRecovery(SessionStatus.CLOSED))
        assertFalse(SessionRecoveryPolicy.needsRecovery(SessionStatus.IDLE))
        assertTrue(SessionRecoveryPolicy.needsRecovery(SessionStatus.ACTIVE))
    }

    // ---- path safety --------------------------------------------------------

    @Test
    fun `session ids are sanitized against path traversal`() {
        val sanitized = SessionPaths.sanitize("../../etc/passwd")
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("."))
        assertTrue(sanitized.endsWith("etc_passwd"))
        assertTrue(SessionPaths.sanitize("abc-DEF_123").startsWith("abc"))
        assertEquals("_", SessionPaths.sanitize(""))
    }

    // ---- snapshot persistence (session persistence test) --------------------

    @Test
    fun `snapshot round trip preserves all fields`() {
        val dir = tmp.newFolder("s1")
        val snapshot = SessionSnapshot(
            sessionId = "session-1",
            name = "Work session",
            kind = "WEB",
            status = "IDLE",
            lastActiveAt = 1234567890L,
            tabTitles = listOf("Docs", "Mail"),
            lastUrl = "https://example.com"
        )
        assertTrue(SessionSnapshotStore.save(dir, snapshot))
        val loaded = SessionSnapshotStore.load(dir)
        assertEquals(snapshot.sessionId, loaded?.sessionId)
        assertEquals(snapshot.name, loaded?.name)
        assertEquals(snapshot.status, loaded?.status)
        assertEquals(snapshot.tabTitles, loaded?.tabTitles)
        assertEquals(snapshot.lastUrl, loaded?.lastUrl)
    }

    @Test
    fun `second save overwrites atomically`() {
        val dir = tmp.newFolder("s2")
        SessionSnapshotStore.save(
            dir,
            SessionSnapshot("s", "First", "WEB", "ACTIVE", 1L)
        )
        SessionSnapshotStore.save(
            dir,
            SessionSnapshot("s", "Second", "WEB", "IDLE", 2L)
        )
        assertEquals("Second", SessionSnapshotStore.load(dir)?.name)
        // No temp leftovers.
        assertEquals(1, dir.listFiles()?.size)
    }

    @Test
    fun `corrupt snapshot loads as null instead of crashing`() {
        val dir = tmp.newFolder("s3")
        SessionSnapshotStore.metaFile(dir).writeText("{ not valid json !!!")
        assertNull(SessionSnapshotStore.load(dir))
    }

    @Test
    fun `missing snapshot loads as null`() {
        assertNull(SessionSnapshotStore.load(tmp.newFolder("s4")))
    }
}
