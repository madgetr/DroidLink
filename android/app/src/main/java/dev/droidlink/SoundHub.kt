package dev.droidlink

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The droid's voice: synthesized astromech-style chirps (whistles and sweeps
 * shaped by mood), and text-to-speech run through a robot-voice effect.
 * Everything plays through one queue, so a chirp and the speech after it
 * never talk over each other.
 */
class SoundHub(private val ctx: Context) : TextToSpeech.OnInitListener {

    private val chirpRate = 22050
    private val player = Executors.newSingleThreadExecutor()
    private val tts = TextToSpeech(ctx, this)
    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var ttsReady = false

    private val settings = Settings(ctx)   // saved voice choice, pitch, rate, robot effect
    val ready get() = ttsReady

    /** When the currently queued audio will have finished playing. */
    @Volatile private var playingUntil = 0L
    fun isPlaying() = System.currentTimeMillis() < playingUntil + 400
    /** Did any of our audio play after [sinceMs]? (so the mic may have heard us) */
    fun playedSince(sinceMs: Long) = playingUntil + 400 > sinceMs

    private fun queued(ms: Long) {
        playingUntil = maxOf(playingUntil, System.currentTimeMillis()) + ms
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.US
        applyVoice()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { pending.remove(id)?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { pending.remove(id)?.countDown() }
        })
        ttsReady = true
    }

    /** Use the saved voice, or else a male US/UK English voice that works offline. */
    private fun applyVoice() {
        val voices = tts.voices ?: return
        val saved = settings.voice?.let { name -> voices.firstOrNull { it.name == name } }
        val v = saved ?: MALE_VOICES.firstNotNullOfOrNull { want ->
            voices.firstOrNull { it.name.startsWith(want) && !it.isNetworkConnectionRequired }
        }
        if (v != null) tts.voice = v
        Log.i(TAG, "voice: ${tts.voice?.name}")
    }

    /** Offline English voices, male ones first, as (name, friendly label). */
    fun voiceChoices(): List<Pair<String, String>> {
        val voices = (tts.voices ?: emptySet())
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && it.name.contains("-x-") }
        fun label(name: String): String {
            val region = name.substring(0, 5).uppercase().replace("EN-", "")
            val id = name.substringAfter("-x-").substringBefore("-")
            val male = MALE_VOICES.any { name.startsWith(it) }
            return "$region ${if (male) "male" else "voice"} ($id)"
        }
        return voices.map { it.name to label(it.name) }
            .sortedWith(compareBy({ !it.second.contains("male") }, { it.second }))
    }

    val currentVoice: String? get() = tts.voice?.name

    // ---------------------------------------------------------------- chirps

    /** One tone: frequency glides from f0 to f1 over ms, then gap ms of silence. */
    private class Seg(val f0: Double, val f1: Double, val ms: Int, val gap: Int)

    fun chirp(mood: String, seed: Long?): JSONObject {
        val r = Random(seed ?: System.nanoTime())
        val pcm = renderChirp(pattern(mood, r))
        queued(pcm.size * 1000L / chirpRate)
        player.execute { play(pcm, chirpRate) }
        return JSONObject().put("mood", mood).put("ms", pcm.size * 1000 / chirpRate)
    }

    private fun pattern(mood: String, r: Random): List<Seg> {
        fun rnd(a: Int, b: Int) = r.nextInt(a, b + 1).toDouble()
        return when (mood) {
            "happy" -> List(r.nextInt(4, 7)) {
                val f = rnd(900, 1500); Seg(f, f * rnd(14, 18) / 10, rnd(60, 110).toInt(), 30)
            }
            "sad" -> List(r.nextInt(2, 4)) {
                Seg(rnd(1100, 1300), rnd(450, 600), rnd(250, 400).toInt(), 80)
            }
            "curious" -> List(r.nextInt(2, 4)) {
                Seg(rnd(900, 1800), rnd(900, 1800), rnd(50, 90).toInt(), 40)
            } + Seg(600.0, 1900.0, 320, 0)        // rising "huh?" at the end
            "alert" -> List(6) { Seg(2000.0, 2400.0, 55, 40) }
            "listen" -> listOf(Seg(900.0, 1300.0, 70, 40), Seg(1300.0, 1900.0, 90, 0))   // "go ahead"
            "excited" -> List(r.nextInt(8, 13)) {
                Seg(rnd(800, 2500), rnd(800, 2500), rnd(30, 70).toInt(), 15)
            }
            "greeting" -> listOf(Seg(1000.0, 1600.0, 120, 20), Seg(1600.0, 1100.0, 120, 60)) +
                List(6) { i -> if (i % 2 == 0) Seg(1800.0, 1800.0, 40, 5) else Seg(2300.0, 2300.0, 40, 5) }
            else -> throw IllegalArgumentException(
                "unknown mood '$mood' (happy, sad, curious, alert, excited, greeting)")
        }
    }

    private fun renderChirp(segs: List<Seg>): ShortArray {
        val total = segs.sumOf { (it.ms + it.gap) * chirpRate / 1000 }
        val out = ShortArray(total)
        var i = 0
        var phase = 0.0
        val fade = chirpRate * 5 / 1000 // 5 ms attack/release to avoid clicks
        for (s in segs) {
            val n = s.ms * chirpRate / 1000
            for (k in 0 until n) {
                val t = k.toDouble() / n
                val f = s.f0 + (s.f1 - s.f0) * t
                phase += 2 * PI * f / chirpRate
                val env = min(1.0, min(k, n - k).toDouble() / fade)
                // A touch of 2nd harmonic makes it sound more like a droid than a test tone
                val v = (0.55 * sin(phase) + 0.15 * sin(2 * phase)) * env
                out[i++] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            i += s.gap * chirpRate / 1000
        }
        return out
    }

    // ---------------------------------------------------------------- speech

    fun speak(text: String): JSONObject {
        if (!ttsReady) throw Unavailable("text-to-speech not ready")
        val clean = text.take(300)
        tts.setPitch(settings.pitch)
        tts.setSpeechRate(settings.rate)

        // Render the speech to a WAV file so we can run it through the robot effect
        val id = "droid-${System.nanoTime()}"
        val file = File(ctx.cacheDir, "$id.wav")
        val done = CountDownLatch(1)
        pending[id] = done
        if (tts.synthesizeToFile(clean, Bundle(), file, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw Unavailable("speech synthesis failed")
        }
        if (!done.await(15, TimeUnit.SECONDS) || !file.exists()) {
            throw Unavailable("speech synthesis timed out")
        }
        val (pcm, rate) = readWav(file)
        file.delete()
        val out = if (settings.robot) robotize(pcm, rate) else pcm
        queued(out.size * 1000L / rate)
        player.execute { play(out, rate) }
        return JSONObject()
            .put("speaking", clean)
            .put("voice", tts.voice?.name ?: JSONObject.NULL)
            .put("ms", out.size * 1000L / rate)
    }

    fun voices(): JSONObject = JSONObject()
        .put("current", tts.voice?.name ?: JSONObject.NULL)
        .put("robot", settings.robot).put("pitch", settings.pitch).put("rate", settings.rate)
        .put("voices", JSONArray((tts.voices ?: emptySet())
            .filter { it.locale.language == "en" }
            .sortedBy { it.name }
            .map {
                JSONObject().put("name", it.name).put("locale", it.locale.toLanguageTag())
                    .put("offline", !it.isNetworkConnectionRequired)
            }))

    /** Change (and save) voice settings; null leaves a setting unchanged. */
    fun configure(name: String?, robot: Boolean?, pitch: Float?, rate: Float?): JSONObject {
        if (name != null) {
            val v = tts.voices?.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("no voice named '$name' (see /sound/voices)")
            tts.voice = v
            settings.voice = name
        }
        robot?.let { settings.robot = it }
        pitch?.let { settings.pitch = it }
        rate?.let { settings.rate = it }
        return voices().apply { remove("voices") }
    }

    /**
     * Classic sci-fi robot voice: ring-modulate the speech with a low sine (the
     * "metallic" buzz), blend some dry signal back for clarity, then add a short
     * resonant echo like it's coming out of a metal dome.
     */
    private fun robotize(pcm: ShortArray, rate: Int): ShortArray {
        val modHz = 55.0
        val delay = rate * 12 / 1000      // 12 ms comb filter
        val feedback = 0.45
        val buf = DoubleArray(pcm.size)
        for (i in pcm.indices) {
            val x = pcm[i] / 32768.0
            val ring = x * sin(2 * PI * modHz * i / rate)
            var y = 0.65 * ring + 0.45 * x
            if (i >= delay) y += feedback * buf[i - delay]
            buf[i] = y
        }
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6)
        val gain = 0.9 / peak
        return ShortArray(pcm.size) { (buf[it] * gain * 32767).toInt().toShort() }
    }

    /** Minimal WAV reader for 16-bit PCM (what Android TTS writes). */
    private fun readWav(f: File): Pair<ShortArray, Int> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 22050
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> rate = b.getInt(body + 4)
                "data" -> {
                    val n = min(size, b.limit() - body) / 2
                    return ShortArray(n) { b.getShort(body + it * 2) } to rate
                }
            }
            pos = body + size + (size and 1)
        }
        throw Unavailable("could not read synthesized speech")
    }

    // ---------------------------------------------------------------- output

    private fun play(pcm: ShortArray, rate: Int) {
        if (pcm.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / rate + 50)
        } finally {
            track.release()
        }
    }

    fun shutdown() {
        tts.shutdown()
        player.shutdownNow()
    }

    companion object {
        private const val TAG = "SoundHub"
        /** Google TTS voices known to be male, in order of preference. */
        val MALE_VOICES = listOf("en-us-x-iom", "en-us-x-tpd", "en-us-x-iol", "en-gb-x-rjs", "en-gb-x-gbd")
    }
}
