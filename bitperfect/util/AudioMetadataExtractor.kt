package com.example.bitperfect.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

object AudioMetadataExtractor {

    private const val TAG = "AudioMetadataExtractor"

    /**
     * Safely loads cover artwork from an audio file Uri using MediaMetadataRetriever.
     * Returns null if no cover artwork is embedded or if an error occurs.
     */
    fun loadAlbumArt(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content" || uri.scheme == "file") {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: run {
                    retriever.setDataSource(context, uri)
                }
            } else {
                retriever.setDataSource(uri.toString())
            }

            val artBytes = retriever.embeddedPicture
            if (artBytes != null) {
                BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load album art for Uri: $uri", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
