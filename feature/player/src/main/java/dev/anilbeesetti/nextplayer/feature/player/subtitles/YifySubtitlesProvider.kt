package dev.anilbeesetti.nextplayer.feature.player.subtitles

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup

class YifySubtitlesProvider : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.YIFY

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isBlank()) return@withContext emptyList()

        val preferredLanguage = request.preferredLanguage
            ?.lowercase(Locale.ROOT)
            ?.substringBefore('-')

        val movies = fetchMovies(query).take(4)
        if (movies.isEmpty()) return@withContext emptyList()

        val all = coroutineScope {
            movies.map { movie ->
                async { fetchMovieSubtitles(movie, preferredLanguage) }
            }.awaitAll().flatten()
        }

        all
            .distinctBy { listOf(it.downloadUrl.orEmpty(), it.displayName.lowercase(Locale.ROOT), it.languageCode.orEmpty().lowercase(Locale.ROOT)) }
            .take(120)
    }

    override suspend fun download(result: OnlineSubtitleResult): DownloadedSubtitle? = withContext(Dispatchers.IO) {
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

    private fun fetchMovies(query: String): List<YifyMovie> {
        val response = runCatching {
            httpGet("https://yifysubtitles.ch/ajax/search/?mov=${query.urlEncode()}")
        }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val parsed = runCatching { JSONArray(response.bodyAsString()) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until parsed.length()) {
                val item = parsed.optJSONObject(index) ?: continue
                val imdb = item.optString("imdb").ifBlank { continue }
                val title = item.optString("movie").ifBlank { continue }
                add(YifyMovie(title = title, imdb = imdb))
            }
        }
    }

    private fun fetchMovieSubtitles(movie: YifyMovie, preferredLanguage: String?): List<OnlineSubtitleResult> {
        val response = runCatching {
            httpGet("https://yifysubtitles.ch/movie-imdb/${movie.imdb}")
        }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = Jsoup.parse(response.bodyAsString(), "https://yifysubtitles.ch")
        val rows = root.select("table tbody tr")
        return rows.mapNotNull { row ->
            val language = row.selectFirst("span.sub-lang")
                ?.text()
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.substringBefore(' ')
                ?.ifBlank { null }

            if (!preferredLanguage.isNullOrBlank() && !language.equals(preferredLanguage, ignoreCase = true)) {
                return@mapNotNull null
            }

            val muted = row.selectFirst("a > span.text-muted") ?: return@mapNotNull null
            val anchor = muted.parent() ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val text = anchor.text().trim()
            val releaseName = text.substringAfter(' ', missingDelimiterValue = text).ifBlank { movie.title }
            val relativeZip = href
                .replace("/subtitles/", "/subtitle/")
                .let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" }
            val downloadUrl = if (relativeZip.startsWith("http")) relativeZip else "https://yifysubtitles.ch$relativeZip"

            OnlineSubtitleResult(
                id = "yify:${downloadUrl.hashCode()}",
                source = source,
                displayName = releaseName,
                languageCode = language,
                downloadUrl = downloadUrl,
                detailsUrl = if (href.startsWith("http")) href else "https://yifysubtitles.ch$href",
            )
        }
    }

    private data class YifyMovie(
        val title: String,
        val imdb: String,
    )
}
