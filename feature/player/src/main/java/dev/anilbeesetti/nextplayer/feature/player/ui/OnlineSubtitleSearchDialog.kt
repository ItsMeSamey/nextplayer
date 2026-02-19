package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
    downloadErrors: Map<String, String>,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onDownloadClick: (OnlineSubtitleResult) -> Unit,
    onRemoveDownloadedClick: (OnlineSubtitleResult) -> Unit,
    onBack: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val columns = if (configuration.isPortrait) 1 else 2
    val selectedErrorDetails = remember { mutableStateOf<String?>(null) }

    OverlayView(
        show = show,
        title = stringResource(R.string.search_subtitles),
        fullScreen = true,
        onBackClick = onBack,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(text = stringResource(R.string.search)) },
                    singleLine = true,
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
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
            }

            if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SourceStatusSection(sourceStatuses = sourceStatuses)
            }

            if (!errorMessage.isNullOrBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (results.isNotEmpty()) {
                items(
                    items = results,
                    key = { result -> "${result.id}:${result.downloadUrl.orEmpty()}" },
                ) { result ->
                    val sourceKey = result.sourceKey()
                    SubtitleResultRow(
                        result = result,
                        isDownloaded = sourceKey in downloadedSourceUrls,
                        errorDetails = downloadErrors[sourceKey],
                        onDownloadClick = { onDownloadClick(result) },
                        onRemoveClick = { onRemoveDownloadedClick(result) },
                        onErrorClick = { selectedErrorDetails.value = it },
                    )
                }
            } else if (!isLoading && hasSearched) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(text = stringResource(R.string.subtitle_search_no_results))
                }
            }
        }
    }

    selectedErrorDetails.value?.let { details ->
        AlertDialog(
            onDismissRequest = { selectedErrorDetails.value = null },
            title = { Text(text = stringResource(R.string.subtitle_download_error_title)) },
            text = { Text(text = details) },
            confirmButton = {
                TextButton(onClick = { selectedErrorDetails.value = null }) {
                    Text(text = stringResource(R.string.done))
                }
            },
        )
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
    errorDetails: String?,
    onDownloadClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onErrorClick: (String) -> Unit,
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
                text = result.subtitleDetailsText(),
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
        } else if (!errorDetails.isNullOrBlank()) {
            FilledTonalIconButton(onClick = { onErrorClick(errorDetails) }) {
                Text(
                    text = "!",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
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
    SubtitleSource.MOVIESUBTITLESRT -> "MovieSubtitlesRT"
    SubtitleSource.PODNAPISI -> "Podnapisi"
    SubtitleSource.SUBTITLECAT -> "Subtitlecat"
    SubtitleSource.SUBDL -> "SubDL"
    SubtitleSource.YIFY -> "YIFY Subtitles"
}

private fun OnlineSubtitleResult.sourceKey(): String = downloadUrl ?: id

private fun OnlineSubtitleResult.subtitleDetailsText(): String {
    if (source == SubtitleSource.SUBTITLECAT) {
        return buildString {
            append("Subtitlecat")
            languageCode?.takeIf { it.isNotBlank() }?.let { append(".").append(it) }
            if (isTranslatable) append(".translatable")
            originalLanguageCode?.takeIf { it.isNotBlank() }?.let { append(" • original: ").append(it) }
        }
    }

    return buildString {
        append(source.label())
        languageCode?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
    }
}
