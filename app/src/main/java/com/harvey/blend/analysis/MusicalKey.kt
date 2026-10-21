package com.harvey.blend.analysis

import kotlin.math.abs

/**
 * A musical key. [tonic] is a pitch class (0 = C … 11 = B).
 * Shown to the user in Camelot notation (8A, 9B…), which makes harmonic mixing easy to read.
 */
data class MusicalKey(val tonic: Int, val minor: Boolean) {

    /** Camelot wheel number 1–12. */
    val camelotNumber: Int
        get() {
            // Major keys: C=8B, G=9B … each fifth up is +1. Minor: A minor = 8A.
            val ref = if (minor) 9 else 0 // A minor / C major sit at 8
            val fifths = ((tonic - ref) * 7).mod(12) // semitone distance converted to steps on the circle of fifths
            return (8 - 1 + fifths).mod(12) + 1
        }

    val camelot: String get() = "$camelotNumber${if (minor) "A" else "B"}"

    /** Open Key notation used by Traktor (e.g. "1m", "6d"). */
    val openKey: String get() = "${(camelotNumber + 4).mod(12) + 1}${if (minor) "m" else "d"}"

    val name: String get() = NAMES[tonic] + if (minor) " minor" else " major"

    /** Short name as written by Rekordbox's Tonality field (e.g. "Em", "C"). */
    val shortName: String get() = NAMES[tonic] + if (minor) "m" else ""

    /** Traktor MUSICAL_KEY value: 0–11 major C…B, 12–23 minor C…B. */
    val traktorValue: Int get() = tonic + if (minor) 12 else 0

    companion object {
        val NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

        fun fromCamelot(code: String): MusicalKey? {
            val c = code.trim().uppercase()
            if (c.length < 2) return null
            val minor = when (c.last()) { 'A' -> true; 'B' -> false; else -> return null }
            val n = c.dropLast(1).toIntOrNull() ?: return null
            if (n !in 1..12) return null
            return (0 until 12).map { MusicalKey(it, minor) }.first { it.camelotNumber == n }
        }

        /** Parse common tag spellings: "Am", "A minor", "F#m", "Ebmaj", Camelot "8A", Open Key "1m". */
        fun parse(text: String?): MusicalKey? {
            if (text.isNullOrBlank()) return null
            val t = text.trim()
            fromCamelot(t)?.let { return it }
            Regex("^(\\d{1,2})([mdMD])$").find(t)?.let { m ->
                val n = m.groupValues[1].toInt()
                if (n in 1..12) {
                    val camelot = (n + 6).mod(12) + 1
                    return fromCamelot("$camelot${if (m.groupValues[2].lowercase() == "m") "A" else "B"}")
                }
            }
            val m = Regex("^([A-Ga-g])([#b♯♭]?)\\s*(m|min|minor|maj|major|M)?$").find(t) ?: return null
            val base = when (m.groupValues[1].uppercase()) {
                "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; else -> 11
            }
            val acc = when (m.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
            val q = m.groupValues[3]
            val minor = q == "m" || q.lowercase() == "min" || q.lowercase() == "minor"
            return MusicalKey((base + acc).mod(12), minor)
        }
    }
}

/** How well two keys mix, following the Camelot wheel. */
enum class KeyRelation(val label: String, val score: Double) {
    SAME("same key", 1.0),
    ADJACENT("harmonic", 0.95),
    RELATIVE("mood shift", 0.9),
    DIAGONAL("energy boost", 0.7),
    TWO_STEPS("two steps", 0.5),
    CLASH("key clash", 0.15);

    companion object {
        fun between(a: MusicalKey?, b: MusicalKey?): KeyRelation? {
            if (a == null || b == null) return null
            val na = a.camelotNumber
            val nb = b.camelotNumber
            val d = minOf((na - nb).mod(12), (nb - na).mod(12))
            return when {
                a == b -> SAME
                a.minor == b.minor && d == 1 -> ADJACENT
                na == nb -> RELATIVE
                a.minor != b.minor && d == 1 -> DIAGONAL
                a.minor == b.minor && d == 2 -> TWO_STEPS
                else -> CLASH
            }
        }
    }
}

internal fun closeTo(a: Double, b: Double, eps: Double) = abs(a - b) <= eps
