package com.example.bitperfect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitperfect.data.db.SongEntity
import com.example.bitperfect.player.RepeatMode
import com.example.bitperfect.ui.components.BitPerfectStatusChip
import com.example.bitperfect.ui.playlist.PlaylistDetailScreen
import com.example.bitperfect.ui.playlist.PlaylistsScreen
import com.example.bitperfect.ui.theme.BitPerfectAudioTheme
import com.example.bitperfect.ui.viewmodel.PlaylistViewModel
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "bitperfect_prefs"
        private const val KEY_SAVED_FOLDER_URI = "saved_folder_uri"
    }

    private val viewModel: AudioViewModel by viewModels()
    private val playlistViewModel: PlaylistViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAndLoadSavedFolder()
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            saveFolderUri(treeUri)
            viewModel.scanFolder(this, treeUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            BitPerfectAudioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        playlistViewModel = playlistViewModel,
                        onOpenFolderClick = { folderPickerLauncher.launch(null) }
                    )
                }
            }
        }
    }

    private fun checkAndLoadSavedFolder() {
        val savedUri = getSavedFolderUri()
        if (savedUri != null) {
            val hasPermission = contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == savedUri && permission.isReadPermission
            }
            if (hasPermission) {
                viewModel.scanFolder(this, savedUri)
                return
            }
        }
        folderPickerLauncher.launch(null)
    }

    private fun saveFolderUri(uri: Uri) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVED_FOLDER_URI, uri.toString()).apply()
    }

    private fun getSavedFolderUri(): Uri? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_SAVED_FOLDER_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
fun MainScreen(
    viewModel: AudioViewModel,
    playlistViewModel: PlaylistViewModel,
    onOpenFolderClick: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var activePlaylistId by remember { mutableStateOf<Long?>(null) }
    val isScanning by viewModel.isScanning.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // App Bar Header
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "BitPerfect Player",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                IconButton(onClick = onOpenFolderClick) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Select Folder",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (isScanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        // Navigation Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    selectedTab = 0
                },
                text = { Text("Now Playing") },
                icon = { Icon(Icons.Default.MusicNote, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                },
                text = { Text("Queue") },
                icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = {
                    selectedTab = 2
                },
                text = { Text("Playlists") },
                icon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 3,
                onClick = {
                    selectedTab = 3
                },
                text = { Text("Folders") },
                icon = { Icon(Icons.Default.Folder, contentDescription = null) }
            )
        }

        // Body Content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                0 -> NowPlayingTab(viewModel = viewModel)
                1 -> QueueTab(viewModel = viewModel)
                2 -> {
                    val currentPlaylistId = activePlaylistId
                    if (currentPlaylistId == null) {
                        PlaylistsScreen(
                            playlistViewModel = playlistViewModel,
                            onPlaylistClick = { id -> activePlaylistId = id }
                        )
                    } else {
                        PlaylistDetailScreen(
                            playlistId = currentPlaylistId,
                            playlistViewModel = playlistViewModel,
                            onBackClick = { activePlaylistId = null },
                            onPlaySongs = { songs, startIndex ->
                                viewModel.playPlaylist(context, songs, startIndex)
                            }
                        )
                    }
                }
                3 -> FolderTab(viewModel = viewModel, onOpenFolderClick = onOpenFolderClick)
            }
        }
    }
}

@Composable
fun NowPlayingTab(viewModel: AudioViewModel) {
    val context = LocalContext.current
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPos by viewModel.currentPosition.collectAsState()
    val bitPerfectStatus by viewModel.bitPerfectStatus.collectAsState()
    val dacFormatInfo by viewModel.dacFormatInfo.collectAsState()
    val isShuffle by viewModel.isShuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val coverBitmap by viewModel.currentCoverBitmap.collectAsState()

    var sliderPosition by remember(currentPos) { mutableFloatStateOf(currentPos.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    val song = currentSong
    val duration = song?.durationMs ?: 240000L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Bit-Perfect & USB DAC Dynamic Status Chip
        BitPerfectStatusChip(
            status = bitPerfectStatus,
            dacFormatInfo = dacFormatInfo,
            modifier = Modifier.padding(top = 4.dp)
        )

        // Cover Art Card
        Card(
            modifier = Modifier
                .size(240.dp)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap!!.asImageBitmap(),
                        contentDescription = "Cover Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Metadata Info
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = song?.title ?: "No Track Selected",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${song?.artist ?: "Unknown Artist"} • ${song?.album ?: "Unknown Album"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            if (song != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Played ${song.playCount} times • Folder: ${song.folderName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        // Seek Bar & Timers
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (isDragging) sliderPosition else currentPos.toFloat(),
                onValueChange = { newValue ->
                    sliderPosition = newValue
                    isDragging = true
                },
                onValueChangeFinished = {
                    isDragging = false
                    viewModel.onSeek(sliderPosition.toLong())
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(if (isDragging) sliderPosition.toLong() else currentPos),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    text = formatTime(duration),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        // Controls Row with Smart Shuffle & Cycle Repeat Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Smart Shuffle Toggle
            IconButton(onClick = { viewModel.toggleShuffle() }) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Smart Shuffle",
                    tint = if (isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Previous
            IconButton(
                onClick = { viewModel.playPrevious(context) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Track",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Play / Pause Toggle
            FilledIconButton(
                onClick = { viewModel.togglePlayPause() },
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Next
            IconButton(
                onClick = { viewModel.playNext(context) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Track",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Cycle Repeat Mode Toggle (OFF -> ALL -> ONE -> OFF)
            IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                val (repeatIcon, repeatTint) = when (repeatMode) {
                    RepeatMode.OFF -> Pair(Icons.Default.Repeat, MaterialTheme.colorScheme.onSurfaceVariant)
                    RepeatMode.ALL -> Pair(Icons.Default.Repeat, MaterialTheme.colorScheme.primary)
                    RepeatMode.ONE -> Pair(Icons.Default.RepeatOne, MaterialTheme.colorScheme.primary)
                }
                Icon(
                    imageVector = repeatIcon,
                    contentDescription = "Repeat Mode ($repeatMode)",
                    tint = repeatTint
                )
            }
        }
    }
}

@Composable
fun QueueTab(viewModel: AudioViewModel) {
    val context = LocalContext.current
    val currentQueue by viewModel.currentQueue.collectAsState()
    val currentIndex by viewModel.currentTrackIndex.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Queue (${currentQueue.size} songs)",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (currentQueue.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearQueue() }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", color = Color(0xFFFF6B6B))
                }
            }
        }

        if (currentQueue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Queue is empty. Select a folder or playlist to load tracks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(currentQueue) { index, song ->
                    val isCurrent = index == currentIndex
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.playSongAtIndex(context, index) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${song.artist} • Play count: ${song.playCount}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(onClick = { viewModel.removeTrackAt(index) }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderTab(viewModel: AudioViewModel, onOpenFolderClick: () -> Unit) {
    val context = LocalContext.current
    val folderList by viewModel.folderList.collectAsState()
    val allPlaylists by viewModel.allPlaylists.collectAsState()

    var selectedSongForPlaylist by remember { mutableStateOf<SongEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = onOpenFolderClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = Color.Black
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Music Folder", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (folderList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No folders scanned yet.\nTap 'Select Music Folder' to pick a music directory.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(folderList) { _, folder ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = folder.name,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            text = "${folder.trackCount} tracks",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Button(
                                    onClick = { viewModel.playFolder(context, folder) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Play Folder", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            folder.tracks.take(5).forEach { song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { viewModel.playSong(context, song) },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Audiotrack,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = song.title,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { selectedSongForPlaylist = song },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                            contentDescription = "Add to playlist",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dialog for adding single song to playlist
        if (selectedSongForPlaylist != null) {
            AlertDialog(
                onDismissRequest = { selectedSongForPlaylist = null },
                title = { Text("Add to Playlist") },
                text = {
                    if (allPlaylists.isEmpty()) {
                        Text("No playlists available. Please create a playlist first in the Playlists tab.")
                    } else {
                        LazyColumn {
                            itemsIndexed(allPlaylists) { _, playlist ->
                                Text(
                                    text = playlist.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedSongForPlaylist?.let { song ->
                                                viewModel.addSongsToPlaylist(playlist.id, listOf(song.id))
                                            }
                                            selectedSongForPlaylist = null
                                        }
                                        .padding(vertical = 12.dp),
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { selectedSongForPlaylist = null }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) - TimeUnit.MINUTES.toSeconds(minutes)
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
