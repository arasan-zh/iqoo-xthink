package `in`.arasan.xthink.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Range
import android.util.Size
import android.util.SizeF
import androidx.core.content.ContextCompat
import kotlin.math.atan
import kotlin.math.hypot

/**
 * Reads what this phone actually exposes, so we can stop guessing from spec
 * sheets. See docs/HARDWARE.md for why: a spec sheet quotes chipset ceilings,
 * `CameraCharacteristics` quotes reality.
 *
 * Pure reads, no camera is opened and nothing is captured. Reading
 * characteristics does not strictly require the CAMERA permission, but we ask
 * for it anyway so nothing comes back redacted, and so the permission flow is
 * exercised before v0.2b depends on it.
 */
object DeviceCapabilities {

    /** Diagonal of a 35mm frame, for equivalent-focal-length arithmetic. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666f

    fun report(context: Context): String = buildString {
        section("DEVICE")
        line("manufacturer", Build.MANUFACTURER)
        line("model", Build.MODEL)
        line("device", Build.DEVICE)
        line("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        line("board", Build.BOARD)
        line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("build", Build.DISPLAY)

        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        line("CAMERA permission", if (granted) "granted" else "NOT GRANTED - values may be redacted")

        appendCameras(context)
        appendSensors(context)
        appendHaptics(context)
        appendThermal(context)
        appendDisplay(context)
    }

    // ------------------------------------------------------------------
    // Cameras
    // ------------------------------------------------------------------

    private fun StringBuilder.appendCameras(context: Context) {
        section("CAMERAS")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { manager.cameraIdList }.getOrElse {
            line("ERROR", "could not list cameras: $it")
            return
        }
        line("camera ids", ids.joinToString(", "))

        for (id in ids) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrElse {
                appendLine()
                appendLine("  -- camera $id: unreadable ($it)")
                continue
            }
            appendCamera(id, c, manager)
        }
    }

    private fun StringBuilder.appendCamera(
        id: String,
        c: CameraCharacteristics,
        manager: CameraManager,
    ) {
        appendLine()
        appendLine("  ---- camera $id ----")

        line("  facing", facing(c.get(CameraCharacteristics.LENS_FACING)))
        line("  hardware level", hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))

        val physical = c.physicalCameraIds
        if (physical.isNotEmpty()) {
            line("  LOGICAL multi-camera", "physical ids ${physical.joinToString(", ")}")
        }

        val size: SizeF? = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focals: FloatArray? = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

        if (size != null) {
            line("  sensor physical", "%.2f x %.2f mm".format(size.width, size.height))
        }
        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
            line("  pixel array", "${it.width} x ${it.height} (${megapixels(it)} MP)")
        }
        c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            line("  active array", "$it")
        }

        // The number that matters for the guidance math. Camera2 reports the
        // PHYSICAL focal length in mm; the 35mm-equivalent figure a spec sheet
        // quotes has to be derived from the sensor size.
        if (focals != null && size != null) {
            val diagonal = hypot(size.width, size.height)
            for (f in focals) {
                val equiv = f * FULL_FRAME_DIAGONAL_MM / diagonal
                val hFov = fovDegrees(size.width, f)
                val vFov = fovDegrees(size.height, f)
                line(
                    "  focal",
                    "%.2f mm physical  ~%.0f mm equiv  hFOV %.1f deg  vFOV %.1f deg"
                        .format(f, equiv, hFov, vFov),
                )
            }
        } else if (focals != null) {
            line("  focal", focals.joinToString(", ") { "%.2f mm".format(it) })
        }

        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let { apertures ->
            line("  aperture", apertures.joinToString(", ") { "f/%.1f".format(it) })
        }

        // Diopters. 0.0 means fixed focus at infinity. This is the number that
        // tells us whether STEP_CLOSER on the periscope hits a wall.
        val minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        if (minFocusDiopters != null) {
            val text = if (minFocusDiopters <= 0f) {
                "fixed focus / infinity"
            } else {
                "%.2f diopters  =  closest %.0f cm".format(minFocusDiopters, 100f / minFocusDiopters)
            }
            line("  min focus", text)
        }

        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.let { ois ->
            val on = ois.any { it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON }
            line("  OIS", if (on) "YES" else "no (modes ${ois.joinToString(",")})")
        }

        c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { r: Range<Float> ->
            line("  zoom ratio range", "%.2fx .. %.2fx".format(r.lower, r.upper))
        }
        c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let {
            line("  max digital zoom", "%.2fx".format(it))
        }

        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { r: Range<Int> ->
            line("  ISO range", "${r.lower} .. ${r.upper}")
        }
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { r: Range<Long> ->
            line(
                "  exposure range",
                "${r.lower} ns .. ${r.upper} ns  (1/%.0f s .. %.2f s)"
                    .format(1e9f / r.lower, r.upper / 1e9f),
            )
        }

        c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let { modes ->
            line("  AF modes", modes.joinToString(", ") { afMode(it) })
        }
        c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.let { modes ->
            line("  HW face detect", modes.joinToString(", ") { faceMode(it) })
        }
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.let { caps ->
            line("  capabilities", caps.joinToString(", ") { capability(it) })
        }

        // ImageAnalysis wants 480x360 YUV. Confirm the device will give it to us.
        val map: StreamConfigurationMap? = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)
        if (yuv != null) {
            val wanted = Size(480, 360)
            line("  YUV has 480x360", if (yuv.any { it == wanted }) "YES" else "no")
            line("  YUV smallest", yuv.minByOrNull { it.width * it.height }?.toString() ?: "-")
            line("  YUV largest", yuv.maxByOrNull { it.width * it.height }?.toString() ?: "-")
        }

        // Recurse into the physical lenses of a logical camera. This is where
        // the real per-lens optics live on a multi-camera phone.
        for (pid in physical) {
            val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
            appendLine()
            appendLine("    ---- physical lens $pid (of logical $id) ----")
            val psize = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pfocals = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (psize != null && pfocals != null) {
                val diagonal = hypot(psize.width, psize.height)
                for (f in pfocals) {
                    line(
                        "    focal",
                        "%.2f mm  ~%.0f mm equiv  hFOV %.1f deg"
                            .format(f, f * FULL_FRAME_DIAGONAL_MM / diagonal, fovDegrees(psize.width, f)),
                    )
                }
                line("    sensor", "%.2f x %.2f mm".format(psize.width, psize.height))
            }
            pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let { a ->
                line("    aperture", a.joinToString(", ") { "f/%.1f".format(it) })
            }
            pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { d ->
                line(
                    "    min focus",
                    if (d <= 0f) "fixed / infinity" else "%.2f diopters = %.0f cm".format(d, 100f / d),
                )
            }
            pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.let { ois ->
                line(
                    "    OIS",
                    if (ois.any { it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON }) "YES" else "no",
                )
            }
            pc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                line("    pixel array", "${it.width} x ${it.height} (${megapixels(it)} MP)")
            }
        }
    }

    // ------------------------------------------------------------------
    // Sensors
    // ------------------------------------------------------------------

    private fun StringBuilder.appendSensors(context: Context) {
        section("SENSORS")
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // CLAUDE.md mandates GAME_ROTATION_VECTOR and forbids ROTATION_VECTOR.
        // Report both so we can prove the mandated one exists on this phone.
        describe(sm, Sensor.TYPE_GAME_ROTATION_VECTOR, "GAME_ROTATION_VECTOR (the one we use)")
        describe(sm, Sensor.TYPE_ROTATION_VECTOR, "ROTATION_VECTOR (forbidden by CLAUDE.md)")
        describe(sm, Sensor.TYPE_GYROSCOPE, "GYROSCOPE")
        describe(sm, Sensor.TYPE_ACCELEROMETER, "ACCELEROMETER")
    }

    private fun StringBuilder.describe(sm: SensorManager, type: Int, label: String) {
        val s: Sensor? = sm.getDefaultSensor(type)
        if (s == null) {
            line(label, "ABSENT")
            return
        }
        val maxHz = if (s.minDelay > 0) "%.0f Hz max".format(1e6f / s.minDelay) else "on-change"
        line(label, "${s.name} / ${s.vendor}  $maxHz  ${s.power} mA")
    }

    // ------------------------------------------------------------------
    // Haptics - v0.4-lock depends on these primitives existing
    // ------------------------------------------------------------------

    private fun StringBuilder.appendHaptics(context: Context) {
        section("HAPTICS")
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val v = vm.defaultVibrator
        line("has vibrator", v.hasVibrator().toString())
        line("amplitude control", v.hasAmplitudeControl().toString())

        val primitives = mapOf(
            "CLICK" to VibrationEffect.Composition.PRIMITIVE_CLICK,
            "TICK" to VibrationEffect.Composition.PRIMITIVE_TICK,
            "LOW_TICK" to VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            "THUD" to VibrationEffect.Composition.PRIMITIVE_THUD,
            "SPIN" to VibrationEffect.Composition.PRIMITIVE_SPIN,
            "QUICK_RISE" to VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            "SLOW_RISE" to VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            "QUICK_FALL" to VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        )
        val ids = primitives.values.toIntArray()
        val supported = runCatching { v.arePrimitivesSupported(*ids) }.getOrNull()
        val durations = runCatching { v.getPrimitiveDurations(*ids) }.getOrNull()

        primitives.keys.forEachIndexed { i, name ->
            val ok = supported?.getOrNull(i) == true
            val ms = durations?.getOrNull(i)?.takeIf { it > 0 }?.let { " (${it} ms)" }.orEmpty()
            line("  $name", if (ok) "supported$ms" else "no")
        }
    }

    // ------------------------------------------------------------------
    // Thermal - v0.5-cool governor
    // ------------------------------------------------------------------

    private fun StringBuilder.appendThermal(context: Context) {
        section("THERMAL")
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        line("thermal status", thermalStatus(pm.currentThermalStatus))
        val headroom = runCatching { pm.getThermalHeadroom(0) }.getOrNull()
        line(
            "headroom (now)",
            when {
                headroom == null -> "threw - not supported"
                headroom.isNaN() -> "NaN - device does not report it, or called too often"
                else -> "%.3f  (1.0 = at throttling point)".format(headroom)
            },
        )
    }

    // ------------------------------------------------------------------
    // Display - the HUD should run at native refresh
    // ------------------------------------------------------------------

    private fun StringBuilder.appendDisplay(context: Context) {
        section("DISPLAY")
        // getDisplay() is non-null but THROWS on a non-visual context (an
        // Application context, say). Catch rather than null-check.
        val display = runCatching { context.display }.getOrNull()
        if (display == null) {
            line("display", "unavailable - probe was given a non-visual context")
            return
        }
        line("current mode", modeText(display.mode))
        for (m in display.supportedModes.sortedByDescending { it.refreshRate }) {
            line("  mode ${m.modeId}", modeText(m))
        }
    }

    private fun modeText(m: android.view.Display.Mode): String =
        "${m.physicalWidth} x ${m.physicalHeight} @ %.1f Hz".format(m.refreshRate)

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("======== $title ========")
    }

    private fun StringBuilder.line(key: String, value: String) {
        appendLine(key.padEnd(26) + "  " + value)
    }

    private fun fovDegrees(sensorMm: Float, focalMm: Float): Float =
        Math.toDegrees(2.0 * atan((sensorMm / (2f * focalMm)).toDouble())).toFloat()

    private fun megapixels(s: Size): String =
        "%.1f".format((s.width.toLong() * s.height) / 1_000_000f)

    private fun facing(v: Int?): String = when (v) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT (preview is mirrored)"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "unknown"
    }

    private fun hardwareLevel(v: Int?): String = when (v) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "unknown"
    }

    private fun afMode(v: Int): String = when (v) {
        CameraCharacteristics.CONTROL_AF_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraCharacteristics.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraCharacteristics.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "mode$v"
    }

    private fun faceMode(v: Int): String = when (v) {
        CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF -> "OFF"
        CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE -> "SIMPLE"
        CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL -> "FULL"
        else -> "mode$v"
    }

    private fun capability(v: Int): String = when (v) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        else -> "cap$v"
    }

    private fun thermalStatus(v: Int): String = when (v) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "status$v"
    }
}
