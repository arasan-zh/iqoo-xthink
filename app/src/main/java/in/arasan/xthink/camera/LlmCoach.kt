package `in`.arasan.xthink.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemma 3n, on the phone, as a photography coach.
 *
 * The model is not in the APK - it is 3 GB - so it is looked for on the
 * device and the whole feature is absent, quietly, when it is not there.
 * Every query is one session: the picture plus a short prompt, answered as
 * a stream of words so the wait reads as thinking rather than hanging.
 * One query at a time; a request while one is running is dropped, not
 * queued - the frame it was about is already gone.
 *
 * CLAUDE.md says the LLM never runs in a loop. It does not: the caller
 * asks on a slow cadence gated by steadiness and thermals, on a tap, and
 * after a shutter. See CameraScreen.
 */
class LlmCoach(private val context: Context) {

    enum class State { MISSING, LOADING, READY, FAILED }

    @Volatile
    var state: State = State.MISSING
        private set

    /** Which prompt a stream belongs to; the panel labels it. */
    enum class Kind { LIVE, CROP, REFERENCE, COMMAND, PLAN, CHECK, WRITE }

    private var llm: LlmInference? = null
    private val worker: Executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    /** The model file, if the phone has one. */
    fun modelFile(): File? {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            File("/data/local/tmp/llm"),
        )
        return candidates.asSequence()
            .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".task") }?.asSequence() ?: emptySequence() }
            .firstOrNull()
    }

    val isBusy: Boolean get() = busy.get()

    /** Load the model off the main thread. Seconds, once. */
    fun warmUp(callbackExecutor: Executor, onState: (State) -> Unit) {
        val file = modelFile()
        if (file == null) {
            state = State.MISSING
            Log.i(TAG, "coach: no .task model on device; coach off")
            callbackExecutor.execute { onState(state) }
            return
        }
        state = State.LOADING
        callbackExecutor.execute { onState(state) }
        worker.execute {
            val started = SystemClock.uptimeMillis()
            state = runCatching {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setMaxNumImages(1)
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()
                llm = LlmInference.createFromOptions(context, options)
                Log.i(TAG, "coach: loaded ${file.name} (${file.length() / 1_000_000} MB) in ${SystemClock.uptimeMillis() - started} ms")
                State.READY
            }.getOrElse {
                Log.e(TAG, "coach: model load failed", it)
                State.FAILED
            }
            callbackExecutor.execute { onState(state) }
        }
    }

    /**
     * Ask about a picture. [onText] receives the text so far and whether it
     * is finished, on [callbackExecutor]. Returns false if the coach is not
     * ready or is already answering.
     */
    fun ask(
        kind: Kind,
        image: Bitmap,
        prompt: String,
        callbackExecutor: Executor,
        onText: (text: String, done: Boolean) -> Unit,
    ): Boolean {
        val model = llm ?: return false
        if (state != State.READY) return false
        if (!busy.compareAndSet(false, true)) return false
        worker.execute {
            val started = SystemClock.uptimeMillis()
            val sb = StringBuilder()
            var firstTokenMs = -1L
            runCatching {
                val session = LlmInferenceSession.createFromOptions(
                    model,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(TOP_K)
                        .setTemperature(TEMPERATURE)
                        .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                        .build(),
                )
                session.use { s ->
                    s.addQueryChunk(prompt)
                    s.addImage(BitmapImageBuilder(fit(image)).build())
                    val future = s.generateResponseAsync { partial, done ->
                        if (firstTokenMs < 0) firstTokenMs = SystemClock.uptimeMillis() - started
                        sb.append(partial)
                        val text = tidy(sb.toString())
                        callbackExecutor.execute { onText(text, done) }
                    }
                    future.get()
                }
                Log.i(
                    TAG,
                    "coach %s: first token %d ms, total %d ms, %d chars: %s".format(
                        kind, firstTokenMs, SystemClock.uptimeMillis() - started, sb.length, tidy(sb.toString()).take(160),
                    ),
                )
            }.onFailure {
                Log.e(TAG, "coach $kind failed", it)
                callbackExecutor.execute { onText(sb.toString().ifBlank { "" }, true) }
            }
            busy.set(false)
        }
        return true
    }

    /**
     * Ask with words only - no image, no vision graph, so the answer comes
     * faster. Same one-at-a-time rule as [ask].
     */
    fun askText(
        kind: Kind,
        prompt: String,
        callbackExecutor: Executor,
        onText: (text: String, done: Boolean) -> Unit,
    ): Boolean {
        val model = llm ?: return false
        if (state != State.READY) return false
        if (!busy.compareAndSet(false, true)) return false
        worker.execute {
            val started = SystemClock.uptimeMillis()
            val sb = StringBuilder()
            runCatching {
                val session = LlmInferenceSession.createFromOptions(
                    model,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(TOP_K)
                        .setTemperature(0.2f)
                        .build(),
                )
                session.use { s ->
                    // Gemma's turn template, explicitly: without it the raw
                    // model continues the instructions instead of answering.
                    s.addQueryChunk("<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n")
                    var cut = false
                    s.generateResponseAsync { partial, done ->
                        if (cut) return@generateResponseAsync
                        sb.append(partial)
                        val raw = sb.toString()
                        // COMMAND wants one line; a plan is many, ending at DONE.
                        val oneLine = kind == Kind.COMMAND
                        val nl = if (oneLine) raw.indexOf('\n', startIndex = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)) else -1
                        val text = if (oneLine) tidy(if (nl >= 0) raw.substring(0, nl) else raw) else raw.trim()
                        val planDone = !oneLine && kind != Kind.WRITE && Regex("(?m)^\\s*DONE\\s*$").containsMatchIn(raw)
                        val finished = done || (nl >= 0 && text.isNotBlank()) || planDone
                        if (finished && !done) {
                            cut = true
                            runCatching { s.cancelGenerateResponseAsync() }
                        }
                        callbackExecutor.execute { onText(text, finished) }
                    }.get()
                }
                Log.i(TAG, "coach %s: %d ms, %d chars: %s".format(kind, SystemClock.uptimeMillis() - started, sb.length, tidy(sb.toString().lineSequence().firstOrNull { it.isNotBlank() } ?: "").take(120)))
            }.onFailure {
                Log.e(TAG, "coach $kind failed", it)
                callbackExecutor.execute { onText(sb.toString(), true) }
            }
            busy.set(false)
        }
        return true
    }

    /** The vision encoder wants a modest square-ish image; keep it cheap. */
    private fun fit(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= IMAGE_LONG_EDGE) return src
        val scale = IMAGE_LONG_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    /** Models like to start with a preamble or a markdown bullet; the card does not. */
    private fun tidy(raw: String): String =
        raw.trimStart().removePrefix("*").removePrefix("-").trimStart()
            .replace("**", "").replace(Regex("\\n{2,}"), "\n").trim()

    fun close() {
        runCatching { llm?.close() }
        llm = null
    }

    companion object {
        const val TAG = "xThink"
        /** Whole context: the image is 256 tokens, the prompt ~100, the reply under 60. */
        const val MAX_TOKENS = 1024
        const val TOP_K = 40
        const val TEMPERATURE = 0.7f
        const val IMAGE_LONG_EDGE = 512

        val LIVE_PROMPT = """
            You are a photography coach looking through the phone camera. Say the ONE most useful change
            for this shot - light, angle, background, distance or moment - in at most 12 words.
            Be specific to what you see. No greeting, no preamble, no punctuation flourishes.
        """.trimIndent()

        /** The crop and the look, in two words. Parsed by PhotographerCrop.parseCut and Looks. */
        val CROP_PROMPT = """
            You are a portrait photographer finishing this photo of a person.
            Choose the crop: FEET (full body), THIGH (three-quarter), HIP (half length) or CHEST (head and shoulders) -
            the one that removes clutter and flatters the person, never cutting at a joint.
            Choose the look: NATURAL, WARM, COOL, VIVID, MONO or FILM - the one that suits the light and the mood.
            Answer with exactly two words: the crop, then the look.
        """.trimIndent()

        private const val VERBS = """
            OPEN <app name>          - open an app (Terminal, Safari, Notes, Visual Studio Code...)
            TERMINAL <command>       - open Terminal and run a shell command
            CLAUDE <request>         - open Visual Studio Code, start Claude Code, and give it this request (use for anything that creates, writes, builds or fixes code, websites, documents)
            TYPE <text>              - type text into the current window
            KEY <chord>              - press keys: enter, tab, escape, cmd+space, cmd+n, ctrl+c ...
            WAIT <milliseconds>      - pause
            DONE                     - the request is complete
        """

        /** A spoken request into a plan of verbs. */
        fun planPrompt(spoken: String): String = """
            You operate a Mac through its keyboard on behalf of the user. Reply with a plan: one verb per line, nothing else - no numbering, no explanation, no code fences.
            Verbs:$VERBS
            Prefer the fewest lines. Anything with "terminal", "shell", "command", "run", "list", "directory", "folder", "server", "ssh", "git" is a TERMINAL line with the shell command. Anything that creates, writes, builds or fixes code, a website, an app or a document is one CLAUDE line carrying the whole request. Only a plain "open X" is OPEN X. End with DONE on its own line.

            Examples:
            User: open the terminal and list the files
            TERMINAL ls -la
            DONE

            User: connect to the dev server
            TERMINAL ssh dev@server.local
            DONE

            User: create a portfolio website for Priya
            CLAUDE Create a portfolio website for Priya
            DONE

            User: open safari
            OPEN Safari
            DONE

            User: $spoken
        """.trimIndent()

        /** Did it work? The camera's reading of the Mac screen decides. */
        fun checkPrompt(spoken: String, screen: String, attempt: Int): String = """
            You operate a Mac through its keyboard on behalf of the user. The user asked: "$spoken".
            Attempt $attempt was just performed. The camera now reads this on the Mac screen (OCR, may be noisy):
            ---
            ${screen.take(900)}
            ---
            If the request appears done or the Mac is doing it, reply exactly: DONE
            Otherwise reply ONLY the next steps, one verb per line, then DONE. Verbs:$VERBS
        """.trimIndent()

        /** The writing itself: a letter, notes, a story - plain text, ready to type. */
        fun writePrompt(spoken: String): String = """
            $spoken
            Write it now, in full, as plain text with normal paragraphs - no title line, no markdown, no notes about what you did.
            Keep it under 220 words.
        """.trimIndent()

        /** A short WhatsApp message when the words gave none. */
        fun messagePrompt(spoken: String): String = """
            Write the WhatsApp message for this request, one or two friendly sentences, plain text only, no quotes: $spoken
        """.trimIndent()

        /** One narrow question: the shell command for a spoken request. */
        fun shellPrompt(spoken: String): String = """
            Reply with only the one-line macOS shell command that does this, nothing else: $spoken
        """.trimIndent()

        /** A spoken request, as the exact text to type on the Mac. */
        fun commandPrompt(spoken: String): String = """
            You turn a spoken request into exactly what should be typed on a Mac, in the window that has focus -
            usually a terminal, sometimes an editor or a chat prompt. Reply with ONLY the text to type: a shell
            command for a terminal request, or the sentence itself if the person is dictating. No quotes,
            no explanation, no markdown, no trailing punctuation unless it is part of the command.
            Request: $spoken
        """.trimIndent()

        val REFERENCE_PROMPT = """
            Describe how this photo is composed so a photographer could recreate it with a different subject:
            subject placement, distance, camera angle, light. Three lines of at most eight words each, no preamble.
        """.trimIndent()

        fun livePromptWith(reference: String?): String =
            if (reference.isNullOrBlank()) LIVE_PROMPT else """
                The photographer wants a shot composed like this reference:
                $reference
                Compare the camera view with it and give ONE instruction of at most 12 words to get closer.
                No greeting, no preamble.
            """.trimIndent()
    }
}
