package com.multispace.manager.apk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Sidecar metadata for imported bundles (XAPK/APKS): paths of the split APKs
 * that must be pushed into the same PackageInstaller session as the base APK,
 * and any OBB expansion files extracted from the bundle.
 *
 * Stored as JSON next to the imported APK so the Room schema stays unchanged
 * (no destructive migration for existing imports).
 */
@Serializable
data class BundleArtifact(
    val packageName: String?,
    val splitApkPaths: List<String> = emptyList(),
    val obbPaths: List<String> = emptyList()
)

object BundleInfoStore {

    fun fileFor(apkPath: String): File {
        val apk = File(apkPath)
        val name = apk.nameWithoutExtension
        return File(apk.parentFile, "$name.bundle.json")
    }

    fun save(target: File, artifact: BundleArtifact): Boolean {
        return try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(Json.encodeToString(BundleArtifact.serializer(), artifact))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target) || return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(target: File): BundleArtifact? = try {
        if (!target.exists()) null
        else Json.decodeFromString(BundleArtifact.serializer(), target.readText())
    } catch (_: Exception) {
        null
    }

    fun delete(target: File): Boolean = if (target.exists()) target.delete() else true
}
