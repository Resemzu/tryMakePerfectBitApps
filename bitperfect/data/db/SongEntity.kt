package com.example.bitperfect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val contentUri: String,
    val durationMs: Long = 0L,
    val folderName: String = "Root",
    val playCount: Int = 0,
    val lastPlayedTimestamp: Long = 0L,
    val dateModified: Long = 0L
)
