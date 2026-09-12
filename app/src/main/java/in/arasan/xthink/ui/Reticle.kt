package `in`.arasan.xthink.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/**
 * The alignment layer: corner brackets, a frame that tracks the subject, and a
 * fighter-jet style horizon and pitch ladder.
 *
 * The HUD earns its place rather than occupying it. When the shot is right the
 * screen is a stock camera app with a reticle; the horizon only tilts in once
 * roll leaves its deadzone, the ladder only slides in once pitch does. That
 * keeps CLAUDE.md's "must look like a stock camera app" intact while still
 * giving roll and pitch a visual channel instead of leaving them to the text.
 *
 * Everything here reads from [OverlayState.alignment], which :guidance has been
 * computing since v0.1.
 */
@Composable
fun Reticle(
    state: OverlayState,
    safeCenterYFraction: Float,
    modifier: Modifier = Modifier,
) {

    // Fades, so nothing ever pops. 220ms is quick enough to feel responsive
    // and slow enough not to flicker at a deadzone boundary.
    val horizonAlpha by animateFloatAsState(
        targetValue = if (state.showHorizon) 1f else 0f,
        animationSpec = tween(220),
        label = "horizon",
    )
    val ladderAlpha by animateFloatAsState(
        targetValue = if (state.showPitchLadder) 1f else 0f,
        animationSpec = tween(220),
        label = "ladder",
    )
    val subjectAlpha by animateFloatAsState(
        targetValue = if (state.subject != null) 1f else 0f,
        animationSpec = tween(180),
        label = "subject",
    )
    val lockPulse by animateFloatAsState(
        targetValue = if (state.isLocked) 1f else 0f,
        animationSpec = tween(200),
        label = "lock",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val accent = if (state.isLocked) XT.Green else XT.state(state.alignment.totalError < 0.2f)
        // Subject tracking maps to the FULL canvas - it has to match the live
        // feed underneath pixel for pixel. The fixed guides below use
        // safeCenterYFraction instead, so they sit in the open viewfinder area
        // rather than under the top icon row or behind the bottom card.
        val safeCenterY = size.height * safeCenterYFraction

        state.subject?.let { drawSubjectFrame(it.cx, it.cy, it.w, it.h, accent, subjectAlpha) }

        // The brackets are the TARGET. With a subject in frame they sit where
        // the subject box should be - same size, at the profile's centre and
        // eye line, shifted by gaze lead room - so the instruction reads as
        // "move the green frame into the gold one". With no subject they fall
        // back to the stock centred square, so the app still looks like a
        // camera app rather than an empty one.
        val a = state.alignment
        if (a.hasTarget && a.targetW > 0f && a.targetH > 0f) {
            drawTargetBrackets(a.targetCx, a.targetCy, a.targetW, a.targetH, accent, lockPulse)
            drawCentreCross(
                accent,
                (a.targetCy * size.height).coerceIn(size.height * 0.08f, size.height * 0.92f),
                (a.targetCx * size.width).coerceIn(size.width * 0.08f, size.width * 0.92f),
            )
        } else {
            drawBrackets(accent, lockPulse, safeCenterY)
            drawCentreCross(accent, safeCenterY)
        }
        // On a mirrored preview the world tilts the other way, so the horizon
        // bar - which counter-rotates to stay level with the world - flips sign.
        val horizonRoll = if (state.mirrored) -state.alignment.rollDeg else state.alignment.rollDeg
        if (horizonAlpha > 0.01f) drawHorizon(horizonRoll, horizonAlpha, safeCenterY)
        if (ladderAlpha > 0.01f) drawPitchLadder(state.alignment.pitchErrDeg, ladderAlpha, safeCenterY)
    }
}

/**
 * The frame from the flow reference, tracking the detected face.
 *
 * Coordinates arrive normalised against the VISIBLE crop - the ViewPort in
 * CameraScreen is what makes that true - so they map straight onto this canvas
 * with no further correction.
 */
private fun DrawScope.drawSubjectFrame(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    color: Color,
    alpha: Float,
) {
    if (alpha <= 0.01f) return
    // A face box is tight on the face; open it out so the frame reads as
    // composition rather than as a detection debug box.
    val pad = 1.35f
    // A too-close face (bad framing, exactly what STEP_BACK exists for) can
    // push the padded box past the screen edges. Uncapped, it balloons into a
    // border that runs behind the rails and the guidance card instead of
    // reading as a frame around the face. Capping it to a generous fraction
    // of the screen keeps it centred on the true face position - so "way too
    // big" still looks obviously way too big - without it competing with the
    // chrome. Confirmed on device: an up-close shot drew this as a rectangle
    // wrapping nearly the entire viewfinder before the cap was added.
    val maxFraction = 0.94f
    val bw = (w * pad * size.width).coerceIn(1f, size.width * maxFraction)
    val bh = (h * pad * size.height).coerceIn(1f, size.height * maxFraction)
    val left = cx * size.width - bw / 2f
    val top = cy * size.height - bh / 2f

    drawRoundRect(
        color = color.copy(alpha = 0.9f * alpha),
        topLeft = Offset(left, top),
        size = Size(bw, bh),
        cornerRadius = CornerRadius(24.dp.toPx()),
        style = Stroke(width = 2.dp.toPx()),
    )
    // A soft outer pass, so it glows the way the reference does rather than
    // looking like a bounding box.
    drawRoundRect(
        color = color.copy(alpha = 0.18f * alpha),
        topLeft = Offset(left - 3.dp.toPx(), top - 3.dp.toPx()),
        size = Size(bw + 6.dp.toPx(), bh + 6.dp.toPx()),
        cornerRadius = CornerRadius(27.dp.toPx()),
        style = Stroke(width = 6.dp.toPx()),
    )
}

/**
 * Corner brackets around the target rect - where the subject frame should go.
 * Padded by the same factor as the subject frame so the two coincide exactly
 * when the shot is framed. Capped like the subject frame, for the same reason.
 */
private fun DrawScope.drawTargetBrackets(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    color: Color,
    lockPulse: Float,
) {
    val pad = 1.35f
    val maxFraction = 0.94f
    val bw = (w * pad * size.width).coerceIn(1f, size.width * maxFraction)
    val bh = (h * pad * size.height).coerceIn(1f, size.height * maxFraction)
    // Keep the whole guide on screen. Gaze lead room on a large subject can
    // put the mathematically correct target partly off the left or right edge;
    // a guide you cannot see guides nothing. Slide it inward instead. The
    // subject frame is NOT clamped - it must stay true to the live feed.
    val margin = 6.dp.toPx()
    val left = (cx * size.width - bw / 2f).coerceIn(margin, (size.width - bw - margin).coerceAtLeast(margin))
    val top = (cy * size.height - bh / 2f).coerceIn(margin, (size.height - bh - margin).coerceAtLeast(margin))
    val right = left + bw
    val bottom = top + bh
    val arm = min(bw, bh) * 0.28f
    val stroke = (2.5f + lockPulse * 1.0f).dp.toPx()
    val c = color.copy(alpha = 0.95f)

    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(c, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
        drawLine(c, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
    }

    bracket(left, top, 1f, 1f)
    bracket(right, top, -1f, 1f)
    bracket(left, bottom, 1f, -1f)
    bracket(right, bottom, -1f, -1f)
}

/** The stock centred square, used when there is no subject to target. */
private fun DrawScope.drawBrackets(color: Color, lockPulse: Float, centerY: Float) {
    val half = min(size.width, size.height) * 0.22f
    val arm = half * 0.34f
    val cx = size.width / 2f
    val cy = centerY
    val stroke = (2.5f + lockPulse * 1.0f).dp.toPx()
    val c = color.copy(alpha = 0.95f)

    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(c, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
        drawLine(c, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
    }

    bracket(cx - half, cy - half, 1f, 1f)
    bracket(cx + half, cy - half, -1f, 1f)
    bracket(cx - half, cy + half, 1f, -1f)
    bracket(cx + half, cy + half, -1f, -1f)
}

private fun DrawScope.drawCentreCross(color: Color, centerY: Float, centerX: Float = size.width / 2f) {
    val cx = centerX
    val cy = centerY
    val r = 7.dp.toPx()
    val c = color.copy(alpha = 0.8f)
    val stroke = 1.5f.dp.toPx()
    drawLine(c, Offset(cx - r, cy), Offset(cx + r, cy), stroke, StrokeCap.Round)
    drawLine(c, Offset(cx, cy - r), Offset(cx, cy + r), stroke, StrokeCap.Round)
}

/**
 * Artificial horizon. The bar rotates opposite the phone, so it stays level
 * with the world while the frame tilts - which is the thing the photographer
 * is trying to match.
 */
private fun DrawScope.drawHorizon(rollDeg: Float, alpha: Float, centerY: Float) {
    val cx = size.width / 2f
    val cy = centerY
    val reach = size.width * 0.40f
    val gap = size.width * 0.10f

    // How wrong it is, for colour only. Full amber by 12 degrees.
    val severity = (abs(rollDeg) / 12f).coerceIn(0f, 1f)
    val color = lerpColor(XT.Green, XT.Amber, severity).copy(alpha = 0.92f * alpha)
    val stroke = 2.dp.toPx()

    // Fixed reference pips: where level would be.
    val refColor = XT.OnChip.copy(alpha = 0.35f * alpha)
    drawLine(refColor, Offset(cx - reach, cy), Offset(cx - gap, cy), 1.dp.toPx())
    drawLine(refColor, Offset(cx + gap, cy), Offset(cx + reach, cy), 1.dp.toPx())

    rotate(degrees = -rollDeg, pivot = Offset(cx, cy)) {
        drawLine(color, Offset(cx - reach, cy), Offset(cx - gap, cy), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx + gap, cy), Offset(cx + reach, cy), stroke, StrokeCap.Round)
        // Downturned wingtips, so the bar reads as an attitude indicator and
        // not as a stray rule across the frame.
        val tip = 8.dp.toPx()
        drawLine(color, Offset(cx - reach, cy), Offset(cx - reach, cy + tip), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx + reach, cy), Offset(cx + reach, cy + tip), stroke, StrokeCap.Round)
    }
}

/**
 * Pitch ladder. Rungs slide with the pitch error, so the centre rung meets the
 * cross exactly when pitch is on target.
 */
private fun DrawScope.drawPitchLadder(pitchErrDeg: Float, alpha: Float, centerY: Float) {
    val cx = size.width / 2f
    val cy = centerY
    val pxPerDeg = size.height * 0.012f
    val severity = (abs(pitchErrDeg) / 18f).coerceIn(0f, 1f)
    val color = lerpColor(XT.Green, XT.Amber, severity).copy(alpha = 0.75f * alpha)
    val stroke = 1.5f.dp.toPx()

    for (step in -2..2) {
        if (step == 0) continue
        val deg = step * 5f
        // Positive pitch error means the camera is aimed high, so the ladder
        // sits low - move the phone down and the rungs rise to meet the cross.
        val y = cy + (deg - pitchErrDeg) * pxPerDeg * -1f
        if (y < size.height * 0.18f || y > size.height * 0.82f) continue
        val halfWidth = if (step % 2 == 0) size.width * 0.10f else size.width * 0.065f
        drawLine(color, Offset(cx - halfWidth, y), Offset(cx - size.width * 0.03f, y), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx + size.width * 0.03f, y), Offset(cx + halfWidth, y), stroke, StrokeCap.Round)
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = a.alpha + (b.alpha - a.alpha) * u,
    )
}
