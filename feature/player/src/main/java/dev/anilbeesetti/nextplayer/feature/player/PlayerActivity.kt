package dev.anilbeesetti.nextplayer.feature.player

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getMediaContentUri
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.extensions.registerForSuspendActivityResult
import dev.anilbeesetti.nextplayer.feature.player.extensions.setExtras
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import dev.anilbeesetti.nextplayer.feature.player.service.addSubtitleTrack
import dev.anilbeesetti.nextplayer.feature.player.service.removeSubtitleTrack
import dev.anilbeesetti.nextplayer.feature.player.service.stopPlayerSession
import dev.anilbeesetti.nextplayer.feature.player.subtitles.OnlineSubtitleResult
import dev.anilbeesetti.nextplayer.feature.player.subtitles.OnlineSubtitleSearchEngine
import dev.anilbeesetti.nextplayer.feature.player.subtitles.OpenSubtitlesRestProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.MoviesSubtitlesOrgProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.MoviesSubtitlesRtProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.BrowserDownloadRequired
import dev.anilbeesetti.nextplayer.feature.player.subtitles.DownloadedSubtitle
import dev.anilbeesetti.nextplayer.feature.player.subtitles.SubDbProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.SubdlProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.PodnapisiProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.SubtitlecatProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.YifySubtitlesProvider
import dev.anilbeesetti.nextplayer.feature.player.subtitles.firstSubtitleFromZip
import dev.anilbeesetti.nextplayer.feature.player.subtitles.httpGet
import dev.anilbeesetti.nextplayer.feature.player.subtitles.resolveFileName
import dev.anilbeesetti.nextplayer.feature.player.subtitles.SubtitleSource
import dev.anilbeesetti.nextplayer.feature.player.subtitles.SubtitleSearchRequest
import dev.anilbeesetti.nextplayer.feature.player.ui.SubtitleSourceStatusUi
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalHidePlayerButtonsBackground = compositionLocalOf { false }

@SuppressLint("UnsafeOptInUsageError")
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    @Inject
    lateinit var mediaRepository: MediaRepository
    val playerPreferences get() = viewModel.uiState.value.playerPreferences

    private val onWindowAttributesChangedListener = CopyOnWriteArrayList<Consumer<WindowManager.LayoutParams?>>()

    private var isPlaybackFinished = false
    private var playInBackground: Boolean = false
    private var isIntentNew: Boolean = true

    /**
     * Player
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private lateinit var playerApi: PlayerApi
    private lateinit var onlineSubtitleSearchEngine: OnlineSubtitleSearchEngine

    /**
     * Listeners
     */
    private val playbackStateListener: Player.Listener = playbackStateListener()

    private val subtitleFileSuspendLauncher = registerForSuspendActivityResult(OpenDocument())
    private val yifiWebViewSuspendLauncher = registerForSuspendActivityResult(StartActivityForResult())
    private var shouldPromptSubtitleImportAfterBrowserDownload: Boolean = false
    private val yifiCookiePrefs by lazy { getSharedPreferences(PREFS_YIFI_COOKIE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val downloadedSubtitleSourceUrls by mediaRepository.getDownloadedSubtitleSourceUrls()
                .collectAsStateWithLifecycle(initialValue = emptySet())
            var player by remember { mutableStateOf<MediaController?>(null) }
            var onlineSubtitleQuery by remember { mutableStateOf("") }
            var onlineSubtitleResults by remember { mutableStateOf<List<OnlineSubtitleResult>>(emptyList()) }
            var onlineSubtitleHasSearched by remember { mutableStateOf(false) }
            var onlineSubtitleSearchLoading by remember { mutableStateOf(false) }
            var onlineSubtitleError by remember { mutableStateOf<String?>(null) }
            var onlineSubtitleDownloadErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var onlineSubtitleSearchJob by remember { mutableStateOf<Job?>(null) }
            val enabledSources = enabledOnlineSubtitleSources(uiState.playerPreferences)
            var onlineSubtitleSourceStates by remember(enabledSources) {
                mutableStateOf(
                    enabledSources.associateWith { source ->
                        SubtitleSourceStatusUi(source = source)
                    },
                )
            }

            fun resetSourceStates() {
                onlineSubtitleSourceStates = enabledSources.associateWith { source ->
                    SubtitleSourceStatusUi(source = source)
                }
            }

            fun stopSourceLoadingIndicators() {
                onlineSubtitleSourceStates = onlineSubtitleSourceStates.mapValues { (_, state) ->
                    state.copy(isLoading = false)
                }
            }

            fun cancelSearch() {
                onlineSubtitleSearchJob?.cancel()
                onlineSubtitleSearchJob = null
                onlineSubtitleSearchLoading = false
                stopSourceLoadingIndicators()
            }

            suspend fun performSearch(query: String) {
                val searchQuery = query.trim()
                if (searchQuery.isBlank()) return
                if (enabledSources.isEmpty()) {
                    onlineSubtitleHasSearched = true
                    onlineSubtitleResults = emptyList()
                    onlineSubtitleError = getString(dev.anilbeesetti.nextplayer.core.ui.R.string.no_online_subtitle_sources_enabled)
                    return
                }
                onlineSubtitleSearchLoading = true
                onlineSubtitleHasSearched = true
                onlineSubtitleResults = emptyList()
                onlineSubtitleError = null

                val request = SubtitleSearchRequest(
                    query = searchQuery,
                    videoUri = player?.currentMediaItem?.localConfiguration?.uri,
                    preferredLanguage = uiState.playerPreferences?.onlineSubtitleSearchLanguage
                        ?.takeIf { it.isNotBlank() }
                        ?: uiState.playerPreferences?.preferredSubtitleLanguage,
                    enabledSources = enabledSources.toSet(),
                )
                try {
                    val results = onlineSubtitleSearchEngine.search(
                        request = request,
                        onSourceProgress = { source, isLoading, count ->
                            onlineSubtitleSourceStates = onlineSubtitleSourceStates.toMutableMap().apply {
                                this[source] = SubtitleSourceStatusUi(
                                    source = source,
                                    isLoading = isLoading,
                                    resultCount = if (isLoading) this[source]?.resultCount ?: 0 else count,
                                )
                            }
                        },
                        onPartialResults = { partialResults ->
                            onlineSubtitleResults = partialResults
                        },
                    )
                    onlineSubtitleResults = results
                    onlineSubtitleError = if (results.isEmpty()) {
                        getString(dev.anilbeesetti.nextplayer.core.ui.R.string.subtitle_search_no_results)
                    } else {
                        null
                    }
                } catch (_: CancellationException) {
                    stopSourceLoadingIndicators()
                    return
                } catch (_: Exception) {
                    onlineSubtitleResults = emptyList()
                    onlineSubtitleError = getString(dev.anilbeesetti.nextplayer.core.ui.R.string.subtitle_search_failed)
                } finally {
                    onlineSubtitleSearchLoading = false
                    onlineSubtitleSearchJob = null
                    stopSourceLoadingIndicators()
                }
            }

            LifecycleStartEffect(Unit) {
                maybeInitControllerFuture()
                lifecycleScope.launch {
                    player = controllerFuture?.await()
                }

                onStopOrDispose {
                    player = null
                }
            }

            CompositionLocalProvider(LocalHidePlayerButtonsBackground provides (uiState.playerPreferences?.hidePlayerButtonsBackground == true)) {
                NextPlayerTheme(darkTheme = true) {
                    MediaPlayerScreen(
                        player = player,
                        viewModel = viewModel,
                        playerPreferences = uiState.playerPreferences ?: return@NextPlayerTheme,
                        onSelectSubtitleClick = {
                            lifecycleScope.launch {
                                val uri = subtitleFileSuspendLauncher.launch(
                                    subtitleDocumentMimeTypes(),
                                ) ?: return@launch
                                importSelectedSubtitle(uri)
                            }
                        },
                        onRemoveSubtitleClick = { subtitleUri ->
                            lifecycleScope.launch {
                                maybeInitControllerFuture()
                                controllerFuture?.await()?.removeSubtitleTrack(subtitleUri)
                                releaseSubtitleUriPermission(subtitleUri)
                                deleteSubtitleFromCacheIfExists(subtitleUri)
                            }
                        },
                        onSearchSubtitleClick = {
                            onlineSubtitleQuery = deriveSubtitleSearchQuery(player)
                            onlineSubtitleResults = emptyList()
                            onlineSubtitleHasSearched = false
                            onlineSubtitleError = null
                            onlineSubtitleDownloadErrors = emptyMap()
                            resetSourceStates()
                        },
                        onlineSubtitleQuery = onlineSubtitleQuery,
                        onlineSubtitleResults = onlineSubtitleResults,
                        onlineSubtitleHasSearched = onlineSubtitleHasSearched,
                        onlineSubtitleSearchLoading = onlineSubtitleSearchLoading,
                        canCancelOnlineSubtitleSearch = onlineSubtitleSearchJob != null,
                        onlineSubtitleSourceStates = onlineSubtitleSourceStates.values.toList(),
                        onlineSubtitleError = onlineSubtitleError,
                        onlineDownloadedSubtitleSourceUrls = downloadedSubtitleSourceUrls,
                        onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors,
                        onOnlineSubtitleQueryChange = {
                            onlineSubtitleQuery = it
                            onlineSubtitleHasSearched = false
                            onlineSubtitleError = null
                            onlineSubtitleDownloadErrors = emptyMap()
                            if (onlineSubtitleResults.isNotEmpty()) {
                                onlineSubtitleResults = onlineSubtitleSearchEngine.rankForQuery(
                                    results = onlineSubtitleResults,
                                    query = it,
                                )
                            }
                            resetSourceStates()
                        },
                        onOnlineSubtitleSearch = {
                            onlineSubtitleSearchJob?.cancel()
                            onlineSubtitleSearchJob = lifecycleScope.launch {
                                performSearch(onlineSubtitleQuery)
                            }
                        },
                        onOnlineSubtitleCancelSearch = ::cancelSearch,
                        onOnlineSubtitleDownloadClick = { result ->
                            lifecycleScope.launch {
                                onlineSubtitleSearchLoading = true
                                onlineSubtitleError = getString(dev.anilbeesetti.nextplayer.core.ui.R.string.subtitle_search_downloading)
                                val sourceKey = result.downloadUrl ?: result.id
                                onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors - sourceKey
                                when (val downloadResult = onlineSubtitleSearchEngine.download(result)) {
                                    null -> {
                                        onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors + (
                                            sourceKey to "Failed to download subtitle from ${result.source}."
                                            )
                                        onlineSubtitleError = null
                                    }

                                    is BrowserDownloadRequired -> {
                                        if (result.source == SubtitleSource.YIFY) {
                                            val activityResult = yifiWebViewSuspendLauncher.launch(
                                                YifiWebViewActivity.createIntent(this@PlayerActivity, downloadResult.url),
                                            )
                                            val capturedUrl = activityResult.data
                                                ?.getStringExtra(YifiWebViewActivity.EXTRA_CAPTURED_DOWNLOAD_URL)
                                                .orEmpty()
                                            if (activityResult.resultCode != RESULT_OK || capturedUrl.isBlank()) {
                                                onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors + (
                                                    sourceKey to "YIFY verification/download was canceled before a subtitle file was captured."
                                                    )
                                                onlineSubtitleError = null
                                            } else {
                                                val capturedCookies = activityResult.data
                                                    ?.getStringExtra(YifiWebViewActivity.EXTRA_CAPTURED_COOKIES)
                                                    .orEmpty()
                                                val capturedUserAgent = activityResult.data
                                                    ?.getStringExtra(YifiWebViewActivity.EXTRA_CAPTURED_USER_AGENT)
                                                    .orEmpty()
                                                saveYifiSession(
                                                    cookies = capturedCookies,
                                                    userAgent = capturedUserAgent,
                                                )
                                                val downloadedSubtitle = downloadSubtitleFromUrl(
                                                    url = capturedUrl,
                                                    fallbackFileName = result.displayName,
                                                    headers = yifiRequestHeaders(),
                                                )
                                                if (downloadedSubtitle == null) {
                                                    onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors + (
                                                        sourceKey to "Downloaded link from YIFY could not be fetched using the captured session."
                                                        )
                                                    onlineSubtitleError = null
                                                } else {
                                                    runCatching { attachDownloadedSubtitle(result, downloadedSubtitle) }
                                                        .onSuccess {
                                                            onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors - sourceKey
                                                            onlineSubtitleError = null
                                                        }
                                                        .onFailure { error ->
                                                            onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors + (
                                                                sourceKey to "Subtitle download succeeded but attaching track failed: ${error.message.orEmpty()}"
                                                                )
                                                            onlineSubtitleError = null
                                                        }
                                                }
                                            }
                                        } else {
                                            shouldPromptSubtitleImportAfterBrowserDownload = true
                                            onlineSubtitleError = getString(dev.anilbeesetti.nextplayer.core.ui.R.string.subtitle_download_browser_required)
                                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadResult.url)))
                                        }
                                    }

                                    is DownloadedSubtitle -> {
                                        runCatching { attachDownloadedSubtitle(result, downloadResult) }
                                            .onSuccess {
                                                onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors - sourceKey
                                                onlineSubtitleError = null
                                            }
                                            .onFailure { error ->
                                                onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors + (
                                                    sourceKey to "Subtitle download succeeded but attaching track failed: ${error.message.orEmpty()}"
                                                    )
                                                onlineSubtitleError = null
                                            }
                                    }
                                }
                                onlineSubtitleSearchLoading = false
                            }
                        },
                        onOnlineSubtitleRemoveDownloadedClick = { result ->
                            lifecycleScope.launch {
                                val sourceKey = result.downloadUrl ?: result.id
                                onlineSubtitleDownloadErrors = onlineSubtitleDownloadErrors - sourceKey
                                val subtitleUriString = mediaRepository.removeDownloadedSubtitle(sourceKey)
                                if (!subtitleUriString.isNullOrBlank()) runCatching {
                                    val uri = Uri.parse(subtitleUriString)
                                    if (uri.scheme == "file") uri.path?.let { File(it).delete() }
                                }
                            }
                        },
                        onBackClick = { finishAndStopPlayerSession() },
                        onPlayInBackgroundClick = {
                            playInBackground = true
                            finish()
                        },
                    )
                }
            }
        }

        playerApi = PlayerApi(this)
        onlineSubtitleSearchEngine = OnlineSubtitleSearchEngine(
            providers = listOf(
                SubDbProvider(contentResolver),
                OpenSubtitlesRestProvider(contentResolver),
                MoviesSubtitlesOrgProvider(),
                MoviesSubtitlesRtProvider(),
                PodnapisiProvider(),
                SubtitlecatProvider(),
                SubdlProvider(),
                YifySubtitlesProvider(
                    requestHeadersProvider = ::yifiRequestHeaders,
                    onAuthExpired = ::clearYifiSession,
                ),
            ),
        )
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            maybeInitControllerFuture()
            mediaController = controllerFuture?.await()

            mediaController?.run {
                updateKeepScreenOnFlag()
                addListener(playbackStateListener)
                startPlayback()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!shouldPromptSubtitleImportAfterBrowserDownload) return
        shouldPromptSubtitleImportAfterBrowserDownload = false
        lifecycleScope.launch {
            val subtitleUri = subtitleFileSuspendLauncher.launch(subtitleDocumentMimeTypes()) ?: return@launch
            importSelectedSubtitle(subtitleUri)
        }
    }

    override fun onStop() {
        mediaController?.run {
            viewModel.playWhenReady = playWhenReady
            removeListener(playbackStateListener)
        }
        val shouldPlayInBackground = playInBackground || playerPreferences?.autoBackgroundPlay == true
        if (subtitleFileSuspendLauncher.isAwaitingResult || yifiWebViewSuspendLauncher.isAwaitingResult || !shouldPlayInBackground) {
            mediaController?.pause()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            finish()
            if (!shouldPlayInBackground) {
                mediaController?.stopPlayerSession()
            }
        }

        controllerFuture?.run {
            MediaController.releaseFuture(this)
            controllerFuture = null
        }
        super.onStop()
    }

    private fun maybeInitControllerFuture() {
        if (controllerFuture == null) {
            val sessionToken = SessionToken(applicationContext, ComponentName(applicationContext, PlayerService::class.java))
            controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        }
    }

    private fun startPlayback() {
        val uri = intent.data ?: return

        val returningFromBackground = !isIntentNew && mediaController?.currentMediaItem != null
        val isNewUriTheCurrentMediaItem = mediaController?.currentMediaItem?.localConfiguration?.uri.toString() == uri.toString()

        if (returningFromBackground || isNewUriTheCurrentMediaItem) {
            mediaController?.prepare()
            mediaController?.playWhenReady = viewModel.playWhenReady
            return
        }

        isIntentNew = false

        lifecycleScope.launch {
            playVideo(uri)
        }
    }

    private suspend fun playVideo(uri: Uri) = withContext(Dispatchers.Default) {
        val mediaContentUri = getMediaContentUri(uri)
        val playlist = playerApi.getPlaylist().takeIf { it.isNotEmpty() }
            ?: mediaContentUri?.let { mediaUri ->
                viewModel.getPlaylistFromUri(mediaUri)
                    .map { it.uriString }
                    .toMutableList()
                    .apply {
                        if (!contains(mediaUri.toString())) {
                            add(index = 0, element = mediaUri.toString())
                        }
                    }
            } ?: listOf(uri.toString())

        val mediaItemIndexToPlay = playlist.indexOfFirst {
            it == (mediaContentUri ?: uri).toString()
        }.takeIf { it >= 0 } ?: 0

        val mediaItems = playlist.mapIndexed { index, uri ->
            MediaItem.Builder().apply {
                setUri(uri)
                setMediaId(uri)
                if (index == mediaItemIndexToPlay) {
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(playerApi.title)
                            setExtras(positionMs = playerApi.position?.toLong())
                        }.build(),
                    )
                    val apiSubs = playerApi.getSubs().map { subtitle ->
                        uriToSubtitleConfiguration(
                            uri = subtitle.uri,
                            subtitleEncoding = playerPreferences?.subtitleTextEncoding ?: "",
                            isSelected = subtitle.isSelected,
                        )
                    }
                    setSubtitleConfigurations(apiSubs)
                }
            }.build()
        }

        withContext(Dispatchers.Main) {
            mediaController?.run {
                setMediaItems(mediaItems, mediaItemIndexToPlay, playerApi.position?.toLong() ?: C.TIME_UNSET)
                playWhenReady = viewModel.playWhenReady
                prepare()
            }
        }
    }

    private fun playbackStateListener() = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            intent.data = mediaItem?.localConfiguration?.uri
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            updateKeepScreenOnFlag()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            when (playbackState) {
                Player.STATE_ENDED -> {
                    isPlaybackFinished = mediaController?.playbackState == Player.STATE_ENDED
                    finishAndStopPlayerSession()
                }

                else -> {}
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaController?.repeatMode != Player.REPEAT_MODE_OFF) return
                isPlaybackFinished = true
                finishAndStopPlayerSession()
            }
        }
    }

    override fun finish() {
        if (playerApi.shouldReturnResult) {
            val result = playerApi.getResult(
                isPlaybackFinished = isPlaybackFinished,
                duration = mediaController?.duration ?: C.TIME_UNSET,
                position = mediaController?.currentPosition ?: C.TIME_UNSET,
            )
            setResult(RESULT_OK, result)
        }
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) {
            setIntent(intent)
            isIntentNew = true
            if (mediaController != null) {
                startPlayback()
            }
        }
    }

    private fun updateKeepScreenOnFlag() {
        if (mediaController?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun finishAndStopPlayerSession() {
        finish()
        mediaController?.stopPlayerSession()
    }

    private fun deriveSubtitleSearchQuery(player: MediaController?): String {
        val mediaItem = player?.currentMediaItem
        val title = mediaItem?.mediaMetadata?.title?.toString().orEmpty().trim().withoutLikelyExtension()
        if (title.isNotBlank()) return title

        val mediaUri = mediaItem?.localConfiguration?.uri ?: intent.data
        if (mediaUri != null) {
            val filename = getFilenameFromUri(mediaUri).trim().withoutLikelyExtension()
            if (filename.isNotBlank()) return filename
        }
        return "subtitle"
    }

    private fun String.sanitizeAsFilename(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (sanitized.isBlank()) {
            "subtitle_${System.currentTimeMillis()}.srt"
        } else {
            sanitized
        }
    }

    private fun String.withoutLikelyExtension(): String {
        val trimmed = trim()
        val dotIndex = trimmed.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex >= trimmed.lastIndex) return trimmed
        val extension = trimmed.substring(dotIndex + 1)
        return if (extension.length in 2..5 && extension.all { it.isLetterOrDigit() }) {
            trimmed.substring(0, dotIndex).trim()
        } else {
            trimmed
        }
    }

    private fun subtitleDocumentMimeTypes(): Array<String> {
        return arrayOf(
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.APPLICATION_TTML,
            MimeTypes.TEXT_VTT,
            MimeTypes.TEXT_SSA,
            "application/zip",
            "application/x-zip-compressed",
            MimeTypes.BASE_TYPE_APPLICATION + "/octet-stream",
            MimeTypes.BASE_TYPE_TEXT + "/*",
        )
    }

    private suspend fun importSelectedSubtitle(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val subtitleUri = withContext(Dispatchers.IO) { maybeExtractSubtitleFromZip(uri) } ?: uri
        maybeInitControllerFuture()
        controllerFuture?.await()?.addSubtitleTrack(subtitleUri)
    }

    private fun maybeExtractSubtitleFromZip(uri: Uri): Uri? {
        val fileName = getFilenameFromUri(uri)
        val mimeType = contentResolver.getType(uri).orEmpty()
        val likelyZip = fileName.endsWith(".zip", ignoreCase = true) ||
            mimeType.contains("zip", ignoreCase = true)
        if (!likelyZip) return null

        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
        }.getOrNull() ?: return null

        val extracted = firstSubtitleFromZip(bytes) ?: return null
        val subtitleFile = File(subtitleCacheDir, extracted.fileName.sanitizeAsFilename())
        runCatching {
            subtitleFile.outputStream().use { output ->
                output.write(extracted.bytes)
            }
        }.getOrNull() ?: return null
        return subtitleFile.toUri()
    }

    private fun releaseSubtitleUriPermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun deleteSubtitleFromCacheIfExists(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val subtitleFile = File(path)
        val cacheRoot = subtitleCacheDir.absoluteFile
        val fileInCache = runCatching {
            subtitleFile.exists() && subtitleFile.absoluteFile.toPath().startsWith(cacheRoot.toPath())
        }.getOrDefault(false)
        if (fileInCache) {
            runCatching { subtitleFile.delete() }
        }
    }

    private suspend fun attachDownloadedSubtitle(
        result: OnlineSubtitleResult,
        downloadedSubtitle: DownloadedSubtitle,
    ) {
        val subtitleFile = File(subtitleCacheDir, downloadedSubtitle.fileName.sanitizeAsFilename())
        subtitleFile.outputStream().use { output ->
            output.write(downloadedSubtitle.bytes)
        }

        maybeInitControllerFuture()
        controllerFuture?.await()?.addSubtitleTrack(subtitleFile.toUri())
        val sourceKey = result.downloadUrl ?: result.id
        mediaRepository.addDownloadedSubtitle(
            sourceKey = sourceKey,
            subtitleUri = subtitleFile.toUri().toString(),
        )
    }

    private fun saveYifiSession(cookies: String, userAgent: String) {
        val sanitizedCookies = cookies.trim()
        val sanitizedUserAgent = userAgent.trim()
        if (sanitizedCookies.isBlank() && sanitizedUserAgent.isBlank()) return
        yifiCookiePrefs.edit()
            .putString(KEY_YIFI_COOKIE, sanitizedCookies)
            .putString(KEY_YIFI_USER_AGENT, sanitizedUserAgent)
            .apply()
    }

    private fun clearYifiSession() {
        yifiCookiePrefs.edit()
            .remove(KEY_YIFI_COOKIE)
            .remove(KEY_YIFI_USER_AGENT)
            .apply()
    }

    private fun yifiRequestHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val cookies = yifiCookiePrefs.getString(KEY_YIFI_COOKIE, "").orEmpty().trim()
        val userAgent = yifiCookiePrefs.getString(KEY_YIFI_USER_AGENT, "").orEmpty().trim()
        if (cookies.isNotBlank()) headers["Cookie"] = cookies
        if (userAgent.isNotBlank()) headers["User-Agent"] = userAgent
        return headers
    }

    private suspend fun downloadSubtitleFromUrl(
        url: String,
        fallbackFileName: String,
        headers: Map<String, String> = emptyMap(),
    ): DownloadedSubtitle? = withContext(Dispatchers.IO) {
        val response = runCatching { httpGet(url, headers = headers) }.getOrNull() ?: return@withContext null
        if (response.code !in 200..299 || response.body.isEmpty()) return@withContext null

        val isZip = response.contentType?.contains("zip", ignoreCase = true) == true ||
            (response.body.size > 3 && response.body[0] == 0x50.toByte() && response.body[1] == 0x4B.toByte())

        if (isZip) return@withContext firstSubtitleFromZip(response.body)

        val fileName = resolveFileName(
            fallback = "${fallbackFileName.ifBlank { "subtitle" }}.srt",
            headers = response.headers,
            url = url,
        )
        DownloadedSubtitle(fileName = fileName, bytes = response.body)
    }

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams?) {
        super.onWindowAttributesChanged(params)
        for (listener in onWindowAttributesChangedListener) {
            listener.accept(params)
        }
    }

    fun addOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.add(listener)
    }

    fun removeOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.remove(listener)
    }

    companion object {
        private const val PREFS_YIFI_COOKIE = "prefs_yifi_cookie"
        private const val KEY_YIFI_COOKIE = "key_yifi_cookie"
        private const val KEY_YIFI_USER_AGENT = "key_yifi_user_agent"
    }
}

private fun enabledOnlineSubtitleSources(preferences: PlayerPreferences?): List<SubtitleSource> {
    if (preferences == null) return emptyList()
    return buildList {
        if (preferences.onlineSubtitleSourceSubDbEnabled) add(SubtitleSource.SUBDB)
        if (preferences.onlineSubtitleSourceOpenSubtitlesEnabled) add(SubtitleSource.OPENSUBTITLES)
        if (preferences.onlineSubtitleSourceMovieSubtitlesEnabled) add(SubtitleSource.MOVIESUBTITLES)
        if (preferences.onlineSubtitleSourceMovieSubtitlesRtEnabled) add(SubtitleSource.MOVIESUBTITLESRT)
        if (preferences.onlineSubtitleSourcePodnapisiEnabled) add(SubtitleSource.PODNAPISI)
        if (preferences.onlineSubtitleSourceSubtitlecatEnabled) add(SubtitleSource.SUBTITLECAT)
        if (preferences.onlineSubtitleSourceSubdlEnabled) add(SubtitleSource.SUBDL)
        if (preferences.onlineSubtitleSourceYifyEnabled) add(SubtitleSource.YIFY)
    }
}
