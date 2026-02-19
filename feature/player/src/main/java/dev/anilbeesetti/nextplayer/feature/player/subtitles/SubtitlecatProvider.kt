package dev.anilbeesetti.nextplayer.feature.player.subtitles

import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup

class SubtitlecatProvider : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.SUBTITLECAT

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isBlank()) return@withContext emptyList()

        val preferredLanguage = request.preferredLanguage
            ?.lowercase(Locale.ROOT)
            ?.substringBefore('-')
            ?.ifBlank { null }

        val response = runCatching {
            httpGet("https://www.subtitlecat.com/index.php?search=${query.urlEncode()}&show=10000")
        }.getOrNull() ?: return@withContext emptyList()
        if (response.code !in 200..299) return@withContext emptyList()

        val root = Jsoup.parse(response.bodyAsString(), "https://www.subtitlecat.com")
        val movieLinks = root.select("table.sub-table > tbody > tr")
            .mapNotNull { row ->
                val firstCell = row.selectFirst("td") ?: return@mapNotNull null
                val anchor = firstCell.selectFirst("a[href]") ?: return@mapNotNull null
                val href = anchor.attr("abs:href").trim()
                val title = anchor.text().trim()
                if (href.isBlank() || title.isBlank()) return@mapNotNull null

                val sourceLanguage = Regex("""\(translated from\s+([^)]+)\)""", RegexOption.IGNORE_CASE)
                    .find(firstCell.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.let(::normalizeLanguageCode)
                SubtitlecatMovieLink(title = title, url = href, sourceLanguage = sourceLanguage)
            }
            .distinctBy { it.url }
            .take(5)

        val allResults = coroutineScope {
            movieLinks.map { movie ->
                async { fetchMovieSubtitles(movie) }
            }.awaitAll().flatten()
        }

        val filtered = if (preferredLanguage.isNullOrBlank()) {
            allResults.filter { !it.isTranslatable }
        } else {
            allResults.filter { result ->
                !result.isTranslatable || result.languageCode.equals(preferredLanguage, ignoreCase = true)
            }
        }

        filtered
            .distinctBy {
                listOf(
                    it.downloadUrl.orEmpty(),
                    it.displayName.lowercase(Locale.ROOT),
                    it.languageCode.orEmpty().lowercase(Locale.ROOT),
                    it.isTranslatable.toString(),
                )
            }
            .take(120)
    }

    override suspend fun download(result: OnlineSubtitleResult): OnlineSubtitleDownloadResult? = withContext(Dispatchers.IO) {
        val url = result.downloadUrl ?: return@withContext null

        if (result.isTranslatable) {
            val sourceResponse = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
            if (sourceResponse.code !in 200..299 || sourceResponse.body.isEmpty()) return@withContext null
            val sourceContent = sourceResponse.bodyAsString()
            val targetLanguage = result.languageCode?.trim().orEmpty()

            val translated = if (targetLanguage.isNotBlank()) {
                runCatching { translateSrt(sourceContent, targetLanguage) }.getOrElse { sourceContent }
            } else {
                sourceContent
            }

            val fileName = resolveFileName(
                fallback = result.displayName.ifBlank { "subtitle.srt" },
                headers = sourceResponse.headers,
                url = url,
            )
            return@withContext DownloadedSubtitle(
                fileName = fileName,
                bytes = translated.toByteArray(StandardCharsets.UTF_8),
            )
        }

        val response = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        if (response.code !in 200..299 || response.body.isEmpty()) return@withContext null

        val isZip = response.contentType?.contains("zip", ignoreCase = true) == true ||
            (response.body.size > 3 && response.body[0] == 0x50.toByte() && response.body[1] == 0x4B.toByte())
        if (isZip) return@withContext firstSubtitleFromZip(response.body)

        val fileName = resolveFileName(
            fallback = result.displayName.ifBlank { "subtitle.srt" },
            headers = response.headers,
            url = url,
        )
        DownloadedSubtitle(fileName = fileName, bytes = response.body)
    }

    private fun fetchMovieSubtitles(movie: SubtitlecatMovieLink): List<OnlineSubtitleResult> {
        val response = runCatching { httpGet(movie.url) }.getOrNull() ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val root = Jsoup.parse(response.bodyAsString(), "https://www.subtitlecat.com")
        return root.select("div.sub-single").mapNotNull { block ->
            val spans = block.select("span")
            val languageCode = spans.getOrNull(0)
                ?.selectFirst("img")
                ?.attr("alt")
                ?.trim()
                ?.let(::normalizeLanguageCode)
            val languageName = spans.getOrNull(1)?.text()?.trim().orEmpty()
            val actionSpan = spans.getOrNull(2)
            val downloadAnchor = actionSpan?.selectFirst("a[href]")
            val translateButton = actionSpan?.selectFirst("button[onclick]")

            if (downloadAnchor != null) {
                val href = downloadAnchor.attr("abs:href").trim()
                if (href.isBlank()) return@mapNotNull null
                val fileName = decodeFilenameFromUrl(href).ifBlank { "${movie.title}.srt" }
                val nativeLang = languageCode ?: normalizeLanguageCode(languageName)
                return@mapNotNull OnlineSubtitleResult(
                    id = "subtitlecat:${href.hashCode()}",
                    source = source,
                    displayName = fileName,
                    languageCode = nativeLang,
                    originalLanguageCode = nativeLang ?: movie.sourceLanguage,
                    isTranslatable = false,
                    isNativeSubtitle = true,
                    downloadUrl = href,
                    detailsUrl = movie.url,
                )
            }

            if (translateButton != null) {
                val spec = parseTranslateSpec(translateButton.attr("onclick"))
                val sourceUrl = spec.sourceUrl ?: return@mapNotNull null
                val targetLangRaw = languageCode
                    ?: translateButton.id().takeIf { it.isNotBlank() }
                    ?: languageName
                val targetLang = normalizeLanguageCode(targetLangRaw) ?: return@mapNotNull null

                val sourceFileName = decodeFilenameFromUrl(sourceUrl)
                val targetFileName = when {
                    sourceFileName.isBlank() -> "${movie.title}-$targetLang.srt"
                    sourceFileName.endsWith("-orig.srt", ignoreCase = true) -> {
                        sourceFileName.replace(Regex("-orig\\.srt$", RegexOption.IGNORE_CASE), "-$targetLang.srt")
                    }

                    else -> sourceFileName
                }

                return@mapNotNull OnlineSubtitleResult(
                    id = "subtitlecat:translatable:${sourceUrl.hashCode()}:$targetLang",
                    source = source,
                    displayName = targetFileName,
                    languageCode = targetLang,
                    originalLanguageCode = movie.sourceLanguage,
                    isTranslatable = true,
                    isNativeSubtitle = false,
                    downloadUrl = sourceUrl,
                    detailsUrl = movie.url,
                )
            }

            null
        }
    }

    private fun parseTranslateSpec(onclick: String?): TranslateSpec {
        if (onclick.isNullOrBlank()) return TranslateSpec()

        val callMatch = Regex("""^\s*([a-zA-Z0-9_]+)\((.*)\)\s*;?\s*$""")
            .find(onclick)
            ?: return TranslateSpec()
        val fnName = callMatch.groupValues.getOrNull(1).orEmpty()
        val argsRaw = callMatch.groupValues.getOrNull(2).orEmpty()
        val args = Regex("""'([^']*)'""").findAll(argsRaw).map { it.groupValues[1] }.toList()
        if (args.isEmpty()) return TranslateSpec()

        if (fnName == "translate_from_server_folder" && args.size >= 3) {
            val fileName = args[1]
            val folder = args[2]
            return TranslateSpec(
                sourceUrl = URL(
                    URL("https://www.subtitlecat.com"),
                    (if (folder.endsWith('/')) folder else "$folder/") + fileName,
                ).toString(),
            )
        }

        if (fnName == "translate_from_server" && args.size >= 2) {
            return TranslateSpec(sourceUrl = URL(URL("https://www.subtitlecat.com"), args[1]).toString())
        }

        val fileName = args.firstOrNull { it.lowercase(Locale.ROOT).endsWith(".srt") }
        val folder = args.firstOrNull { it.startsWith('/') }
        if (fileName != null && folder != null) {
            return TranslateSpec(
                sourceUrl = URL(
                    URL("https://www.subtitlecat.com"),
                    (if (folder.endsWith('/')) folder else "$folder/") + fileName,
                ).toString(),
            )
        }
        if (fileName != null) {
            return TranslateSpec(sourceUrl = URL(URL("https://www.subtitlecat.com"), fileName).toString())
        }
        return TranslateSpec()
    }

    private fun translateSrt(content: String, targetLanguage: String): String {
        val lines = content.split('\n')
        val translated = arrayOfNulls<String>(lines.size)

        val batches = mutableListOf<String>()
        val indicesByBatch = mutableListOf<MutableList<Int>>()
        var currentBatch = StringBuilder()
        var currentIndices = mutableListOf<Int>()
        var currentChars = 0
        val charsPerBatch = 500

        fun flushBatch() {
            if (currentIndices.isNotEmpty()) {
                batches += currentBatch.toString()
                indicesByBatch += currentIndices
                currentBatch = StringBuilder()
                currentIndices = mutableListOf()
                currentChars = 0
            }
        }

        lines.forEachIndexed { index, line ->
            if (line.isTimingOrIndexLine()) {
                translated[index] = line
                return@forEachIndexed
            }
            val sanitized = line
                .replace(Regex("<font[^>]*>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("</font>", RegexOption.IGNORE_CASE), "")
                .replace("&", "and")

            if (currentChars + sanitized.length + 1 >= charsPerBatch && currentIndices.isNotEmpty()) {
                flushBatch()
            }
            if (currentBatch.isNotEmpty()) currentBatch.append('\n')
            currentBatch.append(sanitized)
            currentChars += sanitized.length + 1
            currentIndices += index
        }
        flushBatch()

        batches.forEachIndexed { batchIndex, batch ->
            val indices = indicesByBatch[batchIndex]
            val translatedBatch = runCatching { translateText(batch, targetLanguage) }.getOrNull()
            if (translatedBatch == null) {
                indices.forEach { lineIndex ->
                    translated[lineIndex] = runCatching { translateText(lines[lineIndex], targetLanguage) }.getOrElse { lines[lineIndex] }
                }
                return@forEachIndexed
            }

            val translatedLines = translatedBatch.split('\n')
            if (translatedLines.size == indices.size) {
                indices.forEachIndexed { idx, lineIndex ->
                    translated[lineIndex] = translatedLines[idx]
                }
            } else {
                indices.forEach { lineIndex ->
                    translated[lineIndex] = runCatching { translateText(lines[lineIndex], targetLanguage) }.getOrElse { lines[lineIndex] }
                }
            }
        }

        return lines.indices.joinToString(separator = "\n") { index -> translated[index] ?: lines[index] }
    }

    private fun translateText(text: String, targetLanguage: String): String {
        val url = buildString {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx")
            append("&sl=auto")
            append("&tl=").append(targetLanguage.urlEncode())
            append("&dt=t")
            append("&q=").append(text.urlEncode())
        }
        val response = httpGet(url)
        if (response.code !in 200..299 || response.body.isEmpty()) {
            error("translate.googleapis.com returned ${response.code}")
        }
        return googleTranslateResultToString(response.bodyAsString())
    }

    private fun googleTranslateResultToString(jsonString: String): String {
        val json = JSONArray(jsonString)
        val chunks = json.optJSONArray(0) ?: return ""
        val out = StringBuilder()
        for (index in 0 until chunks.length()) {
            val part = chunks.optJSONArray(index) ?: continue
            out.append(part.optString(0))
        }
        return out.toString()
    }

    private fun normalizeLanguageCode(value: String): String? {
        if (value.isBlank()) return null
        val normalized = value.trim()
        val lower = normalized.lowercase(Locale.ROOT).substringBefore('-').trim()
        if (lower.length == 2 && lower.all { it.isLetter() }) return lower

        return Locale.getAvailableLocales()
            .firstOrNull { locale ->
                locale.displayLanguage.equals(normalized, ignoreCase = true) ||
                    locale.getDisplayLanguage(Locale.ENGLISH).equals(normalized, ignoreCase = true) ||
                    locale.isO3Language.equals(normalized, ignoreCase = true)
            }
            ?.language
            ?.takeIf { it.length == 2 }
            ?.lowercase(Locale.ROOT)
    }

    private fun decodeFilenameFromUrl(url: String): String {
        val encoded = runCatching { URL(url).path.substringAfterLast('/') }.getOrDefault("")
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrDefault(encoded)
    }

    private fun String.isTimingOrIndexLine(): Boolean {
        val trimmed = trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.all { it.isDigit() }) return true
        return Regex("""^[0-9:, ]*-->[0-9:, ]*$""").matches(trimmed)
    }

    private data class TranslateSpec(
        val sourceUrl: String? = null,
    )

    private data class SubtitlecatMovieLink(
        val title: String,
        val url: String,
        val sourceLanguage: String?,
    )
}
