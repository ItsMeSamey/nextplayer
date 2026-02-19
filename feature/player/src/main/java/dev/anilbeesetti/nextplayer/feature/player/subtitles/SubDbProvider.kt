package dev.anilbeesetti.nextplayer.feature.player.subtitles

import android.content.ContentResolver
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubDbProvider(
    private val contentResolver: ContentResolver,
) : OnlineSubtitleProvider {
    override val source: SubtitleSource = SubtitleSource.SUBDB

    override suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val videoUri = request.videoUri ?: return@withContext emptyList()
        val hash = runCatching { SubDbHash.compute(contentResolver, videoUri) }.getOrNull() ?: return@withContext emptyList()

        val response = runCatching {
            httpGet(
                url = "https://api.thesubdb.com/?action=search&hash=$hash",
                userAgent = "SubDB/1.0 ($USER_AGENT)",
            )
        }.getOrNull() ?: return@withContext emptyList()

        if (response.code !in 200..299) return@withContext emptyList()

        val preferred = request.preferredLanguage?.normalizeLanguage()
        response.bodyAsString()
            .split(',')
            .map { it.trim().normalizeLanguage() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareByDescending<String> { it == preferred }.thenBy { it })
            .map { language ->
                OnlineSubtitleResult(
                    id = "subdb:$hash:$language",
                    source = source,
                    displayName = "${request.query.ifBlank { "Subtitle" }} [$language]",
                    languageCode = language,
                    downloadUrl = "https://api.thesubdb.com/?action=download&hash=$hash&language=$language",
                )
            }
    }

    override suspend fun download(result: OnlineSubtitleResult): OnlineSubtitleDownloadResult? = withContext(Dispatchers.IO) {
        val url = result.downloadUrl ?: return@withContext null
        val response = runCatching {
            httpGet(
                url = url,
                userAgent = "SubDB/1.0 ($USER_AGENT)",
            )
        }.getOrNull() ?: return@withContext null

        if (response.code !in 200..299 || response.body.isEmpty()) return@withContext null

        val language = result.languageCode?.normalizeLanguage().orEmpty().ifBlank { "sub" }
        val fileName = resolveFileName(
            fallback = "subdb_$language.srt",
            headers = response.headers,
            url = url,
        )
        DownloadedSubtitle(fileName = fileName, bytes = response.body)
    }

    private fun String.normalizeLanguage(): String {
        return lowercase(Locale.ROOT)
            .replace('_', '-')
            .substringBefore('-')
    }
}
