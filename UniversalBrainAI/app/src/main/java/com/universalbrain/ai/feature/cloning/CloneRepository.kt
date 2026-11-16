package com.universalbrain.ai.feature.cloning

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.webkit.WebView
import com.universalbrain.ai.core.database.CloneDao
import com.universalbrain.ai.core.database.CloneInstance
import com.universalbrain.ai.domain.cloning.ClonePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Clone instance lifecycle. Real isolation model, honestly scoped:
 *  - WEB instances run in dedicated Android processes (2 slots) with their own
 *    WebView data directory each — genuine storage isolation per instance.
 *  - APP instances launch the installed application through the platform
 *    (Android does not permit in-process cloning of other apps).
 *  - Every instance owns a private data directory for its session artifacts.
 */
class CloneRepository(
    private val context: Context,
    private val cloneDao: CloneDao,
    private val webSlotCount: Int
) {

    private val slots = arrayOfNulls<String>(webSlotCount)

    val instanceRoot: File
        get() = File(context.filesDir, "instances").apply { mkdirs() }

    fun policy(totalRamMb: Long, isLowRam: Boolean): ClonePolicy.Decision =
        ClonePolicy.maxInstances(totalRamMb, isLowRam, webSlotCount)

    suspend fun create(
        appPackage: String,
        appLabel: String,
        kind: String,
        desiredName: String?
    ): CloneInstance = withContext(Dispatchers.IO) {
        val existing = cloneDao.forApp(appPackage)
        val index = (existing.maxOfOrNull { it.index } ?: 0) + 1
        val id = UUID.randomUUID().toString()
        val dataDir = File(instanceRoot, id).apply { mkdirs() }
        val instance = CloneInstance(
            id = id,
            appPackage = appPackage,
            appLabel = appLabel,
            name = desiredName?.takeIf { it.isNotBlank() } ?: "$appLabel Clone %02d".format(index),
            index = index,
            kind = kind,
            status = "CREATED",
            dataDirPath = dataDir.absolutePath,
            accountId = null,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis()
        )
        cloneDao.insert(instance)
        instance
    }

    suspend fun rename(id: String, newName: String) {
        cloneDao.get(id)?.let { cloneDao.update(it.copy(name = newName)) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        cloneDao.get(id)?.let { entity ->
            entity.dataDirPath?.let { File(it).deleteRecursively() }
            cloneDao.delete(id)
        }
        slots.indexOfFirst { it == id }.takeIf { it >= 0 }?.let { slots[it] = null }
        Unit
    }

    /** @return slot index or null when all WEB slots are busy. */
    @Synchronized
    fun acquireSlot(instanceId: String): Int? {
        val existing = slots.indexOfFirst { it == instanceId }
        if (existing >= 0) return existing
        val free = slots.indexOfFirst { it == null }
        if (free < 0) return null
        slots[free] = instanceId
        return free
    }

    @Synchronized
    fun releaseSlot(instanceId: String) {
        slots.indexOfFirst { it == instanceId }.takeIf { it >= 0 }?.let { slots[it] = null }
    }

    suspend fun launch(instance: CloneInstance): String? {
        return if (instance.kind == "WEB") {
            val slot = acquireSlot(instance.id)
                ?: return "All $webSlotCount isolation slots are busy. Close a WEB instance first."
            cloneDao.setStatus(instance.id, "ACTIVE", System.currentTimeMillis())
            val intent = Intent(context, if (slot == 0) WebInstanceActivity0::class.java else WebInstanceActivity1::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instance.id)
                .putExtra(EXTRA_INSTANCE_NAME, instance.name)
                .putExtra(EXTRA_SLOT, slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { releaseSlot(instance.id); return "Could not open instance: ${it.message}" }
            null
        } else {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(instance.appPackage)
            if (launchIntent == null) {
                cloneDao.setStatus(instance.id, "ERROR", System.currentTimeMillis())
                return "${instance.appLabel} is not installed on this device."
            }
            cloneDao.setStatus(instance.id, "ACTIVE", System.currentTimeMillis())
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent) }
                .onFailure { return "Launch failed: ${it.message}" }
            null
        }
    }

    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun installedApps(): List<InstalledAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos.mapNotNull { info ->
            val pkg = info.activityInfo.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val version = runCatching {
                val packageInfo = pm.getPackageInfo(pkg, 0)
                if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
                else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            }.getOrDefault(0L)
            InstalledAppInfo(
                packageName = pkg,
                label = info.loadLabel(pm)?.toString() ?: pkg,
                versionName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "?",
                versionCode = version,
                isSystem = (info.activityInfo.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                icon = runCatching { info.loadIcon(pm) }.getOrNull()
            )
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    data class InstalledAppInfo(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val isSystem: Boolean,
        val icon: android.graphics.drawable.Drawable?
    )

    companion object {
        const val EXTRA_INSTANCE_ID = "extra.instance_id"
        const val EXTRA_INSTANCE_NAME = "extra.instance_name"
        const val EXTRA_SLOT = "extra.slot"
    }
}
