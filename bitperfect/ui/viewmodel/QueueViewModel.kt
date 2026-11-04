package com.example.bitperfect.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.bitperfect.data.db.SongEntity
import com.example.bitperfect.player.QueueManager
import com.example.bitperfect.player.RepeatMode
import kotlinx.coroutines.flow.StateFlow

class QueueViewModel(
    application: Application,
    val queueManager: QueueManager = QueueManager()
) : AndroidViewModel(application) {

    val currentQueue: StateFlow<List<SongEntity>> = queueManager.currentQueue
    val currentIndex: StateFlow<Int> = queueManager.currentIndex
    val currentSong: StateFlow<SongEntity?> = queueManager.currentSong
    val isShuffleEnabled: StateFlow<Boolean> = queueManager.isShuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = queueManager.repeatMode

    fun setQueue(songs: List<SongEntity>, startIndex: Int = 0) {
        queueManager.setQueue(songs, startIndex)
    }

    fun toggleShuffle(): Boolean {
        return queueManager.toggleShuffle()
    }

    fun cycleRepeatMode(): RepeatMode {
        return queueManager.cycleRepeatMode()
    }

    fun next(): SongEntity? {
        return queueManager.next()
    }

    fun previous(): SongEntity? {
        return queueManager.previous()
    }

    fun removeAt(index: Int) {
        queueManager.removeAt(index)
    }

    fun clearQueue() {
        queueManager.clearQueue()
    }
}
