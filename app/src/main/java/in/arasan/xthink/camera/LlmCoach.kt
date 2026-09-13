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
    enum class Kind { LIVE, FINISH, REFERENCE, COMMAND, PLAN, CHECK, WRITE, UNDERSTAND, ASK, TRANSLATE, TIDY, VOICE, CHAT, WATCH, WATCH_FRAME, WATCH_REPORT }

    private var llm: LlmInference? = null
    private val worker: Executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    /** The model file, if the phone has one. */
    fun modelFile(): File? {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            File("/data/local/tmp/llm"),
        )
        // The biggest bundle the phone holds wins: E4B over E2B when both are there.
        return candidates.asSequence()
            .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".task") }?.asSequence() ?: emptySequence() }
            .sortedByDescending { it.length() }
            .firstOrNull()
    }

    val isBusy: Boolean get() = busy.get()

    /** Set after the first generation: the GPU kernels are compiled, the long wait is behind. */
    @Volatile private var warmed = false

    /** What the model is doing, for the chip; null when idle. */
    fun activity(): String? = when {
        state == State.LOADING -> "Gemma loading"
        busy.get() && !warmed -> "Gemma warming up"
        busy.get() -> "Gemma thinking"
        else -> null
    }

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
                        if (!done) callbackExecutor.execute { onText(text, false) }
                    }
                    future.get()
                }
                // Free before the final word is delivered, so the caller may ask again at once.
                busy.set(false); warmed = true
                val finalText = tidy(sb.toString())
                callbackExecutor.execute { onText(finalText, true) }
                Log.i(
                    TAG,
                    "coach %s: first token %d ms, total %d ms, %d chars: %s".format(
                        kind, firstTokenMs, SystemClock.uptimeMillis() - started, sb.length, tidy(sb.toString()).take(160),
                    ),
                )
            }.onFailure {
                Log.e(TAG, "coach $kind failed", it)
                busy.set(false); warmed = true
                callbackExecutor.execute { onText(sb.toString().ifBlank { "" }, true) }
            }
            busy.set(false); warmed = true
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
            var finalText: String? = null
            val oneLine = kind == Kind.COMMAND || kind == Kind.UNDERSTAND || kind == Kind.WATCH || kind == Kind.LIVE
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
                        // COMMAND/UNDERSTAND want one line; a plan is many, ending at DONE.
                        val nl = if (oneLine) raw.indexOf('\n', startIndex = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)) else -1
                        val text = if (oneLine) tidy(if (nl >= 0) raw.substring(0, nl) else raw) else stripTokens(raw).trim()
                        val planDone = !oneLine && (kind == Kind.PLAN || kind == Kind.CHECK) && Regex("(?m)^\\s*DONE\\s*$").containsMatchIn(raw)
                        val finished = done || (nl >= 0 && text.isNotBlank()) || planDone
                        if (finished && !done) {
                            cut = true
                            runCatching { s.cancelGenerateResponseAsync() }
                        }
                        if (finished) finalText = text else callbackExecutor.execute { onText(text, false) }
                    }.get()
                }
                // Free before the final word is delivered, so the caller may ask again at once.
                busy.set(false); warmed = true
                val out = finalText ?: raw(sb, oneLine)
                callbackExecutor.execute { onText(out, true) }
                Log.i(TAG, "coach %s: %d ms, %d chars: %s".format(kind, SystemClock.uptimeMillis() - started, sb.length, out.take(120).replace('\n', ' ')))
            }.onFailure {
                Log.e(TAG, "coach $kind failed", it)
                busy.set(false); warmed = true
                callbackExecutor.execute { onText(sb.toString(), true) }
            }
            busy.set(false); warmed = true
        }
        return true
    }

    private fun raw(sb: StringBuilder, oneLine: Boolean): String =
        if (oneLine) tidy(sb.toString().lineSequence().firstOrNull { it.isNotBlank() } ?: "") else stripTokens(sb.toString()).trim()

    /** The vision encoder wants a modest square-ish image; keep it cheap. */
    private fun fit(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= IMAGE_LONG_EDGE) return src
        val scale = IMAGE_LONG_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    /** Models like to start with a preamble or a markdown bullet; the card does not. */
    private fun tidy(raw: String): String =
        stripTokens(raw).trimStart().removePrefix("*").removePrefix("-").trimStart()
            .replace("**", "").replace(Regex("\\n{2,}"), "\n").trim()

    /** The chat template's control tokens sometimes leak into the text; they are never part of an answer. */
    private fun stripTokens(raw: String): String =
        raw.replace(Regex("""</?(?:end|start)_of_turn>?[a-z_]*|<eos>|<bos>|<end_of_turn|<start_of_turn"""), "")

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

        /** A shot style in words, for the prompt. */
        fun shotWords(style: String): String = when (style) {
            "CLOSE_UP" -> "close-up"
            "EXTREME_CLOSE_UP" -> "extreme close-up"
            "MEDIUM_SHOT" -> "medium shot (waist up)"
            "WIDE_SHOT" -> "wide shot (whole body, with the place around them)"
            "LOW_ANGLE" -> "low-angle shot (camera below the eyes, looking up)"
            "HIGH_ANGLE" -> "high-angle shot (camera above the eyes, looking down)"
            "DUTCH_ANGLE" -> "dutch angle (the frame tilted on purpose)"
            "BIRDS_EYE" -> "bird's-eye shot (straight down from above)"
            "OVER_SHOULDER" -> "over-the-shoulder shot"
            "POV" -> "point-of-view shot"
            else -> style.lowercase().replace('_', ' ')
        }

        /**
         * The chosen shot, coached: one line on the one change that gets
         * it, from the live frame. Asked on a rationed clock while the
         * style is chosen - a session the photographer started.
         */
        fun shotPrompt(style: String): String = """
            You are a photography coach looking through the phone camera. The photographer wants a ${shotWords(style)} of the person in front of them.
            Say the ONE most useful change to get exactly that shot - distance, camera height, angle, where the person sits in the frame, the light - in at most 12 words, specific to what you see.
            If the frame already is that shot, say only: That's the shot.
            No greeting, no preamble.
        """.trimIndent()

        val LIVE_PROMPT = """
            You are a photography coach looking through the phone camera. Say the ONE most useful change
            for this shot - light, angle, background, distance or moment - in at most 12 words.
            Be specific to what you see. No greeting, no preamble, no punctuation flourishes.
        """.trimIndent()

        /**
         * The finish. One look at the photo, one plan: the crop, the
         * headroom, the sides to paint room into, the numbered things to
         * paint out, the look. Read by Finishing.parse, so the shape is
         * fixed - six lines, one key each. The model decides from the
         * picture and the detectors' facts; Finishing checks each line
         * against the geometry, and LaMa is handed only what survives.
         */
        fun finishPrompt(facts: String, candidates: String): String = FINISH_TEMPLATE
            .replace("{facts}", facts)
            .replace("{candidates}", candidates.ifBlank { "(nothing found)" })

        private val FINISH_TEMPLATE = """
            You are a portrait photographer finishing this photo of a person. Look at the picture and decide how to finish it.
            Measured by the detectors: {facts}
            Things in the picture that could be painted out, numbered:
            {candidates}
            How to decide:
            - CROP: where the person is cut - FEET (full body), THIGH (three-quarter), HIP (half length) or CHEST (head and shoulders) - the cut that removes clutter and flatters them, never at a joint. KEEP if the framing is already right.
            - HEADROOM: ADD if the head touches the top edge and the space above it is plain (sky, wall, blur) so room can be painted in. TOO MUCH if there is empty space above the head. Otherwise OK.
            - EXTEND: the side the person is pressed against, when the background at that edge is plain enough to paint - LEFT or RIGHT when the face is against that edge, BOTTOM only with the feet fully in the frame. Never the side that already has room. Otherwise NONE.
            - REMOVE: the numbers of distractions only - litter, a stray bag or bottle, a cable, a bin, a sign, a photobomber at the edge. Never the person, what they wear or hold, or what gives the place its character. Otherwise NONE.
            - LOOK: NATURAL, WARM, COOL, VIVID, MONO or FILM - the one that suits the light and the mood.
            Answer with exactly these six lines and nothing else:
            CROP <FEET or THIGH or HIP or CHEST or KEEP>
            HEADROOM <ADD or OK or TOO MUCH>
            EXTEND <NONE or sides, comma separated>
            REMOVE <NONE or numbers, comma separated>
            LOOK <name>
            WHY <three to eight words>
        """.trimIndent()

        /**
         * Keeping watch: one look at a frame, one or two lines on what
         * would matter to someone reading the notes later. Told the last
         * note so a still scene answers NOTHING NEW, which is not written.
         */
        fun watchFramePrompt(previous: String?): String = """
            You are keeping watch through a phone camera for someone who will read your notes later. In one or two short lines, say what is happening in this frame that would matter: who is there and what they are doing, things that arrived or left, text or screens, anything unusual. Plain words, no preamble.
            {prev}
        """.trimIndent().replace("{prev}", if (previous.isNullOrBlank()) "" else "Your last note was: \"$previous\". If nothing worth noting has changed since, answer exactly: NOTHING NEW").trim()

        /** The watch's summary, from the notes and the transcript. One ask, at the end. */
        fun watchReportPrompt(facts: String, seen: String, heard: String): String = """
            You kept watch through a phone camera. Write the summary for the file: three to six short lines of what mattered, in order, plain words, no preamble. Only what is in the notes and the transcript - invent nothing; if little happened, say so in one line.
            Facts: {facts}
            Notes on what was seen, in order (minutes:seconds from the start):
            {seen}
            What was heard, in order:
            {heard}
        """.trimIndent().replace("{facts}", facts).replace("{seen}", seen).replace("{heard}", heard)

        /**
         * Babysitting Claude Code: the terminal as the camera read it, and
         * what to do - the keys that answer a question, WAIT while it works,
         * DONE when the job is finished. The common prompts are answered
         * without the model; this is for the rest.
         */
        fun monitorPrompt(screen: String): String = """
            You are watching a terminal where Claude Code, a coding agent, is running, through a phone camera (OCR, may be noisy):
            ---
            ${screen.take(900)}
            ---
            If it is asking for a confirmation or a choice - proceed?, allow?, trust this folder?, a numbered menu, y/n - reply ONLY the keys that say yes, one per line: KEY enter, or TYPE y then KEY enter, or KEY <number> then KEY enter for a menu - then DONE.
            If it is still working - thinking, building, a spinner, reading files - reply exactly: WAIT
            If the job is finished - a summary of what it did, or a bare shell prompt with nothing asked - reply exactly: DONE
        """.trimIndent()

        /**
         * The words the phone heard, rewritten as the sentence the user most
         * likely said - speech gets names and jargon wrong, and everything
         * Steve does is names and jargon. The vocabulary is the base prompt:
         * what a request can be about. One sentence back, nothing else.
         */
        fun hearingPrompt(heard: String): String = """
            A phone's speech recogniser heard this, with mistakes: "$heard"
            Rewrite it as the one sentence the user most likely said. It is a request for a Mac, and it is about one of these:
            apps - Safari, Chrome, Terminal, Notes, TextEdit, Finder, Mail, Calendar, Messages, WhatsApp, Slack, Visual Studio Code, Xcode;
            Claude Code (the coding agent in the terminal, often heard as cloud code, claw, clod) - build, create, write, fix a website, an app, a portfolio, a to-do app;
            monitoring Claude - keep an eye on it, press enter when it asks, answer its prompts;
            the terminal - run a command, make test, make dev, git, npm, ls, pwd, htop, until it passes, keep checking;
            the remote server elitedesk (heard as elite desk) - connect, ssh, open htop;
            websites - open a website by name or domain (GitHub, Google, a domain); play a video or a song on YouTube; a portfolio - one HTML file, opened in the browser; searching; writing a letter, notes, a story; a WhatsApp message to a number; what can you do.
            Common mishearings: "get hub", "git hub" = GitHub; "sofa ri" = Safari; "cloud code", "claw code", "clod code" = Claude Code; "elite desk" = elitedesk; "h top" = htop; "port folio" = portfolio; "make taste" = make test; "what's up" = WhatsApp; "vs code", "visual studio" = Visual Studio Code; "dot com" = .com, "dot in" = .in, "dot org" = .org (write the domain as one word: github.com).
            Rules: keep the user's meaning and every detail they gave - names, numbers, words to type; fix only what was misheard; never invent a website, a name or a command that was not said - a name you do not know stays as heard; add nothing (not "on a Mac", not politeness). Reply with the sentence only - no quotes, no explanation.
        """.trimIndent()

        /** Nothing readable for a while: where is the screen, and how should the phone move. */
        val AIM_PROMPT = """
            You are helping point a phone camera at a computer screen so its text can be read. In at most 10 words, say where the screen is in this picture and how to move the phone to fill the frame with it - left, right, up, down, closer, farther, tilt. If no screen is in view, say only: No screen in view.
        """.trimIndent()

        /** Steve's eyes: the camera's reading of the Mac screen, narrated in one line. */
        fun watchPrompt(spoken: String, screen: String, typed: String?): String = """
            You are watching a Mac through a phone camera on behalf of the user${if (spoken.isNotBlank()) ", who asked: \"$spoken\"" else ""}.
            The camera reads this on the Mac screen now (OCR, may be noisy):
            ---
            ${screen.take(900)}
            ---
            ${if (!typed.isNullOrBlank()) "The phone just typed on the Mac: \"${typed.take(120)}\"\n" else ""}Say in one short line what the Mac is showing or doing now${if (!typed.isNullOrBlank()) ", and whether the typed text landed" else ""}. No preamble.
        """.trimIndent()

        private const val VERBS = """
            OPEN <app name>          - open an app (Terminal, Safari, Notes, Visual Studio Code...)
            TERMINAL <command>       - open Terminal and run a shell command
            CLAUDE <request>         - open Terminal, start Claude Code, and give it this request (use for anything that creates, writes, builds or fixes code, websites, documents)
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
            Known machines: "elitedesk" is a remote server the Mac reaches as `ssh elitedesk`; to run something there that draws a screen (htop, top) use TERMINAL ssh -t elitedesk <command>.

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
        fun checkPrompt(spoken: String, screen: String, attempt: Int, repeating: Boolean = false): String = """
            You operate a Mac through its keyboard on behalf of the user. The user asked: "$spoken".
            Attempt $attempt was just performed. The camera now reads this on the Mac screen (OCR, may be noisy):
            ---
            ${screen.take(900)}
            ---
            If the request appears done, reply exactly: DONE
            If the Mac is still working on it - Claude Code thinking or building, a command running, a spinner, a download - reply exactly: WAIT
            ${if (repeating) "The user wants this repeated until it succeeds: if the terminal shows it has not succeeded yet (a failure, an error, tests not passing), reply the TERMINAL line that runs it again, then DONE. Only when it has succeeded, reply exactly: DONE." else ""}
            Otherwise reply ONLY the next steps, one verb per line, then DONE. Verbs:$VERBS
        """.trimIndent()

        /** A chat turn with the last few turns in mind. [history] alternates user/assistant, oldest first. */
        fun chatPrompt(history: List<Pair<Boolean, String>>, message: String): String {
            val context = history.takeLast(6).joinToString("\n") { (mine, text) -> (if (mine) "User: " else "Assistant: ") + text.trim().take(400) }
            return """
                You are a helpful, concise assistant on a phone. Answer in the language the user writes in. Plain text, no markdown.
                ${if (context.isNotBlank()) "Earlier:\n$context\n" else ""}User: $message
            """.trimIndent()
        }

        /** A spoken turn, answered in the same language, briefly. */
        fun voicePrompt(heard: String, language: String, history: List<Pair<Boolean, String>> = emptyList()): String {
            val context = history.takeLast(6).joinToString("\n") { (mine, text) -> (if (mine) "User: " else "Assistant: ") + text.trim().take(300) }
            return """
                You are a friendly assistant on a phone. Reply in $language, in one to three short sentences, plainly. No preamble, no markdown.
                ${if (context.isNotBlank()) "Earlier in this conversation:\n$context\n" else ""}User: $heard
            """.trimIndent()
        }

        /** A question about what the camera sees. */
        fun askPrompt(question: String): String = """
            You are looking through the phone's camera with the user. Answer their question about what is in front of the camera,
            plainly and briefly - under 60 words - and say so if you cannot tell. No preamble.
            Question: $question
        """.trimIndent()

        /** Any script in the frame - Tamil, Hindi, anything - into English. */
        val TRANSLATE_PROMPT = """
            Read all the text in this image - Tamil, Hindi, or any other language - and translate it into ENGLISH.
            Reply with ONLY the English translation, in the Latin alphabet, keeping the line breaks.
            Never repeat the original script. If the text is already English, reply with it as is. If there is no text, reply: No text found.
        """.trimIndent()

        /** True when the answer still carries a non-Latin script - the model echoed instead of translating. */
        fun looksUntranslated(text: String): Boolean =
            text.any { c -> c in '\u0B80'..'\u0BFF' || c in '\u0900'..'\u097F' || c in '\u0C00'..'\u0D7F' }

        /** The second, blunter ask when the first came back in the original script. */
        fun retranslatePrompt(echo: String): String = """
            This text is not in English: 
            $echo
            Translate it into English. Reply with only the English, in the Latin alphabet.
        """.trimIndent()

        /** OCR output into clean, copyable text. */
        fun tidyPrompt(ocr: String): String = """
            Below is text read by a camera from a document, with recognition errors. Rewrite it as clean plain text:
            fix obviously misread characters, keep every sentence and number, keep the line structure, add nothing.
            Reply with only the cleaned text.
            ---
            ${ocr.take(2500)}
        """.trimIndent()

        /** What kind of Mac job a sentence is, in one line the macros can act on. */
        fun understandPrompt(spoken: String): String = """
            You turn what a phone heard into one clear request for a Mac. First fix what speech got wrong - misheard names and words ("cloud code" is Claude Code, "elite desk" is elitedesk, "get hub" is GitHub, "sofa ri" is Safari) - then reply with ONE line in the form KIND | ARG and nothing else.
            KIND is one of:
            OPEN - open an app; ARG is the app's name
            WEBSITE - open a website; ARG is the domain if given, else the site's name
            TERMINAL - shell work; ARG is the exact one-line macOS shell command (on the remote server elitedesk: ssh -t elitedesk <command>)
            WRITE - write a letter, notes, a story, a poem; ARG is what to write, as asked
            PORTFOLIO - a portfolio website; ARG is the destination (reelzo, client <name>, venture <name>, or personal), then the person, their role and the details given, comma separated
            PROJECT - create or build any other code, site or app; ARG is the brief for Claude Code: one paragraph starting with the verb - what to build, its parts, the stack (plain HTML, CSS and JavaScript unless another was named), the look - only what was asked, filled in with sensible defaults
            WHATSAPP - message someone on WhatsApp; ARG is the number ; the message
            MONITOR - watch Claude Code running in the terminal and press Enter or answer whenever it asks, until it is done; ARG is claude
            HELP - the user asks what you can do
            OTHER - anything else
            Examples:
            open the terminal and show the current directory -> TERMINAL | pwd
            open sofa ri browser -> OPEN | Safari
            search for the apple website and open it -> WEBSITE | apple
            write a love letter to Priya -> WRITE | a love letter to Priya
            create a portfolio website for Priya, a video editor -> PORTFOLIO | personal, Priya, video editor
            build me a to do app -> PROJECT | Build a small to-do app on one page: add, tick off and delete items, saved in the browser; plain HTML, CSS and JavaScript; clean and minimal
            connect to elite desk and open htop -> TERMINAL | ssh -t elitedesk htop
            text 9442851409 on whatsapp saying hello -> WHATSAPP | 9442851409 ; hello
            monitor claude and press enter when it asks -> MONITOR | claude
            what can you do -> HELP |
            Request: $spoken
        """.trimIndent()

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
