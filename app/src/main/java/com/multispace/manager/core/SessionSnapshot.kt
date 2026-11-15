package com.multispace.manager.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Sanitizes identifiers used as file system paths. Session IDs are generated
 * UUIDs, but this is defense-in-depth against path traversal.
 */
object SessionPaths {
    private val safe = Regex("[^A-Za-z0-9_-]")

    fun sanitize(id: String): String {
        val cleaned = safe.replace(id, "_")
        return if (cleaned.isBlank()) "_" else cleaned.take(64)
    }

    fun sessionDir(root: File, sessionId: String): File =
        File(root, sanitize(sessionId))
}

/**
 * JSON snapshot of a session's persisted state, written under the session's
 * private directory (filesDir/sessions/<id>/meta.json). Used for state
 * recovery after process death or device restart.
 */
@Serializable
data class SessionSnapshot(
    val sessionId: String,
    val name: String,
    val kind: String,
    val status: String,
    val lastActiveAt: Long,
    val tabTitles: List<String> = emptyList(),
    val lastUrl: String? = null,
    val deviceModel: String? = null,
    val savedAtMs: Long = System.currentTimeMillis()
)

/**
 * Atomic file persistence for snapshots: writes to a temp file first and
 * renames, so a crash mid-write can never corrupt the previous snapshot.
 */
object SessionSnapshotStore {

    fun metaFile(sessionDir: File): File = File(sessionDir, "meta.json")

    fun save(sessionDir: File, snapshot: SessionSnapshot): Boolean {
        return try {
            sessionDir.mkdirs()
            val target = metaFile(sessionDir)
            val tmp = File(sessionDir, "meta.json.tmp")
            tmp.writeText(Json.encodeToString(SessionSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target) || return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(sessionDir: File): SessionSnapshot? {
        return try {
            val file = metaFile(sessionDir)
            if (!file.exists()) return null
            Json.decodeFromString(SessionSnapshot.serializer(), file.readText())
        } catch (_: Exception) {
            // Corrupt snapshot: recovery falls back to the Room database row.
            null
        }
    }

    fun clear(sessionDir: File): Boolean {
        val file = metaFile(sessionDir)
        return if (file.exists()) file.delete() else true
    }
}
