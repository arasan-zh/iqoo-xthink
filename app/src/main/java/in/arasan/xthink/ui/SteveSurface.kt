package `in`.arasan.xthink.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import `in`.arasan.xthink.guidance.MacWatch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF07080F)
private val NightHigh = Color(0xFF15183A)
private val Gold = Color(0xFFD9B36B)
private val GoldDim = Color(0xFFD9B36B).copy(alpha = 0.45f)
private val Card = Color.White.copy(alpha = 0.06f)
private val CardLine = Color.White.copy(alpha = 0.10f)

/**
 * Steve's room. Dark, quiet, gold: a Bluetooth ring that breathes while
 * the Mac is being found and holds still, solid, once it is linked; a
 * window onto the Mac - the live camera, seen through the room - with
 * what the camera reads on it; what you said; the plan as cards; the
 * count to Run; and, below, what Steve says the Mac is doing.
 */
@Composable
fun SteveSurface(
    state: GeniusState,
    onPair: () -> Unit,
    onSpeak: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
    onWatch: () -> Unit = {},
    model: String? = null,
    /** The lens for the window, and a tap in it: fractions of the preview to focus on. */
    onZoom: (Float) -> Unit = {},
    onFocus: (Float, Float) -> Unit = { _, _ -> },
) {
    val busy = state.phase in setOf("LISTENING", "THINKING", "WRITING", "RUNNING", "CHECKING")
    val awaiting = state.phase in setOf("PLANNED", "PROPOSED")
    val refused = state.refusals.any { it != null }
    val linking = !state.connected && state.keyboard.let { it.contains("Connecting") || it.contains("Becoming") || it.contains("ready") }
    // The window: a hole in the room through which the camera preview
    // underneath shows. Its place is measured, then cleared out of the layer.
    var window by remember { mutableStateOf<Rect?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    // The room in pieces, so portrait stacks them and landscape - the Mac
    // is wide, and so is the window onto it - puts the Mac on the left and
    // the conversation down the right.
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            DarkRound(onClick = onHome) { Glyph("camera", Color.White, 20.dp) }
            Spacer(Modifier.weight(1f))
            Text(text = "STEVE", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            if (model != null) ModelChip(model) else Spacer(Modifier.size(44.dp))
        }
    }
    val link: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LinkRing(connected = state.connected, linking = linking, diameter = 92.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.keyboard,
                    color = if (state.connected) Color.White else GoldDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 0.3.sp,
                )
                if (!state.connected) {
                    Spacer(Modifier.height(8.dp))
                    Pill(text = "Pair Mac", gold = true, onClick = onPair)
                }
            }
        }
    }
    // [windowHeight]: landscape - the window is this tall and its width follows, so the lines under it stay on screen; null: full width, 16:9.
    val macWindow: @Composable (Modifier, androidx.compose.ui.unit.Dp?) -> Unit = { m, windowHeight ->
        Column(modifier = m) {
            // --- the Mac, through the room ---
            Box(
                modifier = (if (windowHeight != null) Modifier.height(windowHeight).aspectRatio(16f / 9f, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    .onGloballyPositioned { window = it.boundsInRoot() }
                    // A tap in the window focuses the lens there - on the terminal.
                    .pointerInput(Unit) {
                        detectTapGestures { off ->
                            val w = window ?: return@detectTapGestures
                            if (rootSize.width > 0 && rootSize.height > 0) onFocus((w.left + off.x) / rootSize.width, (w.top + off.y) / rootSize.height)
                        }
                    },
            )
            Spacer(Modifier.height(6.dp))
            // The lens: 1x for the whole desk, 2x or 3x to fill the window with the terminal.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (z in listOf(1f, 2f, 3f)) {
                    val on = kotlin.math.abs(state.zoom - z) < 0.25f
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) Gold else Card)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onZoom(z) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(text = "${z.toInt()}x", color = if (on) Night else Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                }
                Spacer(Modifier.width(4.dp))
                Text(text = "tap the window to focus the terminal", color = GoldDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            val headline = MacWatch.headline(state.screen)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "ON THE MAC", color = GoldDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (headline.isBlank()) "point the camera at the screen" else headline,
                    color = if (headline.isBlank()) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.inputSeen != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.inputSeen) "\u2713  The typed text is on the Mac" else "The typed text is not on the screen yet",
                    color = if (state.inputSeen) XT.Green else XT.Amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    val phaseLine: @Composable () -> Unit = {
        Text(
            text = when (state.phase) {
                "LISTENING" -> "Listening…"
                "THINKING" -> "Thinking…"
                "WRITING" -> "Writing…"
                "RUNNING" -> "On the Mac  ·  ${state.step + 1}/${state.plan.size}"
                "PLANNED" -> if (refused) "Refused" else if (state.countdown > 0) "Runs in ${state.countdown}" else "Ready to run"
                "PROPOSED" -> "Next step proposed"
                "DONE" -> "Done"
                "FAILED" -> "Could not"
                else -> "Say what the Mac should do."
            },
            color = Color.White,
            fontFamily = FontFamily.Serif,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val body: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.heard.isNotBlank()) {
                Text(text = "“${state.heard}”", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, lineHeight = 21.sp)
            }
            state.plan.forEachIndexed { i, line ->
                val why = state.refusals.getOrNull(i)
                val done = why == null && (i < state.step || state.phase == "DONE")
                val now = i == state.step && state.phase == "RUNNING"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (now) Gold.copy(alpha = 0.16f) else Card)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape)
                            .background(if (why != null) XT.Record else if (done) XT.Green else if (now) Gold else GoldDim),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(text = line, color = if (why != null) XT.Record else Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        if (why != null) Text(text = why, color = XT.Record.copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                }
            }
            if (state.draft.isNotBlank()) Text(text = state.draft, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
            if (state.note != null) Text(text = state.note, color = GoldDim, fontSize = 12.sp)
            // --- what Steve has said about the Mac, oldest first ---
            state.log.forEach { entry ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Text(text = entry.substringBefore("  "), color = GoldDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp))
                    Text(text = entry.substringAfter("  ").trim(), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            if (state.watching) Text(text = "Looking at the Mac\u2026", color = GoldDim, fontSize = 12.sp)
        }
    }
    val buttons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                busy -> Pill(text = "Stop", gold = false, onClick = onStop, modifier = Modifier.weight(1f))
                awaiting -> {
                    Pill(text = "Cancel", gold = false, onClick = onStop, modifier = Modifier.weight(1f))
                    if (!refused) Pill(
                        text = if (state.countdown > 0) "Run now  ·  ${state.countdown}" else "Run ${state.plan.size}",
                        gold = true, onClick = onRun, modifier = Modifier.weight(1.4f),
                    )
                }
                else -> {
                    Pill(text = "Speak", gold = state.connected && state.speechAvailable, onClick = { if (state.speechAvailable) onSpeak() }, modifier = Modifier.weight(1.3f))
                    Pill(text = if (state.watching) "Looking\u2026" else "What's on the Mac?", gold = false, onClick = { if (!state.watching) onWatch() }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootSize = it.size }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                window?.let { w ->
                    val radius = CornerRadius(20.dp.toPx())
                    drawRoundRect(Color.Black, topLeft = w.topLeft, size = w.size, cornerRadius = radius, blendMode = BlendMode.Clear)
                    drawRoundRect(Gold.copy(alpha = 0.55f), topLeft = w.topLeft, size = w.size, cornerRadius = radius, style = Stroke(1.dp.toPx()))
                }
            }
            .background(Brush.verticalGradient(listOf(NightHigh, Night))),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Palette.Gutter)
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            val available = maxHeight
            if (maxWidth > maxHeight) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.weight(1.25f).fillMaxHeight()) {
                        header()
                        Spacer(Modifier.height(8.dp))
                        macWindow(Modifier.fillMaxWidth(), (available - 250.dp).coerceAtLeast(160.dp))
                        Spacer(Modifier.height(10.dp))
                        link()
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        phaseLine()
                        Spacer(Modifier.height(8.dp))
                        body(Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        buttons()
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    Spacer(Modifier.height(10.dp))
                    link()
                    Spacer(Modifier.height(12.dp))
                    macWindow(Modifier.fillMaxWidth(), null)
                    Spacer(Modifier.height(12.dp))
                    phaseLine()
                    Spacer(Modifier.height(10.dp))
                    body(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    buttons()
                }
            }
        }
    }
}

/** Three rings. Breathing while the Mac is being found; still and gold once linked. */
@Composable
private fun LinkRing(connected: Boolean, linking: Boolean, diameter: androidx.compose.ui.unit.Dp = 150.dp) {
    val t = rememberInfiniteTransition(label = "ring")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension / 2f
            for (i in 0 until 3) {
                val phase = if (connected) 0f else ((p + i / 3f) % 1f)
                val r = if (connected) base * (0.55f + 0.16f * i) else base * (0.45f + 0.55f * phase)
                val a = if (connected) 0.9f - 0.28f * i else (1f - phase) * 0.6f
                drawCircle(if (connected) Gold.copy(alpha = a) else GoldDim.copy(alpha = a), r, c, style = Stroke(1.5f.dp.toPx()))
            }
            // The Bluetooth rune.
            val s = base * 0.42f
            val col = if (connected) Gold else Color.White.copy(alpha = 0.7f)
            val st = 2.2f.dp.toPx()
            val top = Offset(c.x, c.y - s); val bot = Offset(c.x, c.y + s)
            drawLine(col, top, bot, st, StrokeCap.Round)
            drawLine(col, top, Offset(c.x + s * 0.6f, c.y - s * 0.5f), st, StrokeCap.Round)
            drawLine(col, Offset(c.x + s * 0.6f, c.y - s * 0.5f), Offset(c.x - s * 0.6f, c.y + s * 0.5f), st, StrokeCap.Round)
            drawLine(col, bot, Offset(c.x + s * 0.6f, c.y + s * 0.5f), st, StrokeCap.Round)
            drawLine(col, Offset(c.x + s * 0.6f, c.y + s * 0.5f), Offset(c.x - s * 0.6f, c.y - s * 0.5f), st, StrokeCap.Round)
        }
    }
}

@Composable
private fun Pill(text: String, gold: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (gold) Gold else Card)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text = text, color = if (gold) Night else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun DarkRound(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Card)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
