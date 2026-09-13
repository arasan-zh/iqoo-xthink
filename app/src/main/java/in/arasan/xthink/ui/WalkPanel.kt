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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** What WALK shows. [verb] is a WalkVerb name. */
data class WalkState(
    val verb: String,
    val walking: Boolean,
    val steps: Int,
    val remainingMs: Long,
)

/**
 * WALK. The camera points ahead and the phone says what is in the way;
 * the words and the pulses are the product, and this card is for whoever
 * is looking over the walker's shoulder: the instruction, huge; whether
 * they are walking; the time left of the five minutes.
 */
@Composable
fun WalkPanel(state: WalkState, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val (word, colour) = when (state.verb) {
        "STOP" -> "STOP" to XT.Record
        "SLOW" -> "SLOW DOWN" to XT.Amber
        "KEEP_LEFT" -> "KEEP LEFT" to XT.Amber
        "KEEP_RIGHT" -> "KEEP RIGHT" to XT.Amber
        else -> "CLEAR" to XT.Green
    }
    val s = (state.remainingMs / 1000L).coerceAtLeast(0L)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.ChipStrong)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "WALK ASSIST", color = XT.OnChipMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Spacer(Modifier.weight(1f))
            Text(text = "%d:%02d left".format(s / 60, s % 60), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Text(text = word, color = colour, fontSize = 54.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (state.walking) "Walking" else "Standing", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Text(text = "${state.steps} steps", color = XT.OnChipMuted, fontSize = 13.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onFinish),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Finish walk", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
