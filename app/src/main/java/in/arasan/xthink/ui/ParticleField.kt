package `in`.arasan.xthink.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The living background of the assistant's rooms: a dense sphere of
 * points, a few hundred of them, turning slowly - and turning a little
 * more with the phone's own tilt, so the thing feels held rather than
 * played. All of it is arithmetic: a Fibonacci sphere, two rotations, an
 * orthographic drop, colour by depth. No image, no shader, no library.
 */
@Composable
fun ParticleField(modifier: Modifier = Modifier, points: Int = 1100, tint: Color = Color(0xFFB36BFF), warm: Color = Color(0xFFFF7A59)) {
    val context = LocalContext.current
    // The sphere, once.
    val sphere = remember(points) {
        val golden = PI * (3.0 - sqrt(5.0))
        FloatArray(points * 3).also { arr ->
            for (i in 0 until points) {
                val y = 1f - (i / (points - 1f)) * 2f
                val r = sqrt(1f - y * y)
                val theta = (golden * i).toFloat()
                arr[i * 3] = cos(theta) * r
                arr[i * 3 + 1] = y
                arr[i * 3 + 2] = sin(theta) * r
            }
        }
    }
    // Tilt from the accelerometer, low-passed, as two small angles.
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                tiltX = tiltX * 0.9f + (e.values[0] / 9.81f) * 0.1f
                tiltY = tiltY * 0.9f + (e.values[1] / 9.81f) * 0.1f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (acc != null) sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }

    Canvas(modifier = modifier.fillMaxSize()) {
        val t = frame / 1_000_000_000.0
        val cx = size.width / 2f + tiltX * -40f
        val cy = size.height * 0.36f + tiltY * 24f
        val radius = size.minDimension * 0.34f
        val a = (t * 0.12).toFloat() + tiltX * 0.35f          // slow spin about y, nudged by tilt
        val b = (sin(t * 0.07) * 0.35).toFloat() + tiltY * 0.25f  // a lazy nod about x
        val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b)
        for (i in 0 until points) {
            val x0 = sphere[i * 3]; val y0 = sphere[i * 3 + 1]; val z0 = sphere[i * 3 + 2]
            // rotate about y, then x
            val x1 = x0 * ca + z0 * sa
            val z1 = -x0 * sa + z0 * ca
            val y2 = y0 * cb - z1 * sb
            val z2 = y0 * sb + z1 * cb
            val depth = (z2 + 1f) / 2f                 // 0 far .. 1 near
            val scale = 0.85f + 0.3f * depth
            val px = cx + x1 * radius * scale
            val py = cy + y2 * radius * scale
            val mix = (y0 + 1f) / 2f
            val col = Color(
                red = tint.red + (warm.red - tint.red) * mix,
                green = tint.green + (warm.green - tint.green) * mix,
                blue = tint.blue + (warm.blue - tint.blue) * mix,
                alpha = 0.12f + 0.7f * depth,
            )
            drawCircle(col, radius = 1.2f + 1.6f * depth, center = Offset(px, py))
        }
    }
}
