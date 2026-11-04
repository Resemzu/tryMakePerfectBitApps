package com.example.bitperfect

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitperfect.audio.BitPerfectStatus
import com.example.bitperfect.audio.DacFormatInfo
import com.example.bitperfect.audio.UsbDacDetector
import com.example.bitperfect.data.db.AppDatabase
import com.example.bitperfect.data.db.PlaylistEntity
import com.example.bitperfect.data.db.PlaylistSongCrossRef
import com.example.bitperfect.data.db.PlaylistWithSongs
import com.example.bitperfect.data.db.SongEntity
import com.example.bitperfect.player.QueueManager
import com.example.bitperfect.player.RepeatMode
import com.example.bitperfect.util.AudioMetadataExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderNode(
    val name: String,
    val uri: Uri,
    val trackCount: Int,
    val tracks: List<SongEntity>
)

class AudioViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val dao = database.playlistDao()

    val usbDacDetector = UsbDacDetector(application)
    val queueManager = QueueManager()
    private val nativeEngine = NativeAudioEngine()
    private var decoder: AudioDecoder? = null

    // Room Database Flows
    val allPlaylists: StateFlow<List<PlaylistEntity>> = dao.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dbSongs: StateFlow<List<SongEntity>> = dao.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Queue Flows exposed from QueueManager
    val currentQueue: StateFlow<List<SongEntity>> = queueManager.currentQueue
    val currentTrackIndex: StateFlow<Int> = queueManager.currentIndex
    val currentSong: StateFlow<SongEntity?> = queueManager.currentSong
    val isShuffleEnabled: StateFlow<Boolean> = queueManager.isShuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = queueManager.repeatMode

    // USB DAC & Bit-Perfect Status
    val isUsbDacConnected: StateFlow<Boolean> = usbDacDetector.isUsbDacConnected
    val dacFormatInfo: StateFlow<DacFormatInfo> = usbDacDetector.dacFormatInfo

    private val _bitPerfectStatus = MutableStateFlow(BitPerfectStatus.DISABLED_INTERNAL)
    val bitPerfectStatus: StateFlow<BitPerfectStatus> = _bitPerfectStatus.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _folderList = MutableStateFlow<List<FolderNode>>(emptyList())
    val folderList: StateFlow<List<FolderNode>> = _folderList.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _currentCoverBitmap = MutableStateFlow<Bitmap?>(null)
    val currentCoverBitmap: StateFlow<Bitmap?> = _currentCoverBitmap.asStateFlow()

    private var pollingJob: Job? = null
    private var lastUiUpdateMs = 0L

    init {
        initAudioEngine()
        // Automatically trigger MediaStore Delta Sync on startup
        scanMediaStore(application)

        // Observe USB DAC connection & engine state
        viewModelScope.launch {
            usbDacDetector.isUsbDacConnected.collect {
                updateBitPerfectStatus()
            }
        }
        viewModelScope.launch {
            queueManager.currentSong.collect { song ->
                if (song != null) {
                    loadCoverBitmap(song)
                } else {
                    _currentCoverBitmap.value = null
                }
            }
        }
    }

    fun initAudioEngine() {
        try {
            nativeEngine.initAudioEngine()
            updateBitPerfectStatus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateBitPerfectStatus() {
        val isExclusive = try { nativeEngine.isBitPerfectMode() } catch (e: Exception) { false }
        val newStatus = usbDacDetector.evaluateBitPerfectStatus(isExclusive)
        _bitPerfectStatus.value = newStatus
    }

    // --- MEDIASTORE BACKGROUND SCANNING & DELTA SYNC ---

    fun scanMediaStore(context: Context) {
        viewModelScope.launch {
            _isScanning.value = true
            withContext(Dispatchers.IO) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_MODIFIED
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                val mediaStoreSongs = mutableListOf<SongEntity>()

                try {
                    context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        null,
                        "${MediaStore.Audio.Media.TITLE} ASC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val title = cursor.getString(titleCol) ?: "Unknown Track"
                            val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                            val album = cursor.getString(albumCol) ?: "Unknown Album"
                            val duration = cursor.getLong(durCol)
                            val dateMod = cursor.getLong(dateCol)
                            val uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                id
                            ).toString()

                            mediaStoreSongs.add(
                                SongEntity(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    contentUri = uri,
                                    durationMs = if (duration > 0) duration else 240000L,
                                    folderName = "Library",
                                    dateModified = dateMod
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioViewModel", "Error scanning MediaStore", e)
                }

                // Delta Sync
                val existingDbSongs = dao.getSongsList()
                val existingUris = existingDbSongs.map { it.contentUri }.toSet()
                val newMediaUris = mediaStoreSongs.map { it.contentUri }.toSet()

                val songsToInsert = mediaStoreSongs.filter { it.contentUri !in existingUris }
                if (songsToInsert.isNotEmpty()) {
                    dao.insertSongs(songsToInsert)
                }

                val urisToDelete = existingUris.filter { it !in newMediaUris && it.startsWith("content://media/") }
                if (urisToDelete.isNotEmpty()) {
                    dao.deleteSongsByUris(urisToDelete)
                }
            }
            _isScanning.value = false
        }
    }

    // --- THROTTLED UI POSITION POLLING ---

    private fun startThrottledPositionPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.Default) {
            while (_isPlaying.value) {
                val now = SystemClock.uptimeMillis()
                if (now - lastUiUpdateMs >= 50) {
                    lastUiUpdateMs = now
                    val pos = nativeEngine.getNativePlaybackPositionMs()
                    _currentPosition.value = pos
                }
                delay(50)
            }
        }
    }

    private fun stopThrottledPositionPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // --- PLAYBACK & QUEUE CONTROLS ---

    fun playSongAtIndex(context: Context, index: Int) {
        val list = currentQueue.value
        if (index < 0 || index >= list.size) return

        queueManager.setQueue(list, index)
        val song = list[index]
        playSong(context, song)
    }

    fun playSong(context: Context, song: SongEntity) {
        if (decoder == null) {
            decoder = AudioDecoder(context.applicationContext)
        }

        nativeEngine.stop()

        val uri = Uri.parse(song.contentUri)
        val fdOpened = decoder?.openFileDescriptorAndSendToNative(uri, nativeEngine) { _, _, _ ->
            // Duration update if needed
        } ?: false

        if (!fdOpened) {
            nativeEngine.loadTrack(song.contentUri)
        }

        nativeEngine.play()
        updateBitPerfectStatus()

        _isPlaying.value = true
        _currentPosition.value = 0L
        lastUiUpdateMs = 0L
        startThrottledPositionPolling()
    }

    fun togglePlayPause() {
        val song = currentSong.value
        if (song == null) {
            val list = currentQueue.value
            if (list.isNotEmpty()) {
                playSongAtIndex(getApplication(), 0)
            }
            return
        }

        if (_isPlaying.value) {
            nativeEngine.pause()
            _isPlaying.value = false
            stopThrottledPositionPolling()
        } else {
            nativeEngine.play()
            _isPlaying.value = true
            updateBitPerfectStatus()
            startThrottledPositionPolling()
        }
    }

    fun playNext(context: Context) {
        val nextSong = queueManager.next() ?: return
        playSong(context, nextSong)
    }

    fun playPrevious(context: Context) {
        val prevSong = queueManager.previous() ?: return
        playSong(context, prevSong)
    }

    fun toggleShuffle() {
        queueManager.toggleShuffle()
    }

    fun cycleRepeatMode(): RepeatMode {
        return queueManager.cycleRepeatMode()
    }

    fun stopAudio() {
        nativeEngine.stop()
        _isPlaying.value = false
        _currentPosition.value = 0L
        stopThrottledPositionPolling()
    }

    fun onSeek(positionMs: Long) {
        _currentPosition.value = positionMs
        nativeEngine.seekTo(positionMs)
    }

    fun reinitializeStream(forceFallback: Boolean) {
        nativeEngine.reinitializeStream(forceFallback)
        updateBitPerfectStatus()
    }

    // --- ROOM PLAYLIST & SONG OPERATIONS ---

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertPlaylist(PlaylistEntity(name = name.trim()))
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val crossRefs = songIds.map { songId ->
                PlaylistSongCrossRef(playlistId = playlistId, songId = songId)
            }
            dao.insertPlaylistSongCrossRefs(crossRefs)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deletePlaylist(playlistId)
        }
    }

    fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs?> {
        return dao.getPlaylistWithSongs(playlistId)
    }

    fun playPlaylist(context: Context, songs: List<SongEntity>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        queueManager.setQueue(songs, startIndex)
        playSong(context, songs[startIndex.coerceIn(0, songs.size - 1)])
    }

    fun onSongFinished(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updatePlayStats(songId)
            withContext(Dispatchers.Main) {
                playNext(getApplication())
            }
        }
    }

    // --- FOLDER SCANNING & NAVIGATION ---

    fun scanFolder(context: Context, treeUri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            val (folders, allTracks) = withContext(Dispatchers.IO) {
                scanUriRecursive(context, treeUri)
            }

            _folderList.value = folders
            if (allTracks.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    dao.insertSongs(allTracks)
                }
                queueManager.setQueue(allTracks, 0)
            }
            _isScanning.value = false
        }
    }

    private fun scanUriRecursive(context: Context, treeUri: Uri): Pair<List<FolderNode>, List<SongEntity>> {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return Pair(emptyList(), emptyList())
        val folderNodes = mutableListOf<FolderNode>()
        val allTracks = mutableListOf<SongEntity>()
        val retriever = MediaMetadataRetriever()

        fun traverseDirectory(dir: DocumentFile, currentFolderName: String) {
            val dirTracks = mutableListOf<SongEntity>()
            dir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    traverseDirectory(file, file.name ?: "Folder")
                } else if (isAudioFile(file)) {
                    var durationMs = 0L
                    var trackTitle = file.name ?: "Unknown Track"
                    var trackArtist = "Hi-Res Audio"
                    var trackAlbum = "Unknown Album"

                    try {
                        context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            durationMs = durStr?.toLongOrNull() ?: 0L
                            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            if (!metaTitle.isNullOrEmpty()) {
                                trackTitle = metaTitle
                            }
                            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            if (!metaArtist.isNullOrEmpty()) {
                                trackArtist = metaArtist
                            }
                            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            if (!metaAlbum.isNullOrEmpty()) {
                                trackAlbum = metaAlbum
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("AudioViewModel", "Error reading metadata for ${file.name}", e)
                    }

                    val song = SongEntity(
                        title = trackTitle,
                        artist = trackArtist,
                        album = trackAlbum,
                        contentUri = file.uri.toString(),
                        durationMs = if (durationMs > 0) durationMs else 240000L,
                        folderName = currentFolderName
                    )
                    dirTracks.add(song)
                    allTracks.add(song)
                }
            }
            if (dirTracks.isNotEmpty()) {
                folderNodes.add(
                    FolderNode(
                        name = currentFolderName,
                        uri = dir.uri,
                        trackCount = dirTracks.size,
                        tracks = dirTracks
                    )
                )
            }
        }

        try {
            traverseDirectory(folder, folder.name ?: "Selected Folder")
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return Pair(folderNodes, allTracks)
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val mime = file.type
        if (mime != null && mime.startsWith("audio/")) return true
        val name = file.name?.lowercase() ?: return false
        val audioExtensions = listOf(".mp3", ".flac", ".wav", ".dsf", ".dff", ".m4a", ".aac", ".ogg", ".opus", ".ape", ".alac")
        return audioExtensions.any { name.endsWith(it) }
    }

    private fun loadCoverBitmap(song: SongEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = AudioMetadataExtractor.loadAlbumArt(getApplication(), Uri.parse(song.contentUri))
            _currentCoverBitmap.value = bitmap
        }
    }

    fun playFolder(context: Context, folderNode: FolderNode) {
        if (folderNode.tracks.isNotEmpty()) {
            playPlaylist(context, folderNode.tracks, 0)
        }
    }

    fun removeTrackAt(index: Int) {
        queueManager.removeAt(index)
    }

    fun clearQueue() {
        stopAudio()
        queueManager.clearQueue()
    }

    override fun onCleared() {
        super.onCleared()
        usbDacDetector.unregisterCallback()
        stopThrottledPositionPolling()
    }
}
