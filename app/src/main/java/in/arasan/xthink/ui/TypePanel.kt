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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What TYPE mode shows: the keyboard's state and the text the camera reads. */
data class TypeState(
    val keyboard: String,
    val connected: Boolean,
    val text: String,
    val typing: Boolean = false,
    val lastResult: String? = null,
)

/**
 * TYPE mode. The camera reads whatever it is pointed at; the text sits
 * here, live; one tap types it on the Mac. The keyboard's state is a
 * plain sentence, because pairing is the one step that needs the user.
 */
@Composable
fun TypePanel(
    state: TypeState,
    onPair: () -> Unit,
    onType: () -> Unit,
    onTypeEnter: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.connected) XT.Green else XT.Amber),
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
            if (!state.connected) {
                Answer(text = "Pair Mac", filled = true, onClick = onPair, compact = true)
            }
        }
        val scroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 168.dp)
                .clip(RoundedCornerShape(XT.CornerSmall))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(scroll),
        ) {
            Text(
                text = if (state.text.isBlank()) "Point the camera at text\u2026" else state.text,
                color = if (state.text.isBlank()) XT.OnChipMuted else XT.OnChip,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (state.lastResult != null) {
            Text(text = state.lastResult, color = XT.OnChipMuted, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Answer(
                text = if (state.typing) "Typing\u2026" else "Type",
                filled = false,
                onClick = { if (!state.typing && state.connected) onType() },
                modifier = Modifier.weight(1f),
            )
            Answer(
                text = "Type + \u23ce",
                filled = state.connected,
                onClick = { if (!state.typing && state.connected) onTypeEnter() },
                modifier = Modifier.weight(1f),
            )
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
            .background(if (filled) XT.Amber else XT.Chip)
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
