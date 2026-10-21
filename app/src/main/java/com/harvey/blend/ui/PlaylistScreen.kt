package com.harvey.blend.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.harvey.blend.ExportFormat
import com.harvey.blend.model.EnergyArc
import com.harvey.blend.model.PlaylistKind
import com.harvey.blend.model.Track
import com.harvey.blend.repo
import com.harvey.blend.setmaker.SetMaker
import kotlin.math.roundToInt

private val ROW_HEIGHT = 92.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(playlistId: String, wide: Boolean, showBack: Boolean) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val nav = LocalNav.current
    val playlists by repo.playlists.collectAsState()
    val tracks by repo.tracks.collectAsState()
    val queue by repo.queue.collectAsState()
    val p = playlists.firstOrNull { it.id == playlistId }
    if (p == null) { EmptyHint("Playlist not found", "It may have been deleted."); return }
    val list = p.trackIds.mapNotNull { tracks[it] }
    val playingHere = queue.contextPlaylistId == p.id

    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var arcMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        val text = pendingExport
        if (uri != null && text != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
        pendingExport = null
    }
    fun export(f: ExportFormat) {
        pendingExport = repo.exportXml(f, listOf(p.id))
        saver.launch("${p.name}.${f.ext}")
    }

    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    val ids by rememberUpdatedState(p.trackIds)
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF5A2C1A), 0.18f to Color(0xFF2E1B14), 0.4f to Blend.Bg))) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 170.dp)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = if (wide) 24.dp else 16.dp).padding(top = 8.dp)) {
                    if (showBack) RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.padding(bottom = 4.dp)) { nav.pop() }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CollageCover(list.take(4), if (wide) 150.dp else 112.dp)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (p.kind == PlaylistKind.SMART_SET) Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp), tint = Blend.Orange)
                                SectionLabel(if (p.kind == PlaylistKind.SMART_SET) "Smart set" else "Playlist", color = Color.White)
                            }
                            Text(p.name, style = if (wide) Type.hero.copy(fontSize = Type.hero.fontSize * 1.3f) else Type.hero, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { showRename = true })
                            Text(
                                "${list.size} tracks · ${formatDuration(list.sumOf { it.duration })}" + rangeSummary(list).let { if (it.isEmpty()) "" else " · $it" },
                                style = Type.bodySmall, color = Color(0xFFE0E0E0),
                            )
                        }
                    }
                    if (list.size >= 2) {
                        Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel("Story arc · energy", Modifier.weight(1f))
                                Text(p.arc.label, style = Type.caption, color = Blend.Sub)
                            }
                            StoryArc(list, if (playingHere) queue.currentId else null, Modifier.fillMaxWidth().height(if (wide) 96.dp else 70.dp))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box {
                            GlassChip(p.arc.label, icon = Icons.Rounded.ExpandMore) { arcMenu = true }
                            DropdownMenu(arcMenu, { arcMenu = false }, containerColor = Blend.Raised) {
                                EnergyArc.entries.forEach { a ->
                                    DropdownMenuItem(text = { Text(a.label, color = Color.White) }, onClick = { arcMenu = false; repo.reshuffleStory(p.id, a) })
                                }
                            }
                        }
                        RoundIconButton(Icons.Rounded.Shuffle, "Reshuffle story", tint = Blend.Orange) { repo.reshuffleStory(p.id) }
                        Box {
                            RoundIconButton(Icons.Rounded.IosShare, "Export to Rekordbox or Traktor", tint = Blend.Sub) { exportMenu = true }
                            DropdownMenu(exportMenu, { exportMenu = false }, containerColor = Blend.Raised) {
                                ExportFormat.entries.forEach { f ->
                                    DropdownMenuItem(text = { Text(f.label, color = Color.White) }, onClick = { exportMenu = false; export(f) })
                                }
                            }
                        }
                        RoundIconButton(Icons.Rounded.Add, "Add tracks", tint = Blend.Sub) { showAdd = true }
                        Box {
                            RoundIconButton(Icons.Rounded.MoreVert, "More", tint = Blend.Sub) { menu = true }
                            DropdownMenu(menu, { menu = false }, containerColor = Blend.Raised) {
                                DropdownMenuItem(text = { Text("Rename", color = Color.White) }, onClick = { menu = false; showRename = true })
                                DropdownMenuItem(text = { Text(if (p.kind == PlaylistKind.SMART_SET) "Make it a plain playlist" else "Make it a smart set", color = Color.White) }, onClick = {
                                    menu = false
                                    repo.updatePlaylist(p.id) { it.copy(kind = if (it.kind == PlaylistKind.SMART_SET) PlaylistKind.PLAYLIST else PlaylistKind.SMART_SET) }
                                })
                                DropdownMenuItem(text = { Text("Delete", color = Blend.Orange) }, onClick = { menu = false; repo.deletePlaylist(p.id); nav.pop() })
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(54.dp).clip(CircleShape).background(Blend.Orange).clickable(enabled = list.isNotEmpty(), onClickLabel = "Play as a mixed set") {
                                repo.play(p.trackIds, 0, p.name, p.id)
                                nav.push(Dest.Mix)
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, "Play as a mixed set", Modifier.size(30.dp), tint = Blend.Bg) }
                    }
                    if (list.isEmpty()) EmptyHint("Add some tracks", "Tap + to pick tracks from your library.", Modifier.fillMaxWidth())
                }
            }
            itemsIndexed(list, key = { _, t -> t.id }) { index, t ->
                val dragging = dragId == t.id
                val next = list.getOrNull(index + 1)
                Column(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            shadowElevation = if (dragging) 24f else 0f
                        }
                        .height(ROW_HEIGHT)
                        .background(if (dragging) Blend.Raised else Color.Transparent)
                ) {
                    var rowMenu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().height(72.dp)
                            .background(if (playingHere && queue.currentId == t.id) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                            .combinedClickable(onClick = { repo.play(p.trackIds, index, p.name, p.id) }, onLongClick = { rowMenu = true })
                            .padding(start = if (wide) 24.dp else 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box {
                            Cover(t, 56.dp)
                            if (t.id in p.locked) Box(Modifier.align(Alignment.TopStart).padding(3.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Lock, "Locked in place", Modifier.size(11.dp), tint = Color.White)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = Type.body.copy(fontSize = Type.body.fontSize * 1.05f), color = if (playingHere && queue.currentId == t.id) Blend.Orange else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                KeyChip(t.key)
                                Text(t.bpm?.let { "%.0f".format(it) } ?: "—", style = Type.num.copy(fontSize = Type.bodySmall.fontSize), color = Color(0xFFE0E0E0))
                                Text("· ${t.artist}", style = Type.bodySmall, color = Blend.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                MiniWave(t, Modifier.width(if (wide) 200.dp else 138.dp).height(14.dp), bars = if (wide) 64 else 46)
                                EnergyBars(t.energy)
                                if (t.comment.isNotBlank()) {
                                    if (wide) Text("“${t.comment}”", style = Type.caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = Blend.Comment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    else Icon(Icons.Rounded.ChatBubbleOutline, "Has comment", Modifier.size(13.dp), tint = Blend.Comment)
                                }
                            }
                        }
                        if (wide) Text(formatTime(t.duration), style = Type.num.copy(fontSize = Type.bodySmall.fontSize), color = Blend.Sub)
                        Box(
                            Modifier.size(width = 36.dp, height = 56.dp).pointerInput(t.id) {
                                detectDragGestures(
                                    onDragStart = { dragId = t.id; dragOffset = 0f },
                                    onDragEnd = { dragId = null; dragOffset = 0f },
                                    onDragCancel = { dragId = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val cur = ids.indexOf(t.id)
                                    val target = (cur + (dragOffset / rowPx).roundToInt()).coerceIn(0, ids.size - 1)
                                    if (target != cur && cur >= 0) {
                                        repo.moveInPlaylist(p.id, cur, target)
                                        dragOffset -= (target - cur) * rowPx
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.DragIndicator, "Drag to reorder", tint = Blend.Sub) }
                        DropdownMenu(rowMenu, { rowMenu = false }, containerColor = Blend.Raised) {
                            DropdownMenuItem(text = { Text("Play from here", color = Color.White) }, onClick = { rowMenu = false; repo.play(p.trackIds, index, p.name, p.id) })
                            DropdownMenuItem(text = { Text("Play next", color = Color.White) }, onClick = { rowMenu = false; repo.setUpNext(t.id) })
                            DropdownMenuItem(text = { Text("Edit track & cues", color = Color.White) }, onClick = { rowMenu = false; nav.push(Dest.TrackView(t.id)) })
                            DropdownMenuItem(text = { Text(if (t.id in p.locked) "Unlock position" else "Lock position", color = Color.White) }, onClick = { rowMenu = false; repo.toggleLock(p.id, t.id) })
                            DropdownMenuItem(text = { Text("Remove from playlist", color = Blend.Orange) }, onClick = { rowMenu = false; repo.removeFromPlaylist(p.id, t.id) })
                        }
                    }
                    if (next != null && !dragging) {
                        val s = SetMaker.score(t, next)
                        val c = if (s.risky) Blend.Yellow else Blend.Green
                        Row(Modifier.height(20.dp).padding(start = if (wide) 50.dp else 42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.width(2.dp).height(20.dp).background(c.copy(alpha = 0.55f)))
                            Text(s.describe(t, next), style = Type.num.copy(fontSize = Type.caption.fontSize * 0.92f), color = c, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddTracksDialog(
        existing = p.trackIds.toSet(),
        anchor = list.lastOrNull(),
        onDismiss = { showAdd = false },
    ) { picked ->
        showAdd = false
        repo.addToPlaylist(p.id, picked)
        if (p.kind == PlaylistKind.SMART_SET) repo.reshuffleStory(p.id)
    }
    if (showRename) {
        var name by remember { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { showRename = false }, containerColor = Blend.Card,
            title = { Text("Rename", style = Type.heading, color = Color.White) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { showRename = false; repo.updatePlaylist(p.id) { it.copy(name = name.ifBlank { it.name }) } }) { Text("Save", color = Blend.Orange) } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel", color = Blend.Sub) } },
        )
    }
}

/** A virtual list of every track in a genre, with a shortcut to turn it into a smart set. */
@Composable
fun GenreScreen(name: String, showBack: Boolean) {
    val repo = LocalContext.current.repo
    val nav = LocalNav.current
    val tracks by repo.tracks.collectAsState()
    val queue by repo.queue.collectAsState()
    val list = tracks.values.filter { it.displayGenre.ifBlank { "Unknown" } == name }.sortedBy { it.bpm ?: 0.0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showBack) RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { nav.pop() }
                SectionLabel("Genre", color = Color.White)
                Text(name, style = Type.hero, color = Color.White)
                Text("${list.size} tracks · ${rangeSummary(list)}", style = Type.bodySmall, color = Blend.Sub)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    GlassChip("Make a smart set", icon = Icons.Rounded.AutoAwesome) {
                        val id = repo.createPlaylist(name, PlaylistKind.SMART_SET, list.map { it.id })
                        repo.reshuffleStory(id)
                        nav.replaceDetail(Dest.PlaylistView(id))
                    }
                    GlassChip("Play", icon = Icons.Rounded.PlayArrow) { repo.play(list.map { it.id }, 0, name, null) }
                }
            }
        }
        itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
            TrackListRow(t, queue.currentId == t.id, onClick = { nav.push(Dest.TrackView(t.id)) }, onPlay = { repo.play(list.map { it.id }, i, name, null) })
        }
    }
}

/** Pick tracks to add; can show only tracks that mix well after the playlist's last track. */
@Composable
fun AddTracksDialog(existing: Set<String>, anchor: Track?, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val repo = LocalContext.current.repo
    val tracks by repo.tracks.collectAsState()
    var query by remember { mutableStateOf("") }
    var compatible by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Blend.Bg)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Close") { onDismiss() }
                Text("Add tracks", style = Type.heading, color = Color.White, modifier = Modifier.weight(1f))
                PrimaryButton("Add ${picked.size}") { onAdd(picked.toList()) }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search title, artist, genre, key (9A) or BPM") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            if (anchor != null) Row(Modifier.padding(12.dp)) {
                GlassChip("Mixes well after “${anchor.title.take(24)}”", selected = compatible) { compatible = !compatible }
            }
            val q = query.trim().lowercase()
            val list = tracks.values.filter { it.id !in existing }
                .filter { t ->
                    q.isEmpty() || t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) ||
                        t.displayGenre.lowercase().contains(q) || t.key?.camelot?.lowercase() == q ||
                        (q.toDoubleOrNull()?.let { b -> t.bpm?.let { kotlin.math.abs(it - b) <= 3 } } ?: false)
                }
                .let { l -> if (compatible && anchor != null) l.map { it to SetMaker.score(anchor, it).score }.filter { it.second >= 0.8 }.sortedByDescending { it.second }.map { it.first } else l.sortedBy { it.title.lowercase() } }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, t -> t.id }) { _, t ->
                    val on = t.id in picked
                    TrackListRow(t, false, onClick = {}, onPlay = { picked = if (on) picked - t.id else picked + t.id }) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) Blend.Orange else Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            if (on) Text("✓", color = Blend.Bg, style = Type.caption.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}
