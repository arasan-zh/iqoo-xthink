package `in`.arasan.xthink.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Zoom slider - BUILD, and live. The stops are the ones this phone can
// actually reach: the probe measured CONTROL_ZOOM_RATIO_RANGE at 1.00x..10.00x,
// so the reference mockup's 0.6x stop is not drawn. It would need the
// ultrawide, which vivo does not expose to a third-party app.
// ---------------------------------------------------------------------------

private val ZOOM_STOPS = listOf(1f, 2f, 3f, 10f)

@Composable
fun ZoomSlider(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.Chip)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZOOM_STOPS.forEachIndexed { index, stop ->
            val reachable = stop <= maxZoomRatio + 0.01f
            val active = abs(zoomRatio - stop) < 0.25f
            ZoomStop(stop, active, reachable) { if (reachable) onZoomSelected(stop) }
            if (index != ZOOM_STOPS.lastIndex) {
                Text(
                    text = "···",
                    color = XT.OnChipMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomStop(stop: Float, active: Boolean, reachable: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = when {
            !reachable -> XT.OnChipMuted.copy(alpha = 0.3f)
            active -> Color.Black
            else -> XT.OnChip
        },
        animationSpec = tween(180),
        label = "zoomTint",
    )
    val label = if (stop == 1f) "1x" else stop.toInt().toString()
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (active) XT.Gold else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = reachable,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// Mode tabs - only Portrait exists. Nothing else is drawn, because a tab that
// is visible but inert reads as broken rather than unfinished.
// ---------------------------------------------------------------------------

@Composable
fun ModeTabs(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "PORTRAIT",
            color = XT.Gold,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(2.dp)
                .clip(CircleShape)
                .background(XT.Gold),
        )
    }
}

// ---------------------------------------------------------------------------
// Bottom bar. The shutter is live: a tap captures, and the engine's lock
// captures by itself. The gallery button shows the last shot.
// ---------------------------------------------------------------------------

@Composable
fun BottomBar(
    locked: Boolean,
    thumbnail: ImageBitmap?,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Gallery: the most recent capture, or a placeholder until there is one.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(XT.CornerSmall))
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "Last photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp),
                )
            } else Canvas(modifier = Modifier.size(20.dp)) {
                drawRoundRect(
                    color = XT.Inert,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    style = Stroke(1.5f.dp.toPx()),
                )
                drawCircle(XT.Inert, size.minDimension * 0.12f, Offset(size.width * 0.33f, size.height * 0.34f))
                drawLine(
                    XT.Inert,
                    Offset(size.width * 0.16f, size.height * 0.78f),
                    Offset(size.width * 0.5f, size.height * 0.42f),
                    1.5f.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }

        Shutter(locked = locked, onClick = onShutter)

        // Flip. Rear only in this build, so it is deliberately inert.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val c = size.minDimension / 2f
                val r = c * 0.68f
                drawArc(
                    color = XT.Inert,
                    startAngle = 20f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(c - r, c - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(1.8f.dp.toPx(), cap = StrokeCap.Round),
                )
                val tip = Offset(c + r * 0.94f, c + r * 0.34f)
                drawLine(XT.Inert, tip, Offset(tip.x - r * 0.45f, tip.y - r * 0.1f), 1.8f.dp.toPx(), StrokeCap.Round)
                drawLine(XT.Inert, tip, Offset(tip.x - r * 0.05f, tip.y + r * 0.45f), 1.8f.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/**
 * The shutter. Its ring turns green when the engine reaches LOCKED, so the
 * moment to press is visible without reading the card.
 */
@Composable
private fun Shutter(locked: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(
        targetValue = if (locked) XT.Green else Color.White,
        animationSpec = tween(260),
        label = "shutterRing",
    )
    val inner by animateColorAsState(
        targetValue = if (locked) XT.Green else XT.Gold,
        animationSpec = tween(260),
        label = "shutterInner",
    )
    Canvas(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val c = size.minDimension / 2f
        drawCircle(ring, c - 2.dp.toPx(), Offset(c, c), style = Stroke(3.dp.toPx()))
        drawCircle(inner.copy(alpha = 0.9f), c - 10.dp.toPx(), Offset(c, c), style = Stroke(3.dp.toPx()))
        if (locked) drawCircle(XT.Green.copy(alpha = 0.12f), c - 11.dp.toPx(), Offset(c, c))
    }
}

// ---------------------------------------------------------------------------
// FAKE chrome. Static, non-interactive, present so the app reads as a stock
// camera rather than a demo harness. CLAUDE.md lists exactly these as fakes.
// ---------------------------------------------------------------------------

@Composable
fun TopIconRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(6) { i ->
            Canvas(modifier = Modifier.size(21.dp)) { drawTopIcon(i, XT.Inert) }
        }
    }
}

private fun DrawScope.drawTopIcon(index: Int, color: Color) {
    val c = size.minDimension / 2f
    val r = c * 0.78f
    val stroke = 1.6f.dp.toPx()
    when (index) {
        0 -> { // scan / AI focus
            drawCircle(color, r * 0.34f, Offset(c, c))
            listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                val x = c + sx * r
                val y = c + sy * r
                drawLine(color, Offset(x, y), Offset(x - sx * r * 0.45f, y), stroke, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y - sy * r * 0.45f), stroke, StrokeCap.Round)
            }
        }
        1 -> { // flash off
            drawLine(color, Offset(c - r * 0.3f, c - r), Offset(c + r * 0.25f, c - r * 0.1f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c + r * 0.25f, c - r * 0.1f), Offset(c - r * 0.1f, c + r), stroke, StrokeCap.Round)
            drawLine(color, Offset(c - r, c - r), Offset(c + r, c + r), stroke, StrokeCap.Round)
        }
        2 -> { // timer
            drawCircle(color, r * 0.8f, Offset(c, c), style = Stroke(stroke))
            drawLine(color, Offset(c, c), Offset(c, c - r * 0.45f), stroke, StrokeCap.Round)
        }
        3 -> { // AI badge
            drawCircle(color, r * 0.9f, Offset(c, c), style = Stroke(stroke))
            drawCircle(XT.Green, r * 0.26f, Offset(c + r * 0.72f, c - r * 0.72f))
        }
        4 -> { // mic off
            drawRoundRect(
                color = color,
                topLeft = Offset(c - r * 0.34f, c - r * 0.9f),
                size = Size(r * 0.68f, r * 1.15f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.34f),
                style = Stroke(stroke),
            )
            drawLine(color, Offset(c - r, c - r), Offset(c + r, c + r), stroke, StrokeCap.Round)
        }
        else -> { // settings
            drawCircle(color, r * 0.85f, Offset(c, c), style = Stroke(stroke))
            drawCircle(color, r * 0.3f, Offset(c, c), style = Stroke(stroke))
        }
    }
}

private data class RailItem(val label: String, val glyph: Int)

@Composable
fun LeftRail(modifier: Modifier = Modifier) {
    val items = listOf(
        RailItem("Macro", 0),
        RailItem("Wide", 1),
        RailItem("Portrait", 2),
        RailItem("Night", 3),
    )
    Rail(items, activeIndex = 2, modifier = modifier)
}

@Composable
fun RightRail(modifier: Modifier = Modifier) {
    val items = listOf(
        RailItem("Scene", 4),
        RailItem("Lens", 5),
        RailItem("Settings", 6),
    )
    Rail(items, activeIndex = -1, modifier = modifier)
}

@Composable
private fun Rail(items: List<RailItem>, activeIndex: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.Chip)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.forEachIndexed { index, item ->
            val tint = if (index == activeIndex) XT.Green else XT.Inert
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(modifier = Modifier.size(19.dp)) { drawRailGlyph(item.glyph, tint) }
                Text(
                    text = item.label,
                    color = tint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawRailGlyph(index: Int, color: Color) {
    val c = size.minDimension / 2f
    val r = c * 0.8f
    val stroke = 1.6f.dp.toPx()
    when (index) {
        0 -> { // macro - a leaf
            drawArc(color, -30f, 200f, false, Offset(c - r, c - r), Size(r * 2f, r * 2f), style = Stroke(stroke))
            drawLine(color, Offset(c - r * 0.7f, c + r * 0.7f), Offset(c + r * 0.6f, c - r * 0.6f), stroke, StrokeCap.Round)
        }
        1 -> { // wide - two peaks
            drawLine(color, Offset(c - r, c + r * 0.5f), Offset(c - r * 0.25f, c - r * 0.5f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c - r * 0.25f, c - r * 0.5f), Offset(c + r * 0.35f, c + r * 0.5f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c + r * 0.1f, c + r * 0.1f), Offset(c + r * 0.55f, c - r * 0.4f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c + r * 0.55f, c - r * 0.4f), Offset(c + r, c + r * 0.5f), stroke, StrokeCap.Round)
        }
        2 -> { // portrait - a person
            drawCircle(color, r * 0.34f, Offset(c, c - r * 0.42f), style = Stroke(stroke))
            drawArc(color, 200f, 140f, false, Offset(c - r * 0.72f, c + r * 0.1f), Size(r * 1.44f, r * 1.3f), style = Stroke(stroke))
        }
        3 -> { // night - a moon
            drawArc(color, 40f, 280f, false, Offset(c - r, c - r), Size(r * 2f, r * 2f), style = Stroke(stroke))
        }
        4 -> { // scene
            drawRoundRect(color, Offset(c - r, c - r * 0.8f), Size(r * 2f, r * 1.6f), androidx.compose.ui.geometry.CornerRadius(r * 0.25f), style = Stroke(stroke))
            drawCircle(color, r * 0.2f, Offset(c - r * 0.4f, c - r * 0.25f))
        }
        5 -> { // lens
            drawCircle(color, r * 0.9f, Offset(c, c), style = Stroke(stroke))
            drawCircle(color, r * 0.38f, Offset(c, c), style = Stroke(stroke))
        }
        else -> { // sliders
            drawLine(color, Offset(c - r, c - r * 0.5f), Offset(c + r, c - r * 0.5f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c - r, c + r * 0.5f), Offset(c + r, c + r * 0.5f), stroke, StrokeCap.Round)
            drawCircle(color, r * 0.26f, Offset(c + r * 0.35f, c - r * 0.5f))
            drawCircle(color, r * 0.26f, Offset(c - r * 0.35f, c + r * 0.5f))
        }
    }
}
