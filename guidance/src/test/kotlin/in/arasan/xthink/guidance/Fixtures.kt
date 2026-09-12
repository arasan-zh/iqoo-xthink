package `in`.arasan.xthink.guidance

/**
 * Shared test scaffolding.
 *
 * The profile JSON is the *actual* file :app ships in `assets/`, put on the
 * test classpath by `guidance/build.gradle.kts`, so the two cannot drift apart
 * without a test going red.
 */
internal object Fixtures {

    val JSON: String = requireNotNull(
        Fixtures::class.java.getResourceAsStream("/composition_profiles.json")
    ) { "composition_profiles.json is not on the :guidance test classpath" }
        .bufferedReader()
        .use { it.readText() }

    val PROFILES: Map<ShotType, CompositionProfile> = CompositionProfile.parseAll(JSON)

    fun profile(shotType: ShotType): CompositionProfile = PROFILES.getValue(shotType)

    /** Perfectly level phone. */
    val LEVEL = Attitude(rollDeg = 0f, pitchDeg = 0f)

    /**
     * A HEADSHOT box that satisfies every HEADSHOT target by default:
     * size 0.45 exactly, centred, headroom 0.175 against a 0.06 minimum.
     */
    fun box(
        cx: Float = 0.50f,
        cy: Float = 0.40f,
        w: Float = 0.34f,
        h: Float = 0.45f,
    ) = SubjectBox(cx = cx, cy = cy, w = w, h = h)

    /** Eye line on the HEADSHOT target, looking down the lens. */
    fun eyes(y: Float = 0.33f, gazeDx: Float = 0f) = EyeLine(y = y, gazeDx = gazeDx)

    /** Drive [frames] identical frames through the engine, return the last instruction. */
    fun GuidanceEngine.feed(
        frames: Int,
        dtMs: Long = 100L,
        attitude: Attitude = LEVEL,
        subject: SubjectBox? = null,
        eyeLine: EyeLine? = null,
    ): Instruction {
        require(frames >= 1) { "need at least one frame" }
        var last: Instruction? = null
        repeat(frames) { last = update(attitude, subject, eyeLine, dtMs) }
        return requireNotNull(last)
    }

    /** The verb a freshly-built engine produces from a single frame. */
    fun firstVerb(
        shotType: ShotType = ShotType.HEADSHOT,
        attitude: Attitude = LEVEL,
        subject: SubjectBox? = null,
        eyeLine: EyeLine? = null,
        mirrored: Boolean = false,
    ): Verb = GuidanceEngine(profile(shotType), mirrored)
        .update(attitude, subject, eyeLine, 100L).verb
}
