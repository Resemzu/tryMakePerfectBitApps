package com.example.bitperfect.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.bitperfect.MainActivity
import com.example.bitperfect.R
import com.example.bitperfect.data.db.SongEntity

class MusicNotificationManager(
    private val context: Context
) {

    companion object {
        const val CHANNEL_ID = "bitperfect_media_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.bitperfect.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.bitperfect.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.bitperfect.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.bitperfect.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.example.bitperfect.ACTION_STOP"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val mediaSession: MediaSessionCompat = MediaSessionCompat(context, "BitPerfectMediaSession").apply {
        setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        isActive = true
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bit-Perfect Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Playback controls and HyperOS Dynamic Island support"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds and updates the MediaNotification and MediaSession state.
     */
    fun createNotification(
        song: SongEntity?,
        isPlaying: Boolean,
        coverBitmap: Bitmap? = null
    ): Notification {
        val title = song?.title ?: "BitPerfect Player"
        val artist = song?.artist ?: "High-Resolution Audio"
        val album = song?.album ?: "Bit-Perfect Output"

        // 1. Update MediaMetadataCompat
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song?.durationMs ?: 0L)

        if (coverBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        // 2. Update PlaybackStateCompat
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
        mediaSession.isActive = true

        // 3. PendingIntents for Media Actions
        val playIntent = PendingIntent.getService(
            context, 0, Intent(context, com.example.bitperfect.AudioService::class.java).setAction(ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            context, 1, Intent(context, com.example.bitperfect.AudioService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            context, 2, Intent(context, com.example.bitperfect.AudioService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getService(
            context, 3, Intent(context, com.example.bitperfect.AudioService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. NotificationCompat Builder (Monochrome icon for HyperOS Status Capsule / Dynamic Island)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music) // Transparent monochrome icon for HyperOS Dynamic Island
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(album)
            .setLargeIcon(coverBitmap)
            .setContentIntent(contentIntent)
            .setDeleteIntent(
                PendingIntent.getService(
                    context, 4, Intent(context, com.example.bitperfect.AudioService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) pauseIntent else playIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }

    fun updateNotification(song: SongEntity?, isPlaying: Boolean, coverBitmap: Bitmap? = null) {
        val notification = createNotification(song, isPlaying, coverBitmap)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
