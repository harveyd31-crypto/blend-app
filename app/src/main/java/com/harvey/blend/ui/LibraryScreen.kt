package com.harvey.blend.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harvey.blend.model.Playlist
import com.harvey.blend.model.PlaylistKind
import com.harvey.blend.model.Track
import com.harvey.blend.repo

enum class LibraryFilter(val label: String) { PLAYLISTS("Playlists"), SMART_SETS("Smart sets"), GENRES("Genres"), TRACKS("Tracks") }

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    initialFilter: LibraryFilter = LibraryFilter.PLAYLISTS,
    compactRows: Boolean = false,
    onPickFolder: () -> Unit,
) {
    val repo = LocalContext.current.repo
    val nav = LocalNav.current
    val tracks by repo.tracks.collectAsState()
    val playlists by repo.playlists.collectAsState()
    val settings by repo.settings.collectAsState()
    val progress by repo.progress.collectAsState()
    val queue by repo.queue.collectAsState()
    var filter by rememberSaveable(initialFilter) { mutableStateOf(initialFilter) }
    var showCreate by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> repo.importSongs(uris) }
    val importSongs = { importer.launch(arrayOf("audio/*")) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Blend.Raised).clickable { nav.push(Dest.Settings) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, "Settings", Modifier.size(18.dp), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(if (filter == LibraryFilter.SMART_SETS && initialFilter == LibraryFilter.SMART_SETS) "Set maker" else "Your Library", style = Type.title, color = Color.White, modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Rounded.LibraryAdd, "Import songs", size = 44.dp) { importSongs() }
            RoundIconButton(Icons.Rounded.Search, "Search", size = 44.dp) { nav.push(Dest.Search) }
            RoundIconButton(Icons.Rounded.Add, "New playlist or smart set", size = 44.dp) { showCreate = true }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { f -> GlassChip(f.label, selected = filter == f) { filter = f } }
        }

        if (progress.active) AnalysisCard(progress, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        if (settings.musicFolderUri == null && tracks.isEmpty() && !progress.scanning) {
            EmptyHint(
                "Add your music",
                "Import songs from Downloads, Drive or anywhere on the phone (AIFF, WAV, FLAC, MP3, M4A) — or choose a folder that holds your whole library. Blend analyses BPM, key, energy and cue points.",
                Modifier.fillMaxWidth(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Import songs", onClick = importSongs)
                    PrimaryButton("Choose folder", color = Color.White, onClick = onPickFolder)
                }
            }
            return@Column
        }

        if (tracks.isEmpty() && !progress.scanning) {
            val body = when {
                progress.scanError != null -> progress.scanError!!
                progress.lastScanAudio == 0 && progress.lastScanOther > 0 ->
                    "Found ${progress.lastScanOther} files in “${settings.musicFolderName}”, but none are music files (AIFF, WAV, FLAC, MP3, M4A, OGG)."
                progress.lastScanAudio == 0 -> "“${settings.musicFolderName}” is empty. Copy your music into it, then tap Rescan."
                else -> "Tap Rescan to look for music in “${settings.musicFolderName}”."
            }
            EmptyHint("No music found yet", body, Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Import songs", onClick = importSongs)
                    PrimaryButton("Rescan", color = Color.White) { repo.rescan() }
                }
            }
            return@Column
        }

        val all = tracks
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
            when (filter) {
                LibraryFilter.PLAYLISTS, LibraryFilter.SMART_SETS -> {
                    val kind = if (filter == LibraryFilter.SMART_SETS) PlaylistKind.SMART_SET else null
                    val shown = playlists.filter { kind == null || it.kind == kind }.sortedByDescending { it.createdAt }
                    if (shown.isEmpty()) item {
                        EmptyHint(
                            if (kind == PlaylistKind.SMART_SET) "No smart sets yet" else "No playlists yet",
                            if (kind == PlaylistKind.SMART_SET) "A smart set orders its tracks as a story — warm-up, build, peak, cool-down — chaining compatible keys. Tap + to make one."
                            else "Tap + to create a playlist, then add tracks and drag them into order.",
                        ) { PrimaryButton("New ${if (kind == PlaylistKind.SMART_SET) "smart set" else "playlist"}") { showCreate = true } }
                    }
                    items(shown, key = { it.id }) { p ->
                        PlaylistRow(p, p.trackIds.mapNotNull { all[it] }, playing = queue.contextPlaylistId == p.id, compact = compactRows) {
                            nav.replaceDetail(Dest.PlaylistView(p.id))
                        }
                    }
                }
                LibraryFilter.GENRES -> {
                    val groups = all.values.groupBy { it.displayGenre.ifBlank { "Unknown" } }.toList().sortedByDescending { it.second.size }
                    items(groups, key = { it.first }) { (g, ts) ->
                        GenreRow(g, ts) { nav.replaceDetail(Dest.GenreView(g)) }
                    }
                }
                LibraryFilter.TRACKS -> {
                    val list = all.values.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
                    item {
                        Text("${list.size} tracks · ${list.count { it.isAnalyzed }} analysed", style = Type.caption, color = Blend.Sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    items(list, key = { it.id }) { t ->
                        TrackListRow(t, playing = queue.currentId == t.id, onClick = { nav.replaceDetail(Dest.TrackView(t.id)) }, onPlay = {
                            repo.play(list.map { it.id }, list.indexOf(t), "All tracks", null)
                        })
                    }
                }
            }
        }
    }

    if (showCreate) CreatePlaylistDialog(
        defaultKind = if (filter == LibraryFilter.SMART_SETS) PlaylistKind.SMART_SET else PlaylistKind.PLAYLIST,
        onDismiss = { showCreate = false },
    ) { name, kind ->
        showCreate = false
        val id = repo.createPlaylist(name, kind)
        nav.replaceDetail(Dest.PlaylistView(id))
    }
}

@Composable
fun AnalysisCard(p: com.harvey.blend.LibraryProgress, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Blend.Card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = Blend.Blue)
        Column(Modifier.weight(1f)) {
            val title = if (p.scanning) "Adding music… ${p.found} files" else "Analysing ${p.toAnalyze - p.analyzed} tracks"
            Text(title, style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (p.scanning) 0f else p.fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Blend.Blue, trackColor = Color.White.copy(alpha = 0.15f),
            )
            p.current?.let { Text("BPM · key · cues · energy — $it", style = Type.caption, color = Blend.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (!p.scanning) Text("${(p.fraction * 100).toInt()}%", style = Type.num, color = Blend.Sub)
    }
}

@Composable
fun PlaylistRow(p: Playlist, tracks: List<Track>, playing: Boolean, compact: Boolean, onClick: () -> Unit) {
    val coverSize = if (compact) 52.dp else 64.dp
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CollageCover(tracks.take(4), coverSize)
        Column(Modifier.weight(1f)) {
            Text(p.name, style = Type.body, color = if (playing) Blend.Orange else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (p.kind == PlaylistKind.SMART_SET) Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp), tint = Blend.Orange)
                val dur = tracks.sumOf { it.duration }
                Text(
                    "${if (p.kind == PlaylistKind.SMART_SET) "Smart set" else "Playlist"} · ${tracks.size} tracks" + if (dur > 0) " · ${formatDuration(dur)}" else "",
                    style = Type.bodySmall, color = Blend.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (tracks.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 3.dp)) {
                Sparkline(tracks.map { it.energy ?: 5 }, if (p.kind == PlaylistKind.SMART_SET) Blend.Orange else Blend.Dim, Modifier.width(64.dp).height(14.dp))
                Text(rangeSummary(tracks), style = Type.num.copy(fontSize = Type.caption.fontSize), color = Blend.Dim, maxLines = 1)
            }
        }
    }
}

fun rangeSummary(tracks: List<Track>): String {
    val bpms = tracks.mapNotNull { it.bpm }
    val keys = tracks.mapNotNull { it.key }
    val b = if (bpms.isEmpty()) "" else "${bpms.min().toInt()}–${bpms.max().toInt()}"
    val k = if (keys.isEmpty()) "" else if (keys.size == 1) keys.first().camelot else "${keys.first().camelot}→${keys.last().camelot}"
    return listOf(b, k).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
fun Sparkline(values: List<Int>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = size.height - (v - 1) / 9f * size.height
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(1.6.dp.toPx()))
    }
}

@Composable
private fun GenreRow(name: String, tracks: List<Track>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(generatedCover(name)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = Color.White.copy(alpha = 0.8f))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Genre · ${tracks.size} tracks", style = Type.bodySmall, color = Blend.Sub)
            Text(rangeSummary(tracks.sortedBy { it.bpm ?: 0.0 }), style = Type.num.copy(fontSize = Type.caption.fontSize), color = Blend.Dim)
        }
    }
}

@Composable
fun TrackListRow(t: Track, playing: Boolean, onClick: () -> Unit, onPlay: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(t, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(t.title, style = Type.body, color = if (playing) Blend.Orange else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyChip(t.key)
                Text(t.bpm?.let { "%.1f".format(it) } ?: "—", style = Type.num.copy(fontSize = Type.caption.fontSize), color = Color.White)
                Text(t.artist, style = Type.bodySmall, color = Blend.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                t.analysisError != null -> Text(t.analysisError, style = Type.caption, color = Blend.Yellow, maxLines = 1)
                !t.isAnalyzed -> Text("Waiting for analysis…", style = Type.caption, color = Blend.Dim)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    MiniWave(t, Modifier.width(120.dp).height(14.dp), bars = 40)
                    EnergyBars(t.energy)
                }
            }
        }
        if (trailing != null) trailing() else
            Text("Edit", style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = Blend.Sub, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp))
    }
}

@Composable
fun CreatePlaylistDialog(defaultKind: PlaylistKind, onDismiss: () -> Unit, onCreate: (String, PlaylistKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(defaultKind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Blend.Card,
        title = { Text("New", style = Type.heading, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Playlist", kind == PlaylistKind.PLAYLIST) { kind = PlaylistKind.PLAYLIST }
                    GlassChip("Smart set", kind == PlaylistKind.SMART_SET, icon = Icons.Rounded.AutoAwesome) { kind = PlaylistKind.SMART_SET }
                }
                Text(
                    if (kind == PlaylistKind.SMART_SET) "Blend orders the tracks as a story and mixes them automatically. You can still drag tracks and lock them in place."
                    else "You choose the order. Blend still mixes the tracks together when you play it.",
                    style = Type.caption, color = Blend.Sub,
                )
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, kind) }) { Text("Create", color = Blend.Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Blend.Sub) } },
    )
}
