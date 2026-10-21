package com.harvey.blend.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AnalyzerTest {

    private fun check(spec: SyntheticTrack.Spec) {
        val a = TrackAnalyzer().analyze(SyntheticTrack.generate(spec))
        assertEquals("BPM", spec.bpm, a.bpm, 0.05)
        assertEquals("Key", MusicalKey(spec.tonic, spec.minor).camelot, a.key?.camelot)
        assertEquals("First downbeat", spec.leadSilence, a.firstDownbeat, 0.03)
        val expectedOut = spec.leadSilence + spec.outroBar * spec.barSec
        assertEquals("Mix-out cue", expectedOut, a.mixOut, 0.1)
        assertTrue(a.energy in 1..10)
    }

    @Test fun house124EMinor() = check(SyntheticTrack.Spec(124.0, tonic = 4, minor = true))
    @Test fun house120CMajor() = check(SyntheticTrack.Spec(120.0, tonic = 0, minor = false, seed = 2))
    @Test fun fractionalTempoAndLateStart() = check(SyntheticTrack.Spec(122.5, tonic = 7, minor = false, seed = 4, leadSilence = 1.1))
    @Test fun drumAndBassIsNotHalved() = check(SyntheticTrack.Spec(174.0, tonic = 2, minor = true, seed = 5))
}

class MusicalKeyTest {
    @Test fun camelotWheel() {
        assertEquals("8A", MusicalKey(9, true).camelot)   // A minor
        assertEquals("8B", MusicalKey(0, false).camelot)  // C major
        assertEquals("9A", MusicalKey(4, true).camelot)   // E minor
        assertEquals("1A", MusicalKey(8, true).camelot)   // Ab minor
        assertEquals("1B", MusicalKey(11, false).camelot) // B major
        assertEquals("12A", MusicalKey(1, true).camelot)  // C# minor
    }

    @Test fun openKey() {
        assertEquals("1m", MusicalKey(9, true).openKey)  // A minor
        assertEquals("1d", MusicalKey(0, false).openKey) // C major
    }

    @Test fun parsing() {
        assertEquals(MusicalKey(9, true), MusicalKey.parse("Am"))
        assertEquals(MusicalKey(9, true), MusicalKey.parse("8A"))
        assertEquals(MusicalKey(9, true), MusicalKey.parse("1m"))
        assertEquals(MusicalKey(6, true), MusicalKey.parse("F#m"))
        assertEquals(MusicalKey(3, false), MusicalKey.parse("Eb"))
        assertEquals(MusicalKey(4, true), MusicalKey.parse("E minor"))
    }

    @Test fun relations() {
        val am = MusicalKey(9, true)
        assertEquals(KeyRelation.ADJACENT, KeyRelation.between(am, MusicalKey(4, true)))  // 8A→9A
        assertEquals(KeyRelation.RELATIVE, KeyRelation.between(am, MusicalKey(0, false))) // 8A→8B
        assertEquals(KeyRelation.CLASH, KeyRelation.between(am, MusicalKey(3, true)))     // 8A→2A
        for (n in 1..12) {
            val k = MusicalKey.fromCamelot("${n}A")!!
            assertTrue(abs(k.camelotNumber - n) == 0)
        }
    }
}
