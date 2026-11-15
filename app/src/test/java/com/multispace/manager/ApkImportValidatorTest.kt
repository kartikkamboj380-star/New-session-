package com.multispace.manager

import com.multispace.manager.core.ApkImportValidation
import com.multispace.manager.core.ApkImportValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** APK import tests: valid files accepted, invalid files rejected with reasons. */
class ApkImportValidatorTest {

    private val apkHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val elfHeader = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

    private fun validCase(name: String, size: Long, header: ByteArray) =
        ApkImportValidator.validate(name, size, header)

    @Test
    fun `valid apk file is accepted`() {
        val result = validCase("app-release.apk", 4_000_000L, apkHeader)
        assertTrue(result is ApkImportValidation.Valid)
        assertEquals("app-release.apk", (result as ApkImportValidation.Valid).fileName)
        assertEquals(4_000_000L, result.sizeBytes)
    }

    @Test
    fun `non-apk extension is rejected`() {
        val result = validCase("photo.jpg", 4_000_000L, apkHeader)
        assertTrue(result is ApkImportValidation.Invalid)
    }

    @Test
    fun `invalid zip header is rejected (not a real apk)`() {
        val result = validCase("renamed.apk", 4_000_000L, elfHeader)
        assertTrue(result is ApkImportValidation.Invalid)
        assertTrue((result as ApkImportValidation.Invalid).reason.contains("ZIP"))
    }

    @Test
    fun `empty file is rejected`() {
        val result = validCase("empty.apk", 0L, apkHeader)
        assertTrue(result is ApkImportValidation.Invalid)
    }

    @Test
    fun `tiny file below minimum size is rejected`() {
        val result = validCase("tiny.apk", 100L, apkHeader)
        assertTrue(result is ApkImportValidation.Invalid)
    }

    @Test
    fun `null name is rejected`() {
        val result = validCase(null, 4_000_000L, apkHeader)
        assertTrue(result is ApkImportValidation.Invalid)
    }

    @Test
    fun `apk extension is case insensitive`() {
        val result = validCase("GAME.APK", 2_000_000L, apkHeader)
        assertTrue(result is ApkImportValidation.Valid)
    }

    @Test
    fun `zip header check handles short buffers`() {
        assertFalse(ApkImportValidator.isZipApkHeader(byteArrayOf(0x50, 0x4B)))
        assertFalse(ApkImportValidator.isZipApkHeader(null))
        assertTrue(ApkImportValidator.isZipApkHeader(apkHeader))
    }
}
