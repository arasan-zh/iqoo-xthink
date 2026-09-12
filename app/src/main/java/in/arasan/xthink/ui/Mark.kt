package `in`.arasan.xthink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The mark: three strokes chasing each other round a centre - the logo,
 * drawn rather than shipped, so it takes any colour and any size. One
 * stroke is a hook that curls in; rotated a third of a turn twice, the
 * three lock into the whirl.
 */
@Composable
fun Mark(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val st = w * 0.11f
        val hook = Path().apply {
            moveTo(w * 0.50f, w * 0.14f)
            cubicTo(w * 0.78f, w * 0.14f, w * 0.90f, w * 0.40f, w * 0.74f, w * 0.56f)
            cubicTo(w * 0.66f, w * 0.64f, w * 0.54f, w * 0.62f, w * 0.52f, w * 0.52f)
        }
        for (k in 0 until 3) {
            rotate(degrees = k * 120f, pivot = Offset(w / 2f, w / 2f)) {
                drawPath(hook, color, style = Stroke(width = st, cap = StrokeCap.Round))
            }
        }
    }
}

/** The mic of the reference: a gradient disc with a soft glow. */
@Composable
fun GlowMic(size: Dp, active: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size + 28.dp)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = size.toPx() / 2f
        // glow
        for (i in 1..4) drawCircle(Color(0xFFB36BFF).copy(alpha = (if (active) 0.16f else 0.08f) / i), r + i * 7.dp.toPx(), c)
        drawCircle(
            androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFF43F5E)), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)),
            r, c,
        )
        // the mic
        val st = 2.2f.dp.toPx()
        val mw = r * 0.34f; val mh = r * 0.62f
        drawRoundRect(Color.White, Offset(c.x - mw / 2f, c.y - mh * 0.7f), Size(mw, mh), androidx.compose.ui.geometry.CornerRadius(mw / 2f), style = Stroke(st))
        drawArc(Color.White, 0f, 180f, false, Offset(c.x - mw * 0.9f, c.y - mh * 0.45f), Size(mw * 1.8f, mh * 0.9f), style = Stroke(st, cap = StrokeCap.Round))
        drawLine(Color.White, Offset(c.x, c.y + mh * 0.0f), Offset(c.x, c.y + mh * 0.35f), st, StrokeCap.Round)
        drawLine(Color.White, Offset(c.x - mw * 0.5f, c.y + mh * 0.35f), Offset(c.x + mw * 0.5f, c.y + mh * 0.35f), st, StrokeCap.Round)
    }
}
