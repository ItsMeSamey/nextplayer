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
                    onPartialResults(sortAndDistinctResults(mergedResults))
                }
            }
        }.awaitAll()

        return@coroutineScope resultMutex.withLock { sortAndDistinctResults(mergedResults) }
    }

    suspend fun download(result: OnlineSubtitleResult): OnlineSubtitleDownloadResult? {
        val provider = providers.firstOrNull { it.source == result.source } ?: return null
        return runCatching { provider.download(result) }.getOrNull()
    }

    private fun sourceRank(source: SubtitleSource): Int = when (source) {
        SubtitleSource.SUBDB -> 0
        SubtitleSource.OPENSUBTITLES -> 1
        SubtitleSource.MOVIESUBTITLES -> 2
        SubtitleSource.MOVIESUBTITLESRT -> 3
        SubtitleSource.PODNAPISI -> 4
        SubtitleSource.SUBDL -> 5
        SubtitleSource.YIFY -> 6
    }

    private fun sortAndDistinctResults(results: List<OnlineSubtitleResult>): List<OnlineSubtitleResult> {
        return results
            .distinctBy { listOf(it.source.name, it.displayName.lowercase(), it.languageCode.orEmpty().lowercase()) }
            .sortedWith(compareBy<OnlineSubtitleResult> { sourceRank(it.source) }.thenBy { it.displayName.lowercase() })
    }
}
