package `in`.arasan.xthink.camera

import android.content.Context
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

    private val vibrator: Vibrator? = runCatching {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    }.getOrNull()

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
    }
}
