package `in`.arasan.xthink.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The opening. "Think Different." arrives in serif; a gold stroke is
 * drawn through Different; "Think Beyond." writes itself in beneath, as
 * if by hand; then the mark. Three and a half seconds, once, over the
 * camera warming up.
 */
@Composable
fun Splash(onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            delay(160)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 140)
            delay(400)
            tone.release()
        }
        t.animateTo(1f, tween(3200, easing = LinearEasing))
        delay(300)
        fade.animateTo(0f, tween(420))
        onDone()
    }
    fun stage(from: Float, to: Float, ease: Boolean = true): Float {
        val raw = ((t.value - from) / (to - from)).coerceIn(0f, 1f)
        return if (ease) FastOutSlowInEasing.transform(raw) else raw
    }
    val ink = Color(0xFFF4F1EA)
    val gold = Color(0xFFD9B36B)
    var differentWidth by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier.fillMaxSize().alpha(fade.value).background(Color(0xFF07080F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
            // Line 1: Think Different. - with the stroke through Different.
            Row(modifier = Modifier.alpha(stage(0.02f, 0.28f))) {
                Text(text = "Think ", color = ink, fontFamily = FontFamily.Serif, fontSize = 40.sp)
                Box {
                    Text(
                        text = "Different.",
                        color = ink,
                        fontFamily = FontFamily.Serif,
                        fontSize = 40.sp,
                        modifier = Modifier.onGloballyPositioned { differentWidth = it.size.width.toFloat() },
                    )
                    val strike = stage(0.32f, 0.48f)
                    Canvas(modifier = Modifier.matchParentSize()) {
                        if (strike > 0f && differentWidth > 0f) {
                            val y = size.height * 0.56f
                            drawLine(gold, Offset(0f, y + 2f), Offset(differentWidth * strike, y - 6f), 3.5f.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Line 2: Think Beyond. - written in from the left.
            val write = stage(0.5f, 0.78f)
            Box(modifier = Modifier.clipToBounds().layout { m, c ->
                val p = m.measure(c)
                layout((p.width * write).toInt().coerceAtLeast(1), p.height) { p.placeRelative(0, 0) }
            }) {
                Text(
                    text = "Think Beyond.",
                    color = gold,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 44.sp,
                )
            }
            Spacer(Modifier.height(36.dp))
            // The mark, and the name.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(stage(0.8f, 1f))) {
                Mark(size = 64.dp, color = ink)
                Spacer(Modifier.height(12.dp))
                Text(text = "xThink", color = ink.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
            }
        }
    }
}
