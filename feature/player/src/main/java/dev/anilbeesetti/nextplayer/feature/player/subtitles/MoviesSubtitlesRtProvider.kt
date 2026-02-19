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
        val originalUrl = result.downloadUrl ?: return@withContext null
        val urlCandidates = buildSet {
            addAll(buildDownloadUrlCandidates(originalUrl))
            result.detailsUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { details ->
                    runCatching { fetchMovieSubtitle(result.displayName, details) }
                        .getOrNull()
                        .orEmpty()
                        .forEach { refreshed ->
                            refreshed.downloadUrl
                                ?.takeIf { it.isNotBlank() }
                                ?.let { addAll(buildDownloadUrlCandidates(it)) }
                        }
                }
        }

        val response = urlCandidates
            .asSequence()
            .mapNotNull { candidate ->
                val current = runCatching { httpGet(candidate) }.getOrNull() ?: return@mapNotNull null
                if (current.code in 200..299 && current.body.isNotEmpty()) candidate to current else null
            }
            .firstOrNull()
            ?: return@withContext null

        val finalUrl = response.first
        val finalResponse = response.second

        val isZip = finalResponse.contentType?.contains("zip", ignoreCase = true) == true ||
            (finalResponse.body.size > 3 && finalResponse.body[0] == 0x50.toByte() && finalResponse.body[1] == 0x4B.toByte())
        if (isZip) return@withContext firstSubtitleFromZip(finalResponse.body)

        val fileName = resolveFileName(
            fallback = "${result.displayName.ifBlank { "subtitle" }}.srt",
            headers = finalResponse.headers,
            url = finalUrl,
        )
        DownloadedSubtitle(fileName = fileName, bytes = finalResponse.body)
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

        val downloadUrl = normalizeDownloadUrl(link)
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

    private fun buildDownloadUrlCandidates(url: String): List<String> {
        val normalized = normalizeDownloadUrl(url)
        return buildList {
            add(normalized)
            if (normalized.startsWith("http://", ignoreCase = true)) {
                add(normalized.replaceFirst("http://", "https://"))
            }
            if (normalized.startsWith("https://www.moviesubtitlesrt.com", ignoreCase = true)) {
                add(normalized.replaceFirst("https://www.moviesubtitlesrt.com", "https://moviesubtitlesrt.com"))
            }
            if (normalized.startsWith("http://www.moviesubtitlesrt.com", ignoreCase = true)) {
                add(normalized.replaceFirst("http://www.moviesubtitlesrt.com", "https://moviesubtitlesrt.com"))
            }
        }.distinct()
    }

    private fun normalizeDownloadUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return trimmed

        val absolute = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://moviesubtitlesrt.com$trimmed"
            else -> trimmed
        }

        return when {
            absolute.startsWith("http://moviesubtitlesrt.com", ignoreCase = true) -> {
                absolute.replaceFirst("http://", "https://")
            }
            absolute.startsWith("http://www.moviesubtitlesrt.com", ignoreCase = true) -> {
                absolute.replaceFirst("http://www.moviesubtitlesrt.com", "https://moviesubtitlesrt.com")
            }
            else -> absolute
        }
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
