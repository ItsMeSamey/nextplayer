package dev.anilbeesetti.nextplayer.feature.player.subtitles

interface OnlineSubtitleProvider {
    val source: SubtitleSource

    suspend fun search(request: SubtitleSearchRequest): List<OnlineSubtitleResult>

    suspend fun download(result: OnlineSubtitleResult): DownloadedSubtitle?
}
