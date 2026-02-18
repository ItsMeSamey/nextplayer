package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.extensions.getName
import dev.anilbeesetti.nextplayer.feature.player.state.AudioDelayOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.state.rememberAudioDelayOptionsState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberTracksState
import kotlin.math.roundToLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.AudioTrackSelectorView(
    modifier: Modifier = Modifier,
    show: Boolean,
    player: Player,
    onEvent: (AudioDelayOptionsEvent) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val audioTracksState = rememberTracksState(player, C.TRACK_TYPE_AUDIO)
    val audioDelayOptionsState = rememberAudioDelayOptionsState(player, onEvent)

    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.select_audio_track),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp)
                .selectableGroup(),
        ) {
            audioTracksState.tracks.forEachIndexed { index, track ->
                RadioButtonRow(
                    selected = track.isSelected,
                    text = track.mediaTrackGroup.getName(C.TRACK_TYPE_AUDIO, index),
                    onClick = {
                        audioTracksState.switchTrack(index)
                        onDismiss()
                    },
                )
            }
            RadioButtonRow(
                selected = audioTracksState.tracks.none { it.isSelected },
                text = stringResource(R.string.disable),
                onClick = {
                    audioTracksState.switchTrack(-1)
                    onDismiss()
                },
            )
            if (audioTracksState.tracks.any { it.isSelected } && audioDelayOptionsState.isDelaySupported) {
                Spacer(modifier = Modifier.size(16.dp))
                DelayInput(
                    player = player,
                    title = stringResource(R.string.audio_track),
                    value = audioDelayOptionsState.delayMilliseconds,
                    onValueChange = { audioDelayOptionsState.setDelay(it) },
                )
            }
        }
    }
}

@Composable
private fun DelayInput(
    player: Player,
    title: String,
    value: Long,
    onValueChange: (Long) -> Unit,
) {
    var valueString by remember {
        mutableStateOf(if (value == 0L) "0" else "%.2f".format(value / 1000.0))
    }
    var armedMarker by remember { mutableStateOf(AudioSyncMarker.NONE) }
    var armedPositionMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(value) {
        val currentValue = valueString.toDoubleOrNull() ?: 0.0
        if (currentValue == (value / 1000.0)) return@LaunchedEffect
        valueString = if (value == 0L) "0" else "%.2f".format(value / 1000.0)
    }

    NumberChooserInput(
        title = "$title ${stringResource(R.string.delay)}",
        value = valueString,
        suffix = { Text(text = "sec") },
        onValueChange = { newValue ->
            if (newValue.isBlank()) {
                valueString = ""
                armedMarker = AudioSyncMarker.NONE
                armedPositionMs = null
                onValueChange(0)
                return@NumberChooserInput
            }

            val cleanedValue = newValue.trimStart()

            if (cleanedValue == "-" || cleanedValue == ".") {
                valueString = cleanedValue
                return@NumberChooserInput
            }

            val decimalPattern = "^-?\\d*\\.?\\d{0,2}$".toRegex()
            if (!cleanedValue.matches(decimalPattern)) {
                return@NumberChooserInput
            }

            valueString = cleanedValue

            runCatching {
                val doubleValue = cleanedValue.toDoubleOrNull() ?: 0.0
                val milliseconds = (doubleValue * 1000).roundToLong()
                armedMarker = AudioSyncMarker.NONE
                armedPositionMs = null
                onValueChange(milliseconds)
            }
        },
        onIncrement = { onValueChange(value + 100) },
        onDecrement = { onValueChange(value - 100) },
    )

    Spacer(modifier = Modifier.size(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            modifier = Modifier.weight(1f),
            colors = markerButtonColors(isSelected = armedMarker == AudioSyncMarker.HEARD),
            onClick = {
                val position = player.currentPosition.takeIf { it >= 0 } ?: return@FilledTonalButton
                when (armedMarker) {
                    AudioSyncMarker.NONE -> {
                        armedMarker = AudioSyncMarker.HEARD
                        armedPositionMs = position
                    }
                    AudioSyncMarker.HEARD -> {
                        armedMarker = AudioSyncMarker.NONE
                        armedPositionMs = null
                    }
                    AudioSyncMarker.SPOTTED -> {
                        val spottedPosition = armedPositionMs ?: return@FilledTonalButton
                        val heardPosition = position
                        val delta = heardPosition - spottedPosition
                        onValueChange(value - delta)
                        armedMarker = AudioSyncMarker.NONE
                        armedPositionMs = null
                    }
                }
            },
        ) {
            Text(text = stringResource(R.string.heard))
        }
        FilledTonalButton(
            modifier = Modifier.weight(1f),
            colors = markerButtonColors(isSelected = armedMarker == AudioSyncMarker.SPOTTED),
            onClick = {
                val position = player.currentPosition.takeIf { it >= 0 } ?: return@FilledTonalButton
                when (armedMarker) {
                    AudioSyncMarker.NONE -> {
                        armedMarker = AudioSyncMarker.SPOTTED
                        armedPositionMs = position
                    }
                    AudioSyncMarker.SPOTTED -> {
                        armedMarker = AudioSyncMarker.NONE
                        armedPositionMs = null
                    }
                    AudioSyncMarker.HEARD -> {
                        val heardPosition = armedPositionMs ?: return@FilledTonalButton
                        val spottedPosition = position
                        val delta = heardPosition - spottedPosition
                        onValueChange(value - delta)
                        armedMarker = AudioSyncMarker.NONE
                        armedPositionMs = null
                    }
                }
            },
        ) {
            Text(text = stringResource(R.string.spotted))
        }
        FilledTonalIconButton(
            onClick = {
                armedMarker = AudioSyncMarker.NONE
                armedPositionMs = null
                onValueChange(0L)
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_reset),
                contentDescription = stringResource(R.string.reset),
            )
        }
    }
}

private enum class AudioSyncMarker {
    NONE,
    HEARD,
    SPOTTED,
}

@Composable
private fun markerButtonColors(isSelected: Boolean) = if (isSelected) {
    ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
} else {
    ButtonDefaults.filledTonalButtonColors()
}

@Composable
private fun NumberChooserInput(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onIncrement: () -> Unit = {},
    onDecrement: () -> Unit = {},
    suffix: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { },
            modifier = Modifier.repeatingClickable(onClick = onDecrement),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_remove),
                contentDescription = null,
            )
        }
        OutlinedTextField(
            label = { Text(text = title) },
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            suffix = suffix,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
            ),
        )
        FilledTonalIconButton(
            onClick = { },
            modifier = Modifier.repeatingClickable(onClick = onIncrement),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
            )
        }
    }
}

private fun Modifier.repeatingClickable(
    enabled: Boolean = true,
    maxDelayMillis: Long = 200,
    minDelayMillis: Long = 5,
    delayDecayFactor: Float = .20f,
    onClick: () -> Unit,
): Modifier = composed {
    val updatedOnClick by rememberUpdatedState(onClick)

    this.pointerInput(enabled) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val heldButtonJob = launch {
                    var currentDelayMillis = maxDelayMillis
                    while (enabled && down.pressed) {
                        updatedOnClick()
                        delay(currentDelayMillis)
                        val nextMillis = currentDelayMillis - (currentDelayMillis * delayDecayFactor)
                        currentDelayMillis = nextMillis.toLong().coerceAtLeast(minDelayMillis)
                    }
                }
                waitForUpOrCancellation()
                heldButtonJob.cancel()
            }
        }
    }
}
