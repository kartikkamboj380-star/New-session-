package com.multispace.manager.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure classifier for XAPK / APKS bundles (ZIP archives containing one base
 * APK, optional split APKs and optional OBB expansion files).
 *
 * Classification rules (in priority order):
 *  1. If manifest.json lists split_apks, those files are splits.
 *  2. Files whose last path segment starts with "config." are splits.
 *  3. Among the rest, the base APK is: exactly "base.apk", then
 *     "<package>.apk", then the only remaining candidate / first remaining.
 */
object XapkBundleParser {

    data class ManifestInfo(
        val packageName: String?,
        val label: String?,
        val versionName: String?,
        val splitApkFiles: List<String>
    )

    data class Parsed(
        val packageName: String?,
        val label: String?,
        val versionName: String?,
        val baseApkEntry: String?,
        val splitApkEntries: List<String>
    )

    fun readManifest(jsonText: String?): ManifestInfo? {
        if (jsonText.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return null
        fun str(key: String): String? = runCatching {
            obj[key]?.jsonPrimitive?.content
        }.getOrNull()
        val splits = runCatching {
            obj["split_apks"]?.jsonArray?.mapNotNull { element ->
                runCatching { element.jsonObject["file"]?.jsonPrimitive?.content }.getOrNull()
            }
        }.getOrNull() ?: emptyList()
        return ManifestInfo(
            packageName = str("package_name"),
            label = str("name"),
            versionName = str("version_name"),
            splitApkFiles = splits
        )
    }

    /**
     * @param apkEntryNames full ZIP entry paths of every .apk inside the bundle.
     */
    fun parse(
        fileName: String?,
        manifestJson: String?,
        apkEntryNames: List<String>
    ): Parsed {
        val manifest = readManifest(manifestJson)
        val manifestSplits = manifest?.splitApkFiles?.map { it.lowercase() }?.toSet()

        val splits = mutableListOf<String>()
        val baseCandidates = mutableListOf<String>()
        for (entry in apkEntryNames) {
            val segment = entry.substringAfterLast('/')
            val isManifestSplit = manifestSplits?.any {
                it == segment.lowercase() || it == entry.lowercase()
            } == true
            if (isManifestSplit || segment.startsWith("config.", ignoreCase = true)) {
                splits.add(entry)
            } else {
                baseCandidates.add(entry)
            }
        }

        val base = pickBase(baseCandidates, manifest?.packageName)

        return Parsed(
            packageName = manifest?.packageName,
            label = manifest?.label ?: fileName?.substringBeforeLast('.'),
            versionName = manifest?.versionName,
            baseApkEntry = base,
            splitApkEntries = splits.filter { it != base }
        )
    }

    private fun pickBase(candidates: List<String>, packageName: String?): String? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val segment = { entry: String -> entry.substringAfterLast('/') }
        candidates.firstOrNull { segment(it).equals("base.apk", ignoreCase = true) }?.let { return it }
        packageName?.let { pkg ->
            candidates.firstOrNull { segment(it).equals("$pkg.apk", ignoreCase = true) }?.let { return it }
        }
        candidates.firstOrNull { segment(it).startsWith("base", ignoreCase = true) }?.let { return it }
        return candidates[0]
    }
}
