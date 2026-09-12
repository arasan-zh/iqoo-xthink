package `in`.arasan.xthink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import `in`.arasan.xthink.camera.CameraScreen
import `in`.arasan.xthink.camera.LlmCoach
import `in`.arasan.xthink.camera.SpeechInput
import `in`.arasan.xthink.ui.HomeScreen
import `in`.arasan.xthink.ui.Splash
import `in`.arasan.xthink.ui.VOICE_LANGUAGES
import `in`.arasan.xthink.ui.VoiceScreen
import `in`.arasan.xthink.ui.VoiceState
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // One model for every room. Home is the front door; the
                // camera is one room, opened straight into a tab when a card
                // asks for one. Dev hooks (--es enhance/genius/ask) go
                // straight to the camera.
                val context = this
                val coach = remember { LlmCoach(context) }
                DisposableEffect(coach) { onDispose { coach.close() } }
                val hooked = intent.getStringExtra("enhance") != null || intent.getStringExtra("genius") != null || intent.getStringExtra("ask") != null
                var screen by remember { mutableStateOf(if (hooked) "CAMERA" else "HOME") }
                var startIn by remember { mutableStateOf<String?>(null) }
                var splash by remember { mutableStateOf(true) }

                // Voice
                var vLang by remember { mutableStateOf(0) }
                var vPhase by remember { mutableStateOf("READY") }
                var vHeard by remember { mutableStateOf("") }
                var vReply by remember { mutableStateOf("") }
                var vNote by remember { mutableStateOf<String?>(null) }
                var coachState by remember { mutableStateOf(LlmCoach.State.MISSING) }
                val speech = remember { SpeechInput(context) }
                val tts = remember {
                    var engine: TextToSpeech? = null
                    engine = TextToSpeech(context) { st -> if (st == TextToSpeech.SUCCESS) engine?.language = Locale.US }
                    engine
                }
                DisposableEffect(Unit) { onDispose { speech.close(); runCatching { tts?.shutdown() } } }
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val main = ContextCompat.getMainExecutor(context)

                fun ensureCoach() {
                    if (coachState == LlmCoach.State.MISSING && coach.modelFile() != null) coach.warmUp(main) { coachState = it }
                }
                fun voiceTurn() {
                    if (vPhase == "LISTENING") { speech.stop(); return }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO); return
                    }
                    val lang = VOICE_LANGUAGES[vLang]
                    speech.language = lang.tag
                    vPhase = "LISTENING"; vHeard = ""; vReply = ""; vNote = null
                    speech.listen(
                        onPartial = { vHeard = it },
                        onResult = { heard ->
                            if (heard.isBlank()) { vPhase = "READY"; vNote = "Didn't catch that"; return@listen }
                            vHeard = heard
                            if (coachState != LlmCoach.State.READY) { vPhase = "FAILED"; vNote = "Gemma is still loading - a moment"; return@listen }
                            vPhase = "THINKING"
                            val ok = coach.askText(LlmCoach.Kind.VOICE, LlmCoach.voicePrompt(heard, lang.name), main) { text, done ->
                                vReply = text
                                if (done) {
                                    vPhase = "SPEAKING"
                                    runCatching {
                                        tts?.language = Locale.forLanguageTag(lang.tag)
                                        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                                            override fun onStart(id: String?) {}
                                            override fun onDone(id: String?) { runOnUiThread { if (vPhase == "SPEAKING") vPhase = "READY" } }
                                            @Deprecated("") override fun onError(id: String?) { runOnUiThread { vPhase = "READY" } }
                                        })
                                        tts?.speak(text.take(800), TextToSpeech.QUEUE_FLUSH, null, "voice")
                                    }.onFailure { vPhase = "READY" }
                                }
                            }
                            if (!ok) { vPhase = "FAILED"; vNote = "Gemma is busy" }
                        },
                        onDone = { if (vPhase == "LISTENING") vPhase = "READY" },
                    )
                }

                Box {
                    when (screen) {
                        "HOME" -> HomeScreen(status = "Everything here runs on the phone.", onOpen = { id ->
                            when (id) {
                                "VOICE" -> { screen = "VOICE"; ensureCoach() }
                                else -> { startIn = if (id == "CAMERA") null else id; screen = "CAMERA" }
                            }
                        })
                        "VOICE" -> {
                            VoiceScreen(
                                state = VoiceState(
                                    language = vLang, phase = vPhase, heard = vHeard, reply = vReply,
                                    modelLine = when (coachState) {
                                        LlmCoach.State.READY -> "Tap the mic and talk"
                                        LlmCoach.State.LOADING -> "Gemma is loading\u2026"
                                        else -> if (coach.modelFile() == null) "No model on this phone" else "Gemma is loading\u2026"
                                    },
                                    ready = coachState == LlmCoach.State.READY,
                                    note = vNote,
                                ),
                                onLanguage = { vLang = it },
                                onMic = { voiceTurn() },
                                onStop = { speech.stop(); runCatching { tts?.stop() }; vPhase = "READY" },
                                onHome = { speech.stop(); runCatching { tts?.stop() }; screen = "HOME" },
                            )
                            BackHandler { screen = "HOME" }
                        }
                        else -> {
                            CameraScreen(
                                coach = coach,
                                debugEnhanceUri = intent.getStringExtra("enhance"),
                                debugGenius = intent.getStringExtra("genius"),
                                debugAsk = intent.getStringExtra("ask"),
                                startIn = startIn,
                                onHome = { screen = "HOME" },
                            )
                            BackHandler { screen = "HOME" }
                        }
                    }
                    if (splash) Splash(onDone = { splash = false })
                }
            }
        }
    }
}
