package com.multispace.manager

import com.multispace.manager.core.XapkBundleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * XAPK/APKS bundle classification: base APK vs split APKs, manifest parsing,
 * and honest null when the base cannot be identified.
 */
class XapkBundleParserTest {

    private val manifest = """
        {
          "xapk_version": 2,
          "package_name": "com.game.test",
          "name": "Test Game",
          "version_name": "1.2.3",
          "split_apks": [
            {"file": "config.arm64_v8a.apk", "id": "arm64"},
            {"file": "config.en.apk", "id": "en"}
          ]
        }
    """.trimIndent()

    @Test
    fun `manifest fields are parsed`() {
        val info = XapkBundleParser.readManifest(manifest)!!
        assertEquals("com.game.test", info.packageName)
        assertEquals("Test Game", info.label)
        assertEquals("1.2.3", info.versionName)
        assertEquals(listOf("config.arm64_v8a.apk", "config.en.apk"), info.splitApkFiles)
    }

    @Test
    fun `manifest-based split classification picks the package-named base`() {
        val parsed = XapkBundleParser.parse(
            fileName = "testgame.xapk",
            manifestJson = manifest,
            apkEntryNames = listOf("com.game.test.apk", "config.arm64_v8a.apk", "config.en.apk")
        )
        assertEquals("com.game.test.apk", parsed.baseApkEntry)
        assertEquals(listOf("config.arm64_v8a.apk", "config.en.apk"), parsed.splitApkEntries)
        assertEquals("com.game.test", parsed.packageName)
        assertEquals("Test Game", parsed.label)
        assertEquals("1.2.3", parsed.versionName)
    }

    @Test
    fun `bundletool style splits are classified without manifest`() {
        val parsed = XapkBundleParser.parse(
            fileName = "game.apks",
            manifestJson = null,
            apkEntryNames = listOf(
                "splits/base.apk", "splits/config.arm64_v8a.apk", "splits/config.xxhdpi.apk"
            )
        )
        assertEquals("splits/base.apk", parsed.baseApkEntry)
        assertEquals(2, parsed.splitApkEntries.size)
        assertNull(parsed.packageName)
    }

    @Test
    fun `without manifest the single non-config apk is the base`() {
        val parsed = XapkBundleParser.parse(
            fileName = "app.xapk",
            manifestJson = null,
            apkEntryNames = listOf("com.foo.bar.apk", "config.en.apk")
        )
        assertEquals("com.foo.bar.apk", parsed.baseApkEntry)
        assertEquals(listOf("config.en.apk"), parsed.splitApkEntries)
        assertEquals("app", parsed.label)
    }

    @Test
    fun `case insensitive config detection`() {
        val parsed = XapkBundleParser.parse(
            fileName = null,
            manifestJson = null,
            apkEntryNames = listOf("base.apk", "CONFIG.ARM.apk")
        )
        assertEquals("base.apk", parsed.baseApkEntry)
        assertEquals(listOf("CONFIG.ARM.apk"), parsed.splitApkEntries)
    }

    @Test
    fun `all-splits bundle reports no base honestly`() {
        val parsed = XapkBundleParser.parse(
            fileName = "broken.xapk",
            manifestJson = null,
            apkEntryNames = listOf("config.arm64_v8a.apk", "config.en.apk")
        )
        assertNull(parsed.baseApkEntry)
    }

    @Test
    fun `corrupt manifest json is ignored and heuristics still apply`() {
        val parsed = XapkBundleParser.parse(
            fileName = "game.xapk",
            manifestJson = "{ not json !!!",
            apkEntryNames = listOf("base.apk", "config.arm.apk")
        )
        assertEquals("base.apk", parsed.baseApkEntry)
        assertNull(parsed.packageName)
    }

    @Test
    fun `empty bundle has no base`() {
        val parsed = XapkBundleParser.parse(fileName = "empty.xapk", manifestJson = null, apkEntryNames = emptyList())
        assertNull(parsed.baseApkEntry)
        assertEquals(0, parsed.splitApkEntries.size)
    }
}
