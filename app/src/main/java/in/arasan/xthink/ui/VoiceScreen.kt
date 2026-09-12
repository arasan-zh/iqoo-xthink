package `in`.arasan.xthink.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A language the phone can listen in and Gemma can answer in. */
data class VoiceLanguage(val name: String, val native: String, val tag: String)

val VOICE_LANGUAGES: List<VoiceLanguage> = listOf(
    VoiceLanguage("English", "English", "en-US"),
    VoiceLanguage("Tamil", "தமிழ்", "ta-IN"),
    VoiceLanguage("Hindi", "हिन्दी", "hi-IN"),
    VoiceLanguage("Telugu", "తెలుగు", "te-IN"),
    VoiceLanguage("Kannada", "ಕನ್ನಡ", "kn-IN"),
    VoiceLanguage("Malayalam", "മലയാളം", "ml-IN"),
)

/** What the Voice screen shows. */
data class VoiceState(
    val language: Int,
    /** READY, LISTENING, THINKING, SPEAKING, FAILED. */
    val phase: String,
    val heard: String,
    val reply: String,
    val modelLine: String,
    val ready: Boolean,
    val note: String? = null,
)

/**
 * Voice. Pick a language, hold a conversation with the model on the
 * phone: the recogniser listens in that language, Gemma answers in it,
 * the phone reads the answer out. One turn per tap.
 */
@Composable
fun VoiceScreen(
    state: VoiceState,
    onLanguage: (Int) -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
) {
    val busy = state.phase in setOf("LISTENING", "THINKING", "SPEAKING")
    Box(modifier = Modifier.fillMaxSize().background(Palette.Ground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Palette.Gutter)
                .padding(top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RoundButton(onClick = onHome) { Glyph("home", Palette.Ink, 20.dp) }
                Spacer(Modifier.weight(1f))
                Text(text = "Voice", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(44.dp))
            }
            Spacer(Modifier.height(18.dp))
            // Languages
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                VOICE_LANGUAGES.forEachIndexed { i, l ->
                    val on = i == state.language
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) Palette.Ink else Palette.Glass)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onLanguage(i) },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(text = l.native, color = if (on) Color.White else Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = when (state.phase) {
                    "LISTENING" -> "Listening…"
                    "THINKING" -> "Thinking…"
                    "SPEAKING" -> "Speaking…"
                    "FAILED" -> "Could not"
                    else -> if (state.ready) "Tap the mic and talk" else state.modelLine
                },
                color = Palette.InkMuted,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            Bars(active = state.phase == "LISTENING" || state.phase == "SPEAKING")
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Talk freely. The phone answers in ${VOICE_LANGUAGES[state.language].name}.",
                color = Palette.Ink,
                fontFamily = Palette.Display,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Palette.Corner))
                    .background(Palette.Glass)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.heard.isNotBlank()) Text(text = "“${state.heard}”", color = Palette.InkMuted, fontSize = 14.sp)
                if (state.reply.isNotBlank()) Text(text = state.reply, color = Palette.Ink, fontSize = 17.sp, lineHeight = 24.sp)
                if (state.heard.isBlank() && state.reply.isBlank()) {
                    Text(text = "Ask anything. What you say and what the phone says appear here.", color = Palette.InkMuted, fontSize = 14.sp)
                }
                if (state.note != null) Text(text = state.note, color = Palette.InkMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RoundButton(onClick = onStop, size = 52.dp) { Text("✕", color = Palette.Ink, fontSize = 18.sp) }
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Palette.Peach, Palette.Mint, Palette.Lavender)))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onMic,
                        ),
                    contentAlignment = Alignment.Center,
                ) { Text(text = if (busy) "■" else "🎤", fontSize = 30.sp) }
                Spacer(Modifier.size(52.dp))
            }
        }
    }
}

@Composable
fun RoundButton(onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.GlassStrong)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The five breathing bars of the reference. Still when idle. */
@Composable
private fun Bars(active: Boolean) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val base = listOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
        base.forEachIndexed { i, b ->
            val wobble = if (active) (0.25f * kotlin.math.sin((phase + i * 0.2f) * 2f * Math.PI).toFloat()) else 0f
            val h = (b + wobble).coerceIn(0.3f, 1f) * 110f
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Brush.verticalGradient(listOf(Color.White, Palette.Peach.copy(alpha = 0.9f)))),
            )
        }
    }
}
