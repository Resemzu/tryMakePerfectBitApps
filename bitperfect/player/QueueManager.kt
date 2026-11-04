package com.example.bitperfect.player

import com.example.bitperfect.data.db.SongEntity
import com.example.bitperfect.util.SmartShuffleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object QueueManager {

    // Original, unmutated queue
    private var originalQueue: List<SongEntity> = emptyList()

    // Shuffled Index Mapping: maps mapped position 0..N-1 to index in originalQueue
    private var shuffledIndices: List<Int> = emptyList()
    private var currentShuffledIndex = -1

    private val _currentQueue = MutableStateFlow<List<SongEntity>>(emptyList())
    val currentQueue: StateFlow<List<SongEntity>> = _currentQueue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<SongEntity?>(null)
    val currentSong: StateFlow<SongEntity?> = _currentSong.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    fun setQueue(songs: List<SongEntity>, startIndex: Int = 0) {
        if (songs.isEmpty()) {
            originalQueue = emptyList()
            shuffledIndices = emptyList()
            currentShuffledIndex = -1
            _currentQueue.value = emptyList()
            _currentIndex.value = -1
            _currentSong.value = null
            return
        }

        val clampedIndex = startIndex.coerceIn(0, songs.size - 1)
        originalQueue = songs

        if (_isShuffleEnabled.value) {
            buildShuffledMapping(startOriginalIndex = clampedIndex)
        } else {
            shuffledIndices = (songs.indices).toList()
            currentShuffledIndex = clampedIndex
            updateQueueState()
        }
    }

    fun toggleShuffle(): Boolean {
        val newShuffleState = !_isShuffleEnabled.value
        _isShuffleEnabled.value = newShuffleState

        if (originalQueue.isEmpty()) return newShuffleState

        val activeSong = _currentSong.value
        val activeOriginalIndex = if (activeSong != null) {
            originalQueue.indexOfFirst { it.id == activeSong.id }.coerceAtLeast(0)
        } else 0

        if (newShuffleState) {
            buildShuffledMapping(startOriginalIndex = activeOriginalIndex)
        } else {
            shuffledIndices = (originalQueue.indices).toList()
            currentShuffledIndex = activeOriginalIndex
            updateQueueState()
        }
        return newShuffleState
    }

    fun cycleRepeatMode(): RepeatMode {
        val nextMode = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _repeatMode.value = nextMode
        return nextMode
    }

    fun next(): SongEntity? {
        if (originalQueue.isEmpty() || shuffledIndices.isEmpty()) return null

        return when (_repeatMode.value) {
            RepeatMode.ONE -> {
                val song = _currentSong.value
                song
            }
            RepeatMode.ALL -> {
                currentShuffledIndex = (currentShuffledIndex + 1) % shuffledIndices.size
                updateQueueState()
                _currentSong.value
            }
            RepeatMode.OFF -> {
                if (currentShuffledIndex + 1 < shuffledIndices.size) {
                    currentShuffledIndex++
                    updateQueueState()
                    _currentSong.value
                } else {
                    null // End of queue
                }
            }
        }
    }

    fun previous(): SongEntity? {
        if (originalQueue.isEmpty() || shuffledIndices.isEmpty()) return null

        return when (_repeatMode.value) {
            RepeatMode.ONE -> {
                _currentSong.value
            }
            RepeatMode.ALL -> {
                currentShuffledIndex = if (currentShuffledIndex - 1 >= 0) {
                    currentShuffledIndex - 1
                } else {
                    shuffledIndices.size - 1
                }
                updateQueueState()
                _currentSong.value
            }
            RepeatMode.OFF -> {
                currentShuffledIndex = (currentShuffledIndex - 1).coerceAtLeast(0)
                updateQueueState()
                _currentSong.value
            }
        }
    }

    fun removeAt(mappedIndex: Int) {
        if (mappedIndex < 0 || mappedIndex >= shuffledIndices.size) return

        val originalIdx = shuffledIndices[mappedIndex]
        val mutableOriginal = originalQueue.toMutableList()
        if (originalIdx >= 0 && originalIdx < mutableOriginal.size) {
            mutableOriginal.removeAt(originalIdx)
        }
        originalQueue = mutableOriginal

        if (originalQueue.isEmpty()) {
            clearQueue()
            return
        }

        val activeSong = _currentSong.value
        val activeOriginalIdx = if (activeSong != null) {
            originalQueue.indexOfFirst { it.id == activeSong.id }.coerceAtLeast(0)
        } else 0

        if (_isShuffleEnabled.value) {
            buildShuffledMapping(startOriginalIndex = activeOriginalIdx)
        } else {
            shuffledIndices = (originalQueue.indices).toList()
            currentShuffledIndex = activeOriginalIdx
            updateQueueState()
        }
    }

    fun clearQueue() {
        originalQueue = emptyList()
        shuffledIndices = emptyList()
        currentShuffledIndex = -1
        _currentQueue.value = emptyList()
        _currentIndex.value = -1
        _currentSong.value = null
    }

    private fun buildShuffledMapping(startOriginalIndex: Int) {
        val otherIndices = (originalQueue.indices).filter { it != startOriginalIndex }
        val remainingSongs = otherIndices.map { originalQueue[it] }
        val smartShuffledSongs = SmartShuffleHelper.shuffleSongs(remainingSongs)

        val newShuffledIndices = ArrayList<Int>(originalQueue.size)
        newShuffledIndices.add(startOriginalIndex)

        for (song in smartShuffledSongs) {
            val idxInOriginal = originalQueue.indexOfFirst { it.id == song.id }
            if (idxInOriginal != -1 && idxInOriginal != startOriginalIndex) {
                newShuffledIndices.add(idxInOriginal)
            }
        }

        shuffledIndices = newShuffledIndices
        currentShuffledIndex = 0
        updateQueueState()
    }

    private fun updateQueueState() {
        if (shuffledIndices.isEmpty() || currentShuffledIndex !in shuffledIndices.indices) {
            _currentQueue.value = emptyList()
            _currentIndex.value = -1
            _currentSong.value = null
            return
        }

        val displayQueue = shuffledIndices.mapNotNull { idx ->
            if (idx in originalQueue.indices) originalQueue[idx] else null
        }
        _currentQueue.value = displayQueue
        _currentIndex.value = currentShuffledIndex

        val activeOriginalIdx = shuffledIndices[currentShuffledIndex]
        if (activeOriginalIdx in originalQueue.indices) {
            _currentSong.value = originalQueue[activeOriginalIdx]
        }
    }
}