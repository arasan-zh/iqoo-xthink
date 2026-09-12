package `in`.arasan.xthink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/** What FIT shows. [mode] is "SQUAT", "PUSHUP" or "SIGNS". */
data class FitState(
    val mode: String,
    val count: Int,
    val phase: String,
    val angleDeg: Float?,
    val gesture: String?,
    val bodySeen: Boolean,
)

/**
 * FIT. Squats and push-ups counted from the body's joints; hand signs
 * read from the hand. Big numbers, because it is read from the floor.
 */
@Composable
fun FitPanel(
    state: FitState,
    onMode: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.ChipStrong)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Squats", state.mode == "SQUAT", { onMode("SQUAT") }, Modifier.weight(1f))
            Pill("Push-ups", state.mode == "PUSHUP", { onMode("PUSHUP") }, Modifier.weight(1f))
            Pill("Hand signs", state.mode == "SIGNS", { onMode("SIGNS") }, Modifier.weight(1.2f))
        }
        if (state.mode == "SIGNS") {
            val (emoji, name) = when (state.gesture) {
                "Thumb_Up" -> "👍" to "Thumbs up  ·  takes the photo"
                "Thumb_Down" -> "👎" to "Thumbs down"
                "Victory" -> "✌️" to "Victory"
                "Pointing_Up" -> "☝️" to "Pointing up"
                "Open_Palm" -> "🖐️" to "Open palm"
                "Closed_Fist" -> "✊" to "Fist"
                "ILoveYou" -> "🤟" to "I love you"
                else -> "🤚" to "Show a hand sign"
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(text = emoji, fontSize = 64.sp)
                Text(text = name, color = if (state.gesture != null) XT.Amber else XT.OnChipMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.count.toString(),
                    color = XT.Amber,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = when {
                            !state.bodySeen -> "STEP BACK, SHOW THE WHOLE BODY"
                            state.phase == "DOWN" -> "DOWN"
                            state.phase == "UP" -> "UP"
                            else -> "READY"
                        },
                        color = if (state.bodySeen) XT.Green else XT.Amber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    )
                    Text(
                        text = state.angleDeg?.let { "%.0f°".format(it) } ?: "—",
                        color = XT.OnChipMuted,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Pill("Reset", false, onReset, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (on) XT.Amber else XT.Chip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = if (on) Color.Black else XT.OnChip, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
