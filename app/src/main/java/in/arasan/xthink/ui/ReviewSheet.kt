package `in`.arasan.xthink.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What is being reviewed, and what has been chosen so far. */
data class ReviewState(
    val before: ImageBitmap,
    /** The photographer's crop, or null when the photo was already framed. */
    val after: ImageBitmap?,
    val rationale: List<String>,
    /** True = the enhanced picture; false = the original. */
    val enhanced: Boolean,
    val look: Int,
    val saving: Boolean = false,
)

/**
 * The review. After every shutter, before the next: the shot as taken and
 * the photographer's crop side by side, a row of looks, and one decision.
 * Nothing else on the screen is reachable until it is made - that is the
 * point. The chosen picture is saved next to the original; the original
 * is never touched.
 */
@Composable
fun ReviewSheet(
    review: ReviewState,
    coach: CoachText?,
    onChooseEnhanced: (Boolean) -> Unit,
    onChooseLook: (Int) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appear by animateFloatAsState(1f, tween(260), label = "review")
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0C))
            .alpha(appear)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = XT.Gutter)
            .padding(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "YOUR SHOT",
                color = XT.Amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(text = "Original always kept", color = XT.OnChipMuted, fontSize = 11.sp)
        }

        // --- the two pictures; tap to choose ---
        val look = Looks.ALL[review.look]
        val filter = look.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
            Choice(
                label = "AS SHOT",
                image = review.before,
                filter = filter,
                chosen = !review.enhanced,
                onClick = { onChooseEnhanced(false) },
                modifier = Modifier.weight(1f),
            )
            if (review.after != null) {
                Choice(
                    label = "ENHANCED",
                    image = review.after,
                    filter = filter,
                    chosen = review.enhanced,
                    onClick = { onChooseEnhanced(true) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (review.after != null && review.rationale.isNotEmpty()) {
            Text(
                text = review.rationale.joinToString("  ·  "),
                color = XT.OnChipMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // --- the coach's line, when it has one ---
        if (coach != null && (coach.text.isNotBlank() || !coach.done)) {
            Text(
                text = if (coach.text.isBlank()) "Looking…" else coach.text,
                color = if (coach.text.isBlank()) XT.OnChipMuted else XT.OnChip,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(XT.CornerSmall))
                    .background(XT.Chip)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // --- looks ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "LOOK",
                color = XT.OnChipMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            val scroll = rememberScrollState()
            val source = if (review.enhanced && review.after != null) review.after else review.before
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(scroll),
            ) {
                Looks.ALL.forEachIndexed { i, l ->
                    LookTile(
                        look = l,
                        image = source,
                        chosen = i == review.look,
                        onClick = { onChooseLook(i) },
                    )
                }
            }
        }

        // --- the decision ---
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Answer(text = "Discard", filled = false, onClick = onDiscard, modifier = Modifier.weight(1f))
            Answer(
                text = when {
                    review.saving -> "Saving…"
                    !review.enhanced && look.isNatural -> "Keep as shot"
                    else -> "Save this one"
                },
                filled = true,
                onClick = { if (!review.saving) onSave() },
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun Choice(
    label: String,
    image: ImageBitmap,
    filter: ColorFilter?,
    chosen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(RoundedCornerShape(XT.Corner))
                .background(Color.Black)
                .border(
                    width = if (chosen) 2.dp else 1.dp,
                    color = if (chosen) XT.Amber else Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(XT.Corner),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                colorFilter = filter,
                modifier = Modifier.fillMaxSize(),
            )
            if (chosen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(XT.Amber),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✓", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            text = label,
            color = if (chosen) XT.Amber else XT.OnChipMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun LookTile(look: Look, image: ImageBitmap, chosen: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 80.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(
                    width = if (chosen) 2.dp else 0.dp,
                    color = if (chosen) XT.Amber else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            Image(
                bitmap = image,
                contentDescription = look.name,
                contentScale = ContentScale.Crop,
                colorFilter = look.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = look.name,
            color = if (chosen) XT.Amber else XT.OnChipMuted,
            fontSize = 11.sp,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Answer(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
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
            color = if (filled) Color.Black else XT.OnChip,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
