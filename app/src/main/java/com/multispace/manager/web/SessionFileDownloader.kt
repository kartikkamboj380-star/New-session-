package com.multispace.manager.web

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Minimal session-scoped downloader: writes into the session's private
 * downloads directory so files stay inside that session's isolated storage.
 */
object SessionFileDownloader {

    fun download(url: String, contentDisposition: String?, mimeType: String?, targetDir: File): File? {
        require(url.startsWith("https://")) { "Only HTTPS downloads are allowed" }
        targetDir.mkdirs()
        val fileName = sanitizeFileName(pickFileName(url, contentDisposition, mimeType))
        val output = File(targetDir, uniqueName(targetDir, fileName))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                output.outputStream().use { output2 -> input.copyTo(output2, 8192) }
            }
            return output
        } finally {
            connection.disconnect()
        }
    }

    private fun pickFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        contentDisposition?.let {
            val match = Regex("filename\\*?=\\s*\"?([^\";]+)\"?").find(it)
            match?.groupValues?.get(1)?.let { decoded -> return URLDecoder.decode(decoded, "UTF-8") }
        }
        val lastSegment = url.substringBefore('?').substringAfterLast('/')
        if (lastSegment.isNotBlank() && lastSegment.contains('.')) return lastSegment
        return "download_${System.currentTimeMillis()}${mimeType?.let { extFor(it) } ?: ""}"
    }

    private fun extFor(mimeType: String): String = when {
        mimeType.contains("pdf") -> ".pdf"
        mimeType.contains("image/png") -> ".png"
        mimeType.contains("image/") -> ".img"
        mimeType.contains("zip") -> ".zip"
        else -> ""
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "download" }

    private fun uniqueName(dir: File, fileName: String): String {
        if (!File(dir, fileName).exists()) return fileName
        val dot = fileName.lastIndexOf('.')
        val (base, ext) = if (dot > 0) fileName.substring(0, dot) to fileName.substring(dot) else fileName to ""
        var i = 1
        while (File(dir, "$base($i)$ext").exists()) i++
        return "$base($i)$ext"
    }
}
