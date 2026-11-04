package com.example.bitperfect

class NativeAudioEngine {

    external fun registerEosCallback(audioService: Any)
    external fun initAudioEngine(): Boolean
    external fun openFileDescriptor(fd: Int, offset: Long = 0L, length: Long = -1L): Boolean
    external fun loadTrack(filePath: String): Boolean
    external fun play(): Boolean
    external fun pause(): Boolean
    external fun stop(): Boolean
    external fun seekTo(positionMs: Long): Boolean
    external fun getNativePlaybackPositionMs(): Long
    external fun isBitPerfectMode(): Boolean
    external fun reinitializeStream(forceFallback: Boolean): Boolean

    companion object {
        init {
            System.loadLibrary("native-audio")
        }
    }
}
