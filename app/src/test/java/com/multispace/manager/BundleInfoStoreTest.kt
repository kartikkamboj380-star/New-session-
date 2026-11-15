package com.multispace.manager

import com.multispace.manager.apk.BundleArtifact
import com.multispace.manager.apk.BundleInfoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Sidecar persistence for imported bundles (splits + OBB paths). */
class BundleInfoStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round trip preserves splits and obb paths`() {
        val target = tmp.newFile("app.bundle.json")
        val artifact = BundleArtifact(
            packageName = "com.game.test",
            splitApkPaths = listOf("/data/app/a.split0.apk", "/data/app/a.split1.apk"),
            obbPaths = listOf("/data/app/a.obb/main.100.com.game.test.obb")
        )
        assertTrue(BundleInfoStore.save(target, artifact))
        val loaded = BundleInfoStore.load(target)!!
        assertEquals("com.game.test", loaded.packageName)
        assertEquals(2, loaded.splitApkPaths.size)
        assertEquals(1, loaded.obbPaths.size)
    }

    @Test
    fun `fileFor maps apk path to sibling sidecar`() {
        val apk = File(tmp.root, "abc-123.apk")
        val sidecar = BundleInfoStore.fileFor(apk.absolutePath)
        assertEquals("abc-123.bundle.json", sidecar.name)
        assertEquals(tmp.root.absolutePath, sidecar.parentFile?.absolutePath)
    }

    @Test
    fun `missing sidecar loads as null`() {
        assertNull(BundleInfoStore.load(File(tmp.root, "nope.bundle.json")))
    }

    @Test
    fun `corrupt sidecar loads as null instead of crashing`() {
        val target = tmp.newFile("bad.bundle.json")
        target.writeText("{ invalid !!!")
        assertNull(BundleInfoStore.load(target))
    }

    @Test
    fun `delete removes the sidecar`() {
        val target = tmp.newFile("gone.bundle.json")
        target.writeText("{}")
        assertTrue(BundleInfoStore.delete(target))
        assertFalse(target.exists())
        assertTrue(BundleInfoStore.delete(target))
    }
}
