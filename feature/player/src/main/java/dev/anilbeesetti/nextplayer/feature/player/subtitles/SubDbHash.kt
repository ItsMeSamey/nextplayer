package dev.anilbeesetti.nextplayer.feature.player.subtitles

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.security.MessageDigest

internal object SubDbHash {
    private const val CHUNK_SIZE = 64 * 1024

    fun compute(contentResolver: ContentResolver, videoUri: Uri): String? {
        val descriptor = contentResolver.openAssetFileDescriptor(videoUri, "r") ?: return null
        descriptor.use { afd ->
            val length = afd.length
            if (length <= 0 || length < CHUNK_SIZE * 2L) return null

            val fileDescriptor = afd.parcelFileDescriptor ?: return null
            FileInputStream(fileDescriptor.fileDescriptor).use { stream ->
                val channel = stream.channel
                val head = ByteArray(CHUNK_SIZE)
                val tail = ByteArray(CHUNK_SIZE)

                channel.position(afd.startOffset)
                if (stream.read(head) != CHUNK_SIZE) return null

                channel.position(afd.startOffset + length - CHUNK_SIZE)
                if (stream.read(tail) != CHUNK_SIZE) return null

                val digest = MessageDigest.getInstance("MD5")
                digest.update(head)
                digest.update(tail)
                return digest.digest().joinToString(separator = "") { "%02x".format(it) }
            }
        }
    }
}
