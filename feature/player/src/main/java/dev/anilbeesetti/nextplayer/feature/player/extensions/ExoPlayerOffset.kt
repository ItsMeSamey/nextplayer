package dev.anilbeesetti.nextplayer.feature.player.extensions

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.anilbeesetti.nextlib.media3ext.renderer.OffsetRenderer

@OptIn(UnstableApi::class)
private fun ExoPlayer.getOffsetRenderer(trackType: @C.TrackType Int): OffsetRenderer? {
    for (index in 0 until rendererCount) {
        if (getRendererType(index) == trackType) {
            return getRenderer(index) as? OffsetRenderer
        }
    }
    return null
}

@OptIn(UnstableApi::class)
fun ExoPlayer.getAudioDelayMilliseconds(): Long {
    return getOffsetRenderer(C.TRACK_TYPE_AUDIO)?.syncOffsetMilliseconds ?: 0L
}

@OptIn(UnstableApi::class)
fun ExoPlayer.setAudioDelayMilliseconds(delayMillis: Long) {
    getOffsetRenderer(C.TRACK_TYPE_AUDIO)?.syncOffsetMilliseconds = delayMillis
}

@OptIn(UnstableApi::class)
fun ExoPlayer.isAudioDelaySupported(): Boolean {
    return getOffsetRenderer(C.TRACK_TYPE_AUDIO) != null
}
