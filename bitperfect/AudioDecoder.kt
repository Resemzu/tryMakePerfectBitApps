package com.example.bitperfect

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer

data class AudioFormatInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val mimeType: String,
    val durationUs: Long
)

class AudioDecoder(private val context: Context) {

    companion object {
        private const val TAG = "AudioDecoder"
    }

    /**
     * Opens File Descriptor (FD) via SAF, extracts audio metadata using MediaMetadataRetriever,
     * detaches ownership using pfd.detachFd() so Kotlin GC does not auto-close the native FD,
     * and sends the raw FD to NativeAudioEngine.
     */
    fun openFileDescriptorAndSendToNative(
        uri: Uri,
        nativeEngine: NativeAudioEngine,
        onMetadataExtracted: ((durationMs: Long, title: String?, artist: String?) -> Unit)? = null
    ): Boolean {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                // 1. Extract metadata via MediaMetadataRetriever using pfd.fileDescriptor
                val retriever = MediaMetadataRetriever()
                var durationMs = 0L
                var title: String? = null
                var artist: String? = null

                try {
                    retriever.setDataSource(pfd.fileDescriptor)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMs = durStr?.toLongOrNull() ?: 0L
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract metadata for URI: $uri", e)
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
                    }
                }

                onMetadataExtracted?.invoke(durationMs, title, artist)

                // 2. Detach FD so Java/Kotlin GC doesn't close the file descriptor
                val rawFd = pfd.detachFd()
                val statSize = pfd.statSize

                // 3. Send raw FD to C++ Native Engine
                val success = nativeEngine.openFileDescriptor(rawFd, 0L, statSize)
                Log.i(TAG, "Detached FD $rawFd sent to NativeAudioEngine (success=$success, size=$statSize)")
                success
            } else {
                Log.e(TAG, "Failed to open ParcelFileDescriptor for URI: $uri")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening FD for URI: $uri", e)
            false
        }
        // Note: Do NOT call pfd?.close() here because pfd.detachFd() detached ownership.
    }

    /**
     * Optional utility: Extracts Raw PCM data using MediaExtractor + MediaCodec in Kotlin layer if needed.
     */
    fun decodeToPcm(uri: Uri, onPcmChunk: (ByteBuffer, Int) -> Unit): AudioFormatInfo? {
        val extractor = MediaExtractor()
        var pfd: ParcelFileDescriptor? = null
        var codec: MediaCodec? = null

        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) return null

            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                Log.e(TAG, "No audio track found in file.")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(5000L)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 5000L)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        onPcmChunk(outputBuffer, info.size)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }

            AudioFormatInfo(sampleRate, channelCount, mime, durationUs)

        } catch (e: Exception) {
            Log.e(TAG, "Decoding failed for URI: $uri", e)
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
                pfd?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up decoder resources", e)
            }
        }
    }
}
