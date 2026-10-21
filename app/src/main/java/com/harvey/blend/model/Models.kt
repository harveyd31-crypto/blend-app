package com.harvey.blend.model

import com.harvey.blend.analysis.MusicalKey
import com.harvey.blend.analysis.TrackAnalysis

/**
 * A track in the library. Values the user edits ([userBpm], [userKey], …) always win over
 * the automatic analysis and are kept when the track is re-analysed.
 */
data class Track(
    val id: String,
    /** content:// URI on the phone. */
    val uri: String,
    /** Path relative to the music folder the user picked, e.g. "House/Copper Sky.aiff". */
    val relativePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val genre: String = "",
    val tagKey: MusicalKey? = null,
    val tagBpm: Double? = null,
    val analysis: TrackAnalysis? = null,
    val analysisError: String? = null,
    val userBpm: Double? = null,
    val userKey: MusicalKey? = null,
    val userEnergy: Int? = null,
    val userGenre: String? = null,
    val userMixIn: Double? = null,
    val userMixOut: Double? = null,
    val comment: String = "",
    val tags: List<String> = emptyList(),
    val addedAt: Long = 0,
) {
    val bpm: Double? get() = userBpm ?: analysis?.bpm ?: tagBpm
    val key: MusicalKey? get() = userKey ?: analysis?.key ?: tagKey
    val energy: Int? get() = userEnergy ?: analysis?.energy
    val displayGenre: String get() = userGenre ?: genre
    val duration: Double get() = analysis?.duration ?: 0.0

    /** Mix-in point, moved onto the beat grid of the (possibly corrected) BPM. */
    val mixIn: Double get() = userMixIn ?: analysis?.mixIn ?: 0.0
    val mixOut: Double get() = userMixOut ?: analysis?.mixOut ?: (duration - 30).coerceAtLeast(0.0)
    val mixOutNeedsCheck: Boolean get() = userMixOut == null && (analysis?.mixOutConfidence ?: 0.0) < 0.6

    /** Seconds per beat for the current BPM (falls back to 120). */
    val beatSec: Double get() = 60.0 / (bpm ?: 120.0)

    /** First downbeat of the beat grid, which stays the anchor when the user halves or doubles BPM. */
    val gridStart: Double get() = analysis?.firstDownbeat ?: 0.0

    val isAnalyzed: Boolean get() = analysis != null
}

enum class PlaylistKind { PLAYLIST, SMART_SET }

data class Playlist(
    val id: String,
    val name: String,
    val kind: PlaylistKind = PlaylistKind.PLAYLIST,
    val trackIds: List<String> = emptyList(),
    /** Track ids the set maker must keep where they are. */
    val locked: Set<String> = emptySet(),
    val arc: EnergyArc = EnergyArc.JOURNEY,
    val createdAt: Long = 0,
)

/** Shape of energy across a set. Values are targets 1–10 sampled across the set's length. */
enum class EnergyArc(val label: String, val points: List<Double>) {
    JOURNEY("Story arc", listOf(3.0, 4.5, 6.0, 7.5, 9.0, 8.0, 6.0)),
    WARM_UP("Warm-up", listOf(2.0, 3.0, 4.0, 5.0, 6.0)),
    PEAK_TIME("Peak time", listOf(7.0, 8.0, 9.0, 9.5, 9.0)),
    COOL_DOWN("Cool-down", listOf(7.0, 6.0, 5.0, 4.0, 3.0)),
    STEADY("Steady", listOf(6.0, 6.0, 6.0));

    /** Target energy at position [i] of [n] tracks. */
    fun targetAt(i: Int, n: Int): Double {
        if (n <= 1) return points.first()
        val x = i.toDouble() / (n - 1) * (points.size - 1)
        val a = x.toInt().coerceAtMost(points.size - 2)
        val f = x - a
        return points[a] * (1 - f) + points[a + 1] * f
    }
}

enum class TransitionStyle(val label: String) { BASS_SWAP("Bass swap"), FILTER_FADE("Filter fade"), ECHO_OUT("Echo out") }

data class Settings(
    val musicFolderUri: String? = null,
    val musicFolderName: String? = null,
    /** Where the same music folder lives on the computer, e.g. "D:\\Music" or "/Users/david/Music". */
    val computerMusicPath: String = "",
    val transitionBars: Int = 8,
    val transitionStyle: TransitionStyle = TransitionStyle.BASS_SWAP,
    val autoMix: Boolean = true,
)
