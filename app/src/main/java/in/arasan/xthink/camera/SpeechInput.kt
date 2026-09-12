package `in`.arasan.xthink.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The phone listens. Android's speech recogniser - Google's; on-device
 * when a language pack is present, otherwise its service - turns one
 * utterance into text. Gemma 3n cannot hear
 * (the bundles MediaPipe can load carry no audio encoder), so the ears
 * are the platform's and the understanding is the model's.
 */
class SpeechInput(private val context: Context) {

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** BCP-47 tag the recogniser listens in. */
    @Volatile
    var language: String = "en-US"

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    var listening: Boolean = false
        private set

    /**
     * Listen for one utterance. [onPartial] as words arrive, [onResult]
     * once with the final text (empty on error), [onDone] when the
     * microphone is released. All on the main thread.
     */
    fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onDone: () -> Unit) {
        if (listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        listening = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "speech: listening") }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { Log.i(TAG, "speech: end of speech") }
            override fun onError(error: Int) {
                Log.w(TAG, "speech: error $error")
                listening = false
                onResult("")
                onDone()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                Log.i(TAG, "speech: '${text.take(80)}'")
                listening = false
                onResult(text)
                onDone()
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun close() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }

    private companion object {
        const val TAG = "xThink"
    }
}
