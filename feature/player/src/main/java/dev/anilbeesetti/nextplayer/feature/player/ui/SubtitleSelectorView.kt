package dev.anilbeesetti.nextplayer.feature.player.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.player.extensions.getName
import dev.anilbeesetti.nextplayer.feature.player.state.SubtitleOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.state.rememberTracksState

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.SubtitleSelectorView(
    modifier: Modifier = Modifier,
    show: Boolean,
    player: Player,
    onSelectSubtitleClick: () -> Unit,
    onSearchSubtitleClick: () -> Unit,
    onRemoveSubtitleClick: (Uri) -> Unit = {},
    onOpenDelayClick: () -> Unit = {},
    onEvent: (SubtitleOptionsEvent) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val subtitleTracksState = rememberTracksState(player, C.TRACK_TYPE_TEXT)

    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.select_subtitle_track),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp)
                .selectableGroup(),
        ) {
            subtitleTracksState.tracks.forEachIndexed { index, track ->
                val removableSubtitleUri = track.removableSubtitleUri(player)
                RadioButtonRow(
                    selected = track.isSelected,
                    text = track.mediaTrackGroup.getName(C.TRACK_TYPE_TEXT, index),
                    onClick = {
                        subtitleTracksState.switchTrack(index)
                        onDismiss()
                    },
                    trailingContent = removableSubtitleUri?.let { uri ->
                        {
                            FilledTonalIconButton(onClick = { onRemoveSubtitleClick(uri) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.delete),
                                )
                            }
                        }
                    },
                )
            }
            RadioButtonRow(
                selected = subtitleTracksState.tracks.none { it.isSelected },
                text = stringResource(R.string.disable),
                onClick = {
                    subtitleTracksState.switchTrack(-1)
                    onDismiss()
                },
            )
            Spacer(modifier = Modifier.size(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSelectSubtitleClick()
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.open_subtitle))
                }
                FilledTonalIconButton(
                    onClick = {
                        onSearchSubtitleClick()
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.search_subtitle_online),
                    )
                }
                if (subtitleTracksState.tracks.any { it.isSelected }) {
                    FilledTonalIconButton(
                        onClick = {
                            onOpenDelayClick()
                        },
                    ) {
                        Icon(
                            imageVector = NextIcons.Timer,
                            contentDescription = stringResource(R.string.delay),
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.media3.common.Tracks.Group.removableSubtitleUri(player: Player): Uri? {
    val format = mediaTrackGroup.getFormat(0)
    val fromId = format.id
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?.takeIf { it.scheme == "file" || it.scheme == "content" }
    if (fromId != null) return fromId

    val label = format.label?.trim().orEmpty()
    if (label.isBlank()) return null
    val configuration = player.currentMediaItem?.localConfiguration?.subtitleConfigurations
        ?.firstOrNull { candidate ->
            candidate.label?.trim().orEmpty() == label &&
                (candidate.uri.scheme == "file" || candidate.uri.scheme == "content")
        }
    return configuration?.uri
}
