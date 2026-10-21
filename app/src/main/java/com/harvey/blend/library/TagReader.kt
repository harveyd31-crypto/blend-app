package com.harvey.blend.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.harvey.blend.analysis.MusicalKey
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val key: MusicalKey? = null,
    val bpm: Double? = null,
    val artwork: ByteArray? = null,
)

/**
 * Reads title/artist/genre/key/BPM and artwork. ID3v2 is parsed directly (MP3 and the "ID3 " chunk
 * inside AIFF/WAV, where Rekordbox and Mixed In Key store the key); everything else goes through
 * MediaMetadataRetriever.
 */
object TagReader {

    fun read(context: Context, uri: Uri, fileName: String): Tags {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val id3 = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val bis = BufferedInputStream(ins, 1 shl 16)
                when (ext) {
                    "mp3" -> parseId3(bis)
                    "aif", "aiff", "wav" -> findId3Chunk(bis, ext != "wav")
                    else -> null
                }
            }
        }.getOrNull()

        val mmr = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                Tags(
                    title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    artwork = if (id3?.artwork == null) r.embeddedPicture else null,
                )
            } finally { r.release() }
        }.getOrNull()

        // File name fallback: "Artist - Title.aiff"
        val base = fileName.substringBeforeLast('.')
        val (fnArtist, fnTitle) = if (" - " in base) base.substringBefore(" - ") to base.substringAfter(" - ") else null to base
        fun pick(vararg v: String?) = v.firstOrNull { !it.isNullOrBlank() }?.trim()
        return Tags(
            title = pick(id3?.title, mmr?.title, fnTitle),
            artist = pick(id3?.artist, mmr?.artist, fnArtist),
            album = pick(id3?.album, mmr?.album),
            genre = pick(id3?.genre, mmr?.genre)?.let(::cleanGenre),
            key = id3?.key,
            bpm = id3?.bpm,
            artwork = id3?.artwork ?: mmr?.artwork,
        )
    }

    /** ID3 genres can be "(13)" style numeric references. */
    private fun cleanGenre(g: String): String {
        val m = Regex("^\\((\\d+)\\)(.*)$").find(g) ?: return g
        val rest = m.groupValues[2].trim()
        if (rest.isNotEmpty()) return rest
        return ID3_GENRES.getOrNull(m.groupValues[1].toInt()) ?: g
    }

    private fun findId3Chunk(ins: InputStream, bigEndian: Boolean): Tags? {
        val head = ByteArray(12)
        if (readFully(ins, head) < 12) return null
        while (true) {
            val h = ByteArray(8)
            if (readFully(ins, h) < 8) return null
            val id = String(h, 0, 4, Charsets.US_ASCII)
            val size = if (bigEndian) ((h[4].u() shl 24) or (h[5].u() shl 16) or (h[6].u() shl 8) or h[7].u()).toLong()
            else ((h[7].u() shl 24) or (h[6].u() shl 16) or (h[5].u() shl 8) or h[4].u()).toLong() and 0xffffffffL
            if (id == "ID3 " || id == "id3 ") return parseId3(ins)
            var left = size + (size and 1)
            while (left > 0) { val s = ins.skip(left); if (s <= 0) return null; left -= s }
        }
    }

    private fun parseId3(ins: InputStream): Tags? {
        val h = ByteArray(10)
        if (readFully(ins, h) < 10 || String(h, 0, 3, Charsets.US_ASCII) != "ID3") return null
        val ver = h[3].toInt()
        val flags = h[5].toInt()
        val size = syncsafe(h, 6)
        if (size <= 0 || size > 40_000_000) return null
        val body = ByteArray(size)
        readFully(ins, body)
        var p = 0
        if (flags and 0x40 != 0 && ver >= 3) {
            val ext = if (ver == 4) syncsafe(body, 0) else int32(body, 0) + 4
            p += ext
        }
        var title: String? = null; var artist: String? = null; var album: String? = null
        var genre: String? = null; var key: MusicalKey? = null; var bpm: Double? = null; var art: ByteArray? = null
        val idLen = if (ver == 2) 3 else 4
        val hdrLen = if (ver == 2) 6 else 10
        while (p + hdrLen <= body.size) {
            val id = String(body, p, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val fsize = when (ver) {
                2 -> (body[p + 3].u() shl 16) or (body[p + 4].u() shl 8) or body[p + 5].u()
                4 -> syncsafe(body, p + 4)
                else -> int32(body, p + 4)
            }
            val start = p + hdrLen
            if (fsize <= 0 || start + fsize > body.size) break
            val data = body.copyOfRange(start, start + fsize)
            when (id) {
                "TIT2", "TT2" -> title = text(data)
                "TPE1", "TP1" -> artist = text(data)
                "TALB", "TAL" -> album = text(data)
                "TCON", "TCO" -> genre = text(data)
                "TKEY", "TKE" -> key = MusicalKey.parse(text(data))
                "TBPM", "TBP" -> bpm = text(data)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 40.0..250.0 }
                "APIC" -> if (art == null) art = apic(data)
                "PIC" -> if (art == null && data.size > 5) art = data.copyOfRange(5, data.size).let { skipText(it, data[0].toInt()) }
            }
            p = start + fsize
        }
        return Tags(title, artist, album, genre, key, bpm, art)
    }

    private fun text(d: ByteArray): String? {
        if (d.isEmpty()) return null
        val cs = when (d[0].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        return String(d, 1, d.size - 1, cs).trim('\u0000', ' ').substringBefore('\u0000').ifBlank { null }
    }

    private fun apic(d: ByteArray): ByteArray? {
        if (d.size < 4) return null
        val enc = d[0].toInt()
        var p = 1
        while (p < d.size && d[p].toInt() != 0) p++ // MIME
        p += 2 // terminator + picture type
        if (p >= d.size) return null
        return skipText(d.copyOfRange(p, d.size), enc)
    }

    /** Skips a null-terminated description in [enc] encoding and returns what follows. */
    private fun skipText(d: ByteArray, enc: Int): ByteArray? {
        var p = 0
        if (enc == 1 || enc == 2) {
            while (p + 1 < d.size && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < d.size && d[p].toInt() != 0) p++
            p += 1
        }
        return if (p < d.size) d.copyOfRange(p, d.size) else null
    }

    private fun Byte.u() = toInt() and 0xff
    private fun syncsafe(b: ByteArray, o: Int) = (b[o].u() shl 21) or (b[o + 1].u() shl 14) or (b[o + 2].u() shl 7) or b[o + 3].u()
    private fun int32(b: ByteArray, o: Int) = (b[o].u() shl 24) or (b[o + 1].u() shl 16) or (b[o + 2].u() shl 8) or b[o + 3].u()
    private fun readFully(ins: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = ins.read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }

    /** Saves a small square JPEG thumbnail for the UI. */
    fun saveArtwork(context: Context, trackId: String, bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 300 && opts.outHeight / (sample * 2) >= 300) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 300, 300, true)
            FileOutputStream(artFile(context, trackId)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            true
        }.getOrDefault(false)
    }

    fun artFile(context: Context, trackId: String) = File(File(context.filesDir, "art").apply { mkdirs() }, "$trackId.jpg")

    private val ID3_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age",
        "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska",
        "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion",
        "Trance", "Classical", "Instrumental", "Acid", "House",
    )
}
