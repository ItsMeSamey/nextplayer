package dev.anilbeesetti.nextplayer.feature.player.subtitles

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OnlineSubtitleSearchEngine(
    private val providers: List<OnlineSubtitleProvider>,
) {
    val sources: List<SubtitleSource> = providers.map { it.source }

    suspend fun search(
        request: SubtitleSearchRequest,
        onSourceProgress: (source: SubtitleSource, isLoading: Boolean, resultCount: Int) -> Unit = { _, _, _ -> },
        onPartialResults: (results: List<OnlineSubtitleResult>) -> Unit = {},
    ): List<OnlineSubtitleResult> = coroutineScope {
        val activeProviders = if (request.enabledSources.isEmpty()) {
            providers
        } else {
            providers.filter { it.source in request.enabledSources }
        }
        val resultMutex = Mutex()
        val mergedResults = mutableListOf<OnlineSubtitleResult>()

        activeProviders.map { provider ->
            async {
                onSourceProgress(provider.source, true, 0)
                val results = runCatching { provider.search(request) }.getOrDefault(emptyList())
                onSourceProgress(provider.source, false, results.size)
                resultMutex.withLock {
                    mergedResults += results
                    onPartialResults(sortAndDistinctResults(mergedResults, request.query))
                }
            }
        }.awaitAll()

        return@coroutineScope resultMutex.withLock { sortAndDistinctResults(mergedResults, request.query) }
    }

    suspend fun download(result: OnlineSubtitleResult): OnlineSubtitleDownloadResult? {
        val provider = providers.firstOrNull { it.source == result.source } ?: return null
        return runCatching { provider.download(result) }.getOrNull()
    }

    fun rankForQuery(
        results: List<OnlineSubtitleResult>,
        query: String,
    ): List<OnlineSubtitleResult> = sortAndDistinctResults(results, query)

    private fun sourceRank(source: SubtitleSource): Int = when (source) {
        SubtitleSource.SUBDB -> 0
        SubtitleSource.OPENSUBTITLES -> 1
        SubtitleSource.MOVIESUBTITLES -> 2
        SubtitleSource.MOVIESUBTITLESRT -> 3
        SubtitleSource.PODNAPISI -> 4
        SubtitleSource.SUBTITLECAT -> 5
        SubtitleSource.SUBDL -> 6
        SubtitleSource.YIFY -> 7
    }

    private fun sortAndDistinctResults(
        results: List<OnlineSubtitleResult>,
        query: String,
    ): List<OnlineSubtitleResult> {
        val normalizedQuery = normalizeForMatch(query)
        val queryTerms = normalizedQuery.split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(8)

        return results
            .distinctBy { listOf(it.source.name, it.displayName.lowercase(), it.languageCode.orEmpty().lowercase()) }
            .map { result ->
                val normalizedTitle = normalizeForMatch(result.displayName)
                RankedResult(
                    result = result,
                    relevanceScore = relevanceScore(
                        normalizedQuery = normalizedQuery,
                        queryTerms = queryTerms,
                        normalizedTitle = normalizedTitle,
                        languageCode = result.languageCode.orEmpty(),
                        isNativeSubtitle = result.isNativeSubtitle,
                        isTranslatable = result.isTranslatable,
                    ),
                    sourceRank = sourceRank(result.source),
                    normalizedTitle = normalizedTitle,
                )
            }
            .sortedWith(
                compareByDescending<RankedResult> { it.relevanceScore }
                    .thenBy { it.sourceRank }
                    .thenBy { it.normalizedTitle },
            )
            .map { it.result }
    }

    private fun relevanceScore(
        normalizedQuery: String,
        queryTerms: List<String>,
        normalizedTitle: String,
        languageCode: String,
        isNativeSubtitle: Boolean,
        isTranslatable: Boolean,
    ): Int {
        if (normalizedTitle.isBlank()) return 0
        if (normalizedQuery.isBlank()) return 0

        var score = 0

        if (normalizedTitle == normalizedQuery) score += 2_000
        if (normalizedTitle.startsWith(normalizedQuery)) score += 1_100

        val queryIndex = normalizedTitle.indexOf(normalizedQuery)
        if (queryIndex >= 0) {
            score += 700
            score += (220 - queryIndex).coerceAtLeast(0)
        }

        val language = languageCode.lowercase()
        queryTerms.forEach { term ->
            when {
                normalizedTitle.startsWith(term) -> score += 180
                normalizedTitle.contains(term) -> score += 90
            }
            if (language.startsWith(term)) score += 20
        }

        val prefixMatchLength = commonPrefixLength(normalizedQuery, normalizedTitle).coerceAtMost(40)
        score += prefixMatchLength * 3

        val lengthPenalty = kotlin.math.abs(normalizedTitle.length - normalizedQuery.length).coerceAtMost(120)
        score -= lengthPenalty

        if (isNativeSubtitle) score += 180
        if (isTranslatable) score -= 120

        return score
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val max = minOf(left.length, right.length)
        var index = 0
        while (index < max && left[index] == right[index]) index++
        return index
    }

    private fun normalizeForMatch(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private data class RankedResult(
        val result: OnlineSubtitleResult,
        val relevanceScore: Int,
        val sourceRank: Int,
        val normalizedTitle: String,
    )
}
