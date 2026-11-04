package com.example.bitperfect.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitperfect.data.db.SongEntity

@Composable
fun AddSongsToPlaylistDialog(
    allSongs: List<SongEntity>,
    existingSongIds: Set<Long>,
    onDismiss: () -> Unit,
    onSaveSelectedSongs: (List<Long>) -> Unit
) {
    val selectedSongIds = remember { mutableStateSetOf<Long>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Tambah Lagu ke Playlist",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (allSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Belum ada lagu di Perpustakaan.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(allSongs) { _, song ->
                        val isAlreadyInPlaylist = existingSongIds.contains(song.id)
                        val isSelected = selectedSongIds.contains(song.id) || isAlreadyInPlaylist

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAlreadyInPlaylist) {
                                    if (isSelected) {
                                        selectedSongIds.remove(song.id)
                                    } else {
                                        selectedSongIds.add(song.id)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    color = if (isAlreadyInPlaylist) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.artist,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Checkbox(
                                checked = isSelected,
                                enabled = !isAlreadyInPlaylist,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedSongIds.add(song.id)
                                    } else {
                                        selectedSongIds.remove(song.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveSelectedSongs(selectedSongIds.toList())
                    onDismiss()
                },
                enabled = selectedSongIds.isNotEmpty()
            ) {
                Text("Simpan (${selectedSongIds.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
