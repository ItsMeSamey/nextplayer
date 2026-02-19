package dev.anilbeesetti.nextplayer.feature.player.subtitles

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream

internal data class OpenSubtitlesHashResult(
    val hash: String,
    val byteSize: Long,
)

internal object OpenSubtitlesHash {
    private const val CHUNK_SIZE = 64 * 1024

    fun compute(contentResolver: ContentResolver, videoUri: Uri): OpenSubtitlesHashResult? {
        val descriptor = contentResolver.openAssetFileDescriptor(videoUri, "r") ?: return null
        descriptor.use { afd ->
            val length = afd.length
            if (length < CHUNK_SIZE * 2L) return null

            val fileDescriptor = afd.parcelFileDescriptor ?: return null
            FileInputStream(fileDescriptor.fileDescriptor).use { stream ->
                val channel = stream.channel
                val head = ByteArray(CHUNK_SIZE)
                val tail = ByteArray(CHUNK_SIZE)

                channel.position(afd.startOffset)
                if (stream.read(head) != CHUNK_SIZE) return null

                channel.position(afd.startOffset + length - CHUNK_SIZE)
                if (stream.read(tail) != CHUNK_SIZE) return null

                var hash = length.toULong()
                var offset = 0
                while (offset <= CHUNK_SIZE - 8) {
                    hash += littleEndianLong(head, offset).toULong()
                    hash += littleEndianLong(tail, offset).toULong()
                    offset += 8
                }

                val hashHex = hash.toString(16).padStart(16, '0').takeLast(16)
                return OpenSubtitlesHashResult(
                    hash = hashHex,
                    byteSize = length,
                )
            }
        }
    }

    private fun littleEndianLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xffL) shl (8 * i))
        }
        return value
    }
}
