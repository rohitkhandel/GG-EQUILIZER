package com.rohitkhandel.ggaudio.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Reads embedded tags from a user-selected local document.
 * Call from an IO dispatcher; all failures fall back to a usable filename/title.
 */
object AudioMetadataReader {
    fun read(context: Context, uri: Uri): LocalAudioFile {
        val fallbackTitle = queryDisplayName(context, uri)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }

            LocalAudioFile(
                uri = uri,
                title = (embeddedTitle ?: fallbackTitle).take(512),
                artist = artist?.take(256),
                album = album?.take(256),
                durationMs = durationMs
            )
        } catch (_: Exception) {
            LocalAudioFile(uri = uri, title = fallbackTitle.take(512))
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0) {
                    cursor.getString(column)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } ?: fallbackName(uri)
        } catch (_: Exception) {
            fallbackName(uri)
        }
    }

    private fun fallbackName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Selected audio"
}
