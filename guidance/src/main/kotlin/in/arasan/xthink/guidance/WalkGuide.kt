package `in`.arasan.xthink.guidance

/** What the walker should do now. One thing at a time, like the camera's arrows. */
enum class WalkVerb { CLEAR, SLOW, STOP, KEEP_LEFT, KEEP_RIGHT }

/** The instruction, its words, and the shape it takes under the thumb. */
data class WalkAdvice(val verb: WalkVerb, val words: String, val cue: HapticCue)

/** Something to say, and the pulse that goes with it. */
data class WalkAnnouncement(val words: String, val cue: HapticCue)

/** One thing worth remembering from the walk, for the journal. */
data class WalkEvent(val atMs: Long, val text: String)

/**
 * Walking with the camera pointed ahead. The detector finds things in
 * the frame; this decides which of them are in the way and what to say
 * about it - stop, slow, keep left or right, or nothing - with the same
 * discipline as the camera's guidance: smoothed, one instruction at a
 * time, held before it changes, hysteresis on the way out.
 *
 * What counts as an obstacle is geometry, not a label: a box low in the
 * frame (at walking height, near) and big enough to matter. Ahead is the
 * middle third; the sides are the rest. Sizes are smoothed so a detector
 * flicker does not become a shout.
 *
 * The walk is timed - five minutes and it ends - and everything that
 * happened is kept as a short log for the journal. Pure; :app feeds
 * frames and steps, speaks the words, plays the cues, writes the file.
 */
class WalkGuide(private val startMs: Long, private val limitMs: Long = LIMIT_MS) {

    private var ahead = 0f
    private var left = 0f
    private var right = 0f
    private var current = WalkVerb.CLEAR
    private var shownSinceMs = startMs
    private var lastStepMs = -1L
    private var stepCount = 0
    private var wasWalking = false
    private var walkingSinceMs = -1L
    private var stillSinceMs = startMs
    private var lastSpoken: WalkVerb? = null
    private var lastSpokenMs = 0L
    private var saidYouCanWalk = false
    private var ended = false
    private var endedMs = -1L
    private var walkedMs = 0L
    private var stops = 0
    private var steers = 0
    private val log = mutableListOf<WalkEvent>()

    /** The log so far, oldest first. */
    val events: List<WalkEvent> get() = log
    val steps: Int get() = stepCount
    val verb: WalkVerb get() = current

    fun remainingMs(now: Long): Long = (limitMs - (now - startMs)).coerceAtLeast(0L)
    fun over(now: Long): Boolean = now - startMs >= limitMs

    /** A step was felt. */
    fun onStep(now: Long) {
        stepCount++
        lastStepMs = now
    }

    /** Steps in the last little while: walking. None: standing. */
    fun walking(now: Long): Boolean = lastStepMs >= 0 && now - lastStepMs < STILL_MS

    /**
     * The detector's boxes for one frame, as fractions of the frame,
     * top-left origin. Returns what the walker should do now.
     */
    fun onObjects(boxes: List<SubjectBox>, now: Long): WalkAdvice {
        val near = boxes.filter { it.bottom > NEAR_BOTTOM && it.w * it.h >= MIN_AREA }
        fun area(pred: (SubjectBox) -> Boolean): Float =
            near.filter(pred).sumOf { (it.w * it.h).toDouble() }.toFloat().coerceAtMost(1f)
        val a = area { it.cx in LEFT_EDGE..RIGHT_EDGE }
        val l = area { it.cx < LEFT_EDGE }
        val r = area { it.cx > RIGHT_EDGE }
        ahead += ALPHA * (a - ahead)
        left += ALPHA * (l - left)
        right += ALPHA * (r - right)
        val want = decide()
        if (want != current && now - shownSinceMs >= LOCKOUT_MS) {
            current = want
            shownSinceMs = now
            if (want == WalkVerb.STOP) stops++
            if (want == WalkVerb.KEEP_LEFT || want == WalkVerb.KEEP_RIGHT) steers++
            note(now, when (want) {
                WalkVerb.STOP -> "Obstacle ahead - stop"
                WalkVerb.SLOW -> "Something ahead - slowed"
                WalkVerb.KEEP_LEFT -> "Obstacle on the right - kept left"
                WalkVerb.KEEP_RIGHT -> "Obstacle on the left - kept right"
                WalkVerb.CLEAR -> "Path clear"
            })
        }
        return advice(current)
    }

    /**
     * What to say now, if anything: a changed instruction, STOP repeated
     * while the walker keeps walking into it, or - after standing a while
     * with the way clear - that they can go. Call often; it answers rarely.
     */
    fun announcement(now: Long): WalkAnnouncement? {
        // Walking / standing transitions for the journal.
        val walkingNow = walking(now)
        if (walkingNow != wasWalking) {
            if (walkingNow) {
                walkingSinceMs = now
                note(now, "Started walking" + if (stillSinceMs >= 0) " after standing ${(now - stillSinceMs) / 1000} s" else "")
            } else {
                stillSinceMs = now
                if (walkingSinceMs >= 0) {
                    walkedMs += now - walkingSinceMs
                    note(now, "Stood still after walking ${(now - walkingSinceMs) / 1000} s")
                }
            }
            wasWalking = walkingNow
            saidYouCanWalk = false
        }
        val a = advice(current)
        if (current != lastSpoken) {
            lastSpoken = current
            lastSpokenMs = now
            return WalkAnnouncement(a.words, a.cue)
        }
        if (current == WalkVerb.STOP && walkingNow && now - lastSpokenMs >= REPEAT_MS) {
            lastSpokenMs = now
            return WalkAnnouncement("Stop.", HapticCue.DIR_BACK)
        }
        if (current == WalkVerb.CLEAR && !walkingNow && !saidYouCanWalk && now - stillSinceMs >= STANDING_PROMPT_MS && now - lastSpokenMs >= REPEAT_MS) {
            saidYouCanWalk = true
            lastSpokenMs = now
            return WalkAnnouncement("Path is clear. You can walk.", HapticCue.LOCK)
        }
        return null
    }

    /** The walk is over - the time ran out, or the walker stopped it. Once. */
    fun end(now: Long, why: String) {
        if (ended) return
        ended = true
        endedMs = now
        if (wasWalking && walkingSinceMs >= 0) { walkedMs += now - walkingSinceMs; wasWalking = false }
        note(now, "Walk ended ($why) after ${clock(now)}, $stepCount steps")
    }

    /**
     * The numbers, worked out here so the model never has to count: how
     * long, how many steps, how much walking and standing, how many stops
     * and steers. What the journal is written from - and what goes in the
     * file whatever the model makes of it.
     */
    fun facts(now: Long = endedMs): String {
        val at = if (now >= 0) now else startMs
        val total = ((at - startMs) / 1000L).coerceAtLeast(0L)
        val walked = (walkedMs / 1000L).coerceIn(0L, total)
        val standing = total - walked
        val stopTimes = log.filter { it.text.startsWith("Obstacle ahead") }.joinToString(", ") { clock(it.atMs) }
        return "Duration ${clock(at)} - that is $total seconds. Steps: $stepCount. Walking: $walked seconds. Standing: $standing seconds. " +
            "Stopped for an obstacle $stops time${if (stops == 1) "" else "s"}${if (stopTimes.isNotEmpty()) " (at $stopTimes)" else ""}. Steered around something $steers time${if (steers == 1) "" else "s"}."
    }

    /** The log as lines - "m:ss  what happened" - for the journal and the model. */
    fun journal(): String = log.joinToString("\n") { "${clock(it.atMs)}  ${it.text}" }

    private fun clock(atMs: Long): String {
        val s = ((atMs - startMs) / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun note(now: Long, text: String) {
        log += WalkEvent(now, text)
        if (log.size > LOG_MAX) log.removeAt(0)
    }

    private fun decide(): WalkVerb {
        // Leaving a state takes less than entering it, so a box on the
        // threshold does not flap.
        val stopIn = if (current == WalkVerb.STOP) STOP_AREA / HYSTERESIS else STOP_AREA
        val slowIn = if (current == WalkVerb.SLOW || current == WalkVerb.STOP) SLOW_AREA / HYSTERESIS else SLOW_AREA
        val sideIn = if (current == WalkVerb.KEEP_LEFT || current == WalkVerb.KEEP_RIGHT) SIDE_AREA / HYSTERESIS else SIDE_AREA
        return when {
            ahead >= stopIn -> WalkVerb.STOP
            ahead >= slowIn -> WalkVerb.SLOW
            left >= sideIn && left >= right -> WalkVerb.KEEP_RIGHT
            right >= sideIn -> WalkVerb.KEEP_LEFT
            else -> WalkVerb.CLEAR
        }
    }

    private fun advice(v: WalkVerb): WalkAdvice = when (v) {
        WalkVerb.STOP -> WalkAdvice(v, "Stop. Obstacle ahead.", HapticCue.DIR_BACK)
        WalkVerb.SLOW -> WalkAdvice(v, "Slow down. Something ahead.", HapticCue.TICK)
        WalkVerb.KEEP_LEFT -> WalkAdvice(v, "Obstacle on your right. Keep left.", HapticCue.DIR_LEFT)
        WalkVerb.KEEP_RIGHT -> WalkAdvice(v, "Obstacle on your left. Keep right.", HapticCue.DIR_RIGHT)
        WalkVerb.CLEAR -> WalkAdvice(v, "Path clear.", HapticCue.LOCK)
    }

    companion object {
        /** The walk ends here, whatever is happening. */
        const val LIMIT_MS = 5L * 60L * 1000L
        /** A box whose bottom edge is above this line is not at walking height. */
        const val NEAR_BOTTOM = 0.55f
        /** Smaller than this and it is far, or noise. */
        const val MIN_AREA = 0.02f
        /** The middle third of the frame is ahead. */
        const val LEFT_EDGE = 0.36f
        const val RIGHT_EDGE = 0.64f
        /** Smoothed area ahead at which the answer is STOP; below, SLOW; a side at SIDE_AREA. */
        const val STOP_AREA = 0.16f
        const val SLOW_AREA = 0.06f
        const val SIDE_AREA = 0.08f
        /** The camera's own smoothing and discipline. */
        const val ALPHA = GuidanceConstants.EMA_ALPHA_BOX
        const val HYSTERESIS = GuidanceConstants.HYSTERESIS_EXIT_MULTIPLIER
        const val LOCKOUT_MS = GuidanceConstants.INSTRUCTION_LOCKOUT_MS
        /** No step for this long: standing. */
        const val STILL_MS = 2500L
        /** STOP is said again this often while the walker keeps walking. */
        const val REPEAT_MS = 2500L
        /** Standing this long with the way clear earns "you can walk". */
        const val STANDING_PROMPT_MS = 5000L
        const val LOG_MAX = 60
    }
}

/**
 * Steps from the accelerometer, without the step sensor's permission:
 * gravity is taken out with a slow average, and a step is a swing past
 * [THRESHOLD] after the signal has come back below half of it, no sooner
 * than [MIN_STEP_MS] after the last. Walking at a normal pace lands
 * between 1.5 and 2.5 steps a second; this catches those and ignores a
 * phone held still, however shaky the hand.
 */
class StepDetector {
    private var gravity = -1f
    private var armed = true
    private var lastStepMs = -1L

    /** @param magnitude |acceleration| in m/s^2. @return true on a step. */
    fun feed(magnitude: Float, nowMs: Long): Boolean {
        if (gravity < 0f) { gravity = magnitude; return false }
        gravity += GRAVITY_ALPHA * (magnitude - gravity)
        val swing = magnitude - gravity
        if (swing < THRESHOLD / 2f) armed = true
        if (armed && swing >= THRESHOLD && (lastStepMs < 0 || nowMs - lastStepMs >= MIN_STEP_MS)) {
            armed = false
            lastStepMs = nowMs
            return true
        }
        return false
    }

    companion object {
        const val GRAVITY_ALPHA = 0.05f
        const val THRESHOLD = 1.6f
        const val MIN_STEP_MS = 280L
    }
}
