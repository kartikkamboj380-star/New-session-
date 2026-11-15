package com.multispace.manager.apk

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.graphics.drawable.toBitmap
import com.multispace.manager.core.ApkImportValidation
import com.multispace.manager.core.ApkImportValidator
import com.multispace.manager.core.BundleExtractor
import com.multispace.manager.core.XapkBundleParser
import com.multispace.manager.data.db.AppDao
import com.multispace.manager.data.db.AppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Imports APKs and bundles picked through the Storage Access Framework:
 *
 *  - **APK**: streamed into private storage, validated (name/size/ZIP header),
 *    parsed via PackageManager archive APIs (name, package, version, icon).
 *  - **XAPK / APKS bundle**: streamed ZIP extraction of the base APK, split
 *    APKs, manifest.json and OBB files. Base metadata comes from the real
 *    base APK parse; split paths are persisted in a sidecar JSON so the
 *    installer can push every APK into ONE PackageInstaller session — the
 *    platform-supported way to install split apps. No repackaging, no
 *    signature tampering.
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
        val displayName = queryDisplayName(uri)
        if (ApkImportValidator.isBundleFileName(displayName)) {
            importBundle(uri, displayName)
        } else {
            importSingleApk(uri, displayName)
        }
    }

    // ------------------------------------------------------------------
    // Single APK import (existing flow)
    // ------------------------------------------------------------------

    private suspend fun importSingleApk(uri: Uri, displayName: String?): ImportResult =
        withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                val resolver = context.contentResolver
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

    // ------------------------------------------------------------------
    // XAPK / APKS bundle import
    // ------------------------------------------------------------------

    private suspend fun importBundle(uri: Uri, displayName: String?): ImportResult =
        withContext(Dispatchers.IO) {
            var staging: File? = null
            try {
                staging = File(context.cacheDir, "bundle_${UUID.randomUUID()}").apply { mkdirs() }
                val extracted = context.contentResolver.openInputStream(uri)?.use { input ->
                    BundleExtractor.extract(input, staging)
                } ?: return@withContext ImportResult.Failure("Cannot open the selected file.")

                if (extracted.apks.isEmpty()) {
                    return@withContext ImportResult.Failure(
                        "Invalid XAPK/bundle: no APK files found inside (or it is not a ZIP archive)."
                    )
                }

                val parsedBundle = XapkBundleParser.parse(
                    displayName,
                    extracted.manifestJson,
                    extracted.apks.map { it.entryName }
                )
                val byEntry = extracted.apks.associateBy { it.entryName }
                val parserSplits = parsedBundle.splitApkEntries.toSet()

                // Candidate order: the classified base first, then the largest
                // remaining non-split APKs, then the splits. Some bundles ship
                // a stub/empty base with the real app among the splits — the
                // first candidate that Android can actually parse wins.
                val candidates = buildList {
                    parsedBundle.baseApkEntry?.let { add(it) }
                    extracted.apks
                        .filter { it.entryName != parsedBundle.baseApkEntry && it.entryName !in parserSplits }
                        .sortedByDescending { it.sizeBytes }
                        .forEach { add(it.entryName) }
                    extracted.apks
                        .filter { it.entryName in parserSplits }
                        .sortedByDescending { it.sizeBytes }
                        .forEach { add(it.entryName) }
                }.distinct()

                var parsed: ParsedApk? = null
                var baseApkFile: File? = null
                var baseEntryUsed: String? = null
                val diagnosis = mutableListOf<String>()

                for (candidate in candidates) {
                    val apk = byEntry.getValue(candidate)
                    val parsedCandidate = parseArchive(apk.file)
                    if (parsedCandidate?.packageName != null) {
                        parsed = parsedCandidate
                        baseApkFile = apk.file
                        baseEntryUsed = candidate
                        break
                    }
                    diagnosis.add(
                        "${apk.entryName}: ${apk.sizeBytes} bytes, ZIP header " +
                            if (apk.zipHeaderValid) "OK but Android parse FAILED"
                            else "INVALID (not an APK)"
                    )
                }

                if (parsed == null || baseApkFile == null || baseEntryUsed == null) {
                    return@withContext ImportResult.Failure(
                        buildString {
                            appendLine("Invalid bundle: no APK inside could be parsed by Android.")
                            diagnosis.forEach { appendLine("• $it") }
                            append("This XAPK download is most likely incomplete, corrupt, or not a standard bundle — re-download it, or import the plain .apk file instead.")
                        }
                    )
                }

                val id = UUID.randomUUID().toString()
                val finalApk = File(importedDir, "$id.apk")
                baseApkFile.copyTo(finalApk, overwrite = true)

                val splitFiles = candidates
                    .filter { it != baseEntryUsed }
                    .mapIndexedNotNull { index, entryName ->
                        val source = byEntry[entryName] ?: return@mapIndexedNotNull null
                        val target = File(importedDir, "$id.split$index.apk")
                        source.file.copyTo(target, overwrite = true)
                        target.absolutePath
                    }

                val obbDir = File(importedDir, "$id.obb").apply { mkdirs() }
                val obbPaths = extracted.obbs.map { obb ->
                    val target = File(obbDir, BundleExtractor.sanitizeSegment(obb.entryName.substringAfterLast('/')))
                    if (!obb.file.renameTo(target)) {
                        obb.file.copyTo(target, overwrite = true)
                        obb.file.delete()
                    }
                    target.absolutePath
                }

                BundleInfoStore.save(
                    BundleInfoStore.fileFor(finalApk.absolutePath),
                    BundleArtifact(
                        packageName = parsed.packageName ?: parsedBundle.packageName,
                        splitApkPaths = splitFiles,
                        obbPaths = obbPaths
                    )
                )

                val iconPath = saveIcon(parsed, finalApk, id)

                val entity = AppEntity(
                    id = id,
                    packageName = parsed.packageName ?: parsedBundle.packageName ?: "unknown.package",
                    label = parsed.label
                        ?: parsedBundle.label
                        ?: displayName?.substringBeforeLast('.') ?: "Unknown bundle",
                    versionName = parsed.versionName ?: parsedBundle.versionName ?: "?",
                    versionCode = parsed.versionCode,
                    apkPath = finalApk.absolutePath,
                    iconPath = iconPath,
                    sizeBytes = finalApk.length() + splitFiles.sumOf { File(it).length() } +
                        obbPaths.sumOf { File(it).length() },
                    importedAt = System.currentTimeMillis()
                )
                appDao.insert(entity)
                ImportResult.Success(entity)
            } catch (e: Exception) {
                ImportResult.Failure("Bundle import failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                staging?.deleteRecursively()
            }
        }

    // ------------------------------------------------------------------
    // Bundle info + OBB placement
    // ------------------------------------------------------------------

    fun loadBundleInfo(app: AppEntity): BundleArtifact? =
        BundleInfoStore.load(BundleInfoStore.fileFor(app.apkPath))

    /**
     * Best-effort OBB placement after a successful install. Android blocks
     * writing into another app's `Android/obb/<pkg>/` directory unless the
     * user granted "All files access" — the user-controlled, platform path.
     * Returns a user-facing note (or null when there is nothing to report).
     */
    fun placeObbFiles(app: AppEntity, packageName: String): String? {
        val bundle = loadBundleInfo(app) ?: return null
        if (bundle.obbPaths.isEmpty()) return null

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            return ("This bundle contains OBB expansion data. Placing it for another app needs the " +
                "'All files access' permission (Settings → Apps → MultiSpace → All files access). " +
                "Without it, the app will usually download its own data on first run.")
        }
        return try {
            val obbDir = File(
                Environment.getExternalStorageDirectory(),
                "Android/obb/$packageName"
            ).apply { mkdirs() }
            var copied = 0
            bundle.obbPaths.forEach { path ->
                val source = File(path)
                if (source.exists()) {
                    source.copyTo(File(obbDir, source.name), overwrite = true)
                    copied++
                }
            }
            if (copied > 0) "OBB expansion data placed for $packageName ($copied file(s))."
            else null
        } catch (e: Exception) {
            "OBB placement failed (${e.message ?: "storage error"}); the app may re-download its data."
        }
    }

    // ------------------------------------------------------------------

    suspend fun delete(appId: String) = withContext(Dispatchers.IO) {
        val app = appDao.get(appId) ?: return@withContext
        val apkFile = File(app.apkPath)
        BundleInfoStore.delete(BundleInfoStore.fileFor(app.apkPath))
        File(apkFile.parentFile, "${apkFile.nameWithoutExtension}.obb").deleteRecursively()
        apkFile.takeIf { it.exists() }?.delete()
        // Split sidecars follow the <id>.splitN.apk naming — clean them too.
        apkFile.parentFile?.listFiles { f -> f.name.startsWith(apkFile.nameWithoutExtension + ".split") }
            ?.forEach { it.delete() }
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
            versionCode = if (Build.VERSION.SDK_INT >= 28) {
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
