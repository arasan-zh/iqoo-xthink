package `in`.arasan.xthink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The coach's words, as they arrive. */
data class CoachText(
    val label: String,
    val text: String,
    val done: Boolean,
)

/**
 * A quiet strip under the top row where the on-device model speaks. Words
 * stream in; while it is still thinking a small dot breathes next to the
 * label so silence never reads as a hang. Nothing here is interactive.
 */
@Composable
fun CoachPanel(coach: CoachText?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = coach != null && (coach.text.isNotBlank() || !coach.done),
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 },
        exit = fadeOut(tween(180)),
        modifier = modifier,
    ) {
        val c = coach ?: return@AnimatedVisibility
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(XT.Corner))
                .background(XT.Chip)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = c.label,
                    color = XT.Amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.width(8.dp))
                if (!c.done) Breathing()
            }
            Text(
                text = if (c.text.isBlank()) "Looking…" else c.text,
                color = if (c.text.isBlank()) XT.OnChipMuted else XT.OnChip,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
        }
    }
}

@Composable
private fun Breathing() {
    val t = rememberInfiniteTransition(label = "coach")
    val a by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .alpha(a)
            .clip(CircleShape)
            .background(XT.Amber),
    )
}
