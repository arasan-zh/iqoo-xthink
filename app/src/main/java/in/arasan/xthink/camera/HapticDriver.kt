package `in`.arasan.xthink.camera

import android.content.Context
import android.os.CombinedVibration
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import `in`.arasan.xthink.guidance.HapticCue

/**
 * Turns a [HapticCue] into a vibration. The rhythm and the decisions live in
 * :guidance's LockHaptics; this only knows the hardware.
 *
 * The probe (docs/evidence) confirmed all eight composition primitives and
 * amplitude control on this phone, so the game uses them: LOW_TICK for the
 * closing-in pulse, scaled by how close the frame is; QUICK_RISE into CLICK
 * for the lock - a rise then a landing, felt as one event; a faint LOW_TICK
 * for losing it. Phones without primitives get the predefined effects, which
 * lose the scaling but keep the rhythm.
 */
class HapticDriver(context: Context) {

    private val manager: VibratorManager? = runCatching {
        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
    }.getOrNull()

    private val vibrator: Vibrator? = manager?.defaultVibrator

    /**
     * Every motor the phone has. With two, DIR_LEFT and DIR_RIGHT play on
     * one motor each and are spatially left and right; with one they are
     * different signatures instead. Logged at start so the truth is in the
     * log, not assumed from a spec sheet.
     */
    private val motorIds: IntArray = manager?.vibratorIds ?: IntArray(0)

    init {
        Log.i("xThink", "haptics: ${motorIds.size} motor(s) ids=${motorIds.joinToString()}")
    }

    private val primitives: Boolean = vibrator?.let {
        runCatching {
            it.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            )
        }.getOrDefault(false)
    } ?: false

    /** @param strength 0..1 for TICK: how close to lock. Ignored for the others. */
    fun play(cue: HapticCue, strength: Float = 0f) {
        val v = vibrator ?: return
        val effect: VibrationEffect = when (cue) {
            HapticCue.NONE -> return
            HapticCue.TICK -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                        (TICK_FLOOR + (1f - TICK_FLOOR) * strength.coerceIn(0f, 1f)),
                    )
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
            HapticCue.LOCK -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.7f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 20)
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            }
            HapticCue.UNLOCK -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f)
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }

            // Direction signatures. Each is a different shape under the thumb:
            // left is two soft ticks, right one sharp click, up rises, down
            // falls, levelling spins, closer swells, back thuds.
            HapticCue.DIR_LEFT -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.8f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.8f, 90)
                    .compose()
            } else VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            HapticCue.DIR_RIGHT -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .compose()
            } else VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            HapticCue.DIR_UP -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.8f)
                    .compose()
            } else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            HapticCue.DIR_DOWN -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.8f)
                    .compose()
            } else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            HapticCue.DIR_ROTATE -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.9f)
                    .compose()
            } else VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            HapticCue.DIR_CLOSER -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.7f)
                    .compose()
            } else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            HapticCue.DIR_BACK -> if (primitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.9f)
                    .compose()
            } else VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        }

        // Two motors: left and right are spatially left and right. Which id
        // is which side is not discoverable from software - if it feels
        // swapped in the hand, swap SPATIAL_LEFT below.
        if (motorIds.size >= 2 && (cue == HapticCue.DIR_LEFT || cue == HapticCue.DIR_RIGHT)) {
            val id = if (cue == HapticCue.DIR_LEFT) motorIds[SPATIAL_LEFT] else motorIds[1 - SPATIAL_LEFT]
            val ok = runCatching {
                manager?.vibrate(CombinedVibration.startParallel().addVibrator(id, effect).combine())
            }.isSuccess
            if (ok) return
        }
        runCatching { v.vibrate(effect) }
    }

    /** One short click, the way a stock camera confirms a manual shot. */
    fun click() {
        val v = vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) }
    }

    private companion object {
        /** Faintest closing-in pulse, so the first ticks are felt but not startling. */
        const val TICK_FLOOR = 0.25f
        /** Index into motorIds of the motor on the LEFT side of the phone, when there are two. */
        const val SPATIAL_LEFT = 0
    }
}
