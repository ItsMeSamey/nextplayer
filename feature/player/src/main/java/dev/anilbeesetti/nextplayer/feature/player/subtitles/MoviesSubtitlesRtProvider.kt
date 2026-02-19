package dev.anilbeesetti.nextplayer.feature.player.subtitles

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class MoviesSubtitlesRtProvider : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.MOVIESUBTITLESRT

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isBlank()) return@withContext emptyList()

        val response = runCatching {
            httpGet("https://moviesubtitlesrt.com/?s=${query.urlEncode()}")
        }.getOrNull() ?: return@withContext emptyList()
        if (response.code !in 200..299) return@withContext emptyList()

        val preferredLanguage = request.preferredLanguage
            ?.lowercase(Locale.ROOT)
            ?.substringBefore('-')

        val root = Jsoup.parse(response.bodyAsString(), "https://moviesubtitlesrt.com")
        val movieLinks = root.select("div.inside-article > header > h2 > a[href]")
            .mapNotNull { anchor ->
                val title = anchor.text().trim()
                val link = anchor.attr("href").trim()
                if (title.isBlank() || link.isBlank()) null else title to link
            }
            .distinctBy { it.second }
            .take(5)

        val results = coroutineScope {
            movieLinks.map { (title, link) ->
                async { fetchMovieSubtitle(title = title, pageUrl = link) }
            }.awaitAll().flatten()
        }

        results
            .filter { result ->
                preferredLanguage.isNullOrBlank() ||
                    result.languageCode.equals(preferredLanguage, ignoreCase = true)
            }
            .ifEmpty { results }
            .distinctBy { it.downloadUrl.orEmpty() }
            .take(100)
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

    private fun fetchMovieSubtitle(title: String, pageUrl: String): List<OnlineSubtitleResult> {
        val response = runCatching { httpGet(pageUrl) }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = Jsoup.parse(response.bodyAsString(), "https://moviesubtitlesrt.com")
        val languageText = root.selectFirst("tbody > tr:nth-child(2) > td:last-child")
            ?.text()
            ?.trim()
            .orEmpty()
        val languageCode = normalizeLanguageCode(languageText)

        val link = root.selectFirst("center > a[href]")
            ?.attr("href")
            ?.trim()
            .orEmpty()
        if (link.isBlank()) return emptyList()

        val downloadUrl = if (link.startsWith("http")) link else "https://moviesubtitlesrt.com$link"
        return listOf(
            OnlineSubtitleResult(
                id = "moviesubtitlesrt:${downloadUrl.hashCode()}",
                source = source,
                displayName = title.ifBlank { "MovieSubtitlesRT subtitle" },
                languageCode = languageCode,
                downloadUrl = downloadUrl,
                detailsUrl = pageUrl,
            ),
        )
    }

    private fun normalizeLanguageCode(value: String): String? {
        if (value.isBlank()) return null
        val raw = value.trim()
        val simple = raw.lowercase(Locale.ROOT).substringBefore('-').trim()
        if (simple.length == 2 && simple.all { it.isLetter() }) return simple

        val byDisplayName = Locale.getAvailableLocales()
            .firstOrNull { locale ->
                locale.displayLanguage.equals(raw, ignoreCase = true) ||
                    locale.getDisplayLanguage(Locale.ENGLISH).equals(raw, ignoreCase = true)
            }
            ?.language
            ?.takeIf { it.length == 2 }
            ?.lowercase(Locale.ROOT)

        return byDisplayName
    }
}

