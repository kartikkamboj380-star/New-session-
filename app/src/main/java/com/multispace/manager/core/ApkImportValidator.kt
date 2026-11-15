package com.multispace.manager.core

/**
 * Pure validation for a file the user picked as an APK import.
 * Runs before any bytes are parsed as an Android package so clearly
 * invalid files fail fast with a precise reason.
 */
sealed interface ApkImportValidation {
    data class Valid(val fileName: String, val sizeBytes: Long) : ApkImportValidation
    data class Invalid(val reason: String) : ApkImportValidation
}

object ApkImportValidator {

    /** Arbitrary smaller files cannot be valid Android packages. */
    const val MIN_APK_BYTES: Long = 1_024

    /** Every APK is a ZIP archive: local file header magic "PK\x03\x04". */
    private val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun isZipApkHeader(header: ByteArray?): Boolean {
        if (header == null || header.size < APK_MAGIC.size) return false
        for (i in APK_MAGIC.indices) {
            if (header[i] != APK_MAGIC[i]) return false
        }
        return true
    }

    fun isValidFileName(fileName: String?): Boolean =
        fileName != null && fileName.endsWith(".apk", ignoreCase = true)

    fun validate(fileName: String?, sizeBytes: Long, header: ByteArray?): ApkImportValidation {
        if (fileName.isNullOrBlank()) {
            return ApkImportValidation.Invalid("File has no name.")
        }
        if (!isValidFileName(fileName)) {
            return ApkImportValidation.Invalid("Selected file is not an APK (missing .apk extension).")
        }
        if (sizeBytes <= 0) {
            return ApkImportValidation.Invalid("File is empty.")
        }
        if (sizeBytes < MIN_APK_BYTES) {
            return ApkImportValidation.Invalid("File too small to be an APK ($sizeBytes bytes).")
        }
        if (!isZipApkHeader(header)) {
            return ApkImportValidation.Invalid("Invalid APK: missing ZIP archive header (PK\\x03\\x04).")
        }
        return ApkImportValidation.Valid(fileName, sizeBytes)
    }
}
