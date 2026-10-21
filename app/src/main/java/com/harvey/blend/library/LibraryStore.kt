package com.harvey.blend.library

import android.content.Context
import android.util.Base64
import com.harvey.blend.analysis.MusicalKey
import com.harvey.blend.analysis.TrackAnalysis
import com.harvey.blend.model.EnergyArc
import com.harvey.blend.model.Playlist
import com.harvey.blend.model.PlaylistKind
import com.harvey.blend.model.Settings
import com.harvey.blend.model.Track
import com.harvey.blend.model.TransitionStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves the library, playlists and settings as JSON in the app's private storage. */
class LibraryStore(private val context: Context) {
    private val file get() = File(context.filesDir, "library.json")

    data class Snapshot(val tracks: List<Track>, val playlists: List<Playlist>, val settings: Settings)

    fun load(): Snapshot {
        val f = file
        if (!f.exists()) return Snapshot(emptyList(), emptyList(), Settings())
        return runCatching {
            val root = JSONObject(f.readText())
            val tracks = root.optJSONArray("tracks")?.let { arr -> (0 until arr.length()).mapNotNull { runCatching { trackFrom(arr.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
            val playlists = root.optJSONArray("playlists")?.let { arr -> (0 until arr.length()).map { playlistFrom(arr.getJSONObject(it)) } } ?: emptyList()
            val settings = root.optJSONObject("settings")?.let(::settingsFrom) ?: Settings()
            Snapshot(tracks, playlists, settings)
        }.getOrElse { Snapshot(emptyList(), emptyList(), Settings()) }
    }

    @Synchronized
    fun save(s: Snapshot) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("tracks", JSONArray().apply { s.tracks.forEach { put(trackJson(it)) } })
        root.put("playlists", JSONArray().apply { s.playlists.forEach { put(playlistJson(it)) } })
        root.put("settings", settingsJson(s.settings))
        val tmp = File(context.filesDir, "library.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.optDbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null

    private fun trackJson(t: Track) = JSONObject().apply {
        put("id", t.id); put("uri", t.uri); put("rel", t.relativePath); put("file", t.fileName); put("size", t.sizeBytes)
        put("title", t.title); put("artist", t.artist); put("album", t.album); put("genre", t.genre)
        t.tagKey?.let { put("tagKey", it.camelot) }; t.tagBpm?.let { put("tagBpm", it) }
        t.userBpm?.let { put("uBpm", it) }; t.userKey?.let { put("uKey", it.camelot) }
        t.userEnergy?.let { put("uEnergy", it) }; t.userGenre?.let { put("uGenre", it) }
        t.userMixIn?.let { put("uIn", it) }; t.userMixOut?.let { put("uOut", it) }
        put("comment", t.comment); put("tags", JSONArray(t.tags)); put("added", t.addedAt)
        t.analysisError?.let { put("err", it) }
        t.analysis?.let { a ->
            put("an", JSONObject().apply {
                put("bpm", a.bpm); put("bpmC", a.bpmConfidence); put("grid", a.firstDownbeat)
                a.key?.let { put("key", it.camelot) }; put("keyC", a.keyConfidence); put("energy", a.energy)
                put("in", a.mixIn); put("out", a.mixOut); put("outC", a.mixOutConfidence); put("dur", a.duration)
                put("wl", Base64.encodeToString(a.waveLow, Base64.NO_WRAP))
                put("wh", Base64.encodeToString(a.waveHigh, Base64.NO_WRAP))
            })
        }
    }

    private fun trackFrom(o: JSONObject): Track {
        val an = o.optJSONObject("an")?.let { a ->
            TrackAnalysis(
                bpm = a.getDouble("bpm"), bpmConfidence = a.optDouble("bpmC", 0.5), firstDownbeat = a.getDouble("grid"),
                key = a.optStr("key")?.let(MusicalKey::fromCamelot), keyConfidence = a.optDouble("keyC", 0.5),
                energy = a.getInt("energy"), mixIn = a.getDouble("in"), mixOut = a.getDouble("out"),
                mixOutConfidence = a.optDouble("outC", 0.5),
                waveLow = Base64.decode(a.getString("wl"), Base64.NO_WRAP),
                waveHigh = Base64.decode(a.getString("wh"), Base64.NO_WRAP),
                duration = a.getDouble("dur"),
            )
        }
        val tagsArr = o.optJSONArray("tags")
        return Track(
            id = o.getString("id"), uri = o.getString("uri"), relativePath = o.getString("rel"), fileName = o.getString("file"),
            sizeBytes = o.optLong("size"), title = o.getString("title"), artist = o.optString("artist"),
            album = o.optString("album"), genre = o.optString("genre"),
            tagKey = o.optStr("tagKey")?.let(MusicalKey::fromCamelot), tagBpm = o.optDbl("tagBpm"),
            analysis = an, analysisError = o.optStr("err"),
            userBpm = o.optDbl("uBpm"), userKey = o.optStr("uKey")?.let(MusicalKey::fromCamelot),
            userEnergy = o.optIntOrNull("uEnergy"), userGenre = o.optStr("uGenre"),
            userMixIn = o.optDbl("uIn"), userMixOut = o.optDbl("uOut"),
            comment = o.optString("comment"),
            tags = tagsArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            addedAt = o.optLong("added"),
        )
    }

    private fun playlistJson(p: Playlist) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("kind", p.kind.name); put("tracks", JSONArray(p.trackIds))
        put("locked", JSONArray(p.locked.toList())); put("arc", p.arc.name); put("created", p.createdAt)
    }

    private fun playlistFrom(o: JSONObject): Playlist {
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Playlist(
            id = o.getString("id"), name = o.getString("name"),
            kind = runCatching { PlaylistKind.valueOf(o.getString("kind")) }.getOrDefault(PlaylistKind.PLAYLIST),
            trackIds = arr("tracks"), locked = arr("locked").toSet(),
            arc = runCatching { EnergyArc.valueOf(o.getString("arc")) }.getOrDefault(EnergyArc.JOURNEY),
            createdAt = o.optLong("created"),
        )
    }

    private fun settingsJson(s: Settings) = JSONObject().apply {
        s.musicFolderUri?.let { put("folder", it) }; s.musicFolderName?.let { put("folderName", it) }
        put("pcPath", s.computerMusicPath); put("bars", s.transitionBars); put("style", s.transitionStyle.name)
        put("autoMix", s.autoMix)
    }

    private fun settingsFrom(o: JSONObject) = Settings(
        musicFolderUri = o.optStr("folder"), musicFolderName = o.optStr("folderName"),
        computerMusicPath = o.optString("pcPath"), transitionBars = o.optInt("bars", 8),
        transitionStyle = runCatching { TransitionStyle.valueOf(o.getString("style")) }.getOrDefault(TransitionStyle.BASS_SWAP),
        autoMix = o.optBoolean("autoMix", true),
    )
}
