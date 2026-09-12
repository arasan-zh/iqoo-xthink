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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
// Mode tabs - PORTRAIT | SCENE (CoachMode.WIDE), both live. The rail on the left switches the
// same state; a stock camera shows modes in both places.
// ---------------------------------------------------------------------------

@Composable
fun ModeTabs(mode: CoachMode, onModeSelected: (CoachMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeTab("PORTRAIT", mode == CoachMode.PORTRAIT) { onModeSelected(CoachMode.PORTRAIT) }
        ModeTab("SCENE", mode == CoachMode.WIDE) { onModeSelected(CoachMode.WIDE) }
        ModeTab("OBJECT", mode == CoachMode.OBJECT) { onModeSelected(CoachMode.OBJECT) }
        ModeTab("CREATIVE", mode == CoachMode.CREATIVE) { onModeSelected(CoachMode.CREATIVE) }
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
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            maxLines = 1,
            softWrap = false,
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
    onFlip: () -> Unit,
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

        // Flip - where a camera app keeps it.
        FlipButton(onClick = onFlip, diameter = 46.dp, background = Color.White.copy(alpha = 0.14f))
    }
}

/** Install link: anyone at the demo scans it and gets the latest build. */
@Composable
fun QrButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(XT.Chip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val u = size.minDimension / 7f
            val c = XT.OnChip
            fun finder(x: Float, y: Float) {
                drawRect(c, Offset(x * u, y * u), Size(3 * u, 3 * u), style = Stroke(u * 0.8f))
                drawRect(c, Offset((x + 1) * u, (y + 1) * u), Size(u, u))
            }
            finder(0f, 0f); finder(4f, 0f); finder(0f, 4f)
            drawRect(c, Offset(4 * u, 4 * u), Size(u, u))
            drawRect(c, Offset(6 * u, 5 * u), Size(u, u))
            drawRect(c, Offset(5 * u, 6 * u), Size(u, u))
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
// Mode rail - Portrait | Scene (CoachMode.WIDE). Live, the same state as the tabs. Nothing on
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
        RailItem("Scene", glyph = 1, active = mode == CoachMode.WIDE) { onModeSelected(CoachMode.WIDE) }
        RailItem("Object", glyph = 2, active = mode == CoachMode.OBJECT) { onModeSelected(CoachMode.OBJECT) }
        RailItem("Creative", glyph = 3, active = mode == CoachMode.CREATIVE) { onModeSelected(CoachMode.CREATIVE) }
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
        1 -> { // wide - two people side by side
            drawCircle(color, r * 0.28f, Offset(c - r * 0.42f, c - r * 0.35f), style = Stroke(stroke))
            drawCircle(color, r * 0.28f, Offset(c + r * 0.42f, c - r * 0.35f), style = Stroke(stroke))
            drawArc(color, 200f, 140f, false, Offset(c - r * 1.0f, c + r * 0.15f), Size(r * 1.15f, r * 1.1f), style = Stroke(stroke))
            drawArc(color, 200f, 140f, false, Offset(c - r * 0.15f, c + r * 0.15f), Size(r * 1.15f, r * 1.1f), style = Stroke(stroke))
        }
        3 -> { // creative - an aperture: a ring with six blades
            drawCircle(color, r * 0.9f, Offset(c, c), style = Stroke(stroke))
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0)
                val b = Math.toRadians(i * 60.0 + 100.0)
                drawLine(
                    color,
                    Offset(c + kotlin.math.cos(a).toFloat() * r * 0.9f, c + kotlin.math.sin(a).toFloat() * r * 0.9f),
                    Offset(c + kotlin.math.cos(b).toFloat() * r * 0.35f, c + kotlin.math.sin(b).toFloat() * r * 0.35f),
                    stroke, StrokeCap.Round,
                )
            }
        }
        else -> { // object - a cube in outline
            val s = r * 0.62f
            val dx = r * 0.32f
            val dy = r * 0.28f
            // front face
            drawRoundRect(color, Offset(c - s / 2f - dx / 2f, c - s / 2f + dy / 2f), Size(s, s), androidx.compose.ui.geometry.CornerRadius(1.5f.dp.toPx()), style = Stroke(stroke))
            // top edge
            drawLine(color, Offset(c - s / 2f - dx / 2f, c - s / 2f + dy / 2f), Offset(c - s / 2f + dx / 2f, c - s / 2f - dy / 2f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c + s / 2f - dx / 2f, c - s / 2f + dy / 2f), Offset(c + s / 2f + dx / 2f, c - s / 2f - dy / 2f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c - s / 2f + dx / 2f, c - s / 2f - dy / 2f), Offset(c + s / 2f + dx / 2f, c - s / 2f - dy / 2f), stroke, StrokeCap.Round)
            // right edge
            drawLine(color, Offset(c + s / 2f + dx / 2f, c - s / 2f - dy / 2f), Offset(c + s / 2f + dx / 2f, c + s / 2f - dy / 2f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c + s / 2f - dx / 2f, c + s / 2f + dy / 2f), Offset(c + s / 2f + dx / 2f, c + s / 2f - dy / 2f), stroke, StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------
// Flip - front / rear. The engine has handled the front camera since v0.1
// (mirrored instructions, fixed focus); this is the button that reaches it.
// ---------------------------------------------------------------------------

@Composable
fun FlipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 44.dp,
    background: Color = XT.Chip,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // A camera body with a circular arrow in the lens: the glyph every
        // camera app uses for front/rear.
        Canvas(modifier = Modifier.size(24.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.7f.dp.toPx()
            val c = XT.OnChip
            val bodyTop = h * 0.28f
            drawRoundRect(
                color = c,
                topLeft = Offset(w * 0.06f, bodyTop),
                size = Size(w * 0.88f, h * 0.62f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
                style = Stroke(stroke),
            )
            // the viewfinder bump
            drawLine(c, Offset(w * 0.32f, bodyTop), Offset(w * 0.40f, h * 0.14f), stroke, StrokeCap.Round)
            drawLine(c, Offset(w * 0.40f, h * 0.14f), Offset(w * 0.60f, h * 0.14f), stroke, StrokeCap.Round)
            drawLine(c, Offset(w * 0.60f, h * 0.14f), Offset(w * 0.68f, bodyTop), stroke, StrokeCap.Round)
            // the arrow in the lens
            val cx = w * 0.5f
            val cy = bodyTop + h * 0.31f
            val r = w * 0.17f
            drawArc(
                color = c,
                startAngle = -60f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val tip = Offset(cx + r * 0.5f, cy - r * 0.87f)
            drawLine(c, tip, Offset(tip.x - r * 0.55f, tip.y - r * 0.05f), stroke, StrokeCap.Round)
            drawLine(c, tip, Offset(tip.x - r * 0.05f, tip.y + r * 0.55f), stroke, StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------
// Look - the chip on the camera page that opens the looks row, and the row.
// ---------------------------------------------------------------------------

@Composable
fun LookChip(name: String, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (open) XT.ChipStrong else XT.Chip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val r = size.minDimension / 2f
            drawCircle(XT.OnChip, r, style = Stroke(1.5f.dp.toPx()))
            drawArc(XT.OnChip, -90f, 180f, useCenter = true, topLeft = Offset(0f, 0f), size = Size(r * 2f, r * 2f))
        }
        Text(
            text = name,
            color = if (name == "Natural") XT.OnChip else XT.Amber,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun LooksRow(look: Int, preview: ImageBitmap?, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.Chip)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Looks.ALL.forEachIndexed { i, l ->
            if (preview != null) {
                LookTile(look = l, image = preview, chosen = i == look, onClick = { onPick(i) })
            } else {
                Text(
                    text = l.name,
                    color = if (i == look) XT.Amber else XT.OnChip,
                    fontSize = 13.sp,
                    fontWeight = if (i == look) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onPick(i) },
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reference - "shoot one like this". A picked photo the coach has read;
// while one is set its thumbnail sits here, and a tap clears it.
// ---------------------------------------------------------------------------

@Composable
fun ReferenceButton(
    thumbnail: ImageBitmap?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(XT.Chip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (thumbnail == null) onPick() else onClear() },
            )
            .padding(horizontal = if (thumbnail == null) 14.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "reference photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Text(
                text = "Like this  \u00d7",
                color = XT.OnChip,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 6.dp),
            )
        } else {
            Canvas(modifier = Modifier.size(16.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    XT.OnChip,
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f.dp.toPx()),
                    style = Stroke(1.6f.dp.toPx()),
                )
                drawLine(XT.OnChip, Offset(w * 0.15f, h * 0.78f), Offset(w * 0.42f, h * 0.48f), 1.6f.dp.toPx(), StrokeCap.Round)
                drawLine(XT.OnChip, Offset(w * 0.42f, h * 0.48f), Offset(w * 0.62f, h * 0.68f), 1.6f.dp.toPx(), StrokeCap.Round)
                drawLine(XT.OnChip, Offset(w * 0.62f, h * 0.68f), Offset(w * 0.85f, h * 0.42f), 1.6f.dp.toPx(), StrokeCap.Round)
            }
            Text(
                text = "Like…",
                color = XT.OnChip,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Easy shot - the "near enough" auto-shutter toggle. Green when on.
// ---------------------------------------------------------------------------

@Composable
fun EasyShotToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (on) XT.Green.copy(alpha = 0.22f) else XT.Chip, tween(180), label = "easyBg")
    val fg by animateColorAsState(if (on) XT.Green else XT.OnChip, tween(180), label = "easyFg")
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(fg),
        )
        Text(
            text = "Easy shot",
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
