package `in`.arasan.xthink.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which of the four status cells this is - each gets its own glyph. */
enum class StatusKind { FOCUS, LIGHTING, STABILITY, COMPOSITION }

/**
 * The four-up status strip: Focus, Lighting, Stability, Composition.
 *
 * Every value here is measured, not decorative. Focus comes from
 * CONTROL_AF_STATE, lighting from the ISP's ISO and exposure, stability from
 * the smoothed angular rate, composition from the engine's deadzones.
 */
@Composable
fun StatusStrip(state: OverlayState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.Chip)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusCell(StatusKind.FOCUS, state.focus, Modifier.weight(1f))
        Divider()
        StatusCell(StatusKind.LIGHTING, state.lighting, Modifier.weight(1f))
        Divider()
        StatusCell(StatusKind.STABILITY, state.stability, Modifier.weight(1f))
        Divider()
        StatusCell(StatusKind.COMPOSITION, state.composition, Modifier.weight(1f))
    }
}

/**
 * The status, standing where the mode rail used to: Lighting, Stability,
 * Composition, one above the other. Focus is not shown - the phone's AF
 * is continuous and a tap sets it; a cell for it said "Good" all day.
 */
@Composable
fun StatusRail(state: OverlayState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(XT.Chip)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusIcon(StatusKind.LIGHTING, state.lighting)
        StatusIcon(StatusKind.STABILITY, state.stability)
        StatusIcon(StatusKind.COMPOSITION, state.composition)
    }
}

/** One glyph, tinted by its state, with the tick/ring badge at its corner. No words. */
@Composable
private fun StatusIcon(kind: StatusKind, value: StatusValue) {
    val tint by animateColorAsState(
        targetValue = XT.state(value.ok),
        animationSpec = tween(240),
        label = "statusIconTint",
    )
    Box(modifier = Modifier.size(22.dp)) {
        Canvas(modifier = Modifier.size(18.dp).align(Alignment.Center)) { drawStatusGlyph(kind, tint) }
        Canvas(modifier = Modifier.size(8.dp).align(Alignment.BottomEnd)) { drawBadge(tint, value.ok) }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(Color.White.copy(alpha = 0.14f)),
    )
}

@Composable
private fun StatusCell(kind: StatusKind, value: StatusValue, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(
        targetValue = XT.state(value.ok),
        animationSpec = tween(240),
        label = "statusTint",
    )
    // Icon above label, not beside it - the label gets the full cell width to
    // itself this way. "Composition" is the longest of the four and was
    // wrapping onto two lines when it had to share a row with the icon.
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(15.dp)) { drawStatusGlyph(kind, XT.OnChip.copy(alpha = 0.85f)) }
        Row(
            modifier = Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.label,
                color = XT.OnChip,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            Canvas(modifier = Modifier.size(10.dp).padding(start = 2.dp)) {
                drawBadge(tint, value.ok)
            }
        }
        Text(text = value.value, color = tint, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

/** A tick when on target, a hollow ring while it is still settling. */
private fun DrawScope.drawBadge(tint: Color, ok: Boolean) {
    val c = size.minDimension / 2f
    if (!ok) {
        drawCircle(tint.copy(alpha = 0.8f), c * 0.8f, Offset(c, c), style = Stroke(1.2f.dp.toPx()))
        return
    }
    drawCircle(tint, c, Offset(c, c))
    val s = c * 0.5f
    drawLine(Color.Black, Offset(c - s * 0.8f, c), Offset(c - s * 0.1f, c + s * 0.6f), 1.3f.dp.toPx(), StrokeCap.Round)
    drawLine(Color.Black, Offset(c - s * 0.1f, c + s * 0.6f), Offset(c + s * 0.85f, c - s * 0.6f), 1.3f.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawStatusGlyph(kind: StatusKind, color: Color) {
    val c = size.minDimension / 2f
    val r = c * 0.82f
    val stroke = 1.5f.dp.toPx()
    when (kind) {
        // A focus reticle.
        StatusKind.FOCUS -> {
            drawCircle(color, r * 0.42f, Offset(c, c), style = Stroke(stroke))
            listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                val x = c + sx * r
                val y = c + sy * r
                drawLine(color, Offset(x, y), Offset(x - sx * r * 0.45f, y), stroke, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y - sy * r * 0.45f), stroke, StrokeCap.Round)
            }
        }
        // A sun.
        StatusKind.LIGHTING -> {
            drawCircle(color, r * 0.42f, Offset(c, c), style = Stroke(stroke))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dx = kotlin.math.cos(a).toFloat()
                val dy = kotlin.math.sin(a).toFloat()
                drawLine(
                    color,
                    Offset(c + dx * r * 0.62f, c + dy * r * 0.62f),
                    Offset(c + dx * r, c + dy * r),
                    stroke,
                    StrokeCap.Round,
                )
            }
        }
        // A phone outline.
        StatusKind.STABILITY -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(c - r * 0.52f, c - r),
                size = Size(r * 1.04f, r * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.28f),
                style = Stroke(stroke),
            )
        }
        // A rule-of-thirds grid.
        StatusKind.COMPOSITION -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(c - r, c - r),
                size = Size(r * 2f, r * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.22f),
                style = Stroke(stroke),
            )
            val t = r * 2f / 3f
            drawLine(color, Offset(c - r + t, c - r), Offset(c - r + t, c + r), stroke * 0.8f)
            drawLine(color, Offset(c - r + 2 * t, c - r), Offset(c - r + 2 * t, c + r), stroke * 0.8f)
            drawLine(color, Offset(c - r, c - r + t), Offset(c + r, c - r + t), stroke * 0.8f)
            drawLine(color, Offset(c - r, c - r + 2 * t), Offset(c + r, c - r + 2 * t), stroke * 0.8f)
        }
    }
}
