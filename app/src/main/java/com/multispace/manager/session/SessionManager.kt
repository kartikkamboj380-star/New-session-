package com.multispace.manager.session

import android.content.Context
import com.multispace.manager.core.SessionKind
import com.multispace.manager.core.SessionPaths
import com.multispace.manager.core.SessionSnapshot
import com.multispace.manager.core.SessionSnapshotStore
import com.multispace.manager.core.SessionStatus
import com.multispace.manager.core.SessionStateMachine
import com.multispace.manager.core.SessionRecoveryPolicy
import com.multispace.manager.data.db.AppDatabase
import com.multispace.manager.data.db.SessionEntity
import com.multispace.manager.data.db.TabEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Owns session lifecycle: creation, switching, close/restart/delete, isolated
 * per-session storage directories, snapshot persistence and state recovery.
 *
 * Every session gets an isolated private directory tree:
 *   filesDir/sessions/<sanitized-id>/data | cache | web | downloads | meta.json
 * Session data is preserved across restarts and only removed on explicit delete.
 */
class SessionManager(
    private val db: AppDatabase,
    private val context: Context
) {

    val sessionsRoot: File
        get() = File(context.filesDir, "sessions").apply { mkdirs() }

    fun sessionDir(sessionId: String): File =
        File(sessionsRoot, SessionPaths.sanitize(sessionId))

    fun dataDir(sessionId: String): File = ensureSubDir(sessionId, "data")
    fun cacheDir(sessionId: String): File = ensureSubDir(sessionId, "cache")
    fun webDir(sessionId: String): File = ensureSubDir(sessionId, "web")
    fun downloadsDir(sessionId: String): File = ensureSubDir(sessionId, "downloads")

    private fun ensureSubDir(sessionId: String, name: String): File {
        val dir = File(sessionDir(sessionId), name)
        dir.mkdirs()
        return dir
    }

    suspend fun createSession(
        name: String,
        kind: SessionKind,
        packageName: String?,
        initialUrl: String?
    ): SessionEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = SessionEntity(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultSessionName(kind) },
            kind = kind.name,
            status = SessionStatus.CREATED.name,
            packageName = packageName,
            createdAt = now,
            lastActiveAt = now
        )
        db.sessionDao().insert(entity)
        dataDir(entity.id)
        cacheDir(entity.id)
        webDir(entity.id)
        downloadsDir(entity.id)

        val firstTab = TabEntity(
            id = UUID.randomUUID().toString(),
            sessionId = entity.id,
            title = "New Tab",
            url = initialUrl?.takeIf { it.isNotBlank() } ?: NEW_TAB_URL,
            isActive = true,
            position = 0,
            createdAt = now
        )
        db.tabDao().insert(firstTab)
        persistSnapshot(entity)
        entity
    }

    suspend fun setStatus(sessionId: String, status: SessionStatus) {
        val current = db.sessionDao().get(sessionId) ?: return
        val persisted = SessionStatus.valueOf(current.status)
        if (!SessionStateMachine.canTransition(persisted, status)) return
        db.sessionDao().updateStatus(sessionId, status.name, System.currentTimeMillis())
    }

    suspend fun activate(sessionId: String): Boolean {
        val current = db.sessionDao().get(sessionId) ?: return false
        val persisted = SessionStatus.valueOf(current.status)
        return try {
            SessionStateMachine.transition(persisted, SessionStatus.ACTIVE)
            db.sessionDao().updateStatus(sessionId, SessionStatus.ACTIVE.name, System.currentTimeMillis())
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Close: session stays on the device, its state is snapshotted. */
    suspend fun close(sessionId: String) = withContext(Dispatchers.IO) {
        val current = db.sessionDao().get(sessionId) ?: return@withContext
        val persisted = SessionStatus.valueOf(current.status)
        if (SessionStateMachine.canTransition(persisted, SessionStatus.IDLE)) {
            db.sessionDao().updateStatus(sessionId, SessionStatus.IDLE.name, System.currentTimeMillis())
            val tabs = db.tabDao().getForSession(sessionId)
            persistSnapshot(current.copy(status = SessionStatus.IDLE.name, lastActiveAt = System.currentTimeMillis()), tabs)
        }
    }

    /**
     * Restart: for WEB sessions this closes the host process and reopens the
     * session with its persisted tabs. Session data directories are kept —
     * only the runtime is reset.
     */
    suspend fun restart(sessionId: String): SessionEntity? = withContext(Dispatchers.IO) {
        val current = db.sessionDao().get(sessionId) ?: return@withContext null
        val persisted = SessionStatus.valueOf(current.status)
        if (persisted != SessionStatus.IDLE) {
            close(sessionId)
        }
        val ok = activate(sessionId)
        if (ok) db.sessionDao().get(sessionId) else null
    }

    /** Delete: removes the session row, tabs and the whole isolated directory tree. */
    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        db.tabDao().deleteForSession(sessionId)
        db.sessionDao().delete(sessionId)
        sessionDir(sessionId).deleteRecursively()
    }

    /**
     * Recovery after app restart: sessions persisted as ACTIVE are marked
     * RECOVERING and resolved against a real process-liveness check.
     */
    suspend fun recoverSessions(processAlive: (String) -> Boolean) = withContext(Dispatchers.IO) {
        val dao = db.sessionDao()
        dao.getAll().forEach { entity ->
            val persisted = runCatching { SessionStatus.valueOf(entity.status) }
                .getOrDefault(SessionStatus.IDLE)
            if (SessionRecoveryPolicy.needsRecovery(persisted)) {
                dao.updateStatus(
                    entity.id,
                    SessionRecoveryPolicy.resolve(persisted, processAlive(entity.id)).name,
                    entity.lastActiveAt
                )
            } else if (persisted == SessionStatus.CREATED) {
                dao.setStatus(entity.id, SessionStatus.CREATED.name)
            }
        }
    }

    suspend fun persistSnapshot(
        entity: SessionEntity,
        tabs: List<TabEntity> = db.tabDao().getForSession(entity.id)
    ) = withContext(Dispatchers.IO) {
        val snapshot = SessionSnapshot(
            sessionId = entity.id,
            name = entity.name,
            kind = entity.kind,
            status = entity.status,
            lastActiveAt = entity.lastActiveAt,
            tabTitles = tabs.map { it.title },
            lastUrl = tabs.firstOrNull { it.isActive }?.url
        )
        SessionSnapshotStore.save(sessionDir(entity.id), snapshot)
    }

    fun loadSnapshot(sessionId: String): SessionSnapshot? =
        SessionSnapshotStore.load(sessionDir(sessionId))

    private fun defaultSessionName(kind: SessionKind): String =
        if (kind == SessionKind.WEB) "Web Session" else "App Session"

    companion object {
        const val NEW_TAB_URL = "about:blank"
        const val DEFAULT_HOME_URL = "https://duckduckgo.com"
    }
}
