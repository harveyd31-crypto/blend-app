package com.harvey.blend.analysis

/** Prints analysis results for a set of synthetic tracks (handy when tuning the analyzer). */
fun main() {
    val specs = listOf(
        SyntheticTrack.Spec(124.0, tonic = 4, minor = true),            // E minor = 9A
        SyntheticTrack.Spec(120.0, tonic = 0, minor = false, seed = 2), // C major = 8B
        SyntheticTrack.Spec(128.0, tonic = 9, minor = true, seed = 3, leadSilence = 0.05), // A minor = 8A
        SyntheticTrack.Spec(122.5, tonic = 7, minor = false, seed = 4, leadSilence = 1.1), // G major = 9B
        SyntheticTrack.Spec(174.0, tonic = 2, minor = true, seed = 5), // D minor = 7A
        SyntheticTrack.Spec(100.0, tonic = 5, minor = true, seed = 6, leadSilence = 0.5), // F minor = 4A
    )
    for (s in specs) {
        val audio = SyntheticTrack.generate(s)
        val t0 = System.currentTimeMillis()
        val a = TrackAnalyzer().analyze(audio)
        val ms = System.currentTimeMillis() - t0
        val expectedKey = MusicalKey(s.tonic, s.minor)
        val expectedOut = s.leadSilence + s.outroBar * s.barSec
        println(
            "bpm %.2f→%.2f (conf %.2f) | key %s→%s | downbeat %.3f→%.3f | mixOut %.2f→%.2f (conf %.2f) | energy %d | %d ms".format(
                s.bpm, a.bpm, a.bpmConfidence, expectedKey.camelot, a.key?.camelot, s.leadSilence, a.firstDownbeat,
                expectedOut, a.mixOut, a.mixOutConfidence, a.energy, ms,
            )
        )
    }
}
