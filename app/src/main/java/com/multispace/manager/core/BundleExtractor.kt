package com.multispace.manager.core

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pure JVM extraction of XAPK/APKS bundles from a stream into a working
 * directory. Records, per extracted APK, whether it even looks like a ZIP
 * archive (header check) and its size — so a corrupt/0-byte base APK inside
 * a bundle can be diagnosed precisely instead of failing with a vague error.
 *
 * ZipInputStream verifies entry CRCs while copying, so a truncated or damaged
 * source archive throws here rather than producing silently corrupt files.
 */
object BundleExtractor {

    data class ApkFile(
        val entryName: String,
        val file: File,
        val sizeBytes: Long,
        val zipHeaderValid: Boolean
    )

    data class ObbFile(val entryName: String, val file: File, val sizeBytes: Long)

    data class Extracted(
        val manifestJson: String?,
        val apks: List<ApkFile>,
        val obbs: List<ObbFile>
    )

    fun isZipHeader(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            var read = 0
            while (read < 4) {
                val r = input.read(header, read, 4 - read)
                if (r < 0) break
                read += r
            }
            ApkImportValidator.isZipApkHeader(if (read == 4) header else header.copyOf(read))
        }
    }.getOrDefault(false)

    fun extract(input: InputStream, workingDir: File): Extracted {
        workingDir.mkdirs()
        var manifestJson: String? = null
        val apkFiles = linkedMapOf<String, File>()
        val obbFiles = linkedMapOf<String, File>()

        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.isNotBlank()) {
                    val segment = name.substringAfterLast('/')
                    when {
                        segment.equals("manifest.json", ignoreCase = true) -> {
                            manifestJson = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        segment.endsWith(".apk", ignoreCase = true) -> {
                            val target = uniqueFile(workingDir, sanitizeSegment(segment))
                            target.outputStream().use { zis.copyTo(it) }
                            apkFiles[name] = target
                        }
                        segment.endsWith(".obb", ignoreCase = true) -> {
                            val target = uniqueFile(workingDir, sanitizeSegment(segment))
                            target.outputStream().use { zis.copyTo(it) }
                            obbFiles[name] = target
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        return Extracted(
            manifestJson = manifestJson,
            apks = apkFiles.map { (entryName, file) ->
                ApkFile(entryName, file, file.length(), isZipHeader(file))
            },
            obbs = obbFiles.map { (entryName, file) -> ObbFile(entryName, file, file.length()) }
        )
    }

    fun sanitizeSegment(segment: String): String =
        segment.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").ifBlank { "entry.bin" }

    private fun uniqueFile(dir: File, desired: String): File {
        if (!File(dir, desired).exists()) return File(dir, desired)
        val dot = desired.lastIndexOf('.')
        val (base, ext) = if (dot > 0) desired.substring(0, dot) to desired.substring(dot) else desired to ""
        var i = 1
        while (File(dir, "$base($i)$ext").exists()) i++
        return File(dir, "$base($i)$ext")
    }
}
