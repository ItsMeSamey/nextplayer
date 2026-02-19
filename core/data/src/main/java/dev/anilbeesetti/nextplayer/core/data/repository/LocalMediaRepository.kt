package dev.anilbeesetti.nextplayer.core.data.repository

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.data.mappers.toFolder
import dev.anilbeesetti.nextplayer.core.data.mappers.toVideo
import dev.anilbeesetti.nextplayer.core.data.mappers.toVideoState
import dev.anilbeesetti.nextplayer.core.data.models.VideoState
import dev.anilbeesetti.nextplayer.core.database.converter.UriListConverter
import dev.anilbeesetti.nextplayer.core.database.dao.DirectoryDao
import dev.anilbeesetti.nextplayer.core.database.dao.MediumDao
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.database.entities.MediumStateEntity
import dev.anilbeesetti.nextplayer.core.database.relations.DirectoryWithMedia
import dev.anilbeesetti.nextplayer.core.database.relations.MediumWithInfo
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.Video
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class LocalMediaRepository @Inject constructor(
    private val mediumDao: MediumDao,
    private val mediumStateDao: MediumStateDao,
    private val directoryDao: DirectoryDao,
) : MediaRepository {

    override fun getVideosFlow(): Flow<List<Video>> {
        return mediumDao.getAllWithInfo().map { it.map(MediumWithInfo::toVideo) }
    }

    override fun getVideosFlowFromFolderPath(folderPath: String): Flow<List<Video>> {
        return mediumDao.getAllWithInfoFromDirectory(folderPath).map { it.map(MediumWithInfo::toVideo) }
    }

    override fun getFoldersFlow(): Flow<List<Folder>> {
        return directoryDao.getAllWithMedia().map { it.map(DirectoryWithMedia::toFolder) }
    }

    override suspend fun getVideoByUri(uri: String): Video? {
        return mediumDao.getWithInfo(uri)?.toVideo()
    }

    override suspend fun getVideoState(uri: String): VideoState? {
        return mediumStateDao.get(uri)?.toVideoState()
    }

    override suspend fun updateMediumLastPlayedTime(uri: String, lastPlayedTime: Long) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                lastPlayedTime = lastPlayedTime,
            ),
        )
    }

    override suspend fun updateMediumPosition(uri: String, position: Long) {
        val duration = mediumDao.get(uri)?.duration ?: position.plus(1)
        val adjustedPosition = position.takeIf { it < duration } ?: Long.MIN_VALUE.plus(1)

        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                playbackPosition = adjustedPosition,
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateMediumPlaybackSpeed(uri: String, playbackSpeed: Float) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                playbackSpeed = playbackSpeed,
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                audioTrackIndex = audioTrackIndex,
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                subtitleTrackIndex = subtitleTrackIndex,
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateMediumVideoTrack(uri: String, videoTrackIndex: Int) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                videoTrackIndex = videoTrackIndex,
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateMediumZoom(uri: String, zoom: Float) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                videoScale = zoom,
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)
        val currentExternalSubs = UriListConverter.fromStringToList(stateEntity.externalSubs)

        if (currentExternalSubs.contains(subtitleUri)) return
        val newExternalSubs = UriListConverter.fromListToString(urlList = currentExternalSubs + subtitleUri)

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                externalSubs = newExternalSubs,
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateAudioDelay(uri: String, trackIndex: Int, delay: Long) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)
        val currentDelays = deserializeDelayMap(stateEntity.audioTrackDelays).toMutableMap()
        currentDelays[trackIndex] = delay

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                audioTrackDelays = serializeDelayMap(currentDelays),
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateSubtitleDelay(uri: String, trackIndex: Int, delay: Long) {
        val stateEntity = mediumStateDao.get(uri) ?: MediumStateEntity(uriString = uri)
        val currentDelays = deserializeDelayMap(stateEntity.subtitleTrackDelays).toMutableMap()
        currentDelays[trackIndex] = delay

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                subtitleTrackDelays = serializeDelayMap(currentDelays),
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override fun getDownloadedSubtitleSourceUrls(): Flow<Set<String>> {
        return mediumStateDao.getAsFlow(DOWNLOADED_SUBTITLE_STATE_URI)
            .map { stateEntity ->
                deserializeDownloadedSubtitles(stateEntity?.downloadedSubtitles.orEmpty()).keys
            }
            .distinctUntilChanged()
    }

    override suspend fun addDownloadedSubtitle(sourceKey: String, subtitleUri: String) {
        if (sourceKey.isBlank() || subtitleUri.isBlank()) return
        val stateEntity = mediumStateDao.get(DOWNLOADED_SUBTITLE_STATE_URI)
            ?: MediumStateEntity(uriString = DOWNLOADED_SUBTITLE_STATE_URI)
        val values = deserializeDownloadedSubtitles(stateEntity.downloadedSubtitles).toMutableMap()
        values[sourceKey] = subtitleUri

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                downloadedSubtitles = serializeDownloadedSubtitles(values),
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeDownloadedSubtitle(sourceKey: String): String? {
        if (sourceKey.isBlank()) return null
        val stateEntity = mediumStateDao.get(DOWNLOADED_SUBTITLE_STATE_URI) ?: return null
        val values = deserializeDownloadedSubtitles(stateEntity.downloadedSubtitles).toMutableMap()
        val removedSubtitleUri = values.remove(sourceKey) ?: return null

        mediumStateDao.upsert(
            mediumState = stateEntity.copy(
                downloadedSubtitles = serializeDownloadedSubtitles(values),
                lastPlayedTime = System.currentTimeMillis(),
            ),
        )

        return removedSubtitleUri
    }

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

    private fun serializeDownloadedSubtitles(values: Map<String, String>): String {
        val jsonObject = JSONObject()
        values.forEach { (sourceKey, subtitleUri) ->
            jsonObject.put(sourceKey, subtitleUri)
        }
        return jsonObject.toString()
    }

    private fun deserializeDownloadedSubtitles(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val jsonObject = JSONObject(raw)
            jsonObject.keys().asSequence()
                .mapNotNull { sourceKey ->
                    val subtitleUri = jsonObject.optString(sourceKey).trim()
                    if (sourceKey.isBlank() || subtitleUri.isBlank()) {
                        null
                    } else {
                        sourceKey to subtitleUri
                    }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val DOWNLOADED_SUBTITLE_STATE_URI = "__downloaded_subtitles__"
    }
}
