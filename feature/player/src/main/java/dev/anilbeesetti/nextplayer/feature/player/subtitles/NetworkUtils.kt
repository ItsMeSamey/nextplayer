package dev.anilbeesetti.nextplayer.feature.player.subtitles

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

internal const val USER_AGENT = "NextPlayer/1.0"

internal data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>>,
    val contentType: String?,
)

internal fun httpGet(
    url: String,
    userAgent: String = USER_AGENT,
    headers: Map<String, String> = emptyMap(),
): HttpResponse {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", userAgent)
        headers.forEach { (key, value) -> setRequestProperty(key, value) }
    }

    return connection.use { conn ->
        val statusCode = conn.responseCode
        val input = if (statusCode in 200..299) conn.inputStream else conn.errorStream
        val body = input?.readBytesSafely() ?: ByteArray(0)
        HttpResponse(
            code = statusCode,
            body = body,
            headers = conn.headerFields.orEmpty(),
            contentType = conn.contentType,
        )
    }
}

private fun InputStream.readBytesSafely(): ByteArray {
    val output = ByteArrayOutputStream()
    copyTo(output)
    return output.toByteArray()
}

internal inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}

internal fun HttpResponse.bodyAsString(): String = body.toString(StandardCharsets.UTF_8)

internal fun String.urlEncode(): String = java.net.URLEncoder.encode(this, StandardCharsets.UTF_8.name())

internal fun firstSubtitleFromZip(bytes: ByteArray): DownloadedSubtitle? {
    ZipInputStream(bytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val name = entry.name.substringAfterLast('/').trim()
                if (name.isNotEmpty() && name.isSubtitleFileName()) {
                    val content = zip.readBytesSafely()
                    return DownloadedSubtitle(fileName = File(name).name, bytes = content)
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return null
}

internal fun String.isSubtitleFileName(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return lower.endsWith(".srt") ||
        lower.endsWith(".ass") ||
        lower.endsWith(".ssa") ||
        lower.endsWith(".vtt") ||
        lower.endsWith(".ttml") ||
        lower.endsWith(".xml") ||
        lower.endsWith(".dfxp")
}

internal fun resolveFileName(
    fallback: String,
    headers: Map<String, List<String>>,
    url: String,
): String {
    val disposition = headers.entries.firstOrNull { it.key.equals("Content-Disposition", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        .orEmpty()

    val fromDisposition = Regex("filename\\*?=([^;]+)", RegexOption.IGNORE_CASE)
        .find(disposition)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim('"', '\'', ' ')
        ?.substringAfter("UTF-8''", missingDelimiterValue = "")
        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        ?.takeIf { it.isNotBlank() }

    val fromUrl = runCatching {
        URL(url).path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }.getOrNull()

    val candidate = fromDisposition ?: fromUrl ?: fallback
    return if (candidate.isSubtitleFileName()) candidate else "$candidate.srt"
}

internal fun ungzip(bytes: ByteArray): ByteArray {
    return GZIPInputStream(bytes.inputStream()).use { stream ->
        stream.readBytes()
    }
}
