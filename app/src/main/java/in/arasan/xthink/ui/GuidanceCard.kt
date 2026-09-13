package `in`.arasan.xthink.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.arasan.xthink.guidance.CoachMode
import `in`.arasan.xthink.guidance.Instruction
import `in`.arasan.xthink.guidance.Magnitude
import `in`.arasan.xthink.guidance.Verb

/**
 * The guidance card from the overlay reference: a label, the one instruction,
 * and a caption telling you what happens next.
 *
 * Exactly one instruction, always - the engine guarantees that, and this draws
 * whatever it was given without ever composing two.
 */
@Composable
fun GuidanceCard(state: OverlayState, modifier: Modifier = Modifier) {
    val accent by animateColorAsState(
        targetValue = when {
            state.isLocked -> XT.Green
            state.isSeeking -> XT.OnChipMuted
            else -> XT.Amber
        },
        animationSpec = tween(240),
        label = "cardAccent",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (state.isLocked) 1f else 0.8f,
        animationSpec = tween(220),
        label = "check",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.Chip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(XT.CornerSmall))
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            VerbGlyph(state.verb, accent, Modifier.size(20.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "xThink Guidance",
                color = XT.OnChipMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = state.instruction?.text ?: "Getting ready",
                color = XT.OnChip,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = captionFor(state.instruction, state.mode),
                color = XT.OnChipMuted,
                fontSize = 12.sp,
            )
        }

        // The lock badge. Filled and green only when the shot is actually
        // ready; a hollow ring the rest of the time, so it reads as a target
        // to reach rather than a button to press.
        Box(
            modifier = Modifier.size(46.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(46.dp)) {
                val r = size.minDimension / 2f - 3.dp.toPx()
                val c = size.minDimension / 2f
                if (state.isLocked) {
                    drawCircle(XT.Green.copy(alpha = 0.22f), r, Offset(c, c))
                }
                drawCircle(
                    color = accent.copy(alpha = if (state.isLocked) 1f else 0.45f),
                    radius = r,
                    center = Offset(c, c),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val s = r * 0.52f * checkScale
                val tick = accent.copy(alpha = if (state.isLocked) 1f else 0.5f)
                drawLine(
                    tick,
                    Offset(c - s * 0.75f, c),
                    Offset(c - s * 0.15f, c + s * 0.6f),
                    2.5f.dp.toPx(),
                    StrokeCap.Round,
                )
                drawLine(
                    tick,
                    Offset(c - s * 0.15f, c + s * 0.6f),
                    Offset(c + s * 0.8f, c - s * 0.55f),
                    2.5f.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * A directional glyph for the verb. Arrows for movement, a rotation arc for
 * levelling, a frame for the terminal states.
 */
@Composable
private fun VerbGlyph(verb: Verb?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = size.minDimension / 2f
        val r = c * 0.78f
        val stroke = 2.dp.toPx()

        fun arrow(dx: Float, dy: Float) {
            val tipX = c + dx * r
            val tipY = c + dy * r
            drawLine(color, Offset(c - dx * r, c - dy * r), Offset(tipX, tipY), stroke, StrokeCap.Round)
            // Head: two short strokes back from the tip, perpendicular-ish.
            val bx = -dx * r * 0.45f
            val by = -dy * r * 0.45f
            drawLine(color, Offset(tipX, tipY), Offset(tipX + bx - dy * r * 0.35f, tipY + by + dx * r * 0.35f), stroke, StrokeCap.Round)
            drawLine(color, Offset(tipX, tipY), Offset(tipX + bx + dy * r * 0.35f, tipY + by - dx * r * 0.35f), stroke, StrokeCap.Round)
        }

        when (verb) {
            Verb.MOVE_LEFT -> arrow(-1f, 0f)
            Verb.MOVE_RIGHT -> arrow(1f, 0f)
            Verb.MOVE_UP, Verb.TILT_UP -> arrow(0f, -1f)
            Verb.MOVE_DOWN, Verb.TILT_DOWN -> arrow(0f, 1f)
            Verb.STEP_CLOSER, Verb.ZOOM_IN -> {
                drawCircle(color, r * 0.7f, Offset(c, c), style = Stroke(stroke))
                drawLine(color, Offset(c - r * 0.35f, c), Offset(c + r * 0.35f, c), stroke, StrokeCap.Round)
                drawLine(color, Offset(c, c - r * 0.35f), Offset(c, c + r * 0.35f), stroke, StrokeCap.Round)
            }
            Verb.STEP_BACK, Verb.ZOOM_OUT -> {
                drawCircle(color, r * 0.7f, Offset(c, c), style = Stroke(stroke))
                drawLine(color, Offset(c - r * 0.35f, c), Offset(c + r * 0.35f, c), stroke, StrokeCap.Round)
            }
            Verb.LEVEL_CW, Verb.LEVEL_CCW -> {
                val sweep = if (verb == Verb.LEVEL_CW) -220f else 220f
                drawArc(
                    color = color,
                    startAngle = if (verb == Verb.LEVEL_CW) 200f else -20f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(c - r * 0.75f, c - r * 0.75f),
                    size = androidx.compose.ui.geometry.Size(r * 1.5f, r * 1.5f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            else -> {
                // Terminal states - focus, hold, seeking, locked - get the
                // reticle, matching the brackets on the preview.
                val a = r * 0.8f
                val arm = a * 0.5f
                listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                    val x = c + sx * a
                    val y = c + sy * a
                    drawLine(color, Offset(x, y), Offset(x - sx * arm, y), stroke, StrokeCap.Round)
                    drawLine(color, Offset(x, y), Offset(x, y - sy * arm), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

/** The second line: what to expect, not a restatement of the instruction. */
private fun captionFor(instruction: Instruction?, mode: CoachMode): String = when (instruction?.verb) {
    null -> "Point the camera at your subject."
    Verb.LOCKED -> "Hold steady and capture."
    // The noun depends on what the mode is looking for.
    Verb.SEEKING -> when (mode) {
        CoachMode.OBJECT -> "Point at the thing you want to frame."
        CoachMode.WIDE -> "People, places, rooms \u2014 anything but a portrait."
        CoachMode.PORTRAIT -> "Find a face to start guidance."
        CoachMode.CREATIVE -> "Compose freely." // the card is not drawn in this mode
    }
    Verb.HOLD_STEADY -> "Almost there, keep still."
    Verb.TAP_FOCUS -> "Tap your subject to focus."
    else -> when (instruction.magnitude) {
        Magnitude.NUDGE -> "Nearly framed."
        Magnitude.MOVE -> "Keep going."
        Magnitude.BIG -> "A fair way to go."
    }
}

/**
 * Gemma on the chosen shot: the style, and the one change that gets it,
 * where the guide's words sit when they are not muted by a style.
 */
@Composable
fun ShotAdviceCard(style: String, text: String?, thinking: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.ChipStrong)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "GEMMA · " + style.replace('_', ' '), color = XT.Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            if (thinking) {
                Spacer(Modifier.width(8.dp))
                Text(text = "looking…", color = XT.OnChipMuted, fontSize = 11.sp)
            }
        }
        Text(
            text = text ?: "Looking through the camera…",
            color = if (text != null) Color.White else XT.OnChipMuted,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
