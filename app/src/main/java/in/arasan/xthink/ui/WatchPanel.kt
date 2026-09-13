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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What WATCH shows. */
data class WatchState(
    val recording: Boolean,
    val remainingMs: Long,
    val lastSeen: String?,
    val lastHeard: String?,
    val seenCount: Int,
    val heardCount: Int,
    /** The model is looking at a frame right now. */
    val looking: Boolean,
    val listening: Boolean,
)

/**
 * WATCH. The camera records for up to five minutes; now and then the
 * model looks and writes down what mattered; what is said is written
 * down too. This card shows the time left, the last note, the last
 * words, and the counts - the file is the product.
 */
@Composable
fun WatchPanel(state: WatchState, onFinish: () -> Unit, modifier: Modifier = Modifier) {
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
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(if (state.recording) XT.Record else XT.OnChipMuted))
            Spacer(Modifier.width(8.dp))
            Text(text = if (state.recording) "WATCHING" else "STARTING", color = XT.OnChipMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Spacer(Modifier.weight(1f))
            Text(text = "%d:%02d left".format(s / 60, s % 60), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            text = state.lastSeen ?: if (state.looking) "Looking…" else "Nothing noted yet",
            color = if (state.lastSeen != null) Color.White else XT.OnChipMuted,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.lastHeard?.let { "“$it”" } ?: if (state.listening) "Listening…" else "Microphone off",
            color = if (state.lastHeard != null) Color.White.copy(alpha = 0.8f) else XT.OnChipMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "${state.seenCount} noted", color = XT.OnChipMuted, fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            Text(text = "${state.heardCount} heard", color = XT.OnChipMuted, fontSize = 12.sp)
            if (state.looking) {
                Spacer(Modifier.width(12.dp))
                Text(text = "looking", color = XT.Green, fontSize = 12.sp)
            }
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
            Text(text = "Finish and write the file", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
