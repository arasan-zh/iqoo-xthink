package `in`.arasan.xthink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Every colour and dimension the overlay uses, in one place.
 *
 * CLAUDE.md fixes the palette: green accent #4ADE80 on dark translucent chips
 * at Color.Black.copy(alpha = 0.55f), 20dp corners. The overlay is a separate
 * layer from the camera code precisely so this file can be rewritten without
 * anything in :app/camera noticing.
 */
object XT {

    /** The accent. Used for anything the app is telling you. */
    val Green = Color(0xFF4ADE80)

    /** Dimmed accent, for chrome that is present but not speaking. */
    val GreenDim = Color(0xFF4ADE80).copy(alpha = 0.55f)

    /** Off-target. Warm, not alarming - this is a nudge, not an error. */
    val Amber = Color(0xFFFBBF24)

    /** The stock-camera active-mode colour from the reference. */
    val Gold = Color(0xFFF5A524)
    /** Recording red. */
    val Record = Color(0xFFEF4444)

    val Chip = Color.Black.copy(alpha = 0.55f)
    val ChipStrong = Color.Black.copy(alpha = 0.72f)
    val OnChip = Color.White
    val OnChipMuted = Color.White.copy(alpha = 0.62f)

    /** FAKE chrome sits quieter than anything real, on purpose. */
    val Inert = Color.White.copy(alpha = 0.78f)

    val Corner = 20.dp
    val CornerSmall = 14.dp
    val Gutter = 12.dp

    /**
     * Colour for a value that is either good or not yet.
     * Everything in the overlay that can be "on target" uses this, so the
     * whole screen agrees about what green means.
     */
    fun state(ok: Boolean): Color = if (ok) Green else Amber
}
