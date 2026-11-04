package com.example.bitperfect.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitperfect.data.db.AppDatabase
import com.example.bitperfect.data.db.PlaylistEntity
import com.example.bitperfect.data.db.PlaylistSongCrossRef
import com.example.bitperfect.data.db.PlaylistWithSongs
import com.example.bitperfect.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val dao = database.playlistDao()

    val allPlaylists: StateFlow<List<PlaylistEntity>> = dao.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSongs: StateFlow<List<SongEntity>> = dao.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertPlaylist(PlaylistEntity(name = name.trim()))
        }
    }

    fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs?> {
        return dao.getPlaylistWithSongs(playlistId)
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
}
