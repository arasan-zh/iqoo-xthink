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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the card needs of a proposal: two pictures and the reasons. */
data class EnhanceProposal(
    val before: ImageBitmap,
    val after: ImageBitmap,
    val rationale: List<String>,
)

/**
 * The photographer's crop, offered. Before on the left, after on the
 * right, the reasons underneath, and two answers. Nothing is applied
 * until the photographer says so; the original is already on disk.
 */
@Composable
fun EnhanceCard(
    proposal: EnhanceProposal,
    saving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XT.Corner))
            .background(XT.ChipStrong)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PHOTOGRAPHER'S CROP",
                color = XT.Amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Original saved",
                color = XT.OnChipMuted,
                fontSize = 11.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shot(label = "BEFORE", image = proposal.before, modifier = Modifier.weight(1f))
            Shot(label = "AFTER", image = proposal.after, accent = true, modifier = Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            proposal.rationale.take(3).forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(XT.Amber),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = line, color = XT.OnChip, fontSize = 13.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
            Answer(text = "Keep original", filled = false, onClick = onDismiss, modifier = Modifier.weight(1f))
            Answer(
                text = if (saving) "Saving…" else "Save enhanced",
                filled = true,
                onClick = { if (!saving) onSave() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Shot(label: String, image: ImageBitmap, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(XT.CornerSmall))
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = label,
            color = if (accent) XT.Amber else XT.OnChipMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Answer(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(if (filled) XT.Amber else XT.Chip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (filled) androidx.compose.ui.graphics.Color.Black else XT.OnChip,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
