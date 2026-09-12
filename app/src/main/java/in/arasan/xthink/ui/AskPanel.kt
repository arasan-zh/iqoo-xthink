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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What ASK shows: the model's state, the question, the answer, and where it is. */
data class AskState(
    val coach: String,
    val ready: Boolean,
    val speechAvailable: Boolean,
    /** READY, LISTENING, LOOKING, READING, DONE, SAVED, FAILED. */
    val phase: String,
    /** What was asked, or what was done: "Scan", "Translate". */
    val prompt: String,
    val answer: String,
    val note: String? = null,
)

/**
 * TRANSLATE. Point the camera at text in any script and get the English.
 * One look per tap - nothing runs on a timer - so the phone stays cool
 * and the answer belongs to the frame you meant.
 */
@Composable
fun AskPanel(
    state: AskState,
    onTranslate: () -> Unit,
    scan: Boolean = false,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.phase in setOf("LISTENING", "LOOKING", "READING")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.ChipStrong)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (state.ready) XT.Green else XT.Amber))
            Spacer(Modifier.width(8.dp))
            Text(text = state.coach, color = XT.OnChip, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                text = when (state.phase) {
                    "LISTENING" -> "LISTENING…"
                    "LOOKING" -> "LOOKING…"
                    "READING" -> "READING…"
                    "SAVED" -> "SAVED"
                    "FAILED" -> "COULD NOT"
                    else -> ""
                },
                color = XT.Amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
        }
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 220.dp)
                .clip(RoundedCornerShape(XT.CornerSmall))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.prompt.isBlank() && state.answer.isBlank()) {
                Text(
                    text = if (scan) "Point the camera at a page, a card, a whiteboard \u2014 and tap Scan. The text comes out clean, ready to copy or save." else "Point the camera at a sign, a menu, a page \u2014 Tamil, Hindi, anything \u2014 and tap Translate.",
                    color = XT.OnChipMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            if (state.prompt.isNotBlank()) {
                Text(text = "“${state.prompt}”", color = XT.OnChipMuted, fontSize = 12.sp)
            }
            if (state.answer.isNotBlank()) {
                Text(text = state.answer, color = XT.OnChip, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (state.note != null) Text(text = state.note, color = XT.OnChipMuted, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                Answer(text = "Stop", filled = false, onClick = onStop, modifier = Modifier.weight(1f))
            } else {
                Answer(text = if (scan) "Scan text" else "Translate to English", filled = scan || state.ready, onClick = onTranslate, modifier = Modifier.weight(1f))
            }
        }
        if (!busy && state.answer.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Answer(text = "Copy", filled = false, onClick = onCopy, modifier = Modifier.weight(1f))
                Answer(text = "Save", filled = false, onClick = onSave, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Answer(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (filled) XT.Amber else XT.Chip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = if (filled) Color.Black else XT.OnChip, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
