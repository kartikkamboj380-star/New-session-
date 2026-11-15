package com.multispace.manager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @Query("SELECT * FROM imported_apps ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<AppEntity>>

    @Query("SELECT * FROM imported_apps WHERE id = :id")
    suspend fun get(id: String): AppEntity?

    @Query("SELECT * FROM imported_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackage(packageName: String): AppEntity?

    @Insert
    suspend fun insert(app: AppEntity)

    @Query("DELETE FROM imported_apps WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions")
    suspend fun getAll(): List<SessionEntity>

    @Insert
    suspend fun insert(session: SessionEntity)

    @Query("UPDATE sessions SET status = :status, lastActiveAt = :lastActiveAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, lastActiveAt: Long)

    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}

@Dao
interface TabDao {

    @Query("SELECT * FROM tabs ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun getForSession(sessionId: String): List<TabEntity>

    @Query("SELECT COUNT(*) FROM tabs WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Insert
    suspend fun insert(tab: TabEntity)

    @Insert
    suspend fun insertAll(tabs: List<TabEntity>)

    @Query("UPDATE tabs SET isActive = :isActive WHERE sessionId = :sessionId")
    suspend fun setActiveFlags(sessionId: String, isActive: Boolean)

    @Query("UPDATE tabs SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    @Query("UPDATE tabs SET title = :title, url = :url WHERE id = :id")
    suspend fun updatePage(id: String, title: String, url: String)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tabs WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
