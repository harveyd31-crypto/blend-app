package com.harvey.blend.export

import com.harvey.blend.model.Playlist
import com.harvey.blend.model.Track
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Maps a track's path inside the phone's music folder to where the same file lives on the computer.
 * [computerRoot] e.g. "D:\Music" (Windows) or "/Users/david/Music" (Mac).
 */
class PathMapper(computerRoot: String, private val macVolume: String = "Macintosh HD") {
    private val root = computerRoot.trim().trimEnd('/', '\\')
    val isWindows = Regex("^[A-Za-z]:").containsMatchIn(root)

    /** Absolute path with forward slashes, e.g. "D:/Music/House/a.aiff" or "/Users/d/Music/a.aiff". */
    fun absolute(relativePath: String): String {
        val rel = relativePath.replace('\\', '/').trimStart('/')
        val base = root.replace('\\', '/')
        return if (base.isEmpty()) "/$rel" else "$base/$rel"
    }

    /** Rekordbox Location attribute. */
    fun rekordboxLocation(relativePath: String): String {
        val abs = absolute(relativePath)
        val encoded = abs.split('/').joinToString("/") { seg ->
            if (Regex("^[A-Za-z]:$").matches(seg)) seg
            else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "file://localhost" + (if (encoded.startsWith("/")) "" else "/") + encoded
    }

    /** Traktor LOCATION parts: volume, DIR in Traktor's "/:" notation, file name. */
    fun traktorLocation(relativePath: String): Triple<String, String, String> {
        val abs = absolute(relativePath)
        val parts = abs.split('/').filter { it.isNotEmpty() }
        val file = parts.last()
        return if (isWindows) {
            val volume = parts.first() // "D:"
            val dirs = parts.drop(1).dropLast(1)
            Triple(volume, "/:" + dirs.joinToString("") { "$it/:" }, file)
        } else {
            val dirs = parts.dropLast(1)
            Triple(macVolume, "/:" + dirs.joinToString("") { "$it/:" }, file)
        }
    }
}

object DjExport {

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            '\n' -> append("&#10;"); '\r' -> {}
            else -> if (c.code < 0x20 && c != '\t') append(' ') else append(c)
        }
    }

    private fun kind(fileName: String) = when (fileName.substringAfterLast('.', "").lowercase()) {
        "aif", "aiff" -> "AIFF File"; "wav" -> "WAV File"; "mp3" -> "MP3 File"
        "flac" -> "FLAC File"; "m4a", "aac", "alac" -> "M4A File"; else -> "Audio File"
    }

    private fun f2(v: Double) = "%.2f".format(java.util.Locale.US, v)
    private fun f3(v: Double) = "%.3f".format(java.util.Locale.US, v)

    /** Rekordbox collection XML (File › Import › rekordbox xml in Rekordbox's preferences). */
    fun rekordboxXml(playlists: List<Playlist>, tracksById: Map<String, Track>, paths: PathMapper, now: Instant = Instant.now()): String {
        val used = playlists.flatMap { it.trackIds }.distinct().mapNotNull { tracksById[it] }
        val numId = used.mapIndexed { i, t -> t.id to (i + 1) }.toMap()
        val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(now)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<DJ_PLAYLISTS Version=\"1.0.0\">\n")
        sb.append("  <PRODUCT Name=\"Blend\" Version=\"0.1\" Company=\"\"/>\n")
        sb.append("  <COLLECTION Entries=\"${used.size}\">\n")
        for (t in used) {
            sb.append("    <TRACK TrackID=\"${numId[t.id]}\" Name=\"${esc(t.title)}\" Artist=\"${esc(t.artist)}\"")
            sb.append(" Album=\"${esc(t.album)}\" Genre=\"${esc(t.displayGenre)}\" Kind=\"${kind(t.fileName)}\"")
            sb.append(" Size=\"${t.sizeBytes}\" TotalTime=\"${t.duration.toInt()}\"")
            t.bpm?.let { sb.append(" AverageBpm=\"${f2(it)}\"") }
            sb.append(" DateAdded=\"$date\" Comments=\"${esc(t.comment)}\"")
            t.key?.let { sb.append(" Tonality=\"${it.shortName}\"") }
            sb.append(" Location=\"${esc(paths.rekordboxLocation(t.relativePath))}\">\n")
            t.bpm?.let { bpm ->
                sb.append("      <TEMPO Inizio=\"${f3(t.gridStart)}\" Bpm=\"${f2(bpm)}\" Metro=\"4/4\" Battito=\"1\"/>\n")
            }
            if (t.isAnalyzed) {
                // Hot cue A = mix in (green), B = mix out (orange); plus matching memory cues.
                sb.append("      <POSITION_MARK Name=\"Mix in\" Type=\"0\" Start=\"${f3(t.mixIn)}\" Num=\"0\" Red=\"47\" Green=\"213\" Blue=\"102\"/>\n")
                sb.append("      <POSITION_MARK Name=\"Mix out\" Type=\"0\" Start=\"${f3(t.mixOut)}\" Num=\"1\" Red=\"240\" Green=\"149\" Blue=\"58\"/>\n")
                sb.append("      <POSITION_MARK Name=\"Mix in\" Type=\"0\" Start=\"${f3(t.mixIn)}\" Num=\"-1\"/>\n")
                sb.append("      <POSITION_MARK Name=\"Mix out\" Type=\"0\" Start=\"${f3(t.mixOut)}\" Num=\"-1\"/>\n")
            }
            sb.append("    </TRACK>\n")
        }
        sb.append("  </COLLECTION>\n")
        sb.append("  <PLAYLISTS>\n")
        sb.append("    <NODE Type=\"0\" Name=\"ROOT\" Count=\"1\">\n")
        sb.append("      <NODE Type=\"0\" Name=\"Blend\" Count=\"${playlists.size}\">\n")
        for (p in playlists) {
            val ids = p.trackIds.filter { it in numId }
            sb.append("        <NODE Name=\"${esc(p.name)}\" Type=\"1\" KeyType=\"0\" Entries=\"${ids.size}\">\n")
            for (id in ids) sb.append("          <TRACK Key=\"${numId[id]}\"/>\n")
            sb.append("        </NODE>\n")
        }
        sb.append("      </NODE>\n    </NODE>\n  </PLAYLISTS>\n</DJ_PLAYLISTS>\n")
        return sb.toString()
    }

    /** Traktor collection NML (Import Collection, or drag into the Playlists folder). */
    fun traktorNml(playlists: List<Playlist>, tracksById: Map<String, Track>, paths: PathMapper): String {
        val used = playlists.flatMap { it.trackIds }.distinct().mapNotNull { tracksById[it] }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n")
        sb.append("<NML VERSION=\"19\"><HEAD COMPANY=\"www.native-instruments.com\" PROGRAM=\"Traktor\"></HEAD>\n")
        sb.append("<MUSICFOLDERS></MUSICFOLDERS>\n")
        sb.append("<COLLECTION ENTRIES=\"${used.size}\">\n")
        for (t in used) {
            val (vol, dir, file) = paths.traktorLocation(t.relativePath)
            sb.append("<ENTRY TITLE=\"${esc(t.title)}\" ARTIST=\"${esc(t.artist)}\">")
            sb.append("<LOCATION DIR=\"${esc(dir)}\" FILE=\"${esc(file)}\" VOLUME=\"${esc(vol)}\" VOLUMEID=\"\"></LOCATION>")
            sb.append("<ALBUM TITLE=\"${esc(t.album)}\"></ALBUM>")
            sb.append("<INFO GENRE=\"${esc(t.displayGenre)}\" COMMENT=\"${esc(t.comment)}\"")
            t.key?.let { sb.append(" KEY=\"${it.camelot}\"") }
            sb.append(" PLAYTIME=\"${t.duration.toInt()}\" FILESIZE=\"${t.sizeBytes / 1024}\"></INFO>")
            t.bpm?.let { sb.append("<TEMPO BPM=\"${"%.6f".format(java.util.Locale.US, it)}\" BPM_QUALITY=\"100.000000\"></TEMPO>") }
            t.key?.let { sb.append("<MUSICAL_KEY VALUE=\"${it.traktorValue}\"></MUSICAL_KEY>") }
            if (t.isAnalyzed) {
                fun ms(s: Double) = "%.6f".format(java.util.Locale.US, s * 1000)
                sb.append("<CUE_V2 NAME=\"AutoGrid\" DISPL_ORDER=\"0\" TYPE=\"4\" START=\"${ms(t.gridStart)}\" LEN=\"0.000000\" REPEATS=\"-1\" HOTCUE=\"-1\"></CUE_V2>")
                sb.append("<CUE_V2 NAME=\"Mix in\" DISPL_ORDER=\"0\" TYPE=\"0\" START=\"${ms(t.mixIn)}\" LEN=\"0.000000\" REPEATS=\"-1\" HOTCUE=\"0\"></CUE_V2>")
                sb.append("<CUE_V2 NAME=\"Mix out\" DISPL_ORDER=\"0\" TYPE=\"0\" START=\"${ms(t.mixOut)}\" LEN=\"0.000000\" REPEATS=\"-1\" HOTCUE=\"1\"></CUE_V2>")
            }
            sb.append("</ENTRY>\n")
        }
        sb.append("</COLLECTION>\n<SETS ENTRIES=\"0\"></SETS>\n<PLAYLISTS><NODE TYPE=\"FOLDER\" NAME=\"\$ROOT\"><SUBNODES COUNT=\"${playlists.size}\">\n")
        for (p in playlists) {
            val ts = p.trackIds.mapNotNull { tracksById[it] }
            sb.append("<NODE TYPE=\"PLAYLIST\" NAME=\"${esc(p.name)}\"><PLAYLIST ENTRIES=\"${ts.size}\" TYPE=\"LIST\" UUID=\"${UUID.nameUUIDFromBytes(p.id.toByteArray()).toString().replace("-", "")}\">")
            for (t in ts) {
                val (vol, dir, file) = paths.traktorLocation(t.relativePath)
                sb.append("<ENTRY><PRIMARYKEY TYPE=\"TRACK\" KEY=\"${esc(vol + dir + file)}\"></PRIMARYKEY></ENTRY>")
            }
            sb.append("</PLAYLIST></NODE>\n")
        }
        sb.append("</SUBNODES></NODE></PLAYLISTS>\n<INDEXING></INDEXING>\n</NML>\n")
        return sb.toString()
    }
}
