package com.harvey.blend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.harvey.blend.analysis.KeyRelation
import com.harvey.blend.model.Track
import com.harvey.blend.model.TransitionStyle
import com.harvey.blend.repo

private enum class Sort(val label: String) { TITLE("Title"), BPM("BPM"), KEY("Key"), ENERGY("Energy"), ADDED("Recent") }

@Composable
fun SearchScreen(showBack: Boolean) {
    val repo = LocalContext.current.repo
    val nav = LocalNav.current
    val tracks by repo.tracks.collectAsState()
    val queue by repo.queue.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.TITLE) }
    var mixesWithNow by rememberSaveable { mutableStateOf(false) }
    val now = queue.currentId?.let { tracks[it] }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { nav.pop() }
            Text("Search", style = Type.title, color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(
            query, { query = it }, singleLine = true,
            placeholder = { Text("Title, artist, genre, comment, key (8A) or BPM (124)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (now != null) GlassChip("Mixes with ${now.key?.camelot ?: "now playing"}", selected = mixesWithNow) { mixesWithNow = !mixesWithNow }
            Sort.entries.forEach { s -> GlassChip(s.label, selected = sort == s) { sort = s } }
        }
        val q = query.trim().lowercase()
        val list = tracks.values.asSequence()
            .filter { t ->
                q.isEmpty() || listOf(t.title, t.artist, t.displayGenre, t.comment, t.album).any { it.lowercase().contains(q) } ||
                    t.key?.camelot?.lowercase() == q || (q.toDoubleOrNull()?.let { b -> t.bpm?.let { kotlin.math.abs(it - b) <= 2.5 } } ?: false)
            }
            .filter { t ->
                !mixesWithNow || now == null || t.id == now.id || run {
                    val rel = KeyRelation.between(now.key, t.key)
                    val bpmOk = now.bpm == null || t.bpm == null || kotlin.math.abs(t.bpm!! / now.bpm!! - 1) <= 0.06
                    rel != null && rel.score >= 0.9 && bpmOk
                }
            }
            .sortedWith(
                when (sort) {
                    Sort.TITLE -> compareBy<Track> { it.title.lowercase() }
                    Sort.BPM -> compareBy<Track> { it.bpm ?: 999.0 }
                    Sort.KEY -> compareBy<Track>({ it.key?.camelotNumber ?: 99 }, { it.key?.minor == false })
                    Sort.ENERGY -> compareByDescending<Track> { it.energy ?: 0 }
                    Sort.ADDED -> compareByDescending<Track> { it.addedAt }
                }
            ).toList()
        if (list.isEmpty()) {
            if (tracks.isEmpty()) EmptyHint("Your library is empty", "Blend hasn't found any music yet. Check the folder in Library, then tap Rescan.") {
                PrimaryButton("Go to Library") { nav.tab(Dest.Library) }
            } else EmptyHint(
                "No match",
                if (mixesWithNow) "Nothing in your ${tracks.size} tracks mixes cleanly with the current one. Turn off the “Mixes with” filter to see everything."
                else "None of your ${tracks.size} tracks match “$query”.",
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
            itemsIndexed(list, key = { _, t -> t.id }) { _, t ->
                TrackListRow(t, queue.currentId == t.id, onClick = { nav.push(Dest.TrackView(t.id)) }, onPlay = {
                    if (now != null && mixesWithNow) repo.setUpNext(t.id) else repo.play(listOf(t.id), 0, "Search", null)
                })
            }
        }
    }
}

@Composable
fun SettingsScreen(showBack: Boolean, onPickFolder: () -> Unit) {
    val repo = LocalContext.current.repo
    val nav = LocalNav.current
    val settings by repo.settings.collectAsState()
    val progress by repo.progress.collectAsState()
    val tracks by repo.tracks.collectAsState()
    var pcPath by remember(settings.computerMusicPath) { mutableStateOf(settings.computerMusicPath) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { nav.pop() }
            Text("Settings", style = Type.title, color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
        Card {
            SectionLabel("Music folder on this phone")
            Text(settings.musicFolderName ?: "Not chosen yet", style = Type.body, color = Color.White)
            Text("${tracks.size} tracks · ${tracks.values.count { it.isAnalyzed }} analysed", style = Type.caption, color = Blend.Sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("Choose folder", icon = Icons.Rounded.Folder) { onPickFolder() }
                GlassChip("Rescan", icon = Icons.Rounded.Refresh) { repo.rescan() }
            }
            if (progress.active) AnalysisCard(progress)
        }
        Card {
            SectionLabel("Same folder on your computer")
            Text("Used when exporting to Rekordbox / Traktor so the tracks link to the files on your computer.", style = Type.caption, color = Blend.Sub)
            OutlinedTextField(
                pcPath, { pcPath = it; repo.updateSettings { s -> s.copy(computerMusicPath = it) } }, singleLine = true,
                placeholder = { Text("D:\\Music   or   /Users/david/Music") }, modifier = Modifier.fillMaxWidth(),
            )
            val example = tracks.values.firstOrNull()?.relativePath ?: "House/Track.aiff"
            if (pcPath.isNotBlank()) Text("e.g. ${com.harvey.blend.export.PathMapper(pcPath).absolute(example)}", style = Type.caption, color = Blend.Dim)
        }
        Card {
            SectionLabel("Mixing")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-mix", style = Type.body, color = Color.White)
                    Text("Beat-matched transitions between tracks. Off = play each track to the end.", style = Type.caption, color = Blend.Sub)
                }
                Switch(settings.autoMix, { v -> repo.updateSettings { it.copy(autoMix = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = Blend.Orange))
            }
            Text("Transition style", style = Type.bodySmall, color = Blend.Sub)
            Segmented(TransitionStyle.entries.map { it.label }, TransitionStyle.entries.indexOf(settings.transitionStyle)) { i ->
                repo.updateSettings { it.copy(transitionStyle = TransitionStyle.entries[i]) }
            }
            Text("Transition length", style = Type.bodySmall, color = Blend.Sub)
            val lengths = listOf(4, 8, 16)
            Segmented(lengths.map { "$it bars" }, lengths.indexOf(settings.transitionBars)) { i -> repo.updateSettings { it.copy(transitionBars = lengths[i]) } }
            Text("8 bars ≈ 15 s at 124 BPM: the next track comes in with its bass cut, both play together, then the basses swap on a bar line.", style = Type.caption, color = Blend.Dim)
        }
        Card {
            SectionLabel("About the analysis")
            Text(
                "BPM, beat grid, key (Camelot), energy and cue points are detected on the phone. Mix-in is the first downbeat; mix-out is the start of the outro, found where the vocals and chords drop away on a phrase line. Cues marked yellow are worth checking.",
                style = Type.caption, color = Blend.Sub,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Blend.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}
