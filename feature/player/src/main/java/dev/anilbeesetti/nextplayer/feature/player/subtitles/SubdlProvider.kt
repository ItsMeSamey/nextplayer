package dev.anilbeesetti.nextplayer.feature.player.subtitles

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup

class SubdlProvider : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.SUBDL

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isBlank()) return@withContext emptyList()

        val suggestions = fetchSuggestions(query).take(4)
        if (suggestions.isEmpty()) return@withContext emptyList()

        val preferredLanguage = request.preferredLanguage
            ?.lowercase(Locale.ROOT)
            ?.substringBefore('-')

        val allResults = coroutineScope {
            suggestions.map { suggestion ->
                async { fetchSubtitlePageResults(suggestion.pagePath, preferredLanguage) }
            }.awaitAll().flatten()
        }

        allResults
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

    private fun fetchSuggestions(query: String): List<SubdlSuggestion> {
        val response = runCatching {
            httpGet("https://api.subdl.com/auto?query=${query.urlEncode()}")
        }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = runCatching { org.json.JSONObject(response.bodyAsString()) }.getOrNull() ?: return emptyList()
        val results = root.optJSONArray("results") ?: JSONArray()
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val name = item.optString("name").ifBlank { continue }
                val pagePath = item.optString("link").ifBlank { continue }
                add(SubdlSuggestion(name = name, pagePath = pagePath))
            }
        }
    }

    private fun fetchSubtitlePageResults(pagePath: String, preferredLanguage: String?): List<OnlineSubtitleResult> {
        val response = runCatching {
            httpGet("https://subdl.com$pagePath")
        }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = Jsoup.parse(response.bodyAsString(), "https://subdl.com")
        val sections = root.select("div.flex.flex-col.mt-4.select-none")
        if (sections.isEmpty()) return emptyList()

        return sections.flatMap { section ->
            val language = section.selectFirst("h2, h3, h4")
                ?.text()
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.substringBefore('-')
                ?.ifBlank { null }

            if (!preferredLanguage.isNullOrBlank() && !language.equals(preferredLanguage, ignoreCase = true)) {
                return@flatMap emptyList()
            }

            section.select("li").mapNotNull { row ->
                val fileName = row.selectFirst("h4, a h4")?.text()?.trim().orEmpty()
                val href = row.selectFirst("a[href*=dl.subdl.com], a[href$=.zip]")?.attr("href")?.trim().orEmpty()
                if (href.isBlank()) return@mapNotNull null

                val title = fileName.ifBlank { "SubDL subtitle" }
                val downloadUrl = when {
                    href.startsWith("http") -> href
                    else -> "https://subdl.com$href"
                }

                OnlineSubtitleResult(
                    id = "subdl:${downloadUrl.hashCode()}",
                    source = source,
                    displayName = title,
                    languageCode = language,
                    downloadUrl = downloadUrl,
                    detailsUrl = "https://subdl.com$pagePath",
                )
            }
        }
    }

    private data class SubdlSuggestion(
        val name: String,
        val pagePath: String,
    )
}
