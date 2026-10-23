package com.harvey.blend.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything the app learns about a track from its audio. Times are in seconds. */
data class TrackAnalysis(
    val bpm: Double,
    val bpmConfidence: Double,
    /** Time of the first downbeat (beat 1 of a bar). The beat grid runs from here at [bpm]. */
    val firstDownbeat: Double,
    val key: MusicalKey?,
    val keyConfidence: Double,
    /** 1–10. */
    val energy: Int,
    /** Where the next track should be brought in when this track starts (a downbeat). */
    val mixIn: Double,
    /** Where this track hands over to the next one (start of its outro, a phrase boundary). */
    val mixOut: Double,
    val mixOutConfidence: Double,
    /** Overview waveform: [WAVE_BINS] values 0–255 each for the lows and the mids/highs. */
    val waveLow: ByteArray,
    val waveHigh: ByteArray,
    val duration: Double,
) {
    val beatSec: Double get() = 60.0 / bpm
    val barSec: Double get() = 4 * beatSec

    /** Bar number (1-based, counted from the first downbeat) at time [t]. */
    fun barAt(t: Double): Int = floor((t - firstDownbeat) / barSec).toInt() + 1

    companion object {
        const val WAVE_BINS = 400
    }
}

/**
 * Analyses mono audio at [TrackAnalyzer.SAMPLE_RATE] Hz.
 *
 * Tempo: onset envelope (spectral flux) → comb-filtered autocorrelation, refined to 0.01 BPM
 * across the whole track so the grid doesn't drift over a 7-minute song.
 * Key: chroma profile correlated with major/minor key profiles.
 * Cues: phrase structure (8-bar blocks) from the first downbeat; the outro is where the
 * mid frequencies (vocals, pads, chords) drop away.
 */
class TrackAnalyzer {

    fun analyze(samples: FloatArray, onProgress: (Float) -> Unit = {}): TrackAnalysis {
        val sr = SAMPLE_RATE
        val duration = samples.size.toDouble() / sr

        // ---- 1. Short-time spectrum → onset envelopes and band energies --------------------------
        val n = 1024
        val hop = HOP
        val fft = Fft(n)
        val bins = n / 2 + 1
        val frames = max(1, (samples.size - n) / hop + 1)
        val mag = FloatArray(bins)
        var prevLog = FloatArray(bins)
        var curLog = FloatArray(bins)
        val flux = FloatArray(frames)
        val lowFlux = FloatArray(frames)
        val kickFlux = FloatArray(frames)
        var prevMag = FloatArray(bins)
        val binHz = sr.toDouble() / n
        val lowEnd = (180 / binHz).toInt()
        val kickEnd = (120 / binHz).toInt()
        for (f in 0 until frames) {
            fft.magnitudes(samples, f * hop, mag)
            var s = 0f
            var sl = 0f
            for (k in 1 until bins) {
                val v = ln(1f + 1000f * mag[k])
                curLog[k] = v
                val d = v - prevLog[k]
                if (d > 0) {
                    s += d
                    if (k <= lowEnd) sl += d
                }
            }
            // Kick drum: linear (not log) magnitude rise in the sub/low band, so a loud kick
            // outweighs an off-beat bass note even though both live in the same frequencies.
            var kf = 0f
            for (k in 1..kickEnd) {
                val d = mag[k] - prevMag[k]
                if (d > 0) kf += d
                prevMag[k] = mag[k]
            }
            flux[f] = s
            lowFlux[f] = sl
            kickFlux[f] = kf
            val t = prevLog; prevLog = curLog; curLog = t
            if (f % 2000 == 0) onProgress(0.6f * f / frames)
        }
        if (frames > 0) { flux[0] = 0f; lowFlux[0] = 0f; kickFlux[0] = 0f }
        val onset = normalizeEnvelope(flux)
        val lowOnset = normalizeEnvelope(lowFlux)
        val kickOnset = normalizeEnvelope(kickFlux)
        val fps = sr.toDouble() / hop

        // ---- 2. Tempo -------------------------------------------------------------------------------
        val (bpm, bpmConf) = estimateTempo(onset, lowOnset, kickOnset, fps)
        onProgress(0.7f)

        // ---- 3. Beat phase and downbeat --------------------------------------------------------------
        val period = fps * 60.0 / bpm
        val combined = FloatArray(frames) { kickOnset[it] * 0.8f + onset[it] * 0.2f }
        val phaseFrames = bestPhase(combined, period)
        // Flux peaks a fixed number of frames after the attack (window lead-in); calibrated on test tones.
        var firstBeat = (phaseFrames * hop + ONSET_LATENCY_SAMPLES) / sr
        val beatSec = 60.0 / bpm
        while (firstBeat - beatSec >= 0) firstBeat -= beatSec
        while (firstBeat < 0) firstBeat += beatSec

        val beatCount = max(1, ((duration - firstBeat) / beatSec).toInt())
        val beatRms = DoubleArray(beatCount) { i ->
            val a = ((firstBeat + i * beatSec) * sr).toInt()
            val b = min(samples.size, ((firstBeat + (i + 1) * beatSec) * sr).toInt())
            rmsDb(samples, a, b)
        }
        // Sound starts: first beat louder than 30 dB under the track's loud level.
        val loud = percentile(beatRms, 0.9)
        val firstSounding = beatRms.indexOfFirst { it > loud - 30 }.coerceAtLeast(0)
        // Section changes happen on bar lines: pick the beat-in-bar offset where loudness jumps most.
        val score = DoubleArray(4)
        for (i in 1 until beatCount) score[i % 4] += abs(beatRms[i] - beatRms[i - 1])
        // Ties (perfectly steady loops) default to the first sounding beat.
        val firstOffset = firstSounding % 4
        var offset = firstOffset
        for (r in 0 until 4) if (score[r] > score[offset] * 1.15) offset = r
        var downIdx = offset
        while (downIdx < firstSounding) downIdx += 4
        while (downIdx - 4 >= firstSounding) downIdx -= 4
        val firstDownbeat = firstBeat + downIdx * beatSec
        onProgress(0.75f)

        // ---- 4. Bar energies, outro detection (mix-out) ---------------------------------------------
        val barSec = 4 * beatSec
        val bars = max(1, ((duration - firstDownbeat) / barSec).toInt())
        val lowCut = OnePole(200.0, sr)
        val highSplit = OnePole(3000.0, sr)
        val low = FloatArray(samples.size)
        val mid = FloatArray(samples.size)
        val high = FloatArray(samples.size)
        for (i in samples.indices) {
            val l = lowCut.process(samples[i])
            val restBelow3k = highSplit.process(samples[i])
            low[i] = l
            mid[i] = restBelow3k - l
            high[i] = samples[i] - restBelow3k
        }
        // "Melodic" band (300 Hz–3.5 kHz, steep filters): chords, vocals and leads, without the bass
        // that would make a breakdown look like an outro.
        val hp1 = Biquad.highPass(300.0, sr); val hp2 = Biquad.highPass(300.0, sr)
        val lp1 = Biquad.lowPass(3500.0, sr); val lp2 = Biquad.lowPass(3500.0, sr)
        val melodic = FloatArray(samples.size) { i -> lp2.process(lp1.process(hp2.process(hp1.process(samples[i])))) }
        val barMid = DoubleArray(bars)
        val barAll = DoubleArray(bars)
        for (b in 0 until bars) {
            val a = ((firstDownbeat + b * barSec) * sr).toInt()
            val e = min(samples.size, ((firstDownbeat + (b + 1) * barSec) * sr).toInt())
            barMid[b] = rmsDb(melodic, a, e)
            barAll[b] = rmsDb(samples, a, e)
        }
        val (mixOutBar, mixOutConf) = findOutro(barMid, barAll)
        val mixIn = firstDownbeat
        val mixOut = firstDownbeat + mixOutBar * barSec
        onProgress(0.85f)

        // ---- 5. Key ------------------------------------------------------------------------------------
        val (key, keyConf) = estimateKey(samples)
        onProgress(0.95f)

        // ---- 6. Waveform overview and energy -----------------------------------------------------------
        val waveLow = ByteArray(TrackAnalysis.WAVE_BINS)
        val waveHigh = ByteArray(TrackAnalysis.WAVE_BINS)
        val lowVals = DoubleArray(TrackAnalysis.WAVE_BINS)
        val highVals = DoubleArray(TrackAnalysis.WAVE_BINS)
        for (w in 0 until TrackAnalysis.WAVE_BINS) {
            val a = (w.toLong() * samples.size / TrackAnalysis.WAVE_BINS).toInt()
            val e = ((w + 1).toLong() * samples.size / TrackAnalysis.WAVE_BINS).toInt()
            lowVals[w] = rms(low, a, e)
            var s = 0.0
            for (i in a until e) { val v = mid[i] + high[i]; s += v * v }
            highVals[w] = if (e > a) sqrt(s / (e - a)) else 0.0
        }
        val peak = max(lowVals.maxOrNull() ?: 0.0, highVals.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        for (w in 0 until TrackAnalysis.WAVE_BINS) {
            waveLow[w] = (min(1.0, lowVals[w] / peak) * 255).roundToInt().toByte()
            waveHigh[w] = (min(1.0, highVals[w] / peak) * 255).roundToInt().toByte()
        }

        val loudness = percentile(barAll, 0.5)
        val highShare = run {
            var h = 0.0; var t = 0.0
            for (i in samples.indices step 4) { h += high[i] * high[i]; t += samples[i] * samples[i] }
            if (t > 0) h / t else 0.0
        }
        val density = onset.average()
        val e = 0.55 * norm(loudness, -26.0, -9.0) + 0.25 * norm(log10(highShare + 1e-6), -2.6, -1.0) +
            0.2 * norm(density, 0.08, 0.35) + 0.15 * norm(bpm, 100.0, 140.0) - 0.075
        val energy = (1 + 9 * e.coerceIn(0.0, 1.0)).roundToInt().coerceIn(1, 10)
        onProgress(1f)

        return TrackAnalysis(
            bpm = bpm, bpmConfidence = bpmConf, firstDownbeat = firstDownbeat,
            key = key, keyConfidence = keyConf, energy = energy,
            mixIn = mixIn, mixOut = mixOut, mixOutConfidence = mixOutConf,
            waveLow = waveLow, waveHigh = waveHigh, duration = duration,
        )
    }

    // ------------------------------------------------------------------------------------------------

    private fun normalizeEnvelope(x: FloatArray): FloatArray {
        // Subtract a moving average (~0.4 s) so only the peaks above the local level remain.
        val w = 35
        val out = FloatArray(x.size)
        var sum = 0.0
        val q = ArrayDeque<Float>()
        for (i in x.indices) {
            q.addLast(x[i]); sum += x[i]
            if (q.size > w) sum -= q.removeFirst()
            val centerIdx = i - w / 2
            if (centerIdx >= 0) out[centerIdx] = max(0f, x[centerIdx] - (sum / q.size).toFloat())
        }
        val mx = out.maxOrNull() ?: 0f
        if (mx > 0) for (i in out.indices) out[i] /= mx
        return out
    }

    /** Value of [x] at fractional index [p] (linear interpolation). */
    private fun at(x: FloatArray, p: Double): Double {
        val i = floor(p).toInt()
        if (i < 0 || i + 1 >= x.size) return 0.0
        val f = p - i
        return x[i] * (1 - f) + x[i + 1] * f
    }

    private fun estimateTempo(onset: FloatArray, lowOnset: FloatArray, kickOnset: FloatArray, fps: Double): Pair<Double, Double> {
        val env = FloatArray(onset.size) { onset[it] * 0.5f + lowOnset[it] * 0.5f }
        // Autocorrelation over the lags that cover 60–200 BPM (up to 4 periods for the comb).
        val maxLag = (fps * 60 / MIN_BPM * 4).toInt() + 2
        val ac = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var s = 0.0
            var i = 0
            while (i + lag < env.size) { s += env[i] * env[i + lag]; i++ }
            ac[lag] = s
        }
        fun acAt(l: Double): Double {
            val i = floor(l).toInt()
            if (i < 1 || i + 1 > maxLag) return 0.0
            val f = l - i
            return ac[i] * (1 - f) + ac[i + 1] * f
        }
        // Coarse: comb over the autocorrelation with a mild preference for 100–135 BPM.
        var best = 120.0
        var bestScore = -1.0
        val scores = ArrayList<Pair<Double, Double>>()
        var b = MIN_BPM
        while (b <= MAX_BPM) {
            val lag = fps * 60 / b
            var s = 0.0
            for (k in 1..4) s += acAt(lag * k) / k
            val pref = exp(-0.5 * (log2(b / 120.0) / 0.55).let { it * it })
            val sc = s * (0.6 + 0.4 * pref)
            scores += b to sc
            if (sc > bestScore) { bestScore = sc; best = b }
            b += 0.25
        }
        // Fine: maximise beat strength along the whole track (sharp peak → 0.01 BPM precision).
        fun strength(bpm: Double): Double {
            val period = fps * 60 / bpm
            var bestPh = 0.0
            var ph = 0.0
            while (ph < period) {
                var s = 0.0
                var p = ph
                while (p < env.size - 1) { s += at(env, p); p += period }
                if (s > bestPh) bestPh = s
                ph += 1.0
            }
            return bestPh / (env.size / period)
        }
        var fine = best
        var fineScore = strength(best)
        var step = 0.2
        while (step >= 0.01) {
            var improved = true
            while (improved) {
                improved = false
                for (cand in doubleArrayOf(fine - step, fine + step)) {
                    val s = strength(cand)
                    if (s > fineScore) { fineScore = s; fine = cand; improved = true }
                }
            }
            step /= 2
        }
        // Half-tempo trap (e.g. drum & bass read as 87): if kicks land as strongly half-way between
        // the detected beats as on them, the real tempo is double.
        if (fine * 2 <= MAX_BPM) {
            val period = fps * 60 / fine
            val ph = bestPhase(kickOnset, period)
            var on = 0.0; var mid = 0.0
            var p = ph
            while (p + period / 2 < kickOnset.size - 1) { on += at(kickOnset, p); mid += at(kickOnset, p + period / 2); p += period }
            if (on > 0 && mid / on > 0.7) fine *= 2
        }
        // Electronic music is almost always produced at a whole (or half) BPM: snap when the grid agrees.
        val whole = (fine * 2).roundToInt() / 2.0
        if (abs(whole - fine) < 0.06 && strength(whole) >= fineScore * 0.985) fine = whole
        // Confidence: how far the winner stands above the median candidate.
        val sorted = scores.map { it.second }.sorted()
        val median = sorted[sorted.size / 2]
        val conf = ((bestScore - median) / (bestScore + 1e-9)).coerceIn(0.0, 1.0)
        return (Math.round(fine * 100) / 100.0) to conf
    }

    private fun bestPhase(env: FloatArray, period: Double): Double {
        var best = 0.0
        var bestScore = -1.0
        var ph = 0.0
        while (ph < period) {
            var s = 0.0
            var p = ph
            while (p < env.size - 1) { s += at(env, p); p += period }
            if (s > bestScore) { bestScore = s; best = ph }
            ph += 0.25
        }
        return best
    }

    /**
     * Returns (bar index of the outro start, confidence). Phrases are 8 bars long.
     * The outro is a phrase line in the second half where the melodic content drops and the track
     * never comes back up afterwards — a breakdown followed by another drop is not an outro.
     */
    private fun findOutro(barMid: DoubleArray, barAll: DoubleArray): Pair<Int, Double> {
        val bars = barMid.size
        val minRemaining = 16
        val fallback = max(0, ((bars - 32) / 8) * 8).let { if (bars - it < minRemaining) max(0, it - 8) else it }
        var bestBar = fallback
        var bestScore = 0.0
        var c = 8
        while (c <= bars - minRemaining) {
            if (c >= bars * 0.5) {
                val before = mean(barMid, c - 8, c)
                val after = mean(barMid, c, min(bars, c + 8))
                val beforeAll = mean(barAll, c - 8, c)
                val afterAll = mean(barAll, c, min(bars, c + 8))
                // Does the track build back up later? Then this is a breakdown, not the outro.
                var laterAll = -200.0
                var laterMid = -200.0
                var k = c + 8
                while (k + 8 <= bars) {
                    laterAll = max(laterAll, mean(barAll, k, k + 8))
                    laterMid = max(laterMid, mean(barMid, k, k + 8))
                    k += 8
                }
                val comesBack = laterAll > afterAll + 2.5 || laterMid > after + 2.5
                // Melody relative to the whole mix: drops when the chords/vocals stop while the beat
                // keeps going (a mixable outro), but not when everything simply fades to silence.
                val share = (before - beforeAll) - (after - afterAll)
                val s = share + 0.5 * (beforeAll - afterAll).coerceIn(0.0, 3.0)
                // The beat coming back after a breakdown (louder than before) is a drop, not an outro.
                val isDrop = afterAll > beforeAll + 1.5
                if (!comesBack && !isDrop && s > bestScore) { bestScore = s; bestBar = c }
            }
            c += 8
        }
        if (bestScore < 1.5) return fallback to 0.3
        return bestBar to (0.4 + bestScore / 8).coerceAtMost(0.98)
    }

    private fun estimateKey(samples: FloatArray): Pair<MusicalKey?, Double> {
        val sr = SAMPLE_RATE
        val n = 8192
        val fft = Fft(n)
        val mag = FloatArray(n / 2 + 1)
        val chroma = DoubleArray(12)
        val binHz = sr.toDouble() / n
        val kMin = (60 / binHz).toInt()
        val kMax = (2000 / binHz).toInt()
        val pcOf = IntArray(kMax + 1)
        val wOf = DoubleArray(kMax + 1)
        for (k in kMin..kMax) {
            val pitch = 69 + 12 * log2(k * binHz / 440.0)
            val r = pitch.roundToInt()
            pcOf[k] = r.mod(12)
            wOf[k] = max(0.0, 1 - 2 * abs(pitch - r))
        }
        // Bass notes (60–250 Hz) carry the tonal centre in dance music, so they count double.
        val kBass = (250 / binHz).toInt()
        var off = 0
        val frame = DoubleArray(12)
        while (off + n <= samples.size) {
            fft.magnitudes(samples, off, mag)
            frame.fill(0.0)
            var total = 0.0
            for (k in kMin..kMax) {
                val v = mag[k] * wOf[k] * (if (k <= kBass) 2.0 else 1.0)
                frame[pcOf[k]] += v
                total += v
            }
            if (total > 1e-6) for (p in 0 until 12) chroma[p] += frame[p] / total
            off += n / 2
        }
        if (chroma.sum() <= 0) return null to 0.0
        var bestKey: MusicalKey? = null
        var best = -2.0
        var second = -2.0
        for (minor in listOf(false, true)) {
            val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
            for (tonic in 0 until 12) {
                val rotated = DoubleArray(12) { profile[(it - tonic).mod(12)] }
                // Most dance music is in minor keys: a small prior breaks relative major/minor ties.
                val r = pearson(chroma, rotated) + if (minor) MINOR_PRIOR else 0.0
                if (r > best) { second = best; best = r; bestKey = MusicalKey(tonic, minor) }
                else if (r > second) second = r
            }
        }
        val conf = (0.5 + (best - second) * 4).coerceIn(0.0, 0.99)
        return bestKey to conf
    }

    /** RBJ biquad filter. */
    private class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(xf: Float): Float {
            val x = xf.toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y.toFloat()
        }
        companion object {
            private fun make(f: Double, sr: Int, high: Boolean): Biquad {
                val w = 2 * Math.PI * f / sr
                val alpha = kotlin.math.sin(w) / (2 * 0.7071)
                val c = kotlin.math.cos(w)
                val a0 = 1 + alpha
                return if (high) Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
                else Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
            fun highPass(f: Double, sr: Int) = make(f, sr, true)
            fun lowPass(f: Double, sr: Int) = make(f, sr, false)
        }
    }

    private class OnePole(cutoff: Double, sr: Int) {
        private val a = exp(-2 * Math.PI * cutoff / sr).toFloat()
        private var y1 = 0f
        private var y2 = 0f
        /** Two cascaded one-pole low-pass stages (12 dB/oct). */
        fun process(x: Float): Float {
            y1 = (1 - a) * x + a * y1
            y2 = (1 - a) * y1 + a * y2
            return y2
        }
    }

    companion object {
        const val SAMPLE_RATE = 11025
        const val HOP = 128
        const val MIN_BPM = 70.0
        const val MAX_BPM = 180.0
        /** Calibrated: flux peaks about this many samples (at 11025 Hz) after an attack's frame start. */
        const val ONSET_LATENCY_SAMPLES = 512.0

        private const val MINOR_PRIOR = 0.03

        // Temperley-style profiles adapted to dance music: the minor profile follows the natural minor
        // (flat 7th, common in house/techno) instead of the classical harmonic minor.
        private val MAJOR_PROFILE = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
        private val MINOR_PROFILE = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 3.5, 2.0)

        fun rms(x: FloatArray, a: Int, b: Int): Double {
            if (b <= a) return 0.0
            var s = 0.0
            for (i in max(0, a) until min(x.size, b)) s += x[i] * x[i]
            return sqrt(s / (b - a))
        }

        fun rmsDb(x: FloatArray, a: Int, b: Int): Double = 20 * log10(rms(x, a, b) + 1e-6)

        fun mean(x: DoubleArray, a: Int, b: Int): Double {
            val lo = max(0, a); val hi = min(x.size, b)
            if (hi <= lo) return 0.0
            var s = 0.0
            for (i in lo until hi) s += x[i]
            return s / (hi - lo)
        }

        fun percentile(x: DoubleArray, p: Double): Double {
            if (x.isEmpty()) return -120.0
            val s = x.sorted()
            return s[((s.size - 1) * p).roundToInt()]
        }

        fun norm(v: Double, lo: Double, hi: Double) = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)

        fun pearson(a: DoubleArray, b: DoubleArray): Double {
            val ma = a.average(); val mb = b.average()
            var num = 0.0; var da = 0.0; var db = 0.0
            for (i in a.indices) {
                num += (a[i] - ma) * (b[i] - mb)
                da += (a[i] - ma) * (a[i] - ma)
                db += (b[i] - mb) * (b[i] - mb)
            }
            return num / (sqrt(da * db) + 1e-12)
        }
    }
}
