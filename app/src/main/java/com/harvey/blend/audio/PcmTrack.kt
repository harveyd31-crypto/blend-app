package com.harvey.blend.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * Decoded audio ready for the mixer: 16-bit stereo PCM in a cache file, memory-mapped so a
 * 10-minute track costs no Java heap and any position can be read instantly (sample-accurate cues).
 */
class PcmTrack(val file: File) {
    val sampleRate: Int
    val frames: Long
    private val data: ShortBuffer

    init {
        RandomAccessFile(file, "r").use { raf ->
            sampleRate = raf.readInt()
            val ch = raf.channel
            val bytes = ch.size() - HEADER
            frames = bytes / 4
            data = ch.map(FileChannel.MapMode.READ_ONLY, HEADER.toLong(), bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
    }

    val durationSec: Double get() = frames.toDouble() / sampleRate

    /** Left (ch 0) or right (ch 1) sample at [frame] as -1..1; silence outside the track. */
    fun sample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= frames) return 0f
        return data.get((frame * 2 + ch).toInt()) / 32768f
    }

    companion object { const val HEADER = 8 }
}
