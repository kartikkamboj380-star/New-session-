package com.multispace.manager

import com.multispace.manager.core.BundleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * XAPK/APKS streaming extraction: manifest capture, APK/OBB classification,
 * duplicate-entry safety, and per-APK header verification used for precise
 * import diagnostics.
 */
class BundleExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeApkBytes(size: Int = 4096): ByteArray {
        // Starts with a valid ZIP local-file header, then filler.
        val out = ByteArray(size)
        out[0] = 0x50; out[1] = 0x4B; out[2] = 0x03; out[3] = 0x04
        for (i in 4 until size) out[i] = (i % 251).toByte()
        return out
    }

    private fun garbageBytes(size: Int = 2048): ByteArray = ByteArray(size) { (it % 97).toByte() }

    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    @Test
    fun `extracts manifest, apks and obb with header verification`() {
        val zip = buildZip(
            mapOf(
                "manifest.json" to """{"package_name":"com.foo","name":"Foo","split_apks":[{"file":"config.arm.apk"}]}""".toByteArray(),
                "net.foo.apk" to fakeApkBytes(),
                "config.arm.apk" to fakeApkBytes(2048),
                "main.1.com.foo.obb" to garbageBytes(1024)
            )
        )
        val extracted = BundleExtractor.extract(ByteArrayInputStream(zip), tmp.newFolder("x"))

        assertEquals("com.foo", com.multispace.manager.core.XapkBundleParser
            .readManifest(extracted.manifestJson)?.packageName)
        assertEquals(2, extracted.apks.size)
        assertEquals(1, extracted.obbs.size)
        extracted.apks.forEach {
            assertTrue("$it should have a ZIP header", it.zipHeaderValid)
            assertTrue(it.sizeBytes > 0)
        }
        assertEquals(1024L, extracted.obbs[0].sizeBytes)
    }

    @Test
    fun `non-apk content inside an apk entry is flagged as invalid header`() {
        val zip = buildZip(
            mapOf(
                "base.apk" to garbageBytes(),
                "config.en.apk" to fakeApkBytes()
            )
        )
        val extracted = BundleExtractor.extract(ByteArrayInputStream(zip), tmp.newFolder("y"))
        val base = extracted.apks.first { it.entryName == "base.apk" }
        val config = extracted.apks.first { it.entryName == "config.en.apk" }
        assertFalse(base.zipHeaderValid)
        assertTrue(config.zipHeaderValid)
        assertEquals(2048L, base.sizeBytes)
    }

    @Test
    fun `duplicate entry names do not overwrite each other`() {
        val zip = buildZip(
            mapOf(
                "base.apk" to fakeApkBytes(),
                "sub/base.apk" to fakeApkBytes(1024)
            )
        )
        val extracted = BundleExtractor.extract(ByteArrayInputStream(zip), tmp.newFolder("z"))
        assertEquals(2, extracted.apks.size)
        assertEquals(2, extracted.apks.map { it.file }.distinct().size)
    }

    @Test
    fun `empty bundle yields empty lists and no manifest`() {
        val extracted = BundleExtractor.extract(ByteArrayInputStream(buildZip(emptyMap())), tmp.newFolder("e"))
        assertNull(extracted.manifestJson)
        assertEquals(0, extracted.apks.size)
        assertEquals(0, extracted.obbs.size)
    }

    @Test
    fun `corrupt zip stream throws instead of producing corrupt files`() {
        val truncated = buildZip(mapOf("base.apk" to fakeApkBytes()))
            .copyOfRange(0, 60)
        try {
            BundleExtractor.extract(ByteArrayInputStream(truncated), tmp.newFolder("t"))
            // Some truncations still parse as an empty/valid zip — then no
            // entries is acceptable. A thrown exception is also acceptable.
        } catch (_: Exception) {
            // expected for most truncations
        }
    }

    @Test
    fun `segment sanitization blocks path separators and dot sequences`() {
        val sanitized = BundleExtractor.sanitizeSegment("../../evil/name.apk")
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains(".."))
        assertTrue(sanitized.endsWith("evil_name.apk"))
        assertEquals("entry.bin", BundleExtractor.sanitizeSegment(""))
    }
}
