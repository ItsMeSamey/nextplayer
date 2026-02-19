package dev.anilbeesetti.nextplayer.feature.player.subtitles

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class PodnapisiProvider : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.PODNAPISI

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isBlank()) return@withContext emptyList()

        val suggestions = fetchSuggestions(query).take(4)
        if (suggestions.isEmpty()) return@withContext emptyList()

        val preferredLanguage = request.preferredLanguage
            ?.lowercase(Locale.ROOT)
            ?.substringBefore('-')
            ?.takeIf { it.isNotBlank() }

        val allResults = coroutineScope {
            suggestions.map { suggestion ->
                async {
                    fetchMovieSubtitles(
                        movieId = suggestion.id,
                        movieTitle = suggestion.title,
                    )
                }
            }.awaitAll().flatten()
        }

        allResults
            .filter { result ->
                preferredLanguage.isNullOrBlank() ||
                    result.languageCode.equals(preferredLanguage, ignoreCase = true)
            }
            .ifEmpty { allResults }
            .distinctBy { listOf(it.downloadUrl.orEmpty(), it.displayName.lowercase(Locale.ROOT), it.languageCode.orEmpty().lowercase(Locale.ROOT)) }
            .take(120)
    }

    override suspend fun download(result: OnlineSubtitleResult): OnlineSubtitleDownloadResult? = withContext(Dispatchers.IO) {
        val url = result.downloadUrl ?: return@withContext null
        val response = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        if (response.code !in 200..299 || response.body.isEmpty()) return@withContext null

        val isZip = response.contentType?.contains("zip", ignoreCase = true) == true ||
            (response.body.size > 3 && response.body[0] == 0x50.toByte() && response.body[1] == 0x4B.toByte())

        if (isZip) return@withContext firstSubtitleFromZip(response.body)

        val fileName = resolveFileName(
            fallback = "${result.displayName.ifBlank { "subtitle" }}.srt",
            headers = response.headers,
            url = url,
        )
        DownloadedSubtitle(fileName = fileName, bytes = response.body)
    }

    private fun fetchSuggestions(query: String): List<PodnapisiSuggestion> {
        val response = runCatching {
            httpGet(
                url = "https://www.podnapisi.net/moviedb/search/?keywords=${query.urlEncode()}",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            )
        }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = runCatching { JSONObject(response.bodyAsString()) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { continue }
                val title = item.optString("title").ifBlank { continue }
                add(PodnapisiSuggestion(id = id, title = title))
            }
        }
    }

    private fun fetchMovieSubtitles(movieId: String, movieTitle: String): List<OnlineSubtitleResult> {
        val pageUrl = "https://www.podnapisi.net/subtitles/search/$movieId"
        val response = runCatching { httpGet(pageUrl) }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = Jsoup.parse(response.bodyAsString(), "https://www.podnapisi.net")
        return root.select("tbody tr")
            .mapNotNull { row ->
                val href = row.selectFirst("a[rel=nofollow][href]")?.attr("href")?.trim().orEmpty()
                if (href.isBlank()) return@mapNotNull null

                val languageText = row.selectFirst("abbr")?.text()?.trim().orEmpty()
                val languageCode = normalizeLanguageCode(languageText)
                val fileName = row.selectFirst("span.release")?.text()?.trim().orEmpty()
                val downloadUrl = if (href.startsWith("http")) href else "https://www.podnapisi.net$href"

                OnlineSubtitleResult(
                    id = "podnapisi:${downloadUrl.hashCode()}",
                    source = source,
                    displayName = fileName.ifBlank { movieTitle.ifBlank { "Podnapisi subtitle" } },
                    languageCode = languageCode,
                    downloadUrl = downloadUrl,
                    detailsUrl = pageUrl,
                )
            }
    }

    private fun normalizeLanguageCode(value: String): String? {
        if (value.isBlank()) return null
        val normalized = value.trim()
        val lower = normalized.lowercase(Locale.ROOT).substringBefore('-').trim()
        if (lower.length == 2 && lower.all { it.isLetter() }) return lower

        return Locale.getAvailableLocales()
            .firstOrNull { locale ->
                locale.displayLanguage.equals(normalized, ignoreCase = true) ||
                    locale.getDisplayLanguage(Locale.ENGLISH).equals(normalized, ignoreCase = true)
            }
            ?.language
            ?.takeIf { it.length == 2 }
            ?.lowercase(Locale.ROOT)
    }

    private data class PodnapisiSuggestion(
        val id: String,
        val title: String,
    )
}
