package `in`.arasan.xthink.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/**
 * What the ISP reports about each frame.
 *
 * These are the readings docs/HARDWARE.md earmarked for v0.2-anchor:
 * autofocus state replaces the engine's placeholder focus flag, and ISO with
 * exposure time drive the Lighting chip and, later, the blur-aware stability
 * signal.
 */
data class CameraTelemetry(
    val afState: Int? = null,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val zoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val minFocusCm: Float? = null,
    val hasAutofocus: Boolean = true,
    val frames: Long = 0L,
) {

    /**
     * True when the ISP says the scene is in focus. Both the locked result of
     * a tap and the settled state of continuous autofocus count.
     */
    val focusLocked: Boolean
        get() = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

    val exposureSeconds: Float?
        get() = exposureNs?.let { it / 1_000_000_000f }

    /** Shutter speed the way a photographer reads it. */
    val shutterText: String
        get() = exposureNs?.let {
            if (it <= 0L) "-" else "1/${(1_000_000_000.0 / it).toInt()}s"
        } ?: "-"

    val afText: String
        get() = when (afState) {
            null -> "-"
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
            else -> "af$afState"
        }

    companion object {

        /** Merge one capture result into the running telemetry. */
        fun from(previous: CameraTelemetry, result: TotalCaptureResult): CameraTelemetry =
            previous.copy(
                afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: previous.afState,
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: previous.iso,
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: previous.exposureNs,
                zoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: previous.zoomRatio,
                frames = previous.frames + 1L,
            )

        /**
         * Static facts about the bound camera. On this phone the rear camera
         * autofocuses down to 10 cm and the front does not focus at all, which
         * is what `hasAutofocus` carries into GuidanceEngine.
         */
        fun fromCharacteristics(c: CameraCharacteristics): CameraTelemetry {
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            val fixedFocus = afModes == null || afModes.isEmpty() ||
                (afModes.size == 1 && afModes[0] == CameraCharacteristics.CONTROL_AF_MODE_OFF)

            val diopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)

            return CameraTelemetry(
                zoomRatio = zoomRange?.lower ?: 1f,
                maxZoomRatio = zoomRange?.upper ?: 1f,
                minFocusCm = diopters?.takeIf { it > 0f }?.let { 100f / it },
                hasAutofocus = !fixedFocus,
            )
        }
    }
}
