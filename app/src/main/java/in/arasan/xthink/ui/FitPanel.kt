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

/** What FIT shows. [mode] is "SQUAT" or "PUSHUP". */
data class FitState(
    val mode: String,
    val count: Int,
    val phase: String,
    val angleDeg: Float?,
    val gesture: String?,
    val bodySeen: Boolean,
)

/**
 * FIT. Squats and push-ups counted from the body's joints. Big numbers,
 * because it is read from the floor.
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
        }
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


/**
 * SIGNS. One hand sign at a time, read from the hand and shown as the
 * English word. Seven signs - what the on-device model knows. It takes
 * no photos and runs nothing else; it is a translator, sign by sign.
 */
@Composable
fun SignsPanel(sign: String?, modifier: Modifier = Modifier) {
    val (emoji, word) = when (sign) {
        "Thumb_Up" -> "👍" to "Yes / Good"
        "Thumb_Down" -> "👎" to "No / Bad"
        "Victory" -> "✌️" to "Peace / Two"
        "Pointing_Up" -> "☝️" to "Wait / One"
        "Open_Palm" -> "🖐️" to "Stop / Hello"
        "Closed_Fist" -> "✊" to "Hold / Strong"
        "ILoveYou" -> "🤟" to "I love you"
        else -> "🤚" to "Show a hand sign"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.ChipStrong)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = emoji, fontSize = 72.sp)
        Text(
            text = word,
            color = if (sign != null) XT.Amber else XT.OnChipMuted,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Seven signs: thumbs up/down, victory, pointing up, open palm, fist, I love you",
            color = XT.OnChipMuted,
            fontSize = 11.sp,
        )
    }
}
