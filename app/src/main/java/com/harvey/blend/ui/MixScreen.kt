package com.harvey.blend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.harvey.blend.audio.MixState
import com.harvey.blend.model.Track
import com.harvey.blend.model.TransitionStyle
import com.harvey.blend.repo

@Composable
fun MixScreen(wide: Boolean) {
    val repo = LocalContext.current.repo
    val nav = LocalNav.current
    val mix by repo.mix.collectAsState()
    val queue by repo.queue.collectAsState()
    val tracks by repo.tracks.collectAsState()
    val settings by repo.settings.collectAsState()
    val cur = queue.currentId?.let { tracks[it] }
    val next = (mix.incomingId ?: queue.nextId)?.let { tracks[it] }

    if (cur == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint("Nothing playing", "Open a playlist or smart set and press play. Blend mixes each track into the next on the beat.") {
                PrimaryButton("Go to library") { nav.tab(Dest.Library) }
            }
        }
        return
    }
    val inTransition = mix.transitionProgress != null
    val curPos = if (mix.masterId == cur.id) mix.masterPos else if (mix.incomingId == cur.id) mix.incomingPos else 0.0

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to Color(0xFF4A2618), 0.3f to Color(0xFF1E1512), 0.5f to Blend.Bg))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) 24.dp else 16.dp)
            .padding(top = 14.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Playing from", color = Color(0xFFE0E0E0))
                Text(
                    (queue.contextName ?: "Library") + if (queue.ids.size > 1) " · track ${queue.index + 1} of ${queue.ids.size}" else "",
                    style = Type.body.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            GlassChip(if (settings.autoMix) "Auto-mix on" else "Auto-mix off", selected = false) { repo.updateSettings { it.copy(autoMix = !it.autoMix) } }
        }

        val deckA: @Composable (Modifier) -> Unit = { m -> Deck(cur, "Deck A · playing", Blend.Orange, curPos, mix, m, wide) }
        val deckB: @Composable (Modifier) -> Unit = { m ->
            if (next != null) Deck(next, if (inTransition) "Deck B · coming in" else "Up next", Blend.Blue, if (mix.incomingId == next.id) mix.incomingPos else next.mixIn, mix, m, wide, lowsCut = inTransition && (mix.transitionProgress ?: 0.0) < 0.5)
            else Box(m) { Text("Nothing queued after this track.", style = Type.bodySmall, color = Blend.Sub) }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { deckA(Modifier.weight(1f)); deckB(Modifier.weight(1f)) }
        else { deckA(Modifier.fillMaxWidth()); deckB(Modifier.fillMaxWidth()) }

        TransitionPanel(cur, next, mix, curPos, wide)

        val picks = remember(queue.currentId, queue.ids, tracks.size) { repo.suggestions() }
        val content: @Composable (Modifier) -> Unit = { m ->
            Column(m.clip(RoundedCornerShape(12.dp)).background(Blend.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Smart picks for next", Modifier.weight(1f), Color.White)
                    Text("key · BPM · energy · story", style = Type.caption, color = Blend.Sub)
                }
                if (picks.isEmpty()) Text("Picks appear once tracks are analysed.", style = Type.caption, color = Blend.Sub)
                picks.forEach { (t, s) ->
                    val chosen = t.id == next?.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (chosen) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                            .clickable(enabled = !inTransition) { repo.setUpNext(t.id) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Cover(t, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text("${t.title} · ${t.artist}", style = Type.bodySmall.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val c = if (s.risky) Blend.Yellow else Blend.Green
                            Text((if (chosen) "Chosen · " else "") + s.describe(cur, t), style = Type.caption, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        KeyChip(t.key)
                        Text("${s.percent}%", style = Type.num, color = if (s.risky) Blend.Yellow else Blend.Green)
                    }
                }
            }
        }
        val style: @Composable (Modifier) -> Unit = { m ->
            Column(m.clip(RoundedCornerShape(12.dp)).background(Blend.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Transition", color = Color.White)
                Segmented(TransitionStyle.entries.map { it.label }, TransitionStyle.entries.indexOf(settings.transitionStyle)) { i ->
                    repo.updateSettings { it.copy(transitionStyle = TransitionStyle.entries[i]) }
                }
                val lengths = listOf(4, 8, 16)
                val bpm = cur.bpm ?: 120.0
                Segmented(lengths.map { "$it bars · ${(it * 240 / bpm).toInt()} s" }, lengths.indexOf(settings.transitionBars)) { i ->
                    repo.updateSettings { it.copy(transitionBars = lengths[i]) }
                }
                if (cur.comment.isNotBlank()) Text("“${cur.comment}”", style = Type.caption.copy(fontStyle = FontStyle.Italic), color = Blend.Comment)
                GlassChip("Edit cues & comment") { nav.push(Dest.TrackView(cur.id)) }
            }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { content(Modifier.weight(1.15f)); style(Modifier.weight(1f)) }
        else { content(Modifier.fillMaxWidth()); style(Modifier.fillMaxWidth()) }

        Controls(mix, repo::previous, repo::togglePlay, repo::skipNext, repo::mixNow) {
            queue.contextPlaylistId?.let { repo.reshuffleStory(it) }
        }
    }
}

@Composable
private fun Deck(t: Track, label: String, color: Color, pos: Double, mix: MixState, modifier: Modifier, wide: Boolean, lowsCut: Boolean = false) {
    val coverSize: Dp = if (wide) 150.dp else 96.dp
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box {
            Cover(t, coverSize, corner = 6.dp)
            if (lowsCut) Text("LOWS CUT", style = Type.label.copy(fontSize = Type.label.fontSize * 0.85f), color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.weight(1f)) {
            SectionLabel(label, color = color)
            Text(t.title, style = if (wide) Type.title else Type.heading, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = Type.bodySmall, color = Blend.Sub, maxLines = 1)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyChip(t.key)
                val bpm = t.bpm
                val shown = if (mix.masterId == t.id && bpm != null && mix.masterRate != 1.0) "%.1f → %.1f".format(bpm, bpm * mix.masterRate) else bpm?.let { "%.1f".format(it) } ?: "—"
                Text(shown, style = Type.num, color = Color.White)
                EnergyBars(t.energy)
            }
            val remaining = if (mix.masterId == t.id) "−" + formatTime(t.duration - pos) else formatTime(pos)
            Text(remaining, style = Type.numBig, color = if (color == Blend.Blue) Blend.Blue else Color.White, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TransitionPanel(cur: Track, next: Track?, mix: MixState, pos: Double, wide: Boolean) {
    val repo = LocalContext.current.repo
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Blend.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val p = mix.transitionProgress
        val start = mix.transitionStart
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                when {
                    p != null -> "Mixing · ${(p * 100).toInt()}%"
                    start != null -> "Mix starts in ${formatTime(start - pos)}"
                    next == null -> "Last track"
                    !mix.nextReady -> "Preparing next track…"
                    else -> "Transition"
                },
                Modifier.weight(1f), Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Blend.Orange)); Text("Lows", style = Type.caption, color = Blend.Sub)
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Blend.Blue)); Text("Mids & highs", style = Type.caption, color = Blend.Sub)
            }
        }
        val window = start?.let { it to it + mix.transitionLength }
        BandWave(
            cur, Modifier.fillMaxWidth().height(if (wide) 80.dp else 56.dp).pointerInput(cur.id) {
                detectTapGestures { o -> repo.seek(o.x / size.width * cur.duration) }
            },
            position = pos, window = window, dimBefore = pos, lowAlpha = if (p != null && p > 0.5) 0.2f else 1f,
        )
        if (next != null) {
            val nPos = if (mix.incomingId == next.id) mix.incomingPos else null
            BandWave(
                next, Modifier.fillMaxWidth().height(if (wide) 70.dp else 48.dp), position = nPos,
                window = next.mixIn to next.mixIn + mix.transitionLength, lowAlpha = if (p == null || p < 0.5) 0.2f else 1f,
            )
            val s = com.harvey.blend.setmaker.SetMaker.score(cur, next)
            Text(s.describe(cur, next), style = Type.num.copy(fontSize = Type.caption.fontSize), color = if (s.risky) Blend.Yellow else Blend.Green)
        }
        Text("Tap the top waveform to jump. The bass of the next track comes in halfway through the mix.", style = Type.caption, color = Blend.Dim)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp)).background(if (i == selected) Color.White else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (i == selected) Blend.Bg else Color.White, maxLines = 1) }
        }
    }
}

@Composable
private fun Controls(mix: MixState, prev: () -> Unit, toggle: () -> Unit, next: () -> Unit, mixNow: () -> Unit, reshuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Rounded.Shuffle, "Reshuffle story", tint = Blend.Orange, onClick = reshuffle)
        RoundIconButton(Icons.Rounded.SkipPrevious, "Previous", onClick = prev)
        Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White).clickable(onClick = toggle), contentAlignment = Alignment.Center) {
            Icon(if (mix.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (mix.playing) "Pause" else "Play", Modifier.size(32.dp), tint = Blend.Bg)
        }
        RoundIconButton(Icons.Rounded.SkipNext, "Skip to next (cut)", onClick = next)
        RoundIconButton(Icons.Rounded.Waves, "Mix now", tint = Blend.Orange, onClick = mixNow)
    }
}

/** Compact player shown above the bottom bar on every screen. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val repo = LocalContext.current.repo
    val mix by repo.mix.collectAsState()
    val queue by repo.queue.collectAsState()
    val tracks by repo.tracks.collectAsState()
    val cur = queue.currentId?.let { tracks[it] } ?: return
    val next = (mix.incomingId ?: queue.nextId)?.let { tracks[it] }
    val pos = if (mix.masterId == cur.id) mix.masterPos else 0.0
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Blend.Raised).clickable(onClick = onOpen)) {
        Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Cover(cur, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(cur.title, style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    queue.preparing -> "Loading…"
                    mix.transitionProgress != null -> "Mixing into ${next?.title ?: "next"} · ${((mix.transitionProgress ?: 0.0) * 100).toInt()}%"
                    next != null && mix.transitionStart != null -> "→ ${next.title} in ${formatTime((mix.transitionStart ?: 0.0) - pos)} · ${cur.key?.camelot ?: "?"}→${next.key?.camelot ?: "?"}"
                    else -> cur.artist
                }
                Text(sub, style = Type.caption, color = Blend.Orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundIconButton(Icons.Rounded.Waves, "Mix now", size = 40.dp, tint = Blend.Orange) { repo.mixNow() }
            RoundIconButton(if (mix.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (mix.playing) "Pause" else "Play", size = 40.dp) { repo.togglePlay() }
        }
        val dur = cur.duration.takeIf { it > 0 } ?: mix.masterDuration.takeIf { it > 0 } ?: 1.0
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 5.dp).height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth((pos / dur).toFloat().coerceIn(0f, 1f)).height(2.dp).background(Color.White))
        }
        Spacer(Modifier.width(1.dp))
    }
}
