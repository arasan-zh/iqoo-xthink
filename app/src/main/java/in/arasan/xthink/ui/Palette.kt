package `in`.arasan.xthink.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The one design system for every screen outside the viewfinder: a soft
 * pastel ground, glass cards, ink text, one gold accent. The camera keeps
 * dark glass over its preview (XT), but shares the corners, the type and
 * the accent, so the app reads as one thing.
 */
object Palette {
    val GroundTop = Color(0xFFEFF5F0)
    val GroundMid = Color(0xFFF7EEE3)
    val GroundBottom = Color(0xFFECE8F6)
    val Ground: Brush = Brush.verticalGradient(listOf(GroundTop, GroundMid, GroundBottom))

    /** A light glass card and its hairline. */
    val Glass = Color.White.copy(alpha = 0.62f)
    val GlassStrong = Color.White.copy(alpha = 0.82f)
    val Hairline = Color.White.copy(alpha = 0.9f)

    val Ink = Color(0xFF16171C)
    val InkMuted = Color(0xFF6B6F7A)
    val Accent = Color(0xFFF5A524)
    val AccentSoft = Color(0xFFF5A524).copy(alpha = 0.16f)
    val Mint = Color(0xFFD7EFE3)
    val Peach = Color(0xFFFBE4D0)
    val Lavender = Color(0xFFE4DEF6)
    val Sky = Color(0xFFD9E9F7)

    /** Headlines in a serif, the rest in the system sans - the reference's mix. */
    val Display: FontFamily = FontFamily.Serif
    val Corner = 24.dp
    val CornerSmall = 16.dp
    val Gutter = 20.dp
}
