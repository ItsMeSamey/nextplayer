package dev.anilbeesetti.nextplayer.core.data.mappers

import dev.anilbeesetti.nextplayer.core.data.models.VideoState
import dev.anilbeesetti.nextplayer.core.database.converter.UriListConverter
import dev.anilbeesetti.nextplayer.core.database.entities.MediumStateEntity

fun MediumStateEntity.toVideoState(): VideoState {
    return VideoState(
        path = uriString,
        position = playbackPosition.takeIf { it != 0L },
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
        videoTrackIndex = videoTrackIndex,
        playbackSpeed = playbackSpeed,
        externalSubs = UriListConverter.fromStringToList(externalSubs),
        videoScale = videoScale,
        audioTrackDelays = deserializeDelayMap(audioTrackDelays),
        subtitleTrackDelays = deserializeDelayMap(subtitleTrackDelays),
    )
}

private fun deserializeDelayMap(raw: String): Map<Int, Long> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(",")
        .mapNotNull { pair ->
            val parts = pair.split(":")
            val key = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            key to value
        }
        .toMap()
}
