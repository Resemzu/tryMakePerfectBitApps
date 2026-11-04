package com.example.bitperfect

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.bitperfect.audio.BitPerfectStatus
import com.example.bitperfect.audio.DacFormatInfo
import com.example.bitperfect.audio.UsbDacDetector
import com.example.bitperfect.data.db.AppDatabase
import com.example.bitperfect.data.db.SongEntity
import com.example.bitperfect.notification.MusicNotificationManager
import com.example.bitperfect.player.QueueManager
import com.example.bitperfect.util.AudioMetadataExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "AudioService"
        private const val WAKE_LOCK_TAG = "BitPerfect::AudioPlaybackWakeLock"

        // State global untuk dibaca oleh UI
        val isPlayingState = MutableStateFlow(false)
        val currentPositionState = MutableStateFlow(0L)
    }

    private val binder = LocalBinder()
    val nativeEngine = NativeAudioEngine()

    lateinit var usbDacDetector: UsbDacDetector
        private set

    private lateinit var notificationManager: MusicNotificationManager
    private lateinit var database: AppDatabase
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null

    private val _currentCoverBitmap = MutableStateFlow<Bitmap?>(null)
    val currentCoverBitmap: StateFlow<Bitmap?> = _currentCoverBitmap.asStateFlow()

    private val _bitPerfectStatus = MutableStateFlow(BitPerfectStatus.DISABLED_INTERNAL)
    val bitPerfectStatus: StateFlow<BitPerfectStatus> = _bitPerfectStatus.asStateFlow()

    private val _dacFormatInfo = MutableStateFlow(DacFormatInfo())
    val dacFormatInfo: StateFlow<DacFormatInfo> = _dacFormatInfo.asStateFlow()

    var isPlaying = false
        private set

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "Headphones/DAC unplugged. Auto-pausing playback...")
                pausePlayback()
            }
        }
    }

    private val usbHotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB DAC Attached! Re-initializing stream to EXCLUSIVE Bit-Perfect Mode...")
                    nativeEngine.reinitializeStream(forceFallback = false)
                    updateBitPerfectStatus()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB DAC Detached! Pausing & falling back to SHARED mode...")
                    pausePlayback()
                    nativeEngine.reinitializeStream(forceFallback = true)
                    updateBitPerfectStatus()
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        database = AppDatabase.getInstance(this)
        notificationManager = MusicNotificationManager(this)
        usbDacDetector = UsbDacDetector(this)

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        nativeEngine.registerEosCallback(this)

        registerNoisyReceiver()
        registerUsbHotplugReceiver()

        serviceScope.launch {
            usbDacDetector.isUsbDacConnected.collect {
                updateBitPerfectStatus()
            }
        }
        serviceScope.launch {
            usbDacDetector.dacFormatInfo.collect { info ->
                _dacFormatInfo.value = info
            }
        }
        serviceScope.launch {
            QueueManager.currentSong.collect { song ->
                if (song != null) {
                    loadCoverAndNotify(song)
                } else {
                    _currentCoverBitmap.value = null
                }
            }
        }
    }

    fun onEosReached() {
        Log.i(TAG, "JNI EOS Callback received in AudioService! Executing Auto-Next in background...")
        serviceScope.launch(Dispatchers.Main) {
            val currentSong = QueueManager.currentSong.value
            if (currentSong != null) {
                withContext(Dispatchers.IO) {
                    database.playlistDao().updatePlayStats(currentSong.id)
                }
            }
            playNextSong()
        }
    }

    fun onNativeTrackEnded() {
        onEosReached()
    }

    fun updateBitPerfectStatus() {
        val isExclusive = try { nativeEngine.isBitPerfectMode() } catch (e: Exception) { false }
        val newStatus = usbDacDetector.evaluateBitPerfectStatus(isExclusive)
        _bitPerfectStatus.value = newStatus
    }

    private fun startThrottledPositionPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch(Dispatchers.Default) {
            while (isPlaying) {
                currentPositionState.value = nativeEngine.getNativePlaybackPositionMs()
                delay(50)
            }
        }
    }

    private fun stopThrottledPositionPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MusicNotificationManager.ACTION_PLAY -> resumePlayback()
            MusicNotificationManager.ACTION_PAUSE -> pausePlayback()
            MusicNotificationManager.ACTION_NEXT -> playNextSong()
            MusicNotificationManager.ACTION_PREVIOUS -> playPreviousSong()
            MusicNotificationManager.ACTION_STOP -> stopPlayback()
            "ACTION_PLAY_NEW" -> {
                QueueManager.currentSong.value?.let { playSong(it) }
            }
            "ACTION_SEEK" -> {
                val pos = intent.getLongExtra("SEEK_POS", 0L)
                nativeEngine.seekTo(pos)
                currentPositionState.value = pos
            }
        }
        val notification = notificationManager.createNotification(
            song = QueueManager.currentSong.value,
            isPlaying = isPlaying,
            coverBitmap = _currentCoverBitmap.value
        )
        startForeground(MusicNotificationManager.NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            audioFocusRequest = request
            val result = audioManager.requestAudioFocus(request)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                resumePlayback()
            }
        }
    }

    fun playSong(song: SongEntity): Boolean {
        if (!requestAudioFocus()) return false
        nativeEngine.stop()

        val uri = Uri.parse(song.contentUri)
        val decoder = AudioDecoder(applicationContext)
        val fdOpened = decoder.openFileDescriptorAndSendToNative(uri, nativeEngine) { _, _, _ -> }

        if (!fdOpened) {
            nativeEngine.loadTrack(song.contentUri)
        }

        val success = nativeEngine.play()
        if (success) {
            isPlaying = true
            isPlayingState.value = true
            acquireWakeLock()
            updateBitPerfectStatus()
            loadCoverAndNotify(song)
            startThrottledPositionPolling()
        }
        return success
    }

    fun playNextSong(): Boolean {
        val nextSong = QueueManager.next() ?: run {
            stopPlayback()
            return false
        }
        return playSong(nextSong)
    }

    fun playPreviousSong(): Boolean {
        val prevSong = QueueManager.previous() ?: return false
        return playSong(prevSong)
    }

    fun resumePlayback(): Boolean {
        if (!requestAudioFocus()) return false
        val success = nativeEngine.play()
        if (success) {
            isPlaying = true
            isPlayingState.value = true
            acquireWakeLock()
            updateBitPerfectStatus()
            notificationManager.updateNotification(QueueManager.currentSong.value, isPlaying = true, coverBitmap = _currentCoverBitmap.value)
            startThrottledPositionPolling()
        }
        return success
    }

    fun pausePlayback(): Boolean {
        val success = nativeEngine.pause()
        isPlaying = false
        isPlayingState.value = false
        releaseWakeLock()
        abandonAudioFocus()
        notificationManager.updateNotification(QueueManager.currentSong.value, isPlaying = false, coverBitmap = _currentCoverBitmap.value)
        stopThrottledPositionPolling()
        return success
    }

    fun stopPlayback(): Boolean {
        val success = nativeEngine.stop()
        isPlaying = false
        isPlayingState.value = false
        releaseWakeLock()
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.release()
        stopThrottledPositionPolling()
        return success
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L)
                Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK for screen-off playback stability")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Released PARTIAL_WAKE_LOCK")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock", e)
        }
    }

    private fun loadCoverAndNotify(song: SongEntity) {
        serviceScope.launch(Dispatchers.IO) {
            val bitmap = AudioMetadataExtractor.loadAlbumArt(applicationContext, Uri.parse(song.contentUri))
            _currentCoverBitmap.value = bitmap
            withContext(Dispatchers.Main) {
                notificationManager.updateNotification(song, isPlaying = isPlaying, coverBitmap = bitmap)
            }
        }
    }

    private fun registerNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
    }

    private fun registerUsbHotplugReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbHotplugReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopThrottledPositionPolling()
        try {
            unregisterReceiver(noisyReceiver)
            unregisterReceiver(usbHotplugReceiver)
            usbDacDetector.unregisterCallback()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        releaseWakeLock()
        stopPlayback()
        serviceScope.cancel()
    }
}