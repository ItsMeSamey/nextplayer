package dev.anilbeesetti.nextplayer.feature.player.subtitles

import android.content.ContentResolver
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenSubtitlesRestProvider(
    private val contentResolver: ContentResolver,
) : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.OPENSUBTITLES

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isBlank()) return@withContext emptyList()

        val preferredLanguage = request.preferredLanguage
            ?.lowercase()
            ?.substringBefore('-')
            ?.takeIf { it.length in 2..3 }
        val hashResults = request.videoUri?.let { videoUri ->
            searchByHash(videoUri = videoUri, preferredLanguage = preferredLanguage)
        }.orEmpty()

        if (hashResults.isNotEmpty()) {
            return@withContext hashResults.take(80)
        }

        return@withContext searchByQuery(query = query, preferredLanguage = preferredLanguage).take(80)
    }

    override suspend fun download(result: OnlineSubtitleResult): DownloadedSubtitle? = withContext(Dispatchers.IO) {
        val url = result.downloadUrl ?: return@withContext null
        val response = runCatching {
            httpGet(
                url = url,
                headers = mapOf("X-User-Agent" to "TemporaryUserAgent"),
            )
        }.getOrNull() ?: return@withContext null

        if (response.code !in 200..299 || response.body.isEmpty()) return@withContext null

        val bytes = runCatching { ungzip(response.body) }.getOrElse { response.body }
        val baseName = result.displayName.ifBlank { "subtitle" }
        val fileName = resolveFileName(
            fallback = if (baseName.endsWith(".srt", ignoreCase = true)) baseName else "$baseName.srt",
            headers = response.headers,
            url = url,
        )
        DownloadedSubtitle(fileName = fileName, bytes = bytes)
    }

    private fun parseResults(rawJson: String): List<OnlineSubtitleResult> {
        val parsed = runCatching { JSONArray(rawJson) }.getOrElse { return emptyList() }
        val output = mutableListOf<OnlineSubtitleResult>()

        for (index in 0 until parsed.length()) {
            val item = parsed.optJSONObject(index) ?: continue
            val id = item.optString("IDSubtitleFile").ifBlank { item.optString("IDSubtitle") }
            val downloadUrl = item.optString("SubDownloadLink").takeIf { it.isNotBlank() }
            if (id.isBlank() || downloadUrl == null) continue

            val fileName = item.optString("SubFileName").ifBlank { item.optString("MovieReleaseName") }
            val language = item.optString("ISO639")
                .ifBlank { item.optString("SubLanguageID") }
                .ifBlank { null }
            val title = when {
                fileName.isNotBlank() -> fileName
                else -> item.optString("MovieName").ifBlank { "subtitle_$id" }
            }

            output += OnlineSubtitleResult(
                id = "opensubtitles:$id",
                source = source,
                displayName = title,
                languageCode = language,
                downloadUrl = downloadUrl,
                detailsUrl = item.optString("SubtitlesLink").takeIf { it.isNotBlank() },
            )
        }

        return output.distinctBy { result ->
            listOf(
                result.displayName.lowercase(),
                result.languageCode.orEmpty().lowercase(),
                result.downloadUrl.orEmpty(),
            )
        }
    }

    private fun searchByHash(
        videoUri: android.net.Uri,
        preferredLanguage: String?,
    ): List<OnlineSubtitleResult> {
        val hash = runCatching { OpenSubtitlesHash.compute(contentResolver, videoUri) }.getOrNull() ?: return emptyList()
        val languages = buildList {
            preferredLanguage?.let { add(it) }
            add("all")
        }.distinct()

        for (language in languages) {
            val response = runCatching {
                httpGet(
                    url = "https://rest.opensubtitles.org/search/moviebytesize-${hash.byteSize}/moviehash-${hash.hash}/sublanguageid-$language",
                    headers = mapOf("X-User-Agent" to "TemporaryUserAgent"),
                )
            }.getOrNull() ?: continue

            if (response.code !in 200..299) continue
            val parsed = parseResults(response.bodyAsString())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun searchByQuery(
        query: String,
        preferredLanguage: String?,
    ): List<OnlineSubtitleResult> {
        val language = preferredLanguage ?: "all"
        val response = runCatching {
            httpGet(
                url = "https://rest.opensubtitles.org/search/query-${query.urlEncode()}/sublanguageid-$language",
                headers = mapOf("X-User-Agent" to "TemporaryUserAgent"),
            )
        }.getOrNull() ?: return emptyList()

        if (response.code !in 200..299) return emptyList()
        return parseResults(response.bodyAsString())
    }
}
