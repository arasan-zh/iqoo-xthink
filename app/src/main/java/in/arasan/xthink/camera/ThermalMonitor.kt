package `in`.arasan.xthink.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Polls the phone's thermal state once a second and reports it.
 *
 * getThermalHeadroom is rate-limited by the platform - poll it faster and it
 * returns NaN - so once a second is the ceiling as well as plenty. The
 * status listener fires on change and is folded into the same callback. The
 * governor in :guidance decides what to do about it.
 *
 * @param forecastSeconds how far ahead to ask the platform to forecast. Ten
 *        gives the governor time to slow the detector before throttling hits.
 */
class ThermalMonitor(
    context: Context,
    private val forecastSeconds: Int = 10,
) {

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var onSample: ((headroom: Float, status: Int) -> Unit)? = null
    private var status: Int = PowerManager.THERMAL_STATUS_NONE

    private val statusListener = PowerManager.OnThermalStatusChangedListener { s ->
        status = s
        // Report immediately on a status change; heat can climb faster than
        // our poll when the phone is already at a boundary.
        emit()
    }

    private val poll = object : Runnable {
        override fun run() {
            emit()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start(onSample: (headroom: Float, status: Int) -> Unit) {
        this.onSample = onSample
        status = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        runCatching { pm.addThermalStatusListener(statusListener) }
        handler.post(poll)
    }

    fun stop() {
        handler.removeCallbacks(poll)
        runCatching { pm.removeThermalStatusListener(statusListener) }
        onSample = null
    }

    private fun emit() {
        val headroom = runCatching { pm.getThermalHeadroom(forecastSeconds) }.getOrDefault(Float.NaN)
        onSample?.invoke(headroom, status)
    }

    companion object {
        const val POLL_MS = 1000L
    }
}
