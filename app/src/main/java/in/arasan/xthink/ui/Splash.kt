package `in`.arasan.xthink.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The opening second. The camera is already warming up underneath; this is
 * the promise, drawn once, then gone. Nothing here is interactive.
 */
@Composable
fun Splash(onDone: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        delay(700)
        fade.animateTo(0f, tween(380))
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fade.value)
            .background(Color(0xFF0B0B0C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(0.85f + 0.15f * reveal.value)
                    .alpha(reveal.value)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Q",
                    color = XT.Amber,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-2).sp,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "xThink",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.alpha(reveal.value),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Ordinary photos, made extraordinary.",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier.alpha(reveal.value),
            )
        }
    }
}
