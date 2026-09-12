package `in`.arasan.xthink.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the strip needs of a proposal: two pictures and the first reason. */
data class EnhanceProposal(
    val before: ImageBitmap,
    val after: ImageBitmap,
    val rationale: List<String>,
)

/**
 * The photographer's crop, already saved next to the original. A compact
 * strip - two thumbnails, one reason, Undo - that never covers the view
 * and leaves on its own. The guidance carries on beneath it; so does the
 * auto-shutter.
 */
@Composable
fun EnhanceStrip(
    proposal: EnhanceProposal,
    saved: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.ChipStrong)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Thumb(proposal.before, accent = false)
        Text(text = "→", color = XT.OnChipMuted, fontSize = 14.sp)
        Thumb(proposal.after, accent = true)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (saved) "Enhanced copy saved" else "Saving enhanced copy…",
                color = XT.OnChip,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = proposal.rationale.firstOrNull() ?: "Photographer's crop",
                color = XT.OnChipMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (saved) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(XT.Chip)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onUndo,
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Undo", color = XT.Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Thumb(image: ImageBitmap, accent: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = if (accent) "after" else "before",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
