package dev.anilbeesetti.nextplayer.feature.player.subtitles

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class MoviesSubtitlesOrgProvider : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.MOVIESUBTITLES

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isBlank()) return@withContext emptyList()

        val response = runCatching {
            httpGet("https://www.moviesubtitles.org/search.php?q=${query.urlEncode()}")
        }.getOrNull() ?: return@withContext emptyList()
        if (response.code !in 200..299) return@withContext emptyList()

        val preferredLanguage = request.preferredLanguage
            ?.lowercase(Locale.ROOT)
            ?.substringBefore('-')

        val root = Jsoup.parse(response.bodyAsString(), "https://www.moviesubtitles.org")
        val movieLinks = root.select("a[href^=/movie-]")
            .mapNotNull { anchor -> anchor.attr("href").takeIf { it.isNotBlank() } }
            .distinct()
            .take(3)

        val results = coroutineScope {
            movieLinks.map { link ->
                async {
                    fetchMovieSubtitles(link)
                }
            }.awaitAll().flatten()
        }

        results
            .filter { result ->
                preferredLanguage.isNullOrBlank() ||
                    result.languageCode.equals(preferredLanguage, ignoreCase = true)
            }
            .ifEmpty { results }
            .distinctBy { it.downloadUrl }
            .take(100)
    }

    override suspend fun download(result: OnlineSubtitleResult): DownloadedSubtitle? = withContext(Dispatchers.IO) {
        val url = result.downloadUrl ?: return@withContext null
        val response = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        if (response.code !in 200..299 || response.body.isEmpty()) return@withContext null

        val isZip = response.contentType?.contains("zip", ignoreCase = true) == true ||
            response.body.size > 4 &&
            response.body[0] == 0x50.toByte() &&
            response.body[1] == 0x4B.toByte()

        if (isZip) {
            return@withContext firstSubtitleFromZip(response.body)
        }

        val fileName = resolveFileName(
            fallback = "${result.displayName.ifBlank { "subtitle" }}.srt",
            headers = response.headers,
            url = url,
        )
        return@withContext DownloadedSubtitle(fileName = fileName, bytes = response.body)
    }

    private fun fetchMovieSubtitles(moviePath: String): List<OnlineSubtitleResult> {
        val response = runCatching {
            httpGet("https://www.moviesubtitles.org$moviePath")
        }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = Jsoup.parse(response.bodyAsString(), "https://www.moviesubtitles.org")
        return root.select("div[style=margin-bottom:0.5em; padding:3px;]")
            .mapNotNull { row ->
                val subtitleLink = row.selectFirst("a[href^=/subtitle-]")?.attr("href") ?: return@mapNotNull null
                val language = row.selectFirst("img[src*=images/flags/]")
                    ?.attr("alt")
                    ?.lowercase(Locale.ROOT)
                    ?.ifBlank { null }

                val title = row.selectFirst("b")?.text()?.trim().orEmpty()
                val downloadPath = subtitleLink.replace("/subtitle-", "/download-")

                OnlineSubtitleResult(
                    id = "moviesubtitles:$downloadPath",
                    source = source,
                    displayName = title.ifBlank { downloadPath },
                    languageCode = language,
                    downloadUrl = "https://www.moviesubtitles.org$downloadPath",
                    detailsUrl = "https://www.moviesubtitles.org$subtitleLink",
                )
            }
    }
}
