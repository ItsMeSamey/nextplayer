package dev.anilbeesetti.nextplayer.core.data.models

import android.net.Uri

data class VideoState(
    val path: String,
    val position: Long?,
    val audioTrackIndex: Int?,
    val subtitleTrackIndex: Int?,
    val playbackSpeed: Float?,
    val externalSubs: List<Uri>,
    val videoScale: Float,
    val audioDelayMilliseconds: Long,
    val audioTrackDelays: Map<Int, Long>,
    val subtitleDelayMilliseconds: Long,
    val subtitleTrackDelays: Map<Int, Long>,
    val subtitleSpeed: Float,
)
