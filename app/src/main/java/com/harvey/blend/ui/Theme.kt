package com.harvey.blend.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.harvey.blend.R
import com.harvey.blend.analysis.MusicalKey

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/** Numbers (BPM, times, keys) — same family as the Helio app. */
val InterTight = FontFamily(
    Font(R.font.intertight_semibold, FontWeight.SemiBold),
    Font(R.font.intertight_bold, FontWeight.Bold),
)

object Blend {
    val Bg = Color(0xFF121212)
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF181818)
    val Card = Color(0xFF1F1F1F)
    val Raised = Color(0xFF2E2E2E)
    val Text = Color(0xFFFFFFFF)
    val Sub = Color(0xFFB3B3B3)
    val Dim = Color(0xFF8E8E8E)
    val Line = Color(0x1AFFFFFF)
    val Orange = Color(0xFFF0953A)
    val Blue = Color(0xFF4FB4FF)
    val Green = Color(0xFF2FD566)
    val Yellow = Color(0xFFF2D22E)
    val Comment = Color(0xFFF2C49B)
    val Glass = Color(0x12FFFFFF)
    val GlassBorder = Color(0x24FFFFFF)
}

/** Each Camelot number gets its own hue, so harmonic neighbours look alike. */
fun keyColor(key: MusicalKey?): Color =
    if (key == null) Blend.Dim else Color.hsl(((key.camelotNumber - 1) * 30f) % 360f, 0.72f, 0.64f)

private fun style(size: Int, weight: FontWeight, family: FontFamily = Inter, spacing: Float = 0f) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, letterSpacing = spacing.sp)

object Type {
    val hero = style(30, FontWeight.ExtraBold, spacing = -0.9f)
    val title = style(24, FontWeight.ExtraBold, spacing = -0.6f)
    val heading = style(18, FontWeight.Bold, spacing = -0.2f)
    val body = style(15, FontWeight.Medium)
    val bodySmall = style(13, FontWeight.Normal)
    val caption = style(12, FontWeight.Normal)
    val label = style(11, FontWeight.SemiBold, spacing = 0.9f)
    val num = style(14, FontWeight.SemiBold, InterTight)
    val numBig = style(28, FontWeight.Bold, InterTight, -0.4f)
}

@Composable
fun BlendTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blend.Orange, onPrimary = Blend.Bg, secondary = Blend.Blue,
            background = Blend.Bg, surface = Blend.Surface, onSurface = Blend.Text, onBackground = Blend.Text,
            surfaceVariant = Blend.Card, onSurfaceVariant = Blend.Sub, outline = Blend.GlassBorder,
            surfaceContainer = Blend.Card, surfaceContainerHigh = Blend.Raised, surfaceContainerHighest = Blend.Raised,
        ),
        typography = Typography(
            bodyLarge = Type.body, bodyMedium = Type.bodySmall, bodySmall = Type.caption,
            titleLarge = Type.title, titleMedium = Type.heading, labelLarge = Type.body.copy(fontWeight = FontWeight.SemiBold),
            labelMedium = Type.label, headlineSmall = Type.title,
        ),
        content = content,
    )
}
