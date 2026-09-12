package `in`.arasan.xthink.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import `in`.arasan.xthink.guidance.Attitude
import `in`.arasan.xthink.guidance.AttitudeMath

/**
 * One attitude reading, with the gap since the previous one.
 *
 * @param dtMs elapsed since the last sample, which is what GuidanceEngine's
 *        lockout and dwell timers run on. Zero on the very first sample.
 */
data class AttitudeSample(
    val attitude: Attitude,
    val rollReliable: Boolean,
    val dtMs: Long,
    val hz: Float,
)

/**
 * TYPE_GAME_ROTATION_VECTOR, and nothing else.
 *
 * CLAUDE.md mandates this sensor and forbids TYPE_ROTATION_VECTOR. The probe
 * showed why that is the right call on this phone as well as the consistent
 * one: the game vector is QTI's at 200 Hz, while TYPE_ROTATION_VECTOR is
 * vivo's at only 100 Hz. Game rotation vector also ignores the magnetometer,
 * so it does not drift when the phone is near a magnet or a laptop.
 *
 * Events are delivered on the caller's [Handler] - the screen passes a main
 * thread one, which is what keeps GuidanceEngine single-threaded without locks.
 */
class AttitudeSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** False when this phone has no game rotation vector at all. */
    val isAvailable: Boolean get() = sensor != null

    private val rotationMatrix = FloatArray(9)
    private var onSample: ((AttitudeSample) -> Unit)? = null
    private var lastTimestampNs = 0L

    /**
     * @param samplingPeriodUs requested period. 100 Hz is plenty: the guidance
     *        loop is smoothed by an EMA and ultimately renders at frame rate,
     *        so the sensor's 200 Hz ceiling would only cost power.
     */
    fun start(
        handler: Handler = Handler(Looper.getMainLooper()),
        samplingPeriodUs: Int = SAMPLING_PERIOD_US,
        onSample: (AttitudeSample) -> Unit,
    ): Boolean {
        val s = sensor ?: return false
        this.onSample = onSample
        lastTimestampNs = 0L
        return manager.registerListener(this, s, samplingPeriodUs, handler)
    }

    fun stop() {
        manager.unregisterListener(this)
        onSample = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // All of the trigonometry lives in :guidance, where it is unit tested
        // against a generated grid of poses. This class only plumbs.
        val attitude = AttitudeMath.fromRotationMatrix(rotationMatrix)
        val reliable = AttitudeMath.isRollReliable(rotationMatrix)

        val dtMs = if (lastTimestampNs == 0L) {
            0L
        } else {
            ((event.timestamp - lastTimestampNs) / 1_000_000L).coerceIn(0L, MAX_DT_MS)
        }
        lastTimestampNs = event.timestamp

        val hz = if (dtMs > 0L) 1000f / dtMs else 0f
        onSample?.invoke(AttitudeSample(attitude, reliable, dtMs, hz))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val SAMPLING_PERIOD_US = 10_000 // 100 Hz

        /**
         * A backgrounded app can return with a huge gap since the last event.
         * Feeding that straight into the engine would instantly satisfy the
         * lock dwell, so clamp it to something a dropped frame could plausibly
         * produce.
         */
        const val MAX_DT_MS = 250L
    }
}
