package com.harvey.blend.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harvey.blend.analysis.MusicalKey
import com.harvey.blend.library.TagReader
import com.harvey.blend.model.Track
import com.harvey.blend.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val artCache = LruCache<String, ImageBitmap>(200)

/** Album art from the file, or a generated cover so every track is recognisable. */
@Composable
fun Cover(track: Track?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 4.dp) {
    val ctx = LocalContext.current
    val artVersion by ctx.repo.artVersion.collectAsState()
    val id = track?.id
    val bmp by produceState<ImageBitmap?>(id?.let { artCache.get(it) }, id, artVersion) {
        if (id == null) { value = null; return@produceState }
        artCache.get(id)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            val f = TagReader.artFile(ctx, id)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { artCache.put(id, it) } else null
        }
    }
    val shape = RoundedCornerShape(corner)
    val b = bmp
    if (b != null) {
        Image(b, null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size).clip(shape).background(generatedCover(track?.id ?: "x")))
    }
}

/** Four covers in a grid, like a playlist mosaic. */
@Composable
fun CollageCover(tracks: List<Track>, size: Dp, modifier: Modifier = Modifier, corner: Dp = 4.dp) {
    if (tracks.size < 4) {
        Cover(tracks.firstOrNull(), size, modifier, corner)
        return
    }
    Box(modifier.size(size).clip(RoundedCornerShape(corner))) {
        val h = size / 2
        Row {
            androidx.compose.foundation.layout.Column {
                Cover(tracks[0], h, corner = 0.dp); Cover(tracks[2], h, corner = 0.dp)
            }
            androidx.compose.foundation.layout.Column {
                Cover(tracks[1], h, corner = 0.dp); Cover(tracks[3], h, corner = 0.dp)
            }
        }
    }
}

fun generatedCover(seed: String): Brush {
    val h = abs(seed.hashCode())
    val hue1 = (h % 360).toFloat()
    val hue2 = ((h / 360) % 360).toFloat()
    return Brush.linearGradient(
        listOf(Color.hsl(hue1, 0.6f, 0.45f), Color.hsl(hue2, 0.55f, 0.2f)),
        start = Offset.Zero, end = Offset(300f, 300f),
    )
}

@Composable
fun KeyChip(key: MusicalKey?, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(if (big) 8.dp else 4.dp))
            .background(keyColor(key))
            .padding(horizontal = if (big) 9.dp else 5.dp, vertical = if (big) 4.dp else 1.dp),
    ) {
        Text(key?.camelot ?: "–", style = Type.num.copy(fontSize = if (big) 16.sp else 11.5.sp, fontWeight = FontWeight.Bold), color = Blend.Bg)
    }
}

@Composable
fun EnergyBars(energy: Int?, modifier: Modifier = Modifier, width: Dp = 6.dp) {
    val lit = ((energy ?: 0) / 2.0).roundToInt()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) Box(
            Modifier.width(width).height(3.dp).clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = if (i <= lit) 1f else 0.2f))
        )
    }
}

@Composable
fun GlassChip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Color.White else Blend.Glass)
            .border(1.dp, if (selected) Color.White else Blend.GlassBorder, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = if (selected) Blend.Bg else Color.White)
        Text(text, style = Type.bodySmall.copy(fontWeight = FontWeight.Medium), color = if (selected) Blend.Bg else Color.White, maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Blend.Sub) {
    Text(text.uppercase(), style = Type.label, color = color, modifier = modifier)
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, size: Dp = 44.dp, background: Color = Color.Transparent, tint: Color = Color.White, iconSize: Dp = 24.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(iconSize), tint = tint) }
}

/**
 * Overview waveform: mids/highs in white (blue before the mix-in, orange after the mix-out),
 * with the two cue lines.
 */
@Composable
fun MiniWave(track: Track, modifier: Modifier = Modifier, bars: Int = 46, showCues: Boolean = true) {
    val a = track.analysis
    Canvas(modifier) {
        if (a == null || a.duration <= 0) {
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
            return@Canvas
        }
        val inF = (track.mixIn / a.duration).toFloat()
        val outF = (track.mixOut / a.duration).toFloat()
        val slot = size.width / bars
        val bw = (slot * 0.62f).coerceAtLeast(1.5f)
        for (i in 0 until bars) {
            val s = i * a.waveHigh.size / bars
            val e = ((i + 1) * a.waveHigh.size / bars).coerceAtLeast(s + 1)
            var v = 0f
            for (j in s until e) v = maxOf(v, maxOf(a.waveHigh[j].toInt() and 0xff, a.waveLow[j].toInt() and 0xff) / 255f)
            val h = (v * size.height).coerceAtLeast(2f)
            val f = i.toFloat() / bars
            val c = when {
                f < inF -> Blend.Blue
                f > outF -> Blend.Orange
                else -> Color.White.copy(alpha = 0.45f)
            }
            drawRoundRect(c, Offset(i * slot, (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2))
        }
        if (showCues) {
            drawLine(Blend.Green, Offset(inF * size.width, 0f), Offset(inF * size.width, size.height), 2.5f)
            val outColor = if (track.mixOutNeedsCheck) Blend.Yellow else Blend.Orange
            drawLine(outColor, Offset(outF * size.width, 0f), Offset(outF * size.width, size.height), 2.5f)
        }
    }
}

/**
 * Full waveform with lows (orange, bottom) and mids/highs (blue, top), a playhead and an optional
 * highlighted window (the transition).
 */
@Composable
fun BandWave(
    track: Track?,
    modifier: Modifier = Modifier,
    position: Double? = null,
    window: Pair<Double, Double>? = null,
    dimBefore: Double? = null,
    lowAlpha: Float = 1f,
) {
    val a = track?.analysis
    Canvas(modifier) {
        if (a == null || a.duration <= 0) {
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
            return@Canvas
        }
        val n = a.waveHigh.size
        val mid = size.height * 0.56f
        val step = size.width / n
        val bw = (step * 0.7f).coerceAtLeast(1f)
        window?.let { (s, e) ->
            val x0 = (s / a.duration * size.width).toFloat()
            val x1 = (e / a.duration * size.width).toFloat()
            drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(12f))
        }
        val dimX = dimBefore?.let { (it / a.duration * size.width).toFloat() } ?: -1f
        for (i in 0 until n) {
            val x = i * step
            val alpha = if (x < dimX) 0.4f else 1f
            val hh = (a.waveHigh[i].toInt() and 0xff) / 255f * mid
            val lh = (a.waveLow[i].toInt() and 0xff) / 255f * (size.height - mid)
            drawRect(Blend.Blue.copy(alpha = alpha), Offset(x, mid - hh), Size(bw, hh))
            drawRect(Blend.Orange.copy(alpha = alpha * lowAlpha), Offset(x, mid + 1f), Size(bw, lh))
        }
        position?.let {
            val x = (it / a.duration * size.width).toFloat()
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3f)
        }
    }
}

/** Energy across the set with each track's key under its dot. */
@Composable
fun StoryArc(tracks: List<Track>, currentId: String?, modifier: Modifier = Modifier, showKeys: Boolean = true) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        if (tracks.isEmpty()) return@Canvas
        val labelH = if (showKeys) 16.dp.toPx() else 0f
        val top = 8.dp.toPx()
        val bottom = size.height - labelH - 4.dp.toPx()
        val pad = 10.dp.toPx()
        val w = size.width - 2 * pad
        fun x(i: Int) = if (tracks.size == 1) size.width / 2 else pad + i * w / (tracks.size - 1)
        fun y(e: Int?) = bottom - ((e ?: 5) - 1) / 9f * (bottom - top)
        val path = Path()
        tracks.forEachIndexed { i, t -> if (i == 0) path.moveTo(x(i), y(t.energy)) else path.lineTo(x(i), y(t.energy)) }
        val fill = Path().apply {
            addPath(path); lineTo(x(tracks.size - 1), bottom); lineTo(x(0), bottom); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(Blend.Orange.copy(alpha = 0.35f), Blend.Orange.copy(alpha = 0f)), top, bottom))
        drawPath(path, Blend.Orange, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
        val r = if (tracks.size > 40) 2.5.dp.toPx() else 5.dp.toPx()
        val showLabel = showKeys && tracks.size <= 24
        tracks.forEachIndexed { i, t ->
            val c = Offset(x(i), y(t.energy))
            val kc = keyColor(t.key)
            drawCircle(if (t.id == currentId) Color.White else Blend.Bg, r, c)
            drawCircle(kc, r, c, style = Stroke(2.dp.toPx()))
            if (showLabel) {
                val label = measurer.measure(t.key?.camelot ?: "–", TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = kc))
                drawText(label, topLeft = Offset(c.x - label.size.width / 2f, size.height - labelH))
            }
        }
    }
}

/** "3:21" */
fun formatTime(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatDuration(sec: Double): String {
    val m = (sec / 60).roundToInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
}

@Composable
fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    androidx.compose.foundation.layout.Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = Type.heading, color = Color.White)
        Text(body, style = Type.bodySmall, color = Blend.Sub, maxLines = 4, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Blend.Orange, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(color).clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(fontWeight = FontWeight.Bold), color = Blend.Bg) }
}

@Composable
fun FillBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
