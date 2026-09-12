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
    enum class Kind { LIVE, CROP, REFERENCE }

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

        /** The crop decision. The answer is parsed by PhotographerCrop.parseCut. */
        val CROP_PROMPT = """
            You are a portrait photographer deciding how to crop this photo of a person.
            Choose exactly one: FEET (full body), THIGH (three-quarter length), HIP (half length), CHEST (head and shoulders).
            Prefer the crop that removes clutter and flatters the person; never cut at a joint.
            Answer with only the one word.
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
