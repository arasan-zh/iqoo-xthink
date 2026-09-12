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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.alpha
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

/**
 * The zoom bar. Tap a stop, or drag anywhere along it: position maps to
 * zoom on a log scale between 1x and the lens's maximum, so 2x sits where
 * the "2" is. The readout above shows the ratio and the 35mm-equivalent
 * focal length - the number a photographer actually thinks in.
 */
@Composable
fun ZoomBar(
    zoomRatio: Float,
    maxZoomRatio: Float,
    baseFocalMm: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxLog = ln(maxZoomRatio.coerceAtLeast(1.01f))
    var dragging by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        val mm = (baseFocalMm * zoomRatio).roundToInt()
        Text(
            text = "%.1fx  \u00b7  %d mm".format(zoomRatio, mm),
            color = if (dragging) XT.Gold else XT.OnChip,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(XT.Chip)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(XT.Corner))
                .background(XT.Chip)
                .pointerInput(maxZoomRatio) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        val t = ((change.position.x - BAR_PAD_PX) / (size.width - 2 * BAR_PAD_PX)).coerceIn(0f, 1f)
                        onZoomSelected(exp(t * maxLog).coerceIn(1f, maxZoomRatio))
                    }
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            // Stops sit at their log position along the bar.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(34.dp)) {
                val w = maxWidth
                ZOOM_STOPS.forEach { stop ->
                    val reachable = stop <= maxZoomRatio + 0.01f
                    val t = (ln(stop) / maxLog).coerceIn(0f, 1f)
                    val active = abs(zoomRatio - stop) < 0.15f
                    ZoomStop(
                        stop = stop,
                        active = active,
                        reachable = reachable,
                        modifier = Modifier.offset(x = (w - 34.dp) * t),
                    ) { if (reachable) onZoomSelected(stop) }
                }
                // The thumb: where the zoom is right now, between stops.
                val tt = (ln(zoomRatio.coerceAtLeast(1f)) / maxLog).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .offset(x = (w - 6.dp) * tt, y = 30.dp)
                        .size(width = 6.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(XT.Gold),
                )
            }
        }
    }
}

private const val BAR_PAD_PX = 26f

@Composable
private fun ZoomStop(stop: Float, active: Boolean, reachable: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
        modifier = modifier
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
fun ModeTabs(
    mode: CoachMode,
    onModeSelected: (CoachMode) -> Unit,
    modifier: Modifier = Modifier,
    portraitOnly: Boolean = false,
    videoMode: Boolean = false,
    onVideo: () -> Unit = {},
    typeMode: Boolean = false,
    onType: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val photo = !videoMode && !typeMode
        ModeTab("PORTRAIT", photo && mode == CoachMode.PORTRAIT) { onModeSelected(CoachMode.PORTRAIT) }
        // The selfie lens is for people: the other modes stay on the rear camera.
        if (!portraitOnly) {
            ModeTab("SCENE", photo && mode == CoachMode.WIDE) { onModeSelected(CoachMode.WIDE) }
            ModeTab("OBJECT", photo && mode == CoachMode.OBJECT) { onModeSelected(CoachMode.OBJECT) }
            ModeTab("CREATIVE", photo && mode == CoachMode.CREATIVE) { onModeSelected(CoachMode.CREATIVE) }
        }
        ModeTab("VIDEO", videoMode, onClick = onVideo)
        if (!portraitOnly) ModeTab("TYPE", typeMode, onClick = onType)
    }
}

/** A red dot and the clock, while recording. */
@Composable
fun RecordingChip(ms: Long, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "recDot",
    )
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(XT.ChipStrong)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).alpha(a).clip(CircleShape).background(XT.Record))
        val s = ms / 1000
        Text(
            text = "%d:%02d".format(s / 60, s % 60),
            color = XT.OnChip,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
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
    video: Boolean = false,
    recording: Boolean = false,
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

        Shutter(locked = locked, video = video, recording = recording, onClick = onShutter)

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
private fun Shutter(locked: Boolean, video: Boolean, recording: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(
        targetValue = if (locked && !video) XT.Green else Color.White,
        animationSpec = tween(260),
        label = "shutterRing",
    )
    val inner by animateColorAsState(
        targetValue = when {
            video -> XT.Record
            locked -> XT.Green
            else -> XT.Gold
        },
        animationSpec = tween(260),
        label = "shutterInner",
    )
    val square by animateFloatAsState(if (recording) 1f else 0f, tween(200), label = "recSquare")
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
        if (video) {
            // A red disc that squares off while recording, as every camera's does.
            val r = c - 12.dp.toPx()
            val side = r * (2f - 0.7f * square)
            val radius = r * (1f - square) + 6.dp.toPx() * square
            drawRoundRect(
                XT.Record,
                topLeft = Offset(c - side / 2f, c - side / 2f),
                size = Size(side, side),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            )
        } else {
            drawCircle(inner.copy(alpha = 0.9f), c - 10.dp.toPx(), Offset(c, c), style = Stroke(3.dp.toPx()))
            if (locked) drawCircle(XT.Green.copy(alpha = 0.12f), c - 11.dp.toPx(), Offset(c, c))
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
fun EasyShotToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) =
    OnOffChip(label = "Easy shot", on = on, onToggle = onToggle, modifier = modifier)

/** The words of guidance, on or off. Off, the reticle and the arrows still guide. */
@Composable
fun GuideToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) =
    OnOffChip(label = "Guide", on = on, onToggle = onToggle, modifier = modifier)

@Composable
private fun OnOffChip(label: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
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
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
