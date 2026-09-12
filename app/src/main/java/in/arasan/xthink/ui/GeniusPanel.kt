package `in`.arasan.xthink.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What GENIUS shows: the link to the Mac, what was heard, the plan, and where it is. */
data class GeniusState(
    val keyboard: String,
    val connected: Boolean,
    val speechAvailable: Boolean,
    /** LISTENING, THINKING, RUNNING, CHECKING, DONE, FAILED, or READY. */
    val phase: String,
    val heard: String,
    val plan: List<String>,
    /** Index of the step being performed, -1 when none. */
    val step: Int,
    val attempt: Int,
    val note: String? = null,
    /** Per step: why it is refused, or null. A plan with any refusal never runs. */
    val refusals: List<String?> = emptyList(),
    /** What Steve is writing, as it arrives. */
    val draft: String = "",
    /** Seconds until the plan runs by itself; 0 when not counting. Cancel stops it. */
    val countdown: Int = 0,
)

/**
 * STEVE. Say it; the phone does it on the Mac. The camera looks at the
 * Mac screen so the model can tell whether it worked and try again. The
 * panel is the log of that: heard, planned, running, checked.
 */
@Composable
fun GeniusPanel(
    state: GeniusState,
    onPair: () -> Unit,
    onSpeak: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.phase in setOf("LISTENING", "THINKING", "WRITING", "RUNNING", "CHECKING")
    val awaiting = state.phase in setOf("PLANNED", "PROPOSED")
    val refused = state.refusals.any { it != null }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(Brush.verticalGradient(listOf(XT.LuxeTop, XT.LuxeBottom)))
            .border(1.dp, XT.LuxeGold.copy(alpha = 0.35f), RoundedCornerShape(XT.Corner))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.connected) XT.Green else XT.LuxeGold),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.keyboard,
                color = XT.OnChip,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            if (!state.connected) Answer(text = "Pair Mac", filled = true, onClick = onPair, compact = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (state.phase) {
                    "LISTENING" -> "LISTENING\u2026"
                    "THINKING" -> "STEVE IS THINKING\u2026"
                    "WRITING" -> "STEVE IS WRITING\u2026"
                    "PLANNED" -> if (refused) "REFUSED \u2014 SEE WHY" else if (state.countdown > 0) "RUNS IN ${state.countdown}\u2026  CANCEL TO STOP" else "PLAN \u2014 TAP RUN TO DO IT"
                    "PROPOSED" -> if (refused) "REFUSED \u2014 SEE WHY" else "NEXT STEP PROPOSED \u2014 TAP RUN"
                    "RUNNING" -> "DOING IT ON THE MAC\u2026  ${state.step + 1}/${state.plan.size}"
                    "CHECKING" -> "LOOKING AT THE SCREEN\u2026  try ${state.attempt}"
                    "DONE" -> "DONE"
                    "FAILED" -> "COULD NOT FINISH"
                    else -> "STEVE"
                },
                color = if (busy) XT.LuxeGold else if (state.phase == "DONE") XT.Green else XT.LuxeGold.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
        }
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 190.dp)
                .clip(RoundedCornerShape(XT.CornerSmall))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.heard.isBlank() && state.plan.isEmpty()) {
                Text(
                    text = if (state.speechAvailable) "Tap Speak and tell Steve what to do on the Mac." else "Speech recognition is not available on this phone.",
                    color = XT.OnChipMuted,
                    fontSize = 13.sp,
                )
            }
            if (state.heard.isNotBlank()) {
                Text(text = "\u201c${state.heard}\u201d", color = XT.OnChip, fontSize = 14.sp, lineHeight = 19.sp)
            }
            state.plan.forEachIndexed { i, line ->
                val why = state.refusals.getOrNull(i)
                val done = why == null && (i < state.step || state.phase == "DONE")
                val now = i == state.step && state.phase == "RUNNING"
                Text(
                    text = (if (why != null) "\u2715 " else if (done) "\u2713 " else if (now) "\u25b6 " else "\u00b7 ") + line +
                        (why?.let { "   \u2190 $it" } ?: ""),
                    color = when {
                        why != null -> XT.Record
                        now -> XT.Amber
                        done -> XT.Green
                        else -> XT.OnChip
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (state.draft.isNotBlank()) {
                Text(text = state.draft, color = XT.OnChip, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (state.note != null) {
                Text(text = state.note, color = XT.OnChipMuted, fontSize = 11.sp)
            }
        }
        // Nothing runs on the Mac without a tap here. The plan is on screen
        // first, every step of it, with anything refused marked.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) {
                Answer(text = "Stop", filled = false, onClick = onStop, modifier = Modifier.weight(1f))
            } else if (awaiting) {
                Answer(text = "Cancel", filled = false, onClick = onStop, modifier = Modifier.weight(1f))
                if (!refused) {
                    Answer(
                        text = if (state.countdown > 0) "Run now  \u00b7  ${state.countdown}" else "Run ${state.plan.size} step${if (state.plan.size == 1) "" else "s"}",
                        filled = true,
                        onClick = onRun,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            } else {
                Answer(
                    text = "Speak",
                    filled = state.connected && state.speechAvailable,
                    onClick = { if (state.speechAvailable) onSpeak() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Answer(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Box(
        modifier = modifier
            .height(if (compact) 34.dp else 42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(if (filled) XT.LuxeGold else Color.White.copy(alpha = 0.08f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 14.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (filled) Color.Black else XT.OnChip,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
