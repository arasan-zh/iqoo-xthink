package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.Fixtures.LEVEL
import `in`.arasan.xthink.guidance.Fixtures.box
import `in`.arasan.xthink.guidance.Fixtures.eyes
import `in`.arasan.xthink.guidance.Fixtures.feed
import `in`.arasan.xthink.guidance.Fixtures.firstVerb
import `in`.arasan.xthink.guidance.Fixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's contract, rung by rung.
 *
 * Note on timing: every EMA seeds on its first sample, so a fresh engine given
 * one frame reports that frame's values exactly. That is what lets most of
 * these tests assert on a single `update` call. Tests that need the smoothing
 * to settle after a *change* feed enough frames to converge.
 */
class GuidanceEngineTest {

    private val headshot = profile(ShotType.HEADSHOT)

    // ---------------------------------------------------------------------
    // The priority ladder: roll -> pitch -> distance -> x/y -> focus -> LOCKED
    // ---------------------------------------------------------------------

    /**
     * One fixture, walked down the ladder by fixing one channel at a time.
     * Each step builds a fresh engine so smoothing lag cannot blur the result.
     */
    @Test
    fun `every priority transition, one rung at a time`() {
        // cx 0.80 is off target, the box is far too small, eye line is on target.
        val wrong = box(cx = 0.80f, cy = 0.40f, w = 0.10f, h = 0.10f)
        val onEyeLine = eyes(y = 0.33f)

        // 1. Roll outranks a wrong pitch, a wrong distance and wrong framing.
        assertEquals(
            Verb.LEVEL_CCW,
            firstVerb(attitude = Attitude(12f, 15f), subject = wrong, eyeLine = onEyeLine),
        )

        // 2. Roll fixed -> pitch. Framing is already good vertically, so this
        //    is a genuine rotation error and TILT is correct.
        assertEquals(
            Verb.TILT_DOWN,
            firstVerb(attitude = Attitude(0f, 15f), subject = wrong, eyeLine = onEyeLine),
        )

        // 3. Pitch fixed -> distance.
        assertEquals(
            Verb.STEP_CLOSER,
            firstVerb(attitude = LEVEL, subject = wrong, eyeLine = onEyeLine),
        )

        // 4. Distance fixed -> x/y framing.
        val rightSize = box(cx = 0.80f)
        assertEquals(
            Verb.MOVE_RIGHT,
            firstVerb(attitude = LEVEL, subject = rightSize, eyeLine = onEyeLine),
        )

        // 5. Framing fixed -> tap focus.
        assertEquals(
            Verb.TAP_FOCUS,
            firstVerb(attitude = LEVEL, subject = box(), eyeLine = onEyeLine),
        )

        // 6. Focus tapped and the dwell served -> LOCKED.
        val engine = GuidanceEngine(headshot)
        engine.feed(frames = 10, subject = box(), eyeLine = onEyeLine)
        engine.reportFocusLocked(true)
        assertEquals(Verb.LOCKED, engine.feed(frames = 6, subject = box(), eyeLine = onEyeLine).verb)
    }

    @Test
    fun `roll sign picks the rotation direction`() {
        assertEquals(Verb.LEVEL_CCW, firstVerb(attitude = Attitude(12f, 0f), subject = box(), eyeLine = eyes()))
        assertEquals(Verb.LEVEL_CW, firstVerb(attitude = Attitude(-12f, 0f), subject = box(), eyeLine = eyes()))
    }

    @Test
    fun `distance outranks framing in both directions`() {
        val tooSmallAndOffCentre = box(cx = 0.75f, h = 0.20f)
        assertEquals(Verb.STEP_CLOSER, firstVerb(subject = tooSmallAndOffCentre, eyeLine = eyes()))

        val tooBigAndOffCentre = box(cx = 0.75f, cy = 0.45f, h = 0.70f)
        assertEquals(Verb.STEP_BACK, firstVerb(subject = tooBigAndOffCentre, eyeLine = eyes()))
    }

    @Test
    fun `horizontal framing points the phone towards the subject's side`() {
        // Subject sits right of target -> pan right so it slides back to centre.
        assertEquals(Verb.MOVE_RIGHT, firstVerb(subject = box(cx = 0.70f), eyeLine = eyes()))
        assertEquals(Verb.MOVE_LEFT, firstVerb(subject = box(cx = 0.30f), eyeLine = eyes()))
    }

    @Test
    fun `vertical framing uses the eye line`() {
        // Eye line too low in frame -> aim the camera down to lift the subject.
        assertEquals(
            Verb.MOVE_DOWN,
            firstVerb(subject = box(cy = 0.60f), eyeLine = eyes(y = 0.55f)),
        )
        assertEquals(
            Verb.MOVE_UP,
            firstVerb(subject = box(cy = 0.40f), eyeLine = eyes(y = 0.15f)),
        )
    }

    @Test
    fun `a headroom violation overrides an otherwise happy eye line`() {
        // Eye line is exactly on target, but the top of the head is above the
        // frame edge. Cropping a scalp is worse than a slightly low eye line.
        val cropped = box(cy = 0.20f, h = 0.45f)
        assertEquals(Verb.MOVE_UP, firstVerb(subject = cropped, eyeLine = eyes(y = 0.33f)))
    }

    @Test
    fun `the larger framing error wins when both axes are out`() {
        assertEquals(
            Verb.MOVE_RIGHT,
            firstVerb(subject = box(cx = 0.85f, cy = 0.45f), eyeLine = eyes(y = 0.41f)),
        )
        assertEquals(
            Verb.MOVE_DOWN,
            firstVerb(subject = box(cx = 0.57f, cy = 0.70f), eyeLine = eyes(y = 0.68f)),
        )
    }

    @Test
    fun `only ever one instruction, never two arrows`() {
        // Everything is wrong at once. The engine still says exactly one thing.
        val everythingWrong = box(cx = 0.88f, cy = 0.15f, w = 0.05f, h = 0.05f)
        val instruction = GuidanceEngine(headshot)
            .update(Attitude(22f, 19f), everythingWrong, eyes(y = 0.80f, gazeDx = 0.9f), 100L)
        assertEquals(Verb.LEVEL_CCW, instruction.verb)
        assertTrue(instruction.text.isNotBlank())
    }

    // ---------------------------------------------------------------------
    // Translation beats rotation
    // ---------------------------------------------------------------------

    @Test
    fun `pitch off and framing off the same way means move, not tilt`() {
        // Camera aimed up by 15 degrees, so the subject has slid low in frame.
        // Lowering the phone fixes both at once.
        val verb = firstVerb(
            attitude = Attitude(0f, 15f),
            subject = box(cy = 0.60f),
            eyeLine = eyes(y = 0.55f),
        )
        assertEquals(Verb.MOVE_DOWN, verb)
        assertNotEquals(Verb.TILT_DOWN, verb)
    }

    @Test
    fun `the same rule holds pointing the other way`() {
        val verb = firstVerb(
            attitude = Attitude(0f, -15f),
            subject = box(cy = 0.40f),
            eyeLine = eyes(y = 0.15f),
        )
        assertEquals(Verb.MOVE_UP, verb)
        assertNotEquals(Verb.TILT_UP, verb)
    }

    @Test
    fun `pitch off with framing already good is a real rotation error`() {
        assertEquals(
            Verb.TILT_DOWN,
            firstVerb(attitude = Attitude(0f, 15f), subject = box(), eyeLine = eyes(y = 0.33f)),
        )
        assertEquals(
            Verb.TILT_UP,
            firstVerb(attitude = Attitude(0f, -15f), subject = box(), eyeLine = eyes(y = 0.33f)),
        )
    }

    @Test
    fun `pitch and framing disagreeing in sign is also a rotation error`() {
        // Aimed up, yet the subject is high in frame. Moving the phone cannot
        // fix both, so correct the rotation.
        assertEquals(
            Verb.TILT_DOWN,
            firstVerb(attitude = Attitude(0f, 15f), subject = box(cy = 0.40f), eyeLine = eyes(y = 0.15f)),
        )
    }

    /** The rule as written in CLAUDE.md: pitch near target, framing off -> move. */
    @Test
    fun `pitch inside its deadzone leaves vertical framing to a translation`() {
        val verb = firstVerb(
            attitude = Attitude(0f, 3f), // inside the 6 degree pitch deadzone
            subject = box(cy = 0.60f),
            eyeLine = eyes(y = 0.55f),
        )
        assertEquals(Verb.MOVE_DOWN, verb)
    }

    // ---------------------------------------------------------------------
    // Deadzones and hysteresis
    // ---------------------------------------------------------------------

    @Test
    fun `errors inside a deadzone produce no instruction`() {
        // Roll 2.0 (< 2.5), pitch 4 (< 6), x off by 0.04 (< 0.06).
        val verb = firstVerb(
            attitude = Attitude(2.0f, 4.0f),
            subject = box(cx = 0.54f),
            eyeLine = eyes(y = 0.36f),
        )
        assertEquals(Verb.TAP_FOCUS, verb)
    }

    @Test
    fun `a deadzone is entered at 1x and left at 1_6x`() {
        val engine = GuidanceEngine(headshot)
        val subject = box()
        val eye = eyes()

        // 3.0 degrees is outside the 2.5 entry threshold.
        assertEquals(
            Verb.LEVEL_CCW,
            engine.update(Attitude(3.0f, 0f), subject, eye, 100L).verb,
        )

        // Settle at 2.0, which is inside. The instruction clears.
        assertEquals(
            Verb.TAP_FOCUS,
            engine.feed(40, attitude = Attitude(2.0f, 0f), subject = subject, eyeLine = eye).verb,
        )

        // 3.5 is past the 2.5 entry threshold but short of the 4.0 exit
        // threshold. Hysteresis keeps us in the deadzone: no new instruction.
        assertEquals(
            "3.5 deg is above entry but below exit - must not re-trigger",
            Verb.TAP_FOCUS,
            engine.feed(40, attitude = Attitude(3.5f, 0f), subject = subject, eyeLine = eye).verb,
        )

        // 5.0 finally clears the exit threshold.
        assertEquals(
            Verb.LEVEL_CCW,
            engine.feed(40, attitude = Attitude(5.0f, 0f), subject = subject, eyeLine = eye).verb,
        )
    }

    // ---------------------------------------------------------------------
    // 600 ms instruction lockout
    // ---------------------------------------------------------------------

    @Test
    fun `a shown instruction is held for 600ms before a different verb replaces it`() {
        val engine = GuidanceEngine(headshot)
        val offCentre = box(cx = 0.70f)
        val eye = eyes()

        // Frame 1 at t=0: adopted immediately, hold timer starts.
        assertEquals(Verb.MOVE_RIGHT, engine.update(LEVEL, offCentre, eye, 100L).verb)

        // A roll error now appears and outranks the framing error. One frame of
        // EMA on a 40 degree roll is 6 degrees, already past the 4.0 exit
        // threshold, so the *candidate* is LEVEL_CCW from frame 2 onwards.
        val tilted = Attitude(40f, 0f)
        for (frame in 2..6) {
            assertEquals(
                "frame $frame, held for ${(frame - 1) * 100}ms - too early to switch",
                Verb.MOVE_RIGHT,
                engine.update(tilted, offCentre, eye, 100L).verb,
            )
        }

        // Frame 7: the hold timer reaches 600ms and the switch lands.
        assertEquals(Verb.LEVEL_CCW, engine.update(tilted, offCentre, eye, 100L).verb)
    }

    @Test
    fun `the same verb refreshes its magnitude without waiting out the lockout`() {
        val engine = GuidanceEngine(headshot)
        val eye = eyes()

        val first = engine.update(LEVEL, box(cx = 0.58f), eye, 100L)
        assertEquals(Verb.MOVE_RIGHT, first.verb)
        assertEquals(Magnitude.NUDGE, first.magnitude)

        // 100ms later - well inside the lockout - the same arrow gets louder.
        val second = engine.update(LEVEL, box(cx = 0.90f), eye, 100L)
        assertEquals(Verb.MOVE_RIGHT, second.verb)
        assertEquals(Magnitude.MOVE, second.magnitude)
    }

    // ---------------------------------------------------------------------
    // 400 ms lock dwell
    // ---------------------------------------------------------------------

    @Test
    fun `LOCKED needs 400ms of everything being inside its deadzone`() {
        val engine = GuidanceEngine(headshot)
        val subject = box()
        val eye = eyes()

        // Settle a well-framed shot. Focus has not been tapped, so the engine
        // asks for that and the dwell timer stays at zero.
        assertEquals(Verb.TAP_FOCUS, engine.feed(10, subject = subject, eyeLine = eye).verb)
        assertTrue(engine.compositionOk)
        assertEquals(0f, engine.lockProgress, 1e-6f)

        engine.reportFocusLocked(true)

        // 100, 200, 300ms of dwell: not yet.
        for (elapsed in 1..3) {
            assertEquals(
                "${elapsed * 100}ms of dwell is not 400ms",
                Verb.TAP_FOCUS,
                engine.update(LEVEL, subject, eye, 100L).verb,
            )
        }

        // 400ms. The 600ms lockout has long since expired on TAP_FOCUS, so the
        // lock lands on this frame.
        assertEquals(Verb.LOCKED, engine.update(LEVEL, subject, eye, 100L).verb)
        assertEquals(1f, engine.lockProgress, 1e-6f)
        assertEquals(0f, engine.totalError, 1e-6f)
    }

    @Test
    fun `the dwell restarts after a disturbance`() {
        val engine = GuidanceEngine(headshot)
        val subject = box()
        val eye = eyes()

        engine.feed(10, subject = subject, eyeLine = eye)
        engine.reportFocusLocked(true)
        engine.feed(3, subject = subject, eyeLine = eye) // 300ms of dwell banked

        // One jolt knocks roll out of its deadzone and empties the dwell.
        engine.update(Attitude(30f, 0f), subject, eye, 100L)
        assertEquals(0f, engine.lockProgress, 1e-6f)

        // Back to level, but the banked dwell is gone: no lock for a while yet.
        for (frame in 1..6) {
            assertNotEquals(
                "frame $frame after the jolt must not be locked",
                Verb.LOCKED,
                engine.update(LEVEL, subject, eye, 100L).verb,
            )
        }
    }

    @Test
    fun `a subject-free landscape locks once the phone is level`() {
        val engine = GuidanceEngine(profile(ShotType.LANDSCAPE))
        assertNotEquals(Verb.LOCKED, engine.feed(3, attitude = LEVEL).verb)
        // 400ms of dwell, then the 600ms lockout on the instruction that was
        // showing during the dwell - the lock arrives behind both.
        assertEquals(Verb.LOCKED, engine.feed(10, attitude = LEVEL).verb)
        assertTrue(engine.focusOk)
    }

    // ---------------------------------------------------------------------
    // Mirrored front camera
    // ---------------------------------------------------------------------

    @Test
    fun `mirrored inverts MOVE_LEFT and MOVE_RIGHT`() {
        val subjectRight = box(cx = 0.70f)
        assertEquals(Verb.MOVE_RIGHT, firstVerb(subject = subjectRight, eyeLine = eyes(), mirrored = false))
        assertEquals(Verb.MOVE_LEFT, firstVerb(subject = subjectRight, eyeLine = eyes(), mirrored = true))

        val subjectLeft = box(cx = 0.30f)
        assertEquals(Verb.MOVE_LEFT, firstVerb(subject = subjectLeft, eyeLine = eyes(), mirrored = false))
        assertEquals(Verb.MOVE_RIGHT, firstVerb(subject = subjectLeft, eyeLine = eyes(), mirrored = true))
    }

    @Test
    fun `mirrored text matches the mirrored verb`() {
        val instruction = GuidanceEngine(headshot, mirrored = true)
            .update(LEVEL, box(cx = 0.70f), eyes(), 100L)
        assertEquals(Verb.MOVE_LEFT, instruction.verb)
        assertTrue(instruction.text, instruction.text.contains("left"))
    }

    @Test
    fun `mirrored leaves vertical and rotation verbs alone`() {
        // Up is up in a mirror.
        assertEquals(
            Verb.MOVE_DOWN,
            firstVerb(subject = box(cy = 0.60f), eyeLine = eyes(y = 0.55f), mirrored = true),
        )
        assertEquals(
            Verb.TILT_DOWN,
            firstVerb(attitude = Attitude(0f, 15f), subject = box(), eyeLine = eyes(), mirrored = true),
        )
        assertEquals(
            Verb.LEVEL_CCW,
            firstVerb(attitude = Attitude(12f, 0f), subject = box(), eyeLine = eyes(), mirrored = true),
        )
    }

    @Test
    fun `mirroring is its own inverse and touches nothing else`() {
        for (verb in Verb.entries) {
            assertEquals(verb, GuidanceEngine.mirror(GuidanceEngine.mirror(verb)))
            if (verb != Verb.MOVE_LEFT && verb != Verb.MOVE_RIGHT) {
                assertEquals(verb, GuidanceEngine.mirror(verb))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Gaze-aware lead room
    // ---------------------------------------------------------------------

    @Test
    fun `looking frame-right moves the target to the left third`() {
        // Target cx becomes 0.333, so a centred subject is now too far right.
        assertEquals(
            Verb.MOVE_RIGHT,
            firstVerb(subject = box(cx = 0.50f), eyeLine = eyes(gazeDx = 0.5f)),
        )
        // ...and a subject already on the left third is correctly framed.
        assertEquals(
            Verb.TAP_FOCUS,
            firstVerb(subject = box(cx = 0.333f), eyeLine = eyes(gazeDx = 0.5f)),
        )
    }

    @Test
    fun `looking frame-left moves the target to the right third`() {
        assertEquals(
            Verb.MOVE_LEFT,
            firstVerb(subject = box(cx = 0.50f), eyeLine = eyes(gazeDx = -0.5f)),
        )
        assertEquals(
            Verb.TAP_FOCUS,
            firstVerb(subject = box(cx = 0.667f), eyeLine = eyes(gazeDx = -0.5f)),
        )
    }

    @Test
    fun `a gaze down the lens keeps the subject centred`() {
        // Inside the gaze deadzone: no lead room, profile target stands.
        assertEquals(
            Verb.TAP_FOCUS,
            firstVerb(subject = box(cx = 0.50f), eyeLine = eyes(gazeDx = 0.10f)),
        )
        assertEquals(
            Verb.TAP_FOCUS,
            firstVerb(subject = box(cx = 0.50f), eyeLine = eyes(gazeDx = -0.10f)),
        )
        // A centred subject with a centred gaze pushed to 0.667 would be wrong.
        assertNotEquals(
            Verb.MOVE_LEFT,
            firstVerb(subject = box(cx = 0.50f), eyeLine = eyes(gazeDx = 0f)),
        )
    }

    @Test
    fun `lead room is skipped for OBJECT`() {
        val objectBox = box(cx = 0.50f, cy = 0.50f, w = 0.40f, h = 0.55f)
        val withGaze = eyes(y = 0.50f, gazeDx = 0.9f)
        assertEquals(
            "an object has no gaze to lead",
            Verb.TAP_FOCUS,
            firstVerb(shotType = ShotType.OBJECT, subject = objectBox, eyeLine = withGaze),
        )
    }

    @Test
    fun `lead room is skipped for LANDSCAPE, and so is the distance rung`() {
        // h = 0.20 would be badly wrong for any profile that sizes its subject.
        val anyBox = box(cx = 0.50f, cy = 0.33f, w = 0.20f, h = 0.20f)
        assertEquals(
            Verb.TAP_FOCUS,
            firstVerb(shotType = ShotType.LANDSCAPE, subject = anyBox, eyeLine = eyes(y = 0.33f, gazeDx = 0.9f)),
        )
    }

    @Test
    fun `lead room is applied for the people profiles`() {
        for (shotType in listOf(ShotType.HEADSHOT, ShotType.HALF_BODY, ShotType.GROUP)) {
            val p = profile(shotType)
            val subject = box(cx = 0.50f, cy = 0.45f, h = p.targetSizeRatio)
            assertEquals(
                shotType.name,
                Verb.MOVE_RIGHT,
                firstVerb(
                    shotType = shotType,
                    subject = subject,
                    eyeLine = eyes(y = p.targetEyeLineY, gazeDx = 0.5f),
                ),
            )
        }
    }

    // ---------------------------------------------------------------------
    // Magnitude
    // ---------------------------------------------------------------------

    @Test
    fun `magnitude buckets scale with the channel's deadzone`() {
        fun rollMagnitude(deg: Float): Magnitude =
            GuidanceEngine(headshot).update(Attitude(deg, 0f), box(), eyes(), 100L).magnitude

        // Roll deadzone is 2.5: NUDGE to 5.0, MOVE to 10.0, BIG beyond.
        assertEquals(Magnitude.NUDGE, rollMagnitude(4.0f))
        assertEquals(Magnitude.MOVE, rollMagnitude(8.0f))
        assertEquals(Magnitude.BIG, rollMagnitude(20.0f))
    }

    @Test
    fun `text carries the magnitude, except for the terminal verbs`() {
        assertEquals("Rotate left to level a little", GuidanceEngine.textFor(Verb.LEVEL_CCW, Magnitude.NUDGE))
        assertEquals("Rotate left to level", GuidanceEngine.textFor(Verb.LEVEL_CCW, Magnitude.MOVE))
        assertEquals("Rotate left to level a lot", GuidanceEngine.textFor(Verb.LEVEL_CCW, Magnitude.BIG))
        assertEquals("Locked", GuidanceEngine.textFor(Verb.LOCKED, Magnitude.BIG))
        assertEquals("Tap to focus", GuidanceEngine.textFor(Verb.TAP_FOCUS, Magnitude.BIG))
    }

    @Test
    fun `every verb has non-blank text at every magnitude, and never says Nilai`() {
        for (verb in Verb.entries) {
            for (magnitude in Magnitude.entries) {
                val text = GuidanceEngine.textFor(verb, magnitude)
                assertTrue("$verb/$magnitude", text.isNotBlank())
                assertFalse(text, text.contains("Nilai", ignoreCase = true))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Status flags and totalError
    // ---------------------------------------------------------------------

    @Test
    fun `totalError is zero inside the deadzones and rises with the error`() {
        val clean = GuidanceEngine(headshot)
        clean.update(Attitude(2.0f, 4.0f), box(cx = 0.54f), eyes(y = 0.36f), 100L)
        assertEquals("inside every deadzone must read as zero", 0f, clean.totalError, 1e-6f)

        val small = GuidanceEngine(headshot)
        small.update(Attitude(12f, 0f), box(), eyes(), 100L)

        val large = GuidanceEngine(headshot)
        large.update(Attitude(30f, 0f), box(), eyes(), 100L)

        assertTrue(small.totalError > 0f)
        assertTrue(large.totalError > small.totalError)
        assertTrue(large.totalError <= 1f)
    }

    @Test
    fun `totalError stays within 0 and 1 under absurd input`() {
        val engine = GuidanceEngine(headshot)
        engine.update(Attitude(179f, -120f), box(cx = 0.99f, cy = 0.01f, w = 0.99f, h = 0.99f), eyes(y = 0.99f), 100L)
        assertTrue(engine.totalError.toString(), engine.totalError in 0f..1f)
        assertEquals(1f, engine.totalError, 1e-5f)
    }

    @Test
    fun `compositionOk tracks the geometric deadzones only`() {
        val good = GuidanceEngine(headshot)
        good.update(LEVEL, box(), eyes(), 100L)
        assertTrue(good.compositionOk)
        assertFalse("focus is a separate flag", good.focusOk)

        val crooked = GuidanceEngine(headshot)
        crooked.update(Attitude(12f, 0f), box(), eyes(), 100L)
        assertFalse(crooked.compositionOk)
    }

    @Test
    fun `focusOk is true when there is nothing to focus on`() {
        val engine = GuidanceEngine(profile(ShotType.LANDSCAPE))
        engine.update(LEVEL, null, null, 100L)
        assertTrue(engine.focusOk)
    }

    @Test
    fun `focusOk needs a reported focus lock while a subject is present`() {
        val engine = GuidanceEngine(headshot)
        engine.update(LEVEL, box(), eyes(), 100L)
        assertFalse(engine.focusOk)

        engine.reportFocusLocked(true)
        engine.update(LEVEL, box(), eyes(), 100L)
        assertTrue(engine.focusOk)
    }

    @Test
    fun `focus goes stale when the subject walks away from where it was tapped`() {
        val engine = GuidanceEngine(headshot)
        engine.feed(6, subject = box(), eyeLine = eyes())
        engine.reportFocusLocked(true)
        assertTrue(engine.focusOk)

        // Subject slides across the frame, past the invalidation radius.
        engine.feed(4, subject = box(cx = 0.90f), eyeLine = eyes())
        assertFalse("focus was tapped on a subject that has since moved", engine.focusOk)
    }

    @Test
    fun `stabilityOk is true when the phone is still and false when it is thrown about`() {
        val steady = GuidanceEngine(headshot)
        steady.feed(6, subject = box(), eyeLine = eyes())
        assertTrue(steady.stabilityOk)

        val shaken = GuidanceEngine(headshot)
        shaken.update(Attitude(0f, 0f), box(), eyes(), 50L)
        shaken.update(Attitude(20f, 0f), box(), eyes(), 50L)
        assertFalse(shaken.stabilityOk)
    }

    // ---------------------------------------------------------------------
    // Alignment telemetry for the HUD
    // ---------------------------------------------------------------------

    @Test
    fun `alignment reports signed errors for the horizon and the reticle`() {
        val engine = GuidanceEngine(headshot)
        engine.update(Attitude(10f, -8f), box(cx = 0.70f, cy = 0.40f), eyes(y = 0.33f), 100L)

        val a = engine.alignment
        assertEquals(10f, a.rollDeg, 1e-4f)
        assertEquals(-8f, a.pitchDeg, 1e-4f)
        assertEquals(10f, a.rollErrDeg, 1e-4f)
        assertEquals(-8f, a.pitchErrDeg, 1e-4f)
        assertEquals(0.20f, a.offsetX, 1e-4f)
        assertEquals(0f, a.offsetY, 1e-4f)
        assertFalse(a.rollInDeadzone)
        assertFalse(a.pitchInDeadzone)
        assertTrue(a.distanceInDeadzone)
        assertFalse(a.framingInDeadzone)
        assertEquals(engine.totalError, a.totalError, 1e-6f)
    }

    @Test
    fun `lockProgress fills over the dwell`() {
        val engine = GuidanceEngine(headshot)
        engine.feed(4, subject = box(), eyeLine = eyes())
        engine.reportFocusLocked(true)
        engine.update(LEVEL, box(), eyes(), 100L)
        assertEquals(0.25f, engine.lockProgress, 1e-6f)
        engine.update(LEVEL, box(), eyes(), 100L)
        assertEquals(0.50f, engine.lockProgress, 1e-6f)
    }

    // ---------------------------------------------------------------------
    // Housekeeping
    // ---------------------------------------------------------------------

    @Test
    fun `reset clears smoothing, timers and the shown instruction`() {
        val engine = GuidanceEngine(headshot)
        engine.feed(10, attitude = Attitude(20f, 0f), subject = box(), eyeLine = eyes())
        engine.reset()

        // A fresh engine would adopt a new instruction on its very first frame,
        // with no lockout inherited from before.
        assertEquals(Verb.TAP_FOCUS, engine.update(LEVEL, box(), eyes(), 100L).verb)
        assertEquals(0f, engine.totalError, 1e-6f)
    }

    @Test
    fun `setProfile retargets the composition`() {
        val engine = GuidanceEngine(headshot)
        // A face at 0.18 of frame height is wrong for a HEADSHOT...
        assertEquals(Verb.STEP_CLOSER, engine.update(LEVEL, box(h = 0.18f), eyes(y = 0.35f), 100L).verb)

        engine.reset()
        engine.setProfile(profile(ShotType.GROUP))
        // ...and right for a GROUP.
        assertEquals(Verb.TAP_FOCUS, engine.update(LEVEL, box(h = 0.18f), eyes(y = 0.35f), 100L).verb)
    }

    @Test
    fun `a negative dt cannot rewind the timers`() {
        val engine = GuidanceEngine(headshot)
        engine.feed(10, subject = box(), eyeLine = eyes())
        engine.reportFocusLocked(true)
        engine.update(LEVEL, box(), eyes(), -5000L)
        assertEquals(0f, engine.lockProgress, 1e-6f)
    }
}
