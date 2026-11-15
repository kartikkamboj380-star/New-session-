package com.multispace.manager.apk

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.drawable.toBitmap
import com.multispace.manager.core.ApkImportValidation
import com.multispace.manager.core.ApkImportValidator
import com.multispace.manager.data.db.AppDao
import com.multispace.manager.data.db.AppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Imports an APK picked through the Storage Access Framework:
 *  1. Streams the picked file into the app's private storage.
 *  2. Validates name/size/ZIP header (pure validator, unit tested).
 *  3. Parses name, package, version and icon via PackageManager archive APIs.
 *  4. Stores a downsampled icon PNG so list scrolling stays cheap on low-RAM devices.
 */
class ApkImporter(
    private val context: Context,
    private val appDao: AppDao
) {

    sealed interface ImportResult {
        data class Success(val app: AppEntity) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    private val importedDir: File
        get() = File(context.filesDir, "imported").apply { mkdirs() }

    private val iconDir: File
        get() = File(importedDir, "icons").apply { mkdirs() }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val resolver = context.contentResolver
            val displayName = queryDisplayName(uri)
            val size = querySize(uri)

            val staging = File(importedDir, "staging_${UUID.randomUUID()}.apk")
            tempFile = staging

            val header = ByteArray(4)
            resolver.openInputStream(uri)?.use { input ->
                val read = input.readNBytes(header, 0, 4)
                val headerRead = if (read == 4) header.copyOf() else header.copyOf(read)
                staging.outputStream().use { out ->
                    out.write(headerRead)
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                }
                // Validate on the bytes actually available.
                when (val validation = validate(displayName, staging.length(), headerRead, size)) {
                    is ApkImportValidation.Invalid -> return@withContext ImportResult.Failure(validation.reason)
                    is ApkImportValidation.Valid -> Unit
                }
            } ?: return@withContext ImportResult.Failure("Cannot open the selected file.")

            val parsed = parseArchive(staging)
                ?: return@withContext ImportResult.Failure(
                    "Invalid APK: Android package information could not be parsed."
                )

            val id = UUID.randomUUID().toString()
            val finalApk = File(importedDir, "$id.apk")
            if (!staging.renameTo(finalApk)) {
                staging.copyTo(finalApk, overwrite = true)
                staging.delete()
            }
            tempFile = null

            val iconPath = saveIcon(parsed, finalApk, id)

            val entity = AppEntity(
                id = id,
                packageName = parsed.packageName ?: "unknown.package",
                label = parsed.label ?: displayName?.removeSuffix(".apk") ?: "Unknown app",
                versionName = parsed.versionName ?: "?",
                versionCode = parsed.versionCode,
                apkPath = finalApk.absolutePath,
                iconPath = iconPath,
                sizeBytes = finalApk.length(),
                importedAt = System.currentTimeMillis()
            )
            appDao.insert(entity)
            ImportResult.Success(entity)
        } catch (e: Exception) {
            ImportResult.Failure("Import failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tempFile?.takeIf { it.exists() }?.delete()
        }
    }

    suspend fun delete(appId: String) = withContext(Dispatchers.IO) {
        val app = appDao.get(appId) ?: return@withContext
        File(app.apkPath).takeIf { it.exists() }?.delete()
        app.iconPath?.let { File(it).takeIf { f -> f.exists() }?.delete() }
        appDao.delete(appId)
    }

    private fun validate(
        displayName: String?,
        actualBytes: Long,
        header: ByteArray,
        claimedSize: Long
    ): ApkImportValidation =
        ApkImportValidator.validate(
            fileName = displayName,
            sizeBytes = if (actualBytes > 0) actualBytes else claimedSize,
            header = header
        )

    data class ParsedApk(
        val packageName: String?,
        val label: String?,
        val versionName: String?,
        val versionCode: Long,
        val icon: android.graphics.drawable.Drawable?
    )

    private fun parseArchive(apkFile: File): ParsedApk? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
        val appInfo = info.applicationInfo ?: return null
        // Archive icons/labels resolve only when sourceDir points at the APK.
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath
        val label = try { appInfo.loadLabel(pm)?.toString() } catch (_: Exception) { null }
        val icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null }
        return ParsedApk(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            },
            icon = icon
        )
    }

    /** Downsampling keeps memory use predictable on low-RAM devices. */
    private fun saveIcon(parsed: ParsedApk, apkFile: File, appId: String): String? {
        return try {
            val drawable = parsed.icon ?: return null
            val bitmap = drawable.toBitmap(maxIconPx, maxIconPx, Bitmap.Config.ARGB_8888)
            val out = File(iconDir, "$appId.png")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)

    companion object {
        private const val maxIconPx = 192
    }
}
