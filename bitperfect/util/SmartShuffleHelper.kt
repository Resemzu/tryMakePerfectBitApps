package com.example.bitperfect.util

import com.example.bitperfect.data.db.SongEntity
import java.util.Random
import kotlin.math.ln
import kotlin.math.pow

data class ShuffleCandidate<T>(
    val item: T,
    val playCount: Int,
    val lastPlayedTimestamp: Long
)

/**
 * Smart Shuffle Helper implementing Weighted Random Sampling without replacement.
 *
 * Weight Formula: Weight = (playCount + 1) * (1 / ln(daysSincePlayed + 2))
 */
object SmartShuffleHelper {

    /**
     * Calculates the statistical weight for a song.
     */
    fun calculateWeight(
        playCount: Int,
        lastPlayedTimestamp: Long,
        currentTimestamp: Long = System.currentTimeMillis()
    ): Double {
        val diffMs = (currentTimestamp - lastPlayedTimestamp).coerceAtLeast(0L)
        val daysSincePlayed = diffMs.toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
        val recencyFactor = 1.0 / ln(daysSincePlayed + 2.0)
        return (playCount + 1).toDouble() * recencyFactor
    }

    /**
     * Performs Weighted Random Sampling without replacement using Efraimidis-Spirakis algorithm (A-Res).
     */
    fun <T> weightedRandomShuffle(
        candidates: List<ShuffleCandidate<T>>,
        currentTimestamp: Long = System.currentTimeMillis()
    ): List<T> {
        if (candidates.isEmpty()) return emptyList()
        val random = Random()

        return candidates.map { candidate ->
            val weight = calculateWeight(candidate.playCount, candidate.lastPlayedTimestamp, currentTimestamp)
            val u = random.nextDouble().coerceIn(1e-10, 1.0 - 1e-10)
            // Key = u ^ (1 / weight)
            val key = u.pow(1.0 / weight.coerceAtLeast(1e-6))
            Pair(candidate.item, key)
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Convenience method for shuffling SongEntity list.
     */
    fun shuffleSongs(
        songs: List<SongEntity>,
        currentTimestamp: Long = System.currentTimeMillis()
    ): List<SongEntity> {
        val candidates = songs.map { song ->
            ShuffleCandidate(
                item = song,
                playCount = song.playCount,
                lastPlayedTimestamp = song.lastPlayedTimestamp
            )
        }
        return weightedRandomShuffle(candidates, currentTimestamp)
    }
}
