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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import `in`.arasan.xthink.camera.CameraScreen
import `in`.arasan.xthink.camera.Conversation
import `in`.arasan.xthink.camera.LlmCoach
import `in`.arasan.xthink.camera.SpeechInput
import `in`.arasan.xthink.ui.ChatScreen
import `in`.arasan.xthink.ui.ChatTurn
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
                var screen by remember { mutableStateOf("CAMERA") }
                var startIn by remember { mutableStateOf<String?>(null) }
                var splash by remember { mutableStateOf(true) }
                val conversation = remember { Conversation() }

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
                            val history = conversation.history()
                            conversation.add(true, heard)
                            val replyIndex = conversation.add(false, "")
                            val ok = coach.askText(LlmCoach.Kind.VOICE, LlmCoach.voicePrompt(heard, lang.name, history), main) { text, done ->
                                vReply = text
                                conversation.set(replyIndex, text)
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

                // Chat
                val chat = conversation.turns
                var chatDraft by remember { mutableStateOf("") }
                var chatBusy by remember { mutableStateOf(false) }
                var chatListening by remember { mutableStateOf(false) }
                var chatImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    chatImage = runCatching {
                        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                            val longest = maxOf(info.size.width, info.size.height)
                            var sample = 1
                            while (longest / (sample * 2) >= 768) sample *= 2
                            d.setTargetSampleSize(sample)
                        }
                    }.getOrNull()
                }
                fun chatSend(text: String) {
                    val msg = text.trim()
                    if ((msg.isEmpty() && chatImage == null) || chatBusy) return
                    if (coachState != LlmCoach.State.READY) { chat.add(ChatTurn(true, msg)); chat.add(ChatTurn(false, "Gemma is still loading - a moment, then ask again.")); chatDraft = ""; return }
                    val history = chat.map { it.mine to it.text }
                    val image = chatImage
                    chat.add(ChatTurn(true, msg, image?.let { it.asImageBitmap() }))
                    chat.add(ChatTurn(false, ""))
                    chatDraft = ""
                    chatImage = null
                    chatBusy = true
                    val idx = chat.size - 1
                    val onReply: (String, Boolean) -> Unit = { reply, done ->
                        if (idx < chat.size) chat[idx] = ChatTurn(false, reply)
                        if (done) chatBusy = false
                    }
                    // A photo goes through the model's eyes; words alone through its ears.
                    val ok = if (image != null) coach.ask(LlmCoach.Kind.CHAT, image, LlmCoach.chatPrompt(history, msg), main, onReply)
                    else coach.askText(LlmCoach.Kind.CHAT, LlmCoach.chatPrompt(history, msg), main, onReply)
                    if (!ok) { chat[idx] = ChatTurn(false, "Busy - try again in a moment."); chatBusy = false }
                }
                fun chatMic() {
                    if (chatListening) { speech.stop(); return }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO); return
                    }
                    speech.language = "en-US"
                    chatListening = true
                    speech.listen(
                        onPartial = { chatDraft = it },
                        onResult = { heard -> chatListening = false; if (heard.isNotBlank()) chatSend(heard) },
                        onDone = { chatListening = false },
                    )
                }

                // Leaving a room silences it: no reading on into the next screen.
                androidx.compose.runtime.LaunchedEffect(screen) {
                    if (screen != "VOICE") { speech.stop(); runCatching { tts?.stop() }; if (vPhase == "SPEAKING" || vPhase == "LISTENING") vPhase = "READY" }
                    if (screen != "CHAT") { chatListening = false }
                }

                Box {
                    when (screen) {
                        "CHAT" -> {
                            ChatScreen(
                                turns = chat,
                                draft = chatDraft,
                                busy = chatBusy,
                                listening = chatListening,
                                attachment = chatImage?.let { it.asImageBitmap() },
                                onAttach = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onClearAttach = { chatImage = null },
                                modelLine = when (coachState) {
                                    LlmCoach.State.READY -> "Gemma 3n · on the phone"
                                    LlmCoach.State.LOADING -> "loading the model\u2026"
                                    else -> if (coach.modelFile() == null) "no model on this phone" else "loading the model\u2026"
                                },
                                onDraft = { chatDraft = it },
                                onSend = { chatSend(chatDraft) },
                                onMic = { chatMic() },
                                onHome = { speech.stop(); screen = "CAMERA" },
                            )
                            BackHandler { screen = "CAMERA" }
                        }
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
                                onHome = { speech.stop(); runCatching { tts?.stop() }; screen = "CAMERA" },
                            )
                            BackHandler { screen = "CAMERA" }
                        }
                        else -> {
                            CameraScreen(
                                coach = coach,
                                debugEnhanceUri = intent.getStringExtra("enhance"),
                                debugGenius = intent.getStringExtra("genius"),
                                debugAsk = intent.getStringExtra("ask"),
                                startIn = startIn,
                                onHome = { },
                                onOpenRoom = { id -> if (id == "VOICE" || id == "CHAT") { screen = id; ensureCoach() } },
                            )
                        }
                    }
                    if (splash) Splash(onDone = { splash = false })
                }
            }
        }
    }
}
