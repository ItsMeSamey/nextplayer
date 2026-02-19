package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.subtitles.OnlineSubtitleResult
import dev.anilbeesetti.nextplayer.feature.player.subtitles.SubtitleSource

data class SubtitleSourceStatusUi(
    val source: SubtitleSource,
    val isLoading: Boolean = false,
    val resultCount: Int = 0,
)

@Composable
fun BoxScope.OnlineSubtitleSearchView(
    show: Boolean,
    query: String,
    hasSearched: Boolean,
    isLoading: Boolean,
    canCancelSearch: Boolean,
    sourceStatuses: List<SubtitleSourceStatusUi>,
    results: List<OnlineSubtitleResult>,
    downloadedSourceUrls: Set<String>,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onDownloadClick: (OnlineSubtitleResult) -> Unit,
    onRemoveDownloadedClick: (OnlineSubtitleResult) -> Unit,
    onBack: () -> Unit,
) {
    OverlayView(
        show = show,
        title = stringResource(R.string.search_subtitles),
        onBackClick = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = onQueryChange,
                label = { Text(text = stringResource(R.string.search)) },
                singleLine = true,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSearch,
                    enabled = !isLoading && query.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.search))
                }
                if (canCancelSearch) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = onCancelSearch,
                    ) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }

            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            SourceStatusSection(sourceStatuses = sourceStatuses)

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (results.isNotEmpty()) {
                results.forEach { result ->
                    SubtitleResultRow(
                        result = result,
                        isDownloaded = result.sourceKey() in downloadedSourceUrls,
                        onDownloadClick = { onDownloadClick(result) },
                        onRemoveClick = { onRemoveDownloadedClick(result) },
                    )
                }
            } else if (!isLoading && hasSearched) {
                if (results.isEmpty()) {
                    Text(text = stringResource(R.string.subtitle_search_no_results))
                }
            }
        }
    }
}

@Composable
private fun SourceStatusSection(
    sourceStatuses: List<SubtitleSourceStatusUi>,
) {
    if (sourceStatuses.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sourceStatuses.forEach { status ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = status.source.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = status.resultCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleResultRow(
    result: OnlineSubtitleResult,
    isDownloaded: Boolean,
    onDownloadClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = result.displayName, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.padding(top = 2.dp))
            Text(
                text = buildString {
                    append(result.source.label())
                    result.languageCode?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isDownloaded) {
            FilledTonalIconButton(onClick = onRemoveClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.delete),
                )
            }
        } else {
            FilledTonalIconButton(onClick = onDownloadClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = stringResource(R.string.download),
                )
            }
        }
    }
}

private fun SubtitleSource.label(): String = when (this) {
    SubtitleSource.SUBDB -> "SubDB"
    SubtitleSource.OPENSUBTITLES -> "OpenSubtitles"
    SubtitleSource.MOVIESUBTITLES -> "MovieSubtitles"
    SubtitleSource.SUBDL -> "SubDL"
    SubtitleSource.YIFY -> "YIFY Subtitles"
}

private fun OnlineSubtitleResult.sourceKey(): String = downloadUrl ?: id
