package com.multispace.manager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An APK the user imported into the MultiSpace library. The APK binary is
 * copied into the app's private storage; icon is decoded once and cached as
 * a small PNG to keep memory use low on low-RAM devices.
 */
@Entity(tableName = "imported_apps")
data class AppEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,
    val iconPath: String?,
    val sizeBytes: Long,
    val importedAt: Long
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** SessionKind.WEB or SessionKind.APP */
    val kind: String,
    /** SessionStatus name. */
    val status: String,
    /** For APP sessions: the bound imported app's package name. */
    val packageName: String?,
    val createdAt: Long,
    val lastActiveAt: Long
)

@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val title: String,
    val url: String,
    val isActive: Boolean,
    val position: Int,
    val createdAt: Long
)
