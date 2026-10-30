package com.harvey.blend.audio

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lines up two tracks by ear, the way a DJ does: compares the kick drums of the outgoing track
 * (from where the mix starts) with the incoming track (from its mix-in) over the next bars and
 * returns how late the incoming kicks land, in milliseconds of output time.
 */
object BeatLock {
    private const val BEATS = 16

    /**
     * @return lag in ms: positive = the incoming track is late and must start that much further in.
     *         0 when either track has no clear kick there (nothing to lock to).
     */
    fun lagMs(a: PcmTrack, aStartSec: Double, aRate: Double, b: PcmTrack, bStartSec: Double, bRate: Double, beatSec: Double): Int {
        val lenSec = BEATS * beatSec
        val ea = kickEnvelope(a, aStartSec, aRate, lenSec)
        val eb = kickEnvelope(b, bStartSec, bRate, lenSec)
        if (!hasBeat(ea) || !hasBeat(eb)) return 0
        // Search up to a quarter beat either way (max 120 ms): enough for any grid error, never a full beat.
        val maxLag = min(120, (beatSec * 1000 / 4).toInt())
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            var s = 0.0
            val from = max(0, -lag)
            val to = min(ea.size, eb.size - lag)
            for (i in from until to) s += ea[i] * eb[i + lag]
            // Normalise by overlap length so edge lags aren't penalised.
            s /= (to - from).coerceAtLeast(1)
            if (s > bestScore) { bestScore = s; best = lag }
        }
        return best
    }

    /** Kick-drum onset envelope at 1 kHz for [lenSec] of output time played from [startSec] at [rate]. */
    fun kickEnvelope(p: PcmTrack, startSec: Double, rate: Double, lenSec: Double): FloatArray {
        val sr = p.sampleRate
        val hop = sr / 1000
        val frames = (lenSec * 1000).toInt()
        val out = FloatArray(frames)
        val alpha = exp(-2 * Math.PI * 110.0 / sr).toFloat()
        var y1 = 0f
        var y2 = 0f
        var prev = 0f
        // Warm the filter up for 20 ms before the start.
        var pos = startSec * sr - 0.02 * sr * rate
        val step = rate
        repeat((0.02 * sr).toInt()) {
            val i0 = floor(pos).toLong()
            val x = (p.sample(i0, 0) + p.sample(i0, 1)) * 0.5f
            y1 = (1 - alpha) * x + alpha * y1; y2 = (1 - alpha) * y1 + alpha * y2
            pos += step
        }
        for (f in 0 until frames) {
            var e = 0f
            for (k in 0 until hop) {
                val i0 = floor(pos).toLong()
                val x = (p.sample(i0, 0) + p.sample(i0, 1)) * 0.5f
                y1 = (1 - alpha) * x + alpha * y1; y2 = (1 - alpha) * y1 + alpha * y2
                e += y2 * y2
                pos += step
            }
            val level = sqrt(e / hop)
            out[f] = max(0f, level - prev)
            prev = level
        }
        // Smooth a little (3 ms) so slightly different kick shapes still correlate.
        val sm = FloatArray(frames)
        for (i in 0 until frames) sm[i] = (out.getOrElse(i - 1) { 0f } + out[i] * 2 + out.getOrElse(i + 1) { 0f }) / 4
        return sm
    }

    private fun hasBeat(e: FloatArray): Boolean {
        if (e.isEmpty()) return false
        val mean = e.average()
        val peak = e.maxOrNull() ?: 0f
        return peak > 1e-4 && peak > mean * 3
    }
}
