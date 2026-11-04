package com.example.bitperfect.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.bitperfect.data.db.SongEntity
import com.example.bitperfect.player.QueueManager
import com.example.bitperfect.player.RepeatMode
import kotlinx.coroutines.flow.StateFlow

class QueueViewModel(application: Application) : AndroidViewModel(application) {

    val currentQueue: StateFlow<List<SongEntity>> = QueueManager.currentQueue
    val currentIndex: StateFlow<Int> = QueueManager.currentIndex
    val currentSong: StateFlow<SongEntity?> = QueueManager.currentSong
    val isShuffleEnabled: StateFlow<Boolean> = QueueManager.isShuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = QueueManager.repeatMode

    fun setQueue(songs: List<SongEntity>, startIndex: Int = 0) {
        QueueManager.setQueue(songs, startIndex)
    }

    fun toggleShuffle(): Boolean {
        return QueueManager.toggleShuffle()
    }

    fun cycleRepeatMode(): RepeatMode {
        return QueueManager.cycleRepeatMode()
    }

    fun next(): SongEntity? {
        return QueueManager.next()
    }

    fun previous(): SongEntity? {
        return QueueManager.previous()
    }

    fun removeAt(index: Int) {
        QueueManager.removeAt(index)
    }

    fun clearQueue() {
        QueueManager.clearQueue()
    }
}