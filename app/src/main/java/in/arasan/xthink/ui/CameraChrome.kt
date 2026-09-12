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
import `in`.arasan.xthink.guidance.CoachMode
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
// Mode tabs - PORTRAIT | WIDE, both live. The rail on the left switches the
// same state; a stock camera shows modes in both places.
// ---------------------------------------------------------------------------

@Composable
fun ModeTabs(mode: CoachMode, onModeSelected: (CoachMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeTab("PORTRAIT", mode == CoachMode.PORTRAIT) { onModeSelected(CoachMode.PORTRAIT) }
        ModeTab("WIDE", mode == CoachMode.WIDE) { onModeSelected(CoachMode.WIDE) }
    }
}

@Composable
private fun ModeTab(label: String, active: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (active) XT.Gold else XT.OnChip.copy(alpha = 0.8f),
        animationSpec = tween(180),
        label = "modeTab",
    )
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = tint,
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
                .background(if (active) XT.Gold else Color.Transparent),
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
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Gallery: the most recent capture, or a placeholder until there is
        // one. Tapping opens it in the phone's viewer.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(XT.CornerSmall))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onGallery,
                ),
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

        // Rear camera only, so there is no flip. A spacer the same size keeps
        // the shutter centred - a real control belongs here when the front
        // camera does (the engine already handles mirrored + fixed focus).
        Spacer(Modifier.size(46.dp))
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
// Mode rail - Portrait | Wide. Live, the same state as the tabs. Nothing on
// this screen is decorative any more: every icon does something.
// ---------------------------------------------------------------------------

@Composable
fun ModeRail(mode: CoachMode, onModeSelected: (CoachMode) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.Chip)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RailItem("Portrait", glyph = 0, active = mode == CoachMode.PORTRAIT) { onModeSelected(CoachMode.PORTRAIT) }
        RailItem("Wide", glyph = 1, active = mode == CoachMode.WIDE) { onModeSelected(CoachMode.WIDE) }
    }
}

@Composable
private fun RailItem(label: String, glyph: Int, active: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (active) XT.Green else XT.Inert,
        animationSpec = tween(180),
        label = "rail",
    )
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(19.dp)) { drawRailGlyph(glyph, tint) }
        Text(
            text = label,
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

private fun DrawScope.drawRailGlyph(index: Int, color: Color) {
    val c = size.minDimension / 2f
    val r = c * 0.8f
    val stroke = 1.6f.dp.toPx()
    when (index) {
        0 -> { // portrait - a person
            drawCircle(color, r * 0.34f, Offset(c, c - r * 0.42f), style = Stroke(stroke))
            drawArc(color, 200f, 140f, false, Offset(c - r * 0.72f, c + r * 0.1f), Size(r * 1.44f, r * 1.3f), style = Stroke(stroke))
        }
        else -> { // wide - two people side by side
            drawCircle(color, r * 0.28f, Offset(c - r * 0.42f, c - r * 0.35f), style = Stroke(stroke))
            drawCircle(color, r * 0.28f, Offset(c + r * 0.42f, c - r * 0.35f), style = Stroke(stroke))
            drawArc(color, 200f, 140f, false, Offset(c - r * 1.0f, c + r * 0.15f), Size(r * 1.15f, r * 1.1f), style = Stroke(stroke))
            drawArc(color, 200f, 140f, false, Offset(c - r * 0.15f, c + r * 0.15f), Size(r * 1.15f, r * 1.1f), style = Stroke(stroke))
        }
    }
}
