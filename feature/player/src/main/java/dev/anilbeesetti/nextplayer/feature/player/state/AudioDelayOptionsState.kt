package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioTrackDelays
import dev.anilbeesetti.nextplayer.feature.player.extensions.copy
import dev.anilbeesetti.nextplayer.feature.player.extensions.getAudioDelayMilliseconds
import dev.anilbeesetti.nextplayer.feature.player.extensions.isAudioDelaySupported
import dev.anilbeesetti.nextplayer.feature.player.extensions.setAudioDelayMilliseconds
import dev.anilbeesetti.nextplayer.feature.player.service.getAudioDelaySupported
import dev.anilbeesetti.nextplayer.feature.player.service.getAudioDelayMilliseconds as getAudioDelayFromController
import dev.anilbeesetti.nextplayer.feature.player.service.setAudioDelayMilliseconds as setAudioDelayFromController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun rememberAudioDelayOptionsState(
    player: Player,
    onEvent: (AudioDelayOptionsEvent) -> Unit = {},
): AudioDelayOptionsState {
    val scope = rememberCoroutineScope()
    val audioDelayOptionsState = remember { AudioDelayOptionsState(player, scope, onEvent) }
    LaunchedEffect(player) { audioDelayOptionsState.observe() }
    return audioDelayOptionsState
}

@Stable
class AudioDelayOptionsState(
    private val player: Player,
    private val scope: CoroutineScope,
    private val onEvent: (AudioDelayOptionsEvent) -> Unit = {},
) {
    var isDelaySupported: Boolean by mutableStateOf(false)
        private set

    var delayMilliseconds: Long by mutableLongStateOf(0L)
        private set

    fun setDelay(delayMillis: Long) {
        if (!isDelaySupported) return
        scope.launch {
            when (player) {
                is MediaController -> player.setAudioDelayFromController(delayMillis)
                is ExoPlayer -> player.setAudioDelayMilliseconds(delayMillis)
                else -> return@launch
            }
            updateAudioDelayMilliseconds()
            updateDelayMetadataAndSendEvent()
        }
    }

    suspend fun observe() {
        updateIsDelaySupported()
        updateAudioDelayMilliseconds()
        player.listen { events ->
            if (events.containsAny(Player.EVENT_TRACKS_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_RENDERED_FIRST_FRAME)) {
                scope.launch {
                    updateIsDelaySupported()
                    updateAudioDelayMilliseconds()
                }
            }
        }
    }

    private suspend fun updateIsDelaySupported() {
        isDelaySupported = when (player) {
            is MediaController -> player.getAudioDelaySupported()
            is ExoPlayer -> player.isAudioDelaySupported()
            else -> false
        }
    }

    private suspend fun updateAudioDelayMilliseconds() {
        if (!isDelaySupported) {
            delayMilliseconds = 0L
            return
        }
        delayMilliseconds = when (player) {
            is MediaController -> player.getAudioDelayFromController()
            is ExoPlayer -> player.getAudioDelayMilliseconds()
            else -> return
        }
    }

    private fun updateDelayMetadataAndSendEvent(delay: Long = this.delayMilliseconds) {
        val currentMediaItem = player.currentMediaItem ?: return
        val selectedTrackIndex = player.getSelectedTrackIndex(C.TRACK_TYPE_AUDIO) ?: return
        val updatedTrackDelays = currentMediaItem.mediaMetadata.audioTrackDelays.toMutableMap().apply {
            this[selectedTrackIndex] = delay
        }
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            currentMediaItem.copy(
                audioDelayMilliseconds = delay,
                audioTrackDelays = updatedTrackDelays,
            ),
        )
        onEvent(AudioDelayOptionsEvent.DelayChanged(currentMediaItem, selectedTrackIndex, delay))
    }
}

sealed interface AudioDelayOptionsEvent {
    data class DelayChanged(
        val mediaItem: MediaItem,
        val trackIndex: Int,
        val delay: Long,
    ) : AudioDelayOptionsEvent
}

private fun Player.getSelectedTrackIndex(trackType: @C.TrackType Int): Int? {
    return currentTracks.groups
        .filter { it.type == trackType && it.isSupported }
        .indexOfFirst { it.isSelected }
        .takeIf { it != -1 }
}
