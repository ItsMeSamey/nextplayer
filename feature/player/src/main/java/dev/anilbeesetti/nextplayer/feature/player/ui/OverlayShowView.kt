package dev.anilbeesetti.nextplayer.feature.player.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.core.model.VideoContentScale
import dev.anilbeesetti.nextplayer.feature.player.state.AudioDelayOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.extensions.noRippleClickable
import dev.anilbeesetti.nextplayer.feature.player.state.SubtitleOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.subtitles.OnlineSubtitleResult

@Composable
fun BoxScope.OverlayShowView(
    player: Player,
    overlayView: OverlayView?,
    videoContentScale: VideoContentScale,
    onDismiss: () -> Unit = {},
    onAudioDelayOptionEvent: (AudioDelayOptionsEvent) -> Unit = {},
    onOpenAudioDelayClick: () -> Unit = {},
    onSelectSubtitleClick: () -> Unit = {},
    onRemoveSubtitleClick: (Uri) -> Unit = {},
    onSearchSubtitleClick: () -> Unit = {},
    onOpenSubtitleDelayClick: () -> Unit = {},
    onlineSubtitleQuery: String = "",
    onlineSubtitleHasSearched: Boolean = false,
    onlineSubtitleSearchLoading: Boolean = false,
    canCancelOnlineSubtitleSearch: Boolean = false,
    onlineSubtitleSourceStates: List<SubtitleSourceStatusUi> = emptyList(),
    onlineSubtitleResults: List<OnlineSubtitleResult> = emptyList(),
    onlineDownloadedSubtitleSourceUrls: Set<String> = emptySet(),
    onlineSubtitleError: String? = null,
    onOnlineSubtitleQueryChange: (String) -> Unit = {},
    onOnlineSubtitleSearch: () -> Unit = {},
    onOnlineSubtitleCancelSearch: () -> Unit = {},
    onOnlineSubtitleDownloadClick: (OnlineSubtitleResult) -> Unit = {},
    onOnlineSubtitleRemoveDownloadedClick: (OnlineSubtitleResult) -> Unit = {},
    onOnlineSubtitleBack: () -> Unit = {},
    onSubtitleOptionEvent: (SubtitleOptionsEvent) -> Unit = {},
    onVideoContentScaleChanged: (VideoContentScale) -> Unit = {},
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .then(
                if (overlayView != null) {
                    Modifier.noRippleClickable(onClick = onDismiss)
                } else {
                    Modifier
                },
            ),
    )

    AudioTrackSelectorView(
        show = overlayView == OverlayView.AUDIO_SELECTOR,
        player = player,
        onEvent = onAudioDelayOptionEvent,
        onOpenDelayClick = onOpenAudioDelayClick,
        onDismiss = onDismiss,
    )

    VideoTrackSelectorView(
        show = overlayView == OverlayView.VIDEO_SELECTOR,
        player = player,
        onDismiss = onDismiss,
    )

    SubtitleSelectorView(
        show = overlayView == OverlayView.SUBTITLE_SELECTOR,
        player = player,
        onSelectSubtitleClick = onSelectSubtitleClick,
        onRemoveSubtitleClick = onRemoveSubtitleClick,
        onSearchSubtitleClick = onSearchSubtitleClick,
        onOpenDelayClick = onOpenSubtitleDelayClick,
        onEvent = onSubtitleOptionEvent,
        onDismiss = onDismiss,
    )

    AudioDelayOverlayView(
        show = overlayView == OverlayView.AUDIO_DELAY,
        player = player,
        onEvent = onAudioDelayOptionEvent,
    )

    SubtitleDelayOverlayView(
        show = overlayView == OverlayView.SUBTITLE_DELAY,
        player = player,
        onEvent = onSubtitleOptionEvent,
    )

    OnlineSubtitleSearchView(
        show = overlayView == OverlayView.ONLINE_SUBTITLE_SEARCH,
        query = onlineSubtitleQuery,
        hasSearched = onlineSubtitleHasSearched,
        isLoading = onlineSubtitleSearchLoading,
        canCancelSearch = canCancelOnlineSubtitleSearch,
        sourceStatuses = onlineSubtitleSourceStates,
        results = onlineSubtitleResults,
        downloadedSourceUrls = onlineDownloadedSubtitleSourceUrls,
        errorMessage = onlineSubtitleError,
        onQueryChange = onOnlineSubtitleQueryChange,
        onSearch = onOnlineSubtitleSearch,
        onCancelSearch = onOnlineSubtitleCancelSearch,
        onDownloadClick = onOnlineSubtitleDownloadClick,
        onRemoveDownloadedClick = onOnlineSubtitleRemoveDownloadedClick,
        onBack = onOnlineSubtitleBack,
    )

    PlaybackSpeedSelectorView(
        show = overlayView == OverlayView.PLAYBACK_SPEED,
        player = player,
    )

    VideoContentScaleSelectorView(
        show = overlayView == OverlayView.VIDEO_CONTENT_SCALE,
        videoContentScale = videoContentScale,
        onVideoContentScaleChanged = onVideoContentScaleChanged,
        onDismiss = onDismiss,
    )

    PlaylistView(
        show = overlayView == OverlayView.PLAYLIST,
        player = player,
    )
}

val Configuration.isPortrait: Boolean
    get() = orientation == Configuration.ORIENTATION_PORTRAIT

enum class OverlayView {
    AUDIO_SELECTOR,
    VIDEO_SELECTOR,
    AUDIO_DELAY,
    SUBTITLE_SELECTOR,
    SUBTITLE_DELAY,
    ONLINE_SUBTITLE_SEARCH,
    PLAYBACK_SPEED,
    VIDEO_CONTENT_SCALE,
    PLAYLIST,
}
