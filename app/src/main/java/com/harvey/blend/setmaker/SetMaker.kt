package com.harvey.blend.setmaker

import com.harvey.blend.analysis.KeyRelation
import com.harvey.blend.model.EnergyArc
import com.harvey.blend.model.Track
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** How well track [from] flows into track [to]. */
data class TransitionScore(
    val score: Double,
    val keyRelation: KeyRelation?,
    val bpmDelta: Double,
    val energyDelta: Int,
) {
    val percent: Int get() = (score * 100).toInt()
    val risky: Boolean get() = score < 0.85

    /** e.g. "8A→9A · harmonic · +1 BPM · 96%". */
    fun describe(from: Track, to: Track): String {
        val parts = mutableListOf<String>()
        val ka = from.key; val kb = to.key
        if (ka != null && kb != null) {
            parts += if (ka == kb) "Same key" else "${ka.camelot}→${kb.camelot} · ${keyRelation?.label}"
        }
        val d = bpmDelta
        if (abs(d) >= 0.5) parts += "%+.0f BPM".format(d)
        if (abs(energyDelta) >= 2) parts += if (energyDelta > 0) "energy jump" else "energy drop"
        parts += "$percent%"
        return parts.joinToString(" · ")
    }
}

object SetMaker {

    fun score(from: Track, to: Track, targetEnergyDelta: Double = 0.0): TransitionScore {
        val rel = KeyRelation.between(from.key, to.key)
        val keyScore = rel?.score ?: 0.6
        val ba = from.bpm; val bb = to.bpm
        val bpmDelta = if (ba != null && bb != null) bb - ba else 0.0
        // Compare tempo as a ratio; half/double time also mixes.
        val bpmScore = if (ba != null && bb != null) {
            val r = listOf(bb / ba, bb * 2 / ba, bb / 2 / ba).minBy { abs(ln(it)) }
            val pct = abs(r - 1) * 100
            exp(-(pct / 4.0) * (pct / 4.0))
        } else 0.7
        val ed = (to.energy ?: 5) - (from.energy ?: 5)
        val energyScore = exp(-abs(ed - targetEnergyDelta) / 2.0)
        val s = 0.5 * keyScore + 0.3 * bpmScore + 0.2 * energyScore
        return TransitionScore(s.coerceIn(0.0, 1.0), rel, bpmDelta, ed)
    }

    /**
     * Orders [tracks] into a set that follows [arc], chaining compatible keys and letting the tempo
     * drift gradually. Randomised: each call with a different [seed] tells a different story.
     * Tracks in [locked] keep their current index.
     */
    fun buildOrder(
        tracks: List<Track>,
        arc: EnergyArc,
        locked: Set<String> = emptySet(),
        seed: Long = System.nanoTime(),
    ): List<Track> {
        val n = tracks.size
        if (n <= 2) return tracks
        val rnd = Random(seed)
        val fixed = HashMap<Int, Track>()
        tracks.forEachIndexed { i, t -> if (t.id in locked) fixed[i] = t }
        val pool = tracks.filter { it.id !in locked }.toMutableList()
        val result = ArrayList<Track>(n)

        // Opener: the free track whose energy is closest to the arc's start (with a little randomness).
        for (i in 0 until n) {
            val fixedHere = fixed[i]
            if (fixedHere != null) { result += fixedHere; continue }
            val target = arc.targetAt(i, n)
            val prev = result.lastOrNull()
            val nextFixed = fixed[i + 1]
            val candidates = pool.map { t ->
                val energyFit = exp(-abs((t.energy ?: 5) - target) / 1.5)
                val flowIn = if (prev != null) score(prev, t, target - (prev.energy ?: 5)).score else 0.8
                val flowOut = if (nextFixed != null) score(t, nextFixed).score else 0.8
                val bpmGlide = if (prev?.bpm != null && t.bpm != null) {
                    // Prefer gentle tempo drift in the arc's direction.
                    val d = t.bpm!! - prev.bpm!!
                    val wantUp = target >= (prev.energy ?: 5)
                    if ((d >= -0.5) == wantUp || abs(d) < 1) 1.0 else 0.85
                } else 1.0
                t to (0.45 * flowIn + 0.35 * energyFit + 0.2 * flowOut) * bpmGlide
            }.sortedByDescending { it.second }
            // Sample among the best few, weighted by score, so the story varies between runs.
            val top = candidates.take(3)
            val weights = top.mapIndexed { idx, c -> c.second * c.second * (if (idx == 0) 1.6 else 1.0) }
            var r = rnd.nextDouble() * weights.sum()
            var pick = top.first().first
            for ((idx, w) in weights.withIndex()) { r -= w; if (r <= 0) { pick = top[idx].first; break } }
            result += pick
            pool.remove(pick)
        }
        return result
    }

    /** Ranked suggestions for what to play after [current], given where we are in the set. */
    fun suggestNext(
        current: Track,
        candidates: List<Track>,
        targetEnergy: Double?,
        limit: Int = 5,
    ): List<Pair<Track, TransitionScore>> {
        val want = targetEnergy?.let { it - (current.energy ?: 5) } ?: 0.0
        return candidates.filter { it.id != current.id }
            .map { it to score(current, it, want) }
            .sortedByDescending { it.second.score }
            .take(limit)
    }
}
