package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

private const val MEDIA_METADATA_POSITION_KEY = "media_metadata_position"
private const val MEDIA_METADATA_PLAYBACK_SPEED_KEY = "media_metadata_playback_speed"
private const val MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY = "audio_track_index"
private const val MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY = "subtitle_track_index"
private const val MEDIA_METADATA_VIDEO_ZOOM_KEY = "media_metadata_video_zoom"
private const val MEDIA_METADATA_SUBTITLE_DELAY_KEY = "media_metadata_subtitle_delay"
private const val MEDIA_METADATA_AUDIO_DELAY_KEY = "media_metadata_audio_delay"
private const val MEDIA_METADATA_AUDIO_TRACK_DELAYS_KEY = "media_metadata_audio_track_delays"
private const val MEDIA_METADATA_SUBTITLE_TRACK_DELAYS_KEY = "media_metadata_subtitle_track_delays"
private const val MEDIA_METADATA_SUBTITLE_SPEED_KEY = "media_metadata_subtitle_speed"

private fun Bundle.setExtras(
    positionMs: Long?,
    videoScale: Float?,
    playbackSpeed: Float?,
    audioTrackIndex: Int?,
    subtitleTrackIndex: Int?,
    audioDelayMilliseconds: Long? = null,
    audioTrackDelays: Map<Int, Long>? = null,
    subtitleDelayMilliseconds: Long? = null,
    subtitleTrackDelays: Map<Int, Long>? = null,
    subtitleSpeed: Float? = null,
) = apply {
    positionMs?.let { putLong(MEDIA_METADATA_POSITION_KEY, it) }
    videoScale?.let { putFloat(MEDIA_METADATA_VIDEO_ZOOM_KEY, it) }
    playbackSpeed?.let { putFloat(MEDIA_METADATA_PLAYBACK_SPEED_KEY, it) }
    audioTrackIndex?.let { putInt(MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY, it) }
    subtitleTrackIndex?.let { putInt(MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY, it) }
    audioDelayMilliseconds?.let { putLong(MEDIA_METADATA_AUDIO_DELAY_KEY, it) }
    audioTrackDelays?.let { putString(MEDIA_METADATA_AUDIO_TRACK_DELAYS_KEY, serializeDelayMap(it)) }
    subtitleDelayMilliseconds?.let { putLong(MEDIA_METADATA_SUBTITLE_DELAY_KEY, it) }
    subtitleTrackDelays?.let { putString(MEDIA_METADATA_SUBTITLE_TRACK_DELAYS_KEY, serializeDelayMap(it)) }
    subtitleSpeed?.let { putFloat(MEDIA_METADATA_SUBTITLE_SPEED_KEY, it) }
}

fun MediaMetadata.Builder.setExtras(
    positionMs: Long? = null,
    videoScale: Float? = null,
    playbackSpeed: Float? = null,
    audioTrackIndex: Int? = null,
    subtitleTrackIndex: Int? = null,
    audioDelayMilliseconds: Long? = null,
    audioTrackDelays: Map<Int, Long>? = null,
    subtitleDelayMilliseconds: Long? = null,
    subtitleTrackDelays: Map<Int, Long>? = null,
    subtitleSpeed: Float? = null,
) = setExtras(
    Bundle().setExtras(
        positionMs = positionMs,
        videoScale = videoScale,
        playbackSpeed = playbackSpeed,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
        audioDelayMilliseconds = audioDelayMilliseconds,
        audioTrackDelays = audioTrackDelays,
        subtitleDelayMilliseconds = subtitleDelayMilliseconds,
        subtitleTrackDelays = subtitleTrackDelays,
        subtitleSpeed = subtitleSpeed,
    ),
)

val MediaMetadata.positionMs: Long?
    get() = extras?.run {
        getLong(MEDIA_METADATA_POSITION_KEY)
            .takeIf { containsKey(MEDIA_METADATA_POSITION_KEY) }
    }

val MediaMetadata.playbackSpeed: Float?
    get() = extras?.run {
        getFloat(MEDIA_METADATA_PLAYBACK_SPEED_KEY)
            .takeIf { containsKey(MEDIA_METADATA_PLAYBACK_SPEED_KEY) }
    }

val MediaMetadata.audioTrackIndex: Int?
    get() = extras?.run {
        getInt(MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY)
            .takeIf { containsKey(MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY) }
    }

val MediaMetadata.subtitleTrackIndex: Int?
    get() = extras?.run {
        getInt(MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY)
            .takeIf { containsKey(MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY) }
    }

val MediaMetadata.videoZoom: Float?
    get() = extras?.run {
        getFloat(MEDIA_METADATA_VIDEO_ZOOM_KEY)
            .takeIf { containsKey(MEDIA_METADATA_VIDEO_ZOOM_KEY) }
    }

val MediaMetadata.audioDelayMilliseconds: Long?
    get() = extras?.run {
        getLong(MEDIA_METADATA_AUDIO_DELAY_KEY)
            .takeIf { containsKey(MEDIA_METADATA_AUDIO_DELAY_KEY) }
    }

val MediaMetadata.audioTrackDelays: Map<Int, Long>
    get() = extras?.getString(MEDIA_METADATA_AUDIO_TRACK_DELAYS_KEY)?.let(::deserializeDelayMap) ?: emptyMap()

val MediaMetadata.subtitleDelayMilliseconds: Long?
    get() = extras?.run {
        getLong(MEDIA_METADATA_SUBTITLE_DELAY_KEY)
            .takeIf { containsKey(MEDIA_METADATA_SUBTITLE_DELAY_KEY) }
    }

val MediaMetadata.subtitleTrackDelays: Map<Int, Long>
    get() = extras?.getString(MEDIA_METADATA_SUBTITLE_TRACK_DELAYS_KEY)?.let(::deserializeDelayMap) ?: emptyMap()

val MediaMetadata.subtitleSpeed: Float?
    get() = extras?.run {
        getFloat(MEDIA_METADATA_SUBTITLE_SPEED_KEY)
            .takeIf { containsKey(MEDIA_METADATA_SUBTITLE_SPEED_KEY) }
    }

fun MediaItem.copy(
    positionMs: Long? = this.mediaMetadata.positionMs,
    videoZoom: Float? = this.mediaMetadata.videoZoom,
    playbackSpeed: Float? = this.mediaMetadata.playbackSpeed,
    audioTrackIndex: Int? = this.mediaMetadata.audioTrackIndex,
    subtitleTrackIndex: Int? = this.mediaMetadata.subtitleTrackIndex,
    audioDelayMilliseconds: Long? = this.mediaMetadata.audioDelayMilliseconds,
    audioTrackDelays: Map<Int, Long> = this.mediaMetadata.audioTrackDelays,
    subtitleDelayMilliseconds: Long? = this.mediaMetadata.subtitleDelayMilliseconds,
    subtitleTrackDelays: Map<Int, Long> = this.mediaMetadata.subtitleTrackDelays,
    subtitleSpeed: Float? = this.mediaMetadata.subtitleSpeed,
) = buildUpon().setMediaMetadata(
    mediaMetadata.buildUpon().setExtras(
        Bundle(mediaMetadata.extras).setExtras(
            positionMs = positionMs,
            videoScale = videoZoom,
            playbackSpeed = playbackSpeed,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            audioDelayMilliseconds = audioDelayMilliseconds,
            audioTrackDelays = audioTrackDelays,
            subtitleDelayMilliseconds = subtitleDelayMilliseconds,
            subtitleTrackDelays = subtitleTrackDelays,
            subtitleSpeed = subtitleSpeed,
        ),
    ).build(),
).build()

private fun serializeDelayMap(value: Map<Int, Long>): String {
    return value.entries.sortedBy { it.key }.joinToString(separator = ",") { "${it.key}:${it.value}" }
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
