package com.harvey.blend.analysis

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Builds a house-style test track with a known tempo, key and structure:
 * intro (drums) 16 bars → main 32 → breakdown 16 (no kick) → main 32 → outro (drums) 16.
 */
object SyntheticTrack {
    data class Spec(
        val bpm: Double,
        val tonic: Int,
        val minor: Boolean,
        val leadSilence: Double = 0.3,
        val sampleRate: Int = TrackAnalyzer.SAMPLE_RATE,
        val seed: Int = 1,
    ) {
        val barSec get() = 240.0 / bpm
        val outroBar get() = 96
    }

    fun generate(spec: Spec): FloatArray {
        val sr = spec.sampleRate
        val beat = 60.0 / spec.bpm
        val bars = 112
        val total = spec.leadSilence + bars * 4 * beat + 1.0
        val out = FloatArray((total * sr).toInt())
        val rnd = Random(spec.seed)

        fun addKick(t0: Double) {
            val start = (t0 * sr).toInt()
            var phase = 0.0
            for (i in 0 until (0.25 * sr).toInt()) {
                val t = i.toDouble() / sr
                val f = 50 + 70 * exp(-t * 30)
                phase += 2 * PI * f / sr
                val j = start + i
                if (j < out.size) out[j] += (0.9 * sin(phase) * exp(-t * 9)).toFloat()
            }
        }
        fun addHat(t0: Double) {
            val start = (t0 * sr).toInt()
            var prev = 0.0
            for (i in 0 until (0.04 * sr).toInt()) {
                val t = i.toDouble() / sr
                val nz = rnd.nextDouble(-1.0, 1.0)
                val hp = nz - prev; prev = nz
                val j = start + i
                if (j < out.size) out[j] += (0.12 * hp * exp(-t * 90)).toFloat()
            }
        }
        fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
        fun addTone(t0: Double, dur: Double, midi: Int, amp: Double, harmonics: Int) {
            val start = (t0 * sr).toInt()
            val f = freq(midi)
            val len = (dur * sr).toInt()
            for (i in 0 until len) {
                val t = i.toDouble() / sr
                val env = minOf(1.0, t / 0.01) * minOf(1.0, (dur - t) / 0.02)
                var v = 0.0
                for (h in 1..harmonics) v += sin(2 * PI * f * h * t) / h
                val j = start + i
                if (j < out.size) out[j] += (amp * env * v).toFloat()
            }
        }

        // Chord roots as scale degrees (semitones from tonic) and chord qualities.
        val progression: List<Pair<Int, Boolean>> = if (spec.minor)
            listOf(0 to true, 0 to true, 8 to false, 10 to false) // i i VI VII
        else
            listOf(0 to false, 0 to false, 5 to false, 7 to false) // I I IV V

        for (bar in 0 until bars) {
            val barStart = spec.leadSilence + bar * 4 * beat
            val inBreakdown = bar in 48 until 64
            val withMusic = bar in 16 until 96
            val (rootDeg, chordMinor) = progression[(bar / 2) % 4]
            for (b in 0 until 4) {
                val t = barStart + b * beat
                if (!inBreakdown) addKick(t)
                if (bar < 112) addHat(t + beat / 2)
                if (withMusic && !inBreakdown) {
                    // Off-beat bass on the chord root.
                    addTone(t + beat / 2, beat * 0.45, 36 + ((spec.tonic + rootDeg) % 12), 0.35, 3)
                }
            }
            if (withMusic) {
                val root = 60 + (spec.tonic + rootDeg) % 12
                val third = root + if (chordMinor) 3 else 4
                val fifth = root + 7
                for (m in listOf(root, third, fifth)) addTone(barStart, 4 * beat, m, if (inBreakdown) 0.12 else 0.08, 4)
            }
        }
        // Normalise to -1 dBFS peak.
        val peak = out.maxOf { kotlin.math.abs(it) }
        if (peak > 0) for (i in out.indices) out[i] = out[i] / peak * 0.89f
        return out
    }
}
