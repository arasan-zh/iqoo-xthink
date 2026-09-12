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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * the Mac is being found and holds still, solid, once it is linked; what
 * you said; the plan as cards; the count to Run. The camera is still
 * underneath, unseen - Steve needs ears and a keyboard, not eyes.
 */
@Composable
fun SteveSurface(
    state: GeniusState,
    onPair: () -> Unit,
    onSpeak: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
) {
    val busy = state.phase in setOf("LISTENING", "THINKING", "WRITING", "RUNNING", "CHECKING")
    val awaiting = state.phase in setOf("PLANNED", "PROPOSED")
    val refused = state.refusals.any { it != null }
    val linking = !state.connected && state.keyboard.let { it.contains("Connecting") || it.contains("Becoming") || it.contains("ready") }
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NightHigh, Night)))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Palette.Gutter)
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                DarkRound(onClick = onHome) { Glyph("home", Color.White, 20.dp) }
                Spacer(Modifier.weight(1f))
                Text(text = "STEVE", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(44.dp))
            }
            Spacer(Modifier.height(20.dp))
            LinkRing(connected = state.connected, linking = linking)
            Spacer(Modifier.height(14.dp))
            Text(
                text = state.keyboard,
                color = if (state.connected) Color.White else GoldDim,
                fontSize = 13.sp,
                letterSpacing = 0.3.sp,
            )
            if (!state.connected) {
                Spacer(Modifier.height(10.dp))
                Pill(text = "Pair Mac", gold = true, onClick = onPair)
            }
            Spacer(Modifier.height(22.dp))
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
                fontSize = 28.sp,
                lineHeight = 34.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
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
            }
            Spacer(Modifier.height(14.dp))
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
                    else -> Pill(text = "Speak", gold = state.connected && state.speechAvailable, onClick = { if (state.speechAvailable) onSpeak() }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Three rings. Breathing while the Mac is being found; still and gold once linked. */
@Composable
private fun LinkRing(connected: Boolean, linking: Boolean) {
    val t = rememberInfiniteTransition(label = "ring")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
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
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text = text, color = if (gold) Night else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
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
