package `in`.arasan.xthink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One destination on the home page. */
data class HomeCard(val id: String, val title: String, val line: String, val tint: Color, val glyph: String)

val HOME_CARDS: List<HomeCard> = listOf(
    HomeCard("CAMERA", "Camera", "Guided shots, portraits, scenes, video.", Palette.Mint, "camera"),
    HomeCard("VOICE", "Voice", "Talk with the phone, in your language.", Palette.Peach, "voice"),
    HomeCard("STEVE", "Steve", "Say it. Your Mac does it.", Palette.Lavender, "mac"),
    HomeCard("TRANSLATE", "Translate", "Any script in view, into English.", Palette.Peach, "translate"),
    HomeCard("SCAN", "Scan", "A page to clean, copyable text.", Palette.Sky, "scan"),
    HomeCard("FIT", "Fit", "Squats and push-ups, counted.", Palette.Mint, "fit"),
)

/**
 * xThink's front door. The camera is one room in it now, not the whole
 * house: pick where to go. Everything below runs on the phone.
 */
@Composable
fun HomeScreen(status: String, onOpen: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Palette.Ground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Palette.Gutter)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) { Text(text = "Q", color = Palette.Accent, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(text = "xThink", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = "iQOO 15  ·  on-device", color = Palette.InkMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Ordinary photos,\nmade extraordinary.",
                color = Palette.Ink,
                fontFamily = Palette.Display,
                fontSize = 34.sp,
                lineHeight = 40.sp,
            )
            Text(
                text = status,
                color = Palette.InkMuted,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
            )
            // The big card: the camera.
            val cam = HOME_CARDS.first()
            GlassCard(cam, tall = true, onClick = { onOpen(cam.id) })
            // The rest, two up.
            HOME_CARDS.drop(1).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { c -> GlassCard(c, tall = false, onClick = { onOpen(c.id) }, modifier = Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GlassCard(card: HomeCard, tall: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Palette.Corner))
            .background(Palette.Glass)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(if (tall) 14.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(if (tall) 56.dp else 48.dp)
                    .clip(RoundedCornerShape(Palette.CornerSmall))
                    .background(Brush.linearGradient(listOf(card.tint, Color.White))),
                contentAlignment = Alignment.Center,
            ) { Glyph(card.glyph, Palette.Ink, if (tall) 26.dp else 22.dp) }
            Spacer(Modifier.weight(1f))
            Canvas(modifier = Modifier.size(14.dp)) {
                val w = size.width; val h = size.height; val st = 1.6f.dp.toPx()
                drawLine(Palette.InkMuted, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.85f, h * 0.15f), st, StrokeCap.Round)
                drawLine(Palette.InkMuted, Offset(w * 0.35f, h * 0.15f), Offset(w * 0.85f, h * 0.15f), st, StrokeCap.Round)
                drawLine(Palette.InkMuted, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.85f, h * 0.65f), st, StrokeCap.Round)
            }
        }
        if (tall) Spacer(Modifier.height(18.dp))
        Text(text = card.title, color = Palette.Ink, fontSize = if (tall) 24.sp else 18.sp, fontWeight = FontWeight.SemiBold)
        Text(text = card.line, color = Palette.InkMuted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** Small line glyphs, drawn - no icon font, no assets. */
@Composable
fun Glyph(name: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val st = 1.7f.dp.toPx()
        when (name) {
            "camera" -> {
                drawRoundRect(color, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.6f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(st))
                drawLine(color, Offset(w * 0.32f, h * 0.28f), Offset(w * 0.4f, h * 0.14f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.4f, h * 0.14f), Offset(w * 0.6f, h * 0.14f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.6f, h * 0.14f), Offset(w * 0.68f, h * 0.28f), st, StrokeCap.Round)
                drawCircle(color, w * 0.16f, Offset(w * 0.5f, h * 0.58f), style = Stroke(st))
            }
            "mac" -> {
                drawRoundRect(color, Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.52f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(st))
                drawLine(color, Offset(w * 0.0f, h * 0.82f), Offset(w * 1f, h * 0.82f), st, StrokeCap.Round)
            }
            "translate" -> {
                drawLine(color, Offset(w * 0.1f, h * 0.3f), Offset(w * 0.55f, h * 0.3f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.18f), Offset(w * 0.32f, h * 0.3f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.47f, h * 0.3f), Offset(w * 0.18f, h * 0.68f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.3f), Offset(w * 0.45f, h * 0.62f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.55f, h * 0.9f), Offset(w * 0.72f, h * 0.5f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.72f, h * 0.5f), Offset(w * 0.89f, h * 0.9f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.61f, h * 0.76f), Offset(w * 0.83f, h * 0.76f), st, StrokeCap.Round)
            }
            "scan" -> {
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(color, Offset(x, y), Offset(x + dx, y), st, StrokeCap.Round)
                    drawLine(color, Offset(x, y), Offset(x, y + dy), st, StrokeCap.Round)
                }
                corner(w * 0.1f, h * 0.1f, w * 0.25f, h * 0.25f); corner(w * 0.9f, h * 0.1f, -w * 0.25f, h * 0.25f)
                corner(w * 0.1f, h * 0.9f, w * 0.25f, -h * 0.25f); corner(w * 0.9f, h * 0.9f, -w * 0.25f, -h * 0.25f)
                drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), st, StrokeCap.Round)
            }
            "fit" -> {
                drawLine(color, Offset(w * 0.12f, h * 0.5f), Offset(w * 0.88f, h * 0.5f), st, StrokeCap.Round)
                drawRoundRect(color, Offset(w * 0.12f, h * 0.3f), Size(w * 0.14f, h * 0.4f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f), style = Stroke(st))
                drawRoundRect(color, Offset(w * 0.74f, h * 0.3f), Size(w * 0.14f, h * 0.4f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f), style = Stroke(st))
            }
            "voice" -> {
                drawRoundRect(color, Offset(w * 0.36f, h * 0.08f), Size(w * 0.28f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.14f), style = Stroke(st))
                drawLine(color, Offset(w * 0.22f, h * 0.45f), Offset(w * 0.22f, h * 0.5f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.9f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.9f), Offset(w * 0.68f, h * 0.9f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), st, StrokeCap.Round)
            }
            "home" -> {
                drawLine(color, Offset(w * 0.1f, h * 0.5f), Offset(w * 0.5f, h * 0.14f), st, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.9f, h * 0.5f), st, StrokeCap.Round)
                drawRoundRect(color, Offset(w * 0.22f, h * 0.48f), Size(w * 0.56f, h * 0.42f), androidx.compose.ui.geometry.CornerRadius(w * 0.05f), style = Stroke(st))
            }
        }
    }
}
