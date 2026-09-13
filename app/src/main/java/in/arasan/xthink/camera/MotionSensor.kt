package `in`.arasan.xthink.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import `in`.arasan.xthink.guidance.StepDetector
import kotlin.math.sqrt

/**
 * Steps, from the accelerometer. The step sensor proper needs the
 * activity-recognition permission on this Android; the accelerometer
 * does not, and [StepDetector] (pure, tested) reads strides out of it
 * well enough to tell walking from standing, which is all WALK needs.
 */
class MotionSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var detector = StepDetector()
    private var onStep: (() -> Unit)? = null

    /** Start listening; [onStep] on the main thread, once per stride. False if there is no accelerometer. */
    fun start(onStep: () -> Unit): Boolean {
        val s = sensor ?: return false
        detector = StepDetector()
        this.onStep = onStep
        return manager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        manager.unregisterListener(this)
        onStep = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (detector.feed(magnitude, SystemClock.uptimeMillis())) onStep?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
