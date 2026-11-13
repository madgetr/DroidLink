package dev.droidlink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The droid's ears: Android speech recognition, on-device (offline) when the
 * phone supports it. One utterance per call; returns "" if nobody spoke.
 */
class VoiceInput(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())   // SpeechRecognizer lives on the main thread
    private val busy = Any()

    /** Use the offline recognizer until we learn its English pack is missing. */
    @Volatile private var useOnDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    @Volatile private var downloadRequested = false

    val onDevice: Boolean get() = useOnDevice

    fun available() = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) ||
        SpeechRecognizer.isRecognitionAvailable(ctx)

    /** Listen for up to [timeoutMs] for one utterance (cuts in on wake-word listening). */
    fun listen(timeoutMs: Long): JSONObject {
        directPending = true
        try {
            cancelCurrent?.invoke()          // stop a background wake session, if any
            return listenDirect(timeoutMs)
        } finally {
            directPending = false
        }
    }

    private fun listenDirect(timeoutMs: Long): JSONObject {
        val r = listenOnce(timeoutMs)
        val err = r.optString("error")
        if (err.isNotEmpty() && handleEngineError(err)) return listenOnce(timeoutMs)
        return r
    }

    /**
     * Switch recognizers when the current one can't serve us. Returns true if it
     * switched (worth retrying). The phone has two: the on-device one (Android
     * System Intelligence), which sometimes drops our connection (error 11), and
     * the default one (Speech Services by Google), which also works offline once
     * its language pack is installed.
     */
    private fun handleEngineError(err: String): Boolean {
        if (!useOnDevice) return false
        when (err) {
            "language pack not downloaded", "language not supported" -> requestOfflinePack()
            "server disconnected", "busy", "error 10" -> {}
            else -> return false
        }
        useOnDevice = false
        Log.i(TAG, "on-device recognizer failed ($err); using the default recognizer")
        return true
    }

    private fun requestOfflinePack() {
        if (downloadRequested || installedLanguages().isNotEmpty()) return
        downloadRequested = true
        main.post {
            try {
                val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                sr.triggerModelDownload(recognizerIntent())
                main.postDelayed({ sr.destroy() }, 5_000)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't request offline speech pack", e)
            }
        }
    }

    /** Languages the on-device recognizer has installed (asked once, cached). */
    @Volatile private var installed: List<String>? = null

    fun installedLanguages(): List<String> {
        installed?.let { return it }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return emptyList()
        val f = CompletableFuture<List<String>>()
        main.post {
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            sr.checkRecognitionSupport(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                { it.run() },
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                        f.complete(s.installedOnDeviceLanguages); main.post { sr.destroy() }
                    }
                    override fun onError(error: Int) {
                        f.complete(emptyList()); main.post { sr.destroy() }
                    }
                })
        }
        return try { f.get(5, TimeUnit.SECONDS) } catch (e: Exception) { emptyList() }
            .also { installed = it; Log.i(TAG, "installed speech languages: $it") }
    }

    /** The phone's own language if its pack is installed, else any installed English. */
    val language: String by lazy {
        val device = java.util.Locale.getDefault().toLanguageTag()          // e.g. en-CA
        val have = installedLanguages()
        have.firstOrNull { it.equals(device, ignoreCase = true) }
            ?: have.firstOrNull { it.lowercase().startsWith("en") }
            ?: device
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    }

    private fun listenOnce(timeoutMs: Long): JSONObject = synchronized(busy) {
        if (!available()) throw Unavailable("speech recognition not available on this phone")
        val result = CompletableFuture<JSONObject>()
        var recognizer: SpeechRecognizer? = null
        val t0 = System.currentTimeMillis()

        fun finish(text: String, alternatives: List<String> = emptyList(), error: String? = null) {
            if (result.isDone) return
            result.complete(JSONObject()
                .put("text", text)
                .put("alternatives", JSONArray(alternatives))
                .put("on_device", onDevice)
                .put("ms", System.currentTimeMillis() - t0)
                .apply { error?.let { put("error", it) } })
            main.post { recognizer?.destroy(); recognizer = null }
        }
        cancelCurrent = { finish("", error = "cancelled") }   // lets a direct listen() cut in

        main.post {
            val sr = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                     else SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) {
                    val texts = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                    finish(texts.firstOrNull() ?: "", texts)
                }
                override fun onError(error: Int) {
                    when (error) {
                        // Nobody said anything (or nothing intelligible): not an error for us
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish("")
                        else -> finish("", error = errorName(error))
                    }
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(type: Int, b: Bundle?) {}
            })
            sr.startListening(recognizerIntent())
            // Out of time: ask for whatever was heard so far...
            main.postDelayed({ if (!result.isDone) recognizer?.stopListening() }, timeoutMs)
        }
        try {
            // ...and if it still hasn't answered shortly after, give up quietly.
            result.get(timeoutMs + 4_000, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "recognizer did not answer")
            finish("", error = "timeout")
            result.get()
        } finally {
            cancelCurrent = null
        }
    }

    // ------------------------------------------------------------ wake word

    @Volatile private var cancelCurrent: (() -> Unit)? = null
    @Volatile private var directPending = false
    @Volatile private var wakeOn = false
    @Volatile private var wakeNames: List<String> = emptyList()
    private var wakeThread: Thread? = null
    private val events = ArrayDeque<JSONObject>()
    private var eventSeq = 0L

    /** Is the droid making sound right now? (set by the service; we don't listen then) */
    @Volatile var isSpeaking: () -> Boolean = { false }
    /** Did the droid make any sound after this time (ms)? Then we may have heard ourselves. */
    @Volatile var spokeSince: (Long) -> Boolean = { false }
    /** Called on the wake thread with whatever followed the name ("" if nothing). */
    @Volatile var onWake: (command: String) -> Unit = {}

    /**
     * Listen continuously (silently) for any of [names]. Each time one is heard,
     * an event is queued for /voice/events with the words that followed it.
     */
    fun startWake(names: List<String>): JSONObject {
        wakeNames = names.map(::normalize).filter { it.isNotEmpty() }
        if (!wakeOn) {
            wakeOn = true
            wakeThread = kotlin.concurrent.thread(name = "wake-word", isDaemon = true) { wakeLoop() }
        }
        return wakeStatus()
    }

    fun stopWake(): JSONObject {
        wakeOn = false
        cancelCurrent?.invoke()
        return wakeStatus()
    }

    private val recent = ArrayDeque<String>()   // last things heard, for debugging

    fun wakeStatus(): JSONObject = JSONObject().put("wake", wakeOn).put("names", JSONArray(wakeNames))
        .put("recently_heard", synchronized(recent) { JSONArray(recent.toList()) })

    fun events(since: Long): JSONObject = synchronized(events) {
        JSONObject().put("next", eventSeq)
            .put("items", JSONArray(events.filter { it.getLong("seq") >= since }))
    }

    private fun wakeLoop() {
        while (wakeOn) {
            // Don't listen to ourselves, and give way to a direct listen() request
            if (isSpeaking() || directPending) { Thread.sleep(150); continue }
            val sessionStart = System.currentTimeMillis()
            val r = try { listenOnce(10_000) } catch (e: Exception) { Thread.sleep(1_000); continue }
            if (!wakeOn) break
            if (spokeSince(sessionStart)) continue      // we were talking: whatever it heard was us
            val err = r.optString("error")
            if (err.isNotEmpty() && err != "cancelled") {
                Log.w(TAG, "wake loop: recognizer error '$err'")
                handleEngineError(err)
                Thread.sleep(1_000); continue
            }
            val heard = (0 until r.getJSONArray("alternatives").length())
                .map { r.getJSONArray("alternatives").getString(it) }
            if (heard.isNotEmpty()) synchronized(recent) {
                recent.addLast(heard.first()); while (recent.size > 8) recent.removeFirst()
                Log.i(TAG, "wake loop heard: $heard")
            }
            for (text in heard) {
                val command = matchName(text) ?: continue
                Log.i(TAG, "wake: '$text' -> command '$command'")
                synchronized(events) {
                    events.addLast(JSONObject().put("seq", eventSeq++).put("ts", now())
                        .put("text", text).put("command", command))
                    while (events.size > 50) events.removeFirst()
                }
                try { onWake(command) } catch (e: Exception) { Log.w(TAG, "onWake failed", e) }
                break
            }
        }
    }

    /**
     * If [text] contains one of the wake names, return the words after it ("" if
     * none); otherwise null. Fuzzy: "HDL" or "H D O" still count as "HDO", and
     * single letters said separately are joined up.
     */
    fun matchName(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val norm = words.map(::normalize)
        for (start in norm.indices) {
            var joined = ""
            for (end in start until minOf(start + 4, norm.size)) {
                joined += norm[end]
                for (name in wakeNames) {
                    val allowed = if (name.length <= 4) 1 else 2
                    if (joined.length >= 2 && levenshtein(joined, name) <= allowed) {
                        return words.drop(end + 1).joinToString(" ").trim(' ', ',', '.', '!', '?')
                    }
                }
            }
        }
        return null
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun levenshtein(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1,
                d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    private fun errorName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no microphone permission"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language pack not downloaded"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
        else -> "error $e"
    }

    companion object { private const val TAG = "VoiceInput" }
}
