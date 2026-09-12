package `in`.arasan.xthink.guidance

/**
 * Where a subject of a given [ShotType] ought to sit in the frame.
 *
 * Loaded from `assets/composition_profiles.json`. :app reads the asset bytes
 * and hands the text to [parseAll] - this module never touches an Android
 * asset API.
 *
 * @param targetSizeRatio subject box height as a fraction of frame height.
 *        `0` means the shot has no subject to size (LANDSCAPE), and the
 *        distance rung is skipped entirely.
 * @param sizeMin lower edge of the ACCEPTED size band. Inside
 *        `sizeMin..sizeMax` distance is not an error at all - the photographer
 *        chose that scale. Only outside the band does the engine say step
 *        closer or step back. Defaults to [targetSizeRatio], which collapses
 *        the band to a point and gives the original single-target behaviour.
 * @param sizeMax upper edge of the accepted band. See [sizeMin].
 * @param targetEyeLineY where the eye line should sit, 0..1 from the top.
 * @param targetCx horizontal target for the subject centre, before any
 *        gaze-aware lead room is applied.
 * @param headroomMin smallest acceptable gap above the subject's head.
 * @param targetPitchDeg pitch the shot wants, usually level.
 */
data class CompositionProfile(
    val shotType: ShotType,
    val targetSizeRatio: Float,
    val targetEyeLineY: Float,
    val targetCx: Float,
    val headroomMin: Float,
    val targetPitchDeg: Float,
    val sizeMin: Float = targetSizeRatio,
    val sizeMax: Float = targetSizeRatio,
) {
    init {
        require(sizeMin <= sizeMax) { "$shotType: sizeMin $sizeMin > sizeMax $sizeMax" }
    }

    /** True when a subject of this height needs no distance correction. */
    fun acceptsSize(h: Float): Boolean = h >= sizeMin && h <= sizeMax

    companion object {

        /** Parse every profile in the document, keyed by shot type. */
        fun parseAll(json: String): Map<ShotType, CompositionProfile> {
            val root = MiniJson.parseObject(json)
            val out = LinkedHashMap<ShotType, CompositionProfile>()
            for (type in ShotType.entries) {
                val row = root[type.name]
                    ?: throw JsonException("composition_profiles.json is missing the '${type.name}' profile")
                @Suppress("UNCHECKED_CAST")
                val fields = row as? Map<String, Any?>
                    ?: throw JsonException("Profile '${type.name}' must be a JSON object")
                val target = fields.float(type, "targetSizeRatio")
                out[type] = CompositionProfile(
                    shotType = type,
                    targetSizeRatio = target,
                    targetEyeLineY = fields.float(type, "targetEyeLineY"),
                    targetCx = fields.float(type, "targetCx"),
                    headroomMin = fields.float(type, "headroomMin"),
                    targetPitchDeg = fields.float(type, "targetPitchDeg"),
                    sizeMin = fields.floatOr(type, "sizeMin", target),
                    sizeMax = fields.floatOr(type, "sizeMax", target),
                )
            }
            return out
        }

        /** Parse the document and pull out one profile. */
        fun parse(json: String, shotType: ShotType): CompositionProfile =
            parseAll(json).getValue(shotType)

        private fun Map<String, Any?>.floatOr(type: ShotType, key: String, default: Float): Float =
            if (this[key] == null) default else float(type, key)

        private fun Map<String, Any?>.float(type: ShotType, key: String): Float {
            val v = this[key]
                ?: throw JsonException("Profile '${type.name}' is missing '$key'")
            val d = v as? Double
                ?: throw JsonException("Profile '${type.name}' field '$key' must be a number, got $v")
            return d.toFloat()
        }
    }
}
