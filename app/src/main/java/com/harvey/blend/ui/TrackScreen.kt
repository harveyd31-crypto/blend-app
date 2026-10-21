package com.harvey.blend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.harvey.blend.analysis.MusicalKey
import com.harvey.blend.model.Track
import com.harvey.blend.repo
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun TrackScreen(trackId: String, wide: Boolean, showBack: Boolean) {
    val repo = LocalContext.current.repo
    val nav = LocalNav.current
    val tracks by repo.tracks.collectAsState()
    val mix by repo.mix.collectAsState()
    val t = tracks[trackId]
    if (t == null) { EmptyHint("Track not found", "The file may have been moved."); return }
    var keyPicker by remember { mutableStateOf(false) }
    var genreEdit by remember { mutableStateOf(false) }
    val playingThis = mix.masterId == t.id

    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("BPM · ${confidence(t.analysis?.bpmConfidence, t.userBpm != null)}", Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.bpm?.let { "%.2f".format(it) } ?: "—", style = Type.numBig.copy(fontSize = Type.numBig.fontSize * 0.8f), color = Color.White, modifier = Modifier.weight(1f))
                        SmallButton("÷2") { t.bpm?.let { b -> repo.updateTrack(t.id) { it.copy(userBpm = b / 2) } } }
                        Spacer(Modifier.width(4.dp))
                        SmallButton("×2") { t.bpm?.let { b -> repo.updateTrack(t.id) { it.copy(userBpm = b * 2) } } }
                    }
                }
                StatCard("Key · ${confidence(t.analysis?.keyConfidence, t.userKey != null)}", Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyChip(t.key, big = true)
                        Text(t.key?.name ?: "", style = Type.caption, color = Blend.Sub, modifier = Modifier.weight(1f), maxLines = 1)
                        SmallButton("Change") { keyPicker = true }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Energy · ${t.energy ?: "—"} / 10", Modifier.weight(1f)) {
                    Slider(
                        value = (t.energy ?: 5).toFloat(), onValueChange = { v -> repo.updateTrack(t.id) { it.copy(userEnergy = v.roundToInt()) } },
                        valueRange = 1f..10f, steps = 8,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Blend.Orange, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
                    )
                }
                StatCard("Genre", Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.displayGenre.ifBlank { "—" }, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                        SmallButton("Edit") { genreEdit = true }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Blend.Glass).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                SectionLabel("Comment · exports to Rekordbox & Traktor")
                var text by remember(t.id) { mutableStateOf(t.comment) }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { v -> text = v; repo.updateTrack(t.id) { it.copy(comment = v) } },
                    textStyle = Type.body.copy(color = Color.White),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Blend.Orange),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(72.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text("“Vocal at 2:10, mix out before it”", style = Type.body, color = Blend.Dim)
                        inner()
                    },
                )
            }
            if (t.analysisError != null) Text("Couldn't analyse: ${t.analysisError}", style = Type.caption, color = Blend.Yellow)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("Re-analyse", icon = Icons.Rounded.Refresh) { repo.reanalyze(t.id) }
                GlassChip("Reset my edits") {
                    repo.updateTrack(t.id) { it.copy(userBpm = null, userKey = null, userEnergy = null, userGenre = null, userMixIn = null, userMixOut = null) }
                }
            }
            Text("Your edits always win over the automatic analysis and are kept if the track is analysed again.", style = Type.caption, color = Blend.Dim)
        }
    }

    val cues: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CueEditor(t, if (playingThis) mix.masterPos else null) { inSec, outSec ->
                repo.updateTrack(t.id) { it.copy(userMixIn = inSec ?: it.userMixIn, userMixOut = outSec ?: it.userMixOut) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CueCard("Mix in", Blend.Green, t, t.mixIn, t.userMixIn == null, "First downbeat of the intro.", Modifier.weight(1f),
                    onNudge = { d -> repo.updateTrack(t.id) { it.copy(userMixIn = (it.mixIn + d).coerceAtLeast(0.0)) } },
                    onPreview = { repo.play(listOf(t.id), 0, t.title, null, t.mixIn) })
                CueCard("Mix out", if (t.mixOutNeedsCheck) Blend.Yellow else Blend.Orange, t, t.mixOut, t.userMixOut == null,
                    if (t.mixOutNeedsCheck) "Not sure here — check it." else "Start of the outro: the next track comes in here.", Modifier.weight(1f),
                    onNudge = { d -> repo.updateTrack(t.id) { it.copy(userMixOut = (it.mixOut + d).coerceIn(0.0, it.duration)) } },
                    onPreview = { repo.play(listOf(t.id), 0, t.title, null, (t.mixOut - 8).coerceAtLeast(0.0)) })
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 16.dp).padding(top = 8.dp, bottom = 170.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showBack) RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { nav.pop() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(t, if (wide) 104.dp else 84.dp, corner = 10.dp)
            Column(Modifier.weight(1f)) {
                Text(t.title, style = Type.title, color = Color.White, maxLines = 2)
                Text(t.artist, style = Type.body, color = Blend.Sub, maxLines = 1)
                Text(
                    "${t.fileName.substringAfterLast('.').uppercase()} · ${formatTime(t.duration)} · ${t.relativePath.substringBeforeLast('/', "")}",
                    style = Type.caption, color = Blend.Dim, maxLines = 1,
                )
            }
            Box(Modifier.size(52.dp).clip(CircleShape).background(Blend.Orange).clickable { repo.play(listOf(t.id), 0, t.title, null) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Play", tint = Blend.Bg, modifier = Modifier.size(28.dp))
            }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f)) { details() }
            Box(Modifier.weight(1.2f)) { cues() }
        } else {
            cues(); details()
        }
    }

    if (keyPicker) KeyPickerDialog(t.key, onDismiss = { keyPicker = false }) { k -> keyPicker = false; repo.updateTrack(t.id) { it.copy(userKey = k) } }
    if (genreEdit) {
        var g by remember { mutableStateOf(t.displayGenre) }
        AlertDialog(
            onDismissRequest = { genreEdit = false }, containerColor = Blend.Card,
            title = { Text("Genre", style = Type.heading, color = Color.White) },
            text = { OutlinedTextField(g, { g = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { genreEdit = false; repo.updateTrack(t.id) { it.copy(userGenre = g.trim().ifBlank { null }) } }) { Text("Save", color = Blend.Orange) } },
            dismissButton = { TextButton(onClick = { genreEdit = false }) { Text("Cancel", color = Blend.Sub) } },
        )
    }
}

private fun confidence(c: Double?, user: Boolean) = when {
    user -> "yours"
    c == null -> "tag"
    else -> "auto ${(c * 100).roundToInt()}%"
}

@Composable
private fun StatCard(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Blend.Glass).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel(label)
        content()
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.num.copy(fontSize = Type.caption.fontSize), color = Color.White) }
}

@Composable
private fun CueCard(
    label: String, color: Color, t: Track, sec: Double, auto: Boolean, hint: String, modifier: Modifier,
    onNudge: (Double) -> Unit, onPreview: () -> Unit,
) {
    val bar = t.analysis?.let { 1 + floor((sec - t.gridStart) / (4 * t.beatSec) + 1e-6).toInt() }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Blend.Glass).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label, Modifier.weight(1f), color)
            Text(if (auto) "auto" else "yours", style = Type.caption, color = color)
        }
        Text("${bar?.let { "Bar $it · " } ?: ""}${formatTime(sec)}", style = Type.numBig.copy(fontSize = Type.numBig.fontSize * 0.7f), color = Color.White)
        Text(hint, style = Type.caption, color = Blend.Sub, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val beat = t.beatSec
            SmallButton("−bar") { onNudge(-4 * beat) }
            SmallButton("−beat") { onNudge(-beat) }
            SmallButton("+beat") { onNudge(beat) }
            SmallButton("+bar") { onNudge(4 * beat) }
        }
        Row(Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onPreview).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp), tint = color)
            Text("Preview", style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
    }
}

/** Waveform with phrase lines (every 8 bars) and draggable cue markers that snap to the beat. */
@Composable
private fun CueEditor(t: Track, position: Double?, onChange: (Double?, Double?) -> Unit) {
    val a = t.analysis
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Blend.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Cue points", Modifier.weight(1f), Color.White)
            Text("Drag the markers · they snap to the beat", style = Type.caption, color = Blend.Sub)
        }
        if (a == null) {
            Text(if (t.analysisError != null) "This track couldn't be analysed." else "Analysing… cue points appear here when it's done.", style = Type.bodySmall, color = Blend.Sub)
            return@Column
        }
        // Gesture handlers outlive recompositions, so they read the latest track through this.
        val latest by rememberUpdatedState(t)
        BoxWithConstraints(Modifier.fillMaxWidth().height(130.dp)) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            fun toSec(x: Float) = (x / widthPx * a.duration).coerceIn(0.0, a.duration)
            fun snap(s: Double): Double {
                val beat = latest.beatSec
                return latest.gridStart + ((s - latest.gridStart) / beat).roundToInt() * beat
            }
            fun toX(s: Double) = (s / a.duration * widthPx).toFloat()
            // Phrase lines every 8 bars.
            val phrase = 32 * t.beatSec
            var s = t.gridStart
            val density = LocalDensity.current
            while (s < a.duration) {
                val xDp = with(density) { toX(s).toDp() }
                Box(Modifier.offset(x = xDp).width(1.dp).fillMaxSize().background(Color.White.copy(alpha = 0.07f)))
                s += phrase
            }
            BandWave(t, Modifier.fillMaxSize().pointerInput(t.id) {
                detectTapGestures { o -> onChangeNearest(o.x, toX(latest.mixIn), toX(latest.mixOut)) { which -> if (which == 0) onChange(snap(toSec(o.x)), null) else onChange(null, snap(toSec(o.x))) } }
            }, position = position, window = t.mixOut to (t.mixOut + 8 * 4 * t.beatSec).coerceAtMost(a.duration))
            CueMarker("IN", Blend.Green, toX(t.mixIn), Modifier.pointerInput(t.id, "in") {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = toX(latest.mixIn) }) { change, d ->
                    change.consume(); acc += d; onChange(snap(toSec(acc)), null)
                }
            })
            CueMarker("OUT", if (t.mixOutNeedsCheck) Blend.Yellow else Blend.Orange, toX(t.mixOut), Modifier.pointerInput(t.id, "out") {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = toX(latest.mixOut) }) { change, d ->
                    change.consume(); acc += d; onChange(null, snap(toSec(acc)))
                }
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", style = Type.num.copy(fontSize = Type.caption.fontSize), color = Blend.Dim)
            Text("Lines = 8-bar phrases", style = Type.caption, color = Blend.Dim)
            Text(formatTime(a.duration), style = Type.num.copy(fontSize = Type.caption.fontSize), color = Blend.Dim)
        }
    }
}

private fun onChangeNearest(x: Float, inX: Float, outX: Float, set: (Int) -> Unit) {
    set(if (kotlin.math.abs(x - inX) <= kotlin.math.abs(x - outX)) 0 else 1)
}

@Composable
private fun CueMarker(label: String, color: Color, xPx: Float, gesture: Modifier) {
    val density = LocalDensity.current
    val half = with(density) { 14.dp.toPx() }
    Box(Modifier.offset { IntOffset((xPx - half).roundToInt(), 0) }.width(28.dp).fillMaxSize().then(gesture)) {
        Box(Modifier.align(Alignment.TopCenter).width(2.5.dp).fillMaxSize().background(color))
        Box(Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 4.dp, vertical = 1.dp)) {
            Text(label, style = Type.label.copy(fontSize = Type.label.fontSize * 0.8f), color = Blend.Bg)
        }
        Box(Modifier.align(Alignment.BottomCenter).size(22.dp).clip(CircleShape).background(Blend.Bg).border(3.dp, color, CircleShape))
    }
}

@Composable
fun KeyPickerDialog(current: MusicalKey?, onDismiss: () -> Unit, onPick: (MusicalKey) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Blend.Card,
        title = { Text("Key", style = Type.heading, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (letter in listOf("A", "B")) {
                    Text(if (letter == "A") "Minor" else "Major", style = Type.caption, color = Blend.Sub)
                    for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in (row * 6 + 1)..(row * 6 + 6)) {
                            val k = MusicalKey.fromCamelot("$n$letter")!!
                            Box(
                                Modifier.size(width = 40.dp, height = 34.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (k == current) Color.White else keyColor(k).copy(alpha = 0.85f))
                                    .clickable { onPick(k) },
                                contentAlignment = Alignment.Center,
                            ) { Text(k.camelot, style = Type.num.copy(fontSize = Type.caption.fontSize), color = Blend.Bg) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Blend.Sub) } },
    )
}
