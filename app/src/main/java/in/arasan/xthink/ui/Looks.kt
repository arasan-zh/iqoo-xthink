package `in`.arasan.xthink.ui

/**
 * The looks. Each is a 4x5 colour matrix - cheap enough to preview on six
 * thumbnails at once and to bake into a 12 MP JPEG in well under a
 * second. Restrained on purpose: a look should make a phone photo feel
 * finished, not filtered.
 */
data class Look(val name: String, val matrix: FloatArray?) {
    val isNatural: Boolean get() = matrix == null
}

object Looks {

    private fun compose(saturation: Float, contrast: Float, warmth: Float, lift: Float): FloatArray {
        // Saturation (luma-preserving), then contrast about mid-grey, then a
        // warm/cool tint on red and blue, then a black lift - one matrix.
        val s = saturation
        val rw = 0.3086f; val gw = 0.6094f; val bw = 0.0820f
        val sat = floatArrayOf(
            rw * (1 - s) + s, gw * (1 - s), bw * (1 - s), 0f, 0f,
            rw * (1 - s), gw * (1 - s) + s, bw * (1 - s), 0f, 0f,
            rw * (1 - s), gw * (1 - s), bw * (1 - s) + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val c = contrast
        val t = (1f - c) * 128f + lift
        val out = FloatArray(20)
        for (row in 0 until 3) {
            for (col in 0 until 5) out[row * 5 + col] = sat[row * 5 + col] * c
            out[row * 5 + 4] += t
        }
        out[18] = 1f
        out[0] *= 1f + warmth * 0.08f
        out[4] += warmth * 6f
        out[12] *= 1f - warmth * 0.08f
        out[14] -= warmth * 6f
        return out
    }

    val ALL: List<Look> = listOf(
        Look("Natural", null),
        Look("Warm", compose(saturation = 1.08f, contrast = 1.05f, warmth = 1f, lift = 0f)),
        Look("Cool", compose(saturation = 1.02f, contrast = 1.05f, warmth = -1f, lift = 0f)),
        Look("Vivid", compose(saturation = 1.35f, contrast = 1.12f, warmth = 0.2f, lift = 0f)),
        Look("Mono", compose(saturation = 0f, contrast = 1.15f, warmth = 0f, lift = 0f)),
        Look("Film", compose(saturation = 0.85f, contrast = 0.92f, warmth = 0.6f, lift = 14f)),
    )
}
