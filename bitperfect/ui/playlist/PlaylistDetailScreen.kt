package com.example.bitperfect.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitperfect.data.db.SongEntity
import com.example.bitperfect.ui.viewmodel.PlaylistViewModel

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistViewModel: PlaylistViewModel,
    onBackClick: () -> Unit,
    onPlaySongs: (List<SongEntity>, Int) -> Unit
) {
    val playlistWithSongsState by playlistViewModel.getPlaylistWithSongs(playlistId).collectAsState(initial = null)
    val allSongs by playlistViewModel.allSongs.collectAsState()

    var showAddSongsDialog by remember { mutableStateOf(false) }

    val playlist = playlistWithSongsState?.playlist
    val songs = playlistWithSongsState?.songs ?: emptyList()
    val existingSongIds = remember(songs) { songs.map { it.id }.toSet() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Top Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = playlist?.name ?: "Playlist",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Header Info Card with "Play All" and "Tambah Lagu"
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${songs.size} Lagu",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // "Play All" Button
                    Button(
                        onClick = {
                            if (songs.isNotEmpty()) {
                                onPlaySongs(songs, 0)
                            }
                        },
                        enabled = songs.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    // "Tambah Lagu" Button
                    OutlinedButton(
                        onClick = { showAddSongsDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tambah Lagu")
                    }
                }
            }
        }

        // Song List
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Playlist ini belum memiliki lagu.\nTekan 'Tambah Lagu' untuk memasukkan lagu.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(songs) { index, song ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaySongs(songs, index) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${song.artist} • ${song.album}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            // Delete song from playlist button
                            IconButton(
                                onClick = {
                                    playlistViewModel.removeSongFromPlaylist(playlistId, song.id)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Hapus lagu dari playlist",
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

    // Add Songs Dialog
    if (showAddSongsDialog) {
        AddSongsToPlaylistDialog(
            allSongs = allSongs,
            existingSongIds = existingSongIds,
            onDismiss = { showAddSongsDialog = false },
            onSaveSelectedSongs = { selectedIds ->
                playlistViewModel.addSongsToPlaylist(playlistId, selectedIds)
                showAddSongsDialog = false
            }
        )
    }
}
