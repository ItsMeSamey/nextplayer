package dev.anilbeesetti.nextplayer.feature.player.subtitles

import android.net.Uri

enum class SubtitleSource {
    SUBDB,
    OPENSUBTITLES,
    MOVIESUBTITLES,
    MOVIESUBTITLESRT,
    PODNAPISI,
    SUBDL,
    YIFY,
}

data class SubtitleSearchRequest(
    val query: String,
    val videoUri: Uri?,
    val preferredLanguage: String?,
    val enabledSources: Set<SubtitleSource> = emptySet(),
)

data class OnlineSubtitleResult(
    val id: String,
    val source: SubtitleSource,
    val displayName: String,
    val languageCode: String? = null,
    val downloadUrl: String? = null,
    val detailsUrl: String? = null,
)

sealed interface OnlineSubtitleDownloadResult

data class DownloadedSubtitle(
    val fileName: String,
    val bytes: ByteArray,
) : OnlineSubtitleDownloadResult

data class BrowserDownloadRequired(
    val url: String,
) : OnlineSubtitleDownloadResult
