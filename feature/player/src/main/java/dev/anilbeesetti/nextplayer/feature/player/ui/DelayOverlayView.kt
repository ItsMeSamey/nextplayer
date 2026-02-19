package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.state.AudioDelayOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.state.SubtitleOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.state.rememberAudioDelayOptionsState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberSubtitleOptionsState
import kotlin.math.roundToLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.SubtitleDelayOverlayView(
    show: Boolean,
    player: Player,
    onEvent: (SubtitleOptionsEvent) -> Unit = {},
) {
    val subtitleOptionsState = rememberSubtitleOptionsState(player, onEvent)

    DelayOverlayContainer(
        show = show,
        title = stringResource(R.string.subtitle_track) + " " + stringResource(R.string.delay),
    ) {
        DelayValueInput(
            title = stringResource(R.string.delay),
            value = subtitleOptionsState.delayMilliseconds,
            onValueChange = subtitleOptionsState::setDelay,
            seenLabel = stringResource(R.string.seen),
            heardLabel = stringResource(R.string.heard),
            useSubtitleMath = true,
            player = player,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.AudioDelayOverlayView(
    show: Boolean,
    player: Player,
    onEvent: (AudioDelayOptionsEvent) -> Unit = {},
) {
    val audioDelayOptionsState = rememberAudioDelayOptionsState(player, onEvent)
    if (!audioDelayOptionsState.isDelaySupported) return

    DelayOverlayContainer(
        show = show,
        title = stringResource(R.string.audio_track) + " " + stringResource(R.string.delay),
    ) {
        DelayValueInput(
            title = stringResource(R.string.delay),
            value = audioDelayOptionsState.delayMilliseconds,
            onValueChange = audioDelayOptionsState::setDelay,
            seenLabel = stringResource(R.string.spotted),
            heardLabel = stringResource(R.string.heard),
            useSubtitleMath = false,
            player = player,
        )
    }
}

@Composable
private fun BoxScope.DelayOverlayContainer(
    show: Boolean,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 24.dp),
        visible = show,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 360.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
}

private enum class SyncMarker {
    NONE,
    HEARD,
    SEEN_OR_SPOTTED,
}

@Composable
private fun DelayValueInput(
    title: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    heardLabel: String,
    seenLabel: String,
    useSubtitleMath: Boolean,
    player: Player,
) {
    var valueString by remember {
        mutableStateOf(if (value == 0L) "0" else "%.2f".format(value / 1000.0))
    }
    var armedMarker by remember { mutableStateOf(SyncMarker.NONE) }
    var armedPositionMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(value) {
        val currentValue = valueString.toDoubleOrNull() ?: 0.0
        if (currentValue == (value / 1000.0)) return@LaunchedEffect
        valueString = if (value == 0L) "0" else "%.2f".format(value / 1000.0)
    }

    NumberChooserInput(
        title = title,
        value = valueString,
        suffix = { Text(text = "sec") },
        onValueChange = { newValue ->
            if (newValue.isBlank()) {
                valueString = ""
                armedMarker = SyncMarker.NONE
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
            if (!cleanedValue.matches(decimalPattern)) return@NumberChooserInput

            valueString = cleanedValue

            val milliseconds = (cleanedValue.toDoubleOrNull() ?: 0.0).times(1000).roundToLong()
            armedMarker = SyncMarker.NONE
            armedPositionMs = null
            onValueChange(milliseconds)
        },
        onIncrement = { onValueChange(value + 100) },
        onDecrement = { onValueChange(value - 100) },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            modifier = Modifier.weight(1f),
            colors = markerButtonColors(isSelected = armedMarker == SyncMarker.HEARD),
            onClick = {
                val position = player.currentPosition.takeIf { it >= 0 } ?: return@FilledTonalButton
                when (armedMarker) {
                    SyncMarker.NONE -> {
                        armedMarker = SyncMarker.HEARD
                        armedPositionMs = position
                    }
                    SyncMarker.HEARD -> {
                        armedMarker = SyncMarker.NONE
                        armedPositionMs = null
                    }
                    SyncMarker.SEEN_OR_SPOTTED -> {
                        val firstPosition = armedPositionMs ?: return@FilledTonalButton
                        val delta = position - firstPosition
                        val applied = if (useSubtitleMath) value + delta else value - delta
                        onValueChange(applied)
                        armedMarker = SyncMarker.NONE
                        armedPositionMs = null
                    }
                }
            },
        ) {
            Text(text = heardLabel)
        }

        FilledTonalButton(
            modifier = Modifier.weight(1f),
            colors = markerButtonColors(isSelected = armedMarker == SyncMarker.SEEN_OR_SPOTTED),
            onClick = {
                val position = player.currentPosition.takeIf { it >= 0 } ?: return@FilledTonalButton
                when (armedMarker) {
                    SyncMarker.NONE -> {
                        armedMarker = SyncMarker.SEEN_OR_SPOTTED
                        armedPositionMs = position
                    }
                    SyncMarker.SEEN_OR_SPOTTED -> {
                        armedMarker = SyncMarker.NONE
                        armedPositionMs = null
                    }
                    SyncMarker.HEARD -> {
                        val firstPosition = armedPositionMs ?: return@FilledTonalButton
                        val delta = firstPosition - position
                        val applied = if (useSubtitleMath) value + delta else value - delta
                        onValueChange(applied)
                        armedMarker = SyncMarker.NONE
                        armedPositionMs = null
                    }
                }
            },
        ) {
            Text(text = seenLabel)
        }

        FilledTonalIconButton(
            onClick = {
                armedMarker = SyncMarker.NONE
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
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
