package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/**
 * HindiTtsService — Piper TTS via hindi_tts_server.py on port 8766
 *
 * Architecture:
 *   speak(text) → fetchQueue (unbounded FIFO)
 *   Fetch worker: text → WAV from Piper (~200-400ms at 1.5x speed)
 *   Play worker:  WAV → AudioTrack (USAGE_ASSISTANT, excluded from LC capture)
 *
 * Loop prevention:
 *   AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: pauses video while TTS plays
 *   → LC has no video audio to caption → loop impossible
 *   Token dedup: same sentence never spoken twice
 *   isSuppressed(): blocks LiveCaptionReader.enqueue() during TTS + grace
 *
 * Gender:
 *   Polls whisper_server :8765/gender (real audio FFT from video)
 *   Passes gender=male/female to Piper server
 *   Female: pitch shifted +35% in numpy on server side (no extra model)
 *
 * Female/Male voices come from same hi_IN-rohan model — female is
 * pitch-shifted up. Not a separate model but clearly distinguishable.
 */
object HindiTtsService {

    private const val TAG      = "HindiTTS"
    private const val TTS_URL  = "http://127.0.0.1:8766/tts"
    private const val GENDER_URL = "http://127.0.0.1:8765/gender"

    enum class Gender  { AUTO, MALE, FEMALE }
    // Extended emotion enum — covers all 26 emotions detected by GenderAnalyzer
    // The 6 original + 20 new ones referenced in Genderanalyzer.kt
    enum class Emotion {
        NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS,
        // Voice texture emotions (used by GenderAnalyzer acoustic analysis)
        WARM, FEARFUL, SURPRISED, SIGHING,
        SINGING, GASPING, PANTING, MOANING,
        STRAINED, GRAVELLY, RASPY, HUSKY,
        WHISPERY, MURMURED, HUSHED, BREATHY,
        SULTRY, TENDER, VELVETY, DISGUST
    }

    // Current emotion detected by GenderAnalyzer — updated externally, read by speak()
    @Volatile var currentEmotion: Emotion = Emotion.NEUTRAL

    // FIX: enabled = true by default.
    // Was false — requiring user to manually toggle in Flutter UI settings panel.
    // speak() has guard 'if (!enabled) return' — with false default, ZERO audio ever played.
    // Auto-enable here; user can still disable from settings if desired.
    @JvmField @Volatile var enabled           = true
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    // Speed 1.8x: faster speech = shorter audio duration = Piper synthesizes fewer samples
    // = synthesis finishes in ~2s instead of 4s on ARM Dimensity 7050
    // User can reduce via Flutter settings if too fast
    @Volatile var ttsSpeedMultiplier          = 1.8f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false
    @Volatile private var speakingUntilMs     = 0L

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cacheDir: java.io.File? = null
    private var am: AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    // ── FIFO queues (unbounded — never drop sentences) ────────────────────────
    data class FetchItem(
        val text: String,
        val gender: String,
        val speed: Float,
        val srcText: String = "",
        val emotion: Emotion = Emotion.NEUTRAL,
        val enqMs: Long = System.currentTimeMillis()
    )
    data class PlayItem (val text: String, val wav: ByteArray, val durMs: Long)

    private val fetchQueue = LinkedBlockingQueue<FetchItem>()
    private val playQueue  = LinkedBlockingQueue<PlayItem>()

    // Token dedup — never re-speak same sentence
    @JvmField val spokenTokens = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()  // accessible from GenderAnalyzer

    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null
    private var genderJob:   Job? = null

    // Gender history
    // genderHistory removed — pronoun detection removed entirely

    // ── Init / lifecycle ──────────────────────────────────────────────────────

    fun init(context: Context) {
        cacheDir = context.cacheDir
        am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Prevent LC from capturing this app's audio
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            am?.allowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_NONE
        }
        startFetchWorker()
        startPlayWorker()
        startGenderPoller()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            fetchQueue.clear(); playQueue.clear()
            stopAudio()
            spokenTokens.clear()
        }
    }

    fun setGender(g: Gender) { selectedGender = g }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun stopAndClear() {
        fetchQueue.clear(); playQueue.clear()
        stopAudio()
        isSpeaking = false
        speakingUntilMs = System.currentTimeMillis() + 2_000L
        spokenTokens.clear()
        Log.d(TAG, "Stopped (LC silent)")
    }

    fun destroy() {
        genderJob?.cancel(); fetchWorker?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopAudio()
        scope.cancel()
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String, srcText: String = "") {
        if (!enabled || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")

        val token = n.hashCode()
        if (spokenTokens.putIfAbsent(token, true) != null) return
        if (spokenTokens.size > 300) spokenTokens.clear()

        val emotion = detectEmotion(n)
        val speed   = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val genderTag = when (selectedGender) {
            Gender.FEMALE -> "female"
            Gender.MALE   -> "male"
            Gender.AUTO   -> "auto"
        }
        CaptionLogger.log(TAG, "SPEAK emo=$emotion spd=${"%.2f".format(speed)} '${n.take(50)}'")
        fetchQueue.offer(FetchItem(n, genderTag, speed, srcText, emotion, System.currentTimeMillis()))
    }


    // updateGenderFromSource() removed — pronouns describe who is talked ABOUT,
    // not who is speaking. Gender detection is audio-only via GenderAnalyzer.kt.

    // ── Fetch worker (Piper HTTP → WAV) ───────────────────────────────────────

    private fun startFetchWorker() {
        fetchWorker = scope.launch {
            while (isActive) {
                val item = fetchQueue.take()   // blocks — never misses
                if (!enabled) continue

                // STALE GUARD: drop sentences waiting >15s
                // With FIFO token display (subtitle shown when TTS plays, not when translation arrives),
                // synthesis delay is hidden from user — they see subtitle exactly when audio plays.
                // So we can afford a longer stale window. 15s = allows 1-2 slow syntheses in queue.
                val ageMs = System.currentTimeMillis() - item.enqMs
                if (ageMs > 15_000L) {
                    CaptionLogger.log(TAG, "TTS-SKIP stale ${ageMs/1000}s '${item.text.take(30)}'")
                    continue
                }

                // QUEUE OVERLOAD: if more than 3 items waiting, skip current
                // With 8-10s synthesis, q>3 = 24-30s of queued content = too old
                if (fetchQueue.size > 3) {
                    CaptionLogger.log(TAG, "TTS-SKIP overloaded q=${fetchQueue.size+1}, going to latest")
                    continue
                }

                try {
                    val resolvedGender = if (item.gender == "auto")
                        if (detectedGender == Gender.FEMALE) "female" else "male"
                    else item.gender

                    val verbGender = when {
                        resolvedGender == "female" -> "female"
                        hasFemininePronouns(item.srcText) -> "female"
                        else -> "male"
                    }
                    val textToSpeak = if (verbGender == "female")
                        toFeminineHindi(item.text) else item.text

                    val t0 = System.currentTimeMillis()
                    val wav = fetchWav(textToSpeak, resolvedGender, item.speed, item.emotion)
                    val fetchMs = System.currentTimeMillis() - t0

                    if (wav != null && wav.size > 44) {
                        val sr  = readInt(wav, 24).coerceAtLeast(8_000)
                        val nch = readShort(wav, 22).coerceAtLeast(1)
                        val bit = readShort(wav, 34).coerceAtLeast(8)
                        val dur = ((wav.size - 44).toLong() * 1000) / (sr.toLong() * nch * (bit / 8))
                        CaptionLogger.log(TAG, "TTS-WAV ${fetchMs}ms ${dur}ms ${resolvedGender} '${textToSpeak.take(40)}'")
                        playQueue.offer(PlayItem(item.text, wav, dur))
                    } else {
                        CaptionLogger.log(TAG, "TTS-ERR ${fetchMs}ms — server returned null/empty WAV")
                        Log.w(TAG, "Empty WAV — is hindi_tts_server2.py running on port 8766?")
                    }
                } catch (e: Exception) {
                    CaptionLogger.log(TAG, "TTS-EXC ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Fetch: ${e.message}")
                }
            }
        }
    }

    // ── Play worker (AudioTrack — excluded from LC capture) ───────────────────

    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                val item = playQueue.take()   // blocks — no gaps
                if (!enabled) continue
                try {
                    isSpeaking = true
                    CaptionLogger.log(TAG, "PLAY ${item.durMs}ms '${item.text.take(40)}'")
                    // FIFO TOKEN: Show subtitle exactly when audio starts playing.
                    // This is the ONLY place where the subtitle overlay is updated.
                    // Livecaptionreader no longer calls OverlayService.updateText() directly.
                    withContext(Dispatchers.Main) {
                        OverlayService.showTtsText(item.text)
                    }
                    playWav(item.wav, item.durMs)
                    // Keep subtitle visible after audio ends until next sentence starts.
                    // clearTtsText() only fades out if nothing is queued — OverlayService
                    // handles this: if playQueue has next item, showTtsText() will immediately
                    // replace it without a gap.
                    withContext(Dispatchers.Main) {
                        OverlayService.clearTtsText()
                    }
                    speakingUntilMs = System.currentTimeMillis() + 300L
                } catch (e: Exception) {
                    CaptionLogger.log(TAG, "PLAY-ERR ${e.message}")
                    Log.e(TAG, "Play: ${e.message}")
                } finally {
                    isSpeaking = false
                }
            }
        }
    }

    // ── HTTP fetch ────────────────────────────────────────────────────────────

    private suspend fun fetchWav(text: String, gender: String, speed: Float, emotion: Emotion = Emotion.NEUTRAL): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc = java.net.URLEncoder.encode(text, "UTF-8")
                val emoStr = emotion.name
                conn = URL("$TTS_URL?text=$enc&gender=$gender&speed=$speed&emo=$emoStr")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                // 12s read timeout: server now has 10s synthesis limit + 2s margin
                conn.readTimeout    = 12_000
                when (conn.responseCode) {
                    200  -> conn.inputStream.readBytes()
                    503  -> { CaptionLogger.log(TAG, "TTS-BUSY server synthesizing, skip"); null }
                    408  -> { CaptionLogger.log(TAG, "TTS-TIMEOUT server 6s limit, skip"); null }
                    else -> { CaptionLogger.log(TAG, "TTS-HTTP ${conn.responseCode}"); null }
                }
            } catch (e: java.net.SocketTimeoutException) {
                CaptionLogger.log(TAG, "TTS-CONN timeout (server not running?)")
                null
            } catch (e: Exception) {
                CaptionLogger.log(TAG, "TTS-EXC ${e.javaClass.simpleName}")
                null
            }
            finally { try { conn?.disconnect() } catch (_: Exception) {} }
        }

    // ── AudioTrack playback (USAGE_ASSISTANT = excluded from LC) ─────────────

    private var audioTrack: AudioTrack? = null

    private fun stopAudio() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private suspend fun playWav(wav: ByteArray, durMs: Long) = withContext(Dispatchers.IO) {
        try {
            val sr   = readInt(wav, 24).coerceAtLeast(8_000)
            val nch  = readShort(wav, 22)
            val bit  = readShort(wav, 34)
            val pcm  = wav.copyOfRange(44, wav.size)
            val fmt  = if (bit == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
            val chan = if (nch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sr, chan, fmt).coerceAtLeast(pcm.size)

            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    // USAGE_ASSISTANT: system services (including Live Captions)
                    // cannot capture audio with this usage type
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sr).setChannelMask(chan).setEncoding(fmt).build())
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            stopAudio()
            audioTrack = track
            track.write(pcm, 0, pcm.size)
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()

            // Wait for playback to finish
            delay(durMs + 200L)
            track.stop(); track.release()
            audioTrack = null
        } catch (e: Exception) { Log.e(TAG, "playWav: ${e.message}") }
    }

    // ── Audio focus (pauses video while TTS plays) ────────────────────────────

    private fun requestAudioFocus() {
        val manager = am ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener {}.build()
            focusRequest = req
            manager.requestAudioFocus(req)
        }
    }

    private fun releaseAudioFocus() {
        val req = focusRequest ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            am?.abandonAudioFocusRequest(req)
        }
        focusRequest = null
    }


    // ── Pronoun hint (verb forms only — does NOT affect voice switching) ──────
    //
    // Checks English source text for feminine pronouns.
    // Result is used ONLY for Hindi verb conjugation, never for voice selection.
    // Voice (male/female) is controlled exclusively by GenderAnalyzer audio pitch.

    private fun hasFemininePronouns(src: String): Boolean {
        if (src.isBlank()) return false
        val t = " ${src.lowercase()} "
        return t.contains(" she ") || t.contains(" her ") ||
               t.contains(" herself ") || t.contains(" she's ") ||
               t.contains(" she'd ") || t.contains(" she'll ")
    }

    // ── Feminine Hindi verb form conversion ──────────────────────────────────
    //
    // Converts masculine verb endings to feminine in Hindi text.
    // Applied when: (a) audio detects female voice, OR (b) source text has she/her pronouns.
    // Covers ि / ी / े / ैं suffix patterns in common verb+auxiliary combinations.

    private fun toFeminineHindi(text: String): String {
        var t = text

        // ── First person present ──────────────────────────────────────────────
        t = t.replace("ता हूँ", "ती हूँ")
        t = t.replace("ता हूं", "ती हूं")
        t = t.replace("रहा हूँ", "रही हूँ")
        t = t.replace("रहा हूं", "रही हूं")
        t = t.replace("सकता हूँ", "सकती हूँ")
        t = t.replace("सकता हूं", "सकती हूं")
        t = t.replace("चाहता हूँ", "चाहती हूँ")
        t = t.replace("चाहता हूं", "चाहती हूं")

        // ── Third person present ──────────────────────────────────────────────
        t = t.replace("ता है", "ती है")
        t = t.replace("रहा है", "रही है")
        t = t.replace("ता हैं", "ती हैं")
        t = t.replace("रहे हैं", "रही हैं")
        t = t.replace("सकता है", "सकती है")
        t = t.replace("चाहता है", "चाहती है")
        t = t.replace("लगता है", "लगती है")
        t = t.replace("होता है", "होती है")
        t = t.replace("मिलता है", "मिलती है")

        // ── Past tense ────────────────────────────────────────────────────────
        t = t.replace("ता था", "ती थी")
        t = t.replace("ते थे", "ती थीं")
        t = t.replace("रहा था", "रही थी")
        t = t.replace("सकता था", "सकती थी")
        t = t.replace("लगता था", "लगती थी")
        t = t.replace("होता था", "होती थी")
        t = t.replace("चाहता था", "चाहती थी")

        // ── Past participle ───────────────────────────────────────────────────
        t = t.replace("गया", "गई")
        t = t.replace("गए", "गईं")
        t = t.replace("आया", "आई")
        t = t.replace("आए", "आईं")
        t = t.replace("किया", "की")
        t = t.replace("लिया", "ली")
        t = t.replace("दिया", "दी")
        t = t.replace("पाया", "पाई")
        t = t.replace("पाए", "पाईं")
        t = t.replace("बताया", "बताई")
        t = t.replace("सुनाया", "सुनाई")
        t = t.replace("बनाया", "बनाई")

        // ── Continuous ────────────────────────────────────────────────────────
        t = t.replace("रहा हूँ", "रही हूँ")  // (already above, idempotent)
        t = t.replace("रहा हूं", "रही हूं")

        return t
    }

    // ── Gender poller ─────────────────────────────────────────────────────────

    // Two-layer gender detection:
    // Layer 1 (primary): GenderAnalyzer.kt — real audio FFT via MediaProjection
    // Layer 2 (fallback): HTTP poll /gender on whisper_server every 3s
    //   whisper_server /gender endpoint returns pitch analysis from mic/playback
    //   This fires if MediaProjection was not granted or GenderAnalyzer lost audio
    private fun startGenderPoller() {
        Log.d(TAG, "Gender poller started (fallback layer)")
        scope.launch(Dispatchers.IO) {
            var failStreak = 0
            while (isActive) {
                delay(3_000L)
                if (selectedGender != Gender.AUTO) continue
                try {
                    val conn = URL(GENDER_URL).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod  = "GET"
                    conn.connectTimeout = 2_000
                    conn.readTimeout    = 3_000
                    if (conn.responseCode == 200) {
                        val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                        val g = json.optString("gender", "")
                        if (g == "female" || g == "male") {
                            val newG = if (g == "female") Gender.FEMALE else Gender.MALE
                            if (newG != detectedGender) {
                                detectedGender = newG
                                Log.d(TAG, "GenderPoller→$newG (audio pitch)")
                                CaptionLogger.log(TAG, "Gender fallback→$g")
                            }
                        }
                        failStreak = 0
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    failStreak++
                    // Back off after 5 consecutive failures (server not running)
                    if (failStreak > 5) delay(15_000L)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun detectEmotion(t: String): Emotion {
        // Prefer GenderAnalyzer's audio-derived emotion when it's not NEUTRAL
        // (audio emotion is more accurate than text heuristics)
        if (currentEmotion != Emotion.NEUTRAL) return currentEmotion

        // Fallback: text-based detection
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","sad","sorry","cry").any { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn").any  { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","खुश","love","great").any { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED    -> 1.10f
        Emotion.HAPPY      -> 1.05f
        Emotion.CURIOUS    -> 0.97f
        Emotion.SAD        -> 0.88f
        Emotion.ANGRY      -> 1.08f
        Emotion.FEARFUL    -> 1.05f   // slightly faster — anxious pace
        Emotion.SURPRISED  -> 1.08f
        Emotion.WARM       -> 0.95f   // warm/calm — slightly slower
        Emotion.SIGHING    -> 0.85f
        Emotion.SINGING    -> 0.90f   // melodic pace
        Emotion.GASPING    -> 1.15f   // rapid
        Emotion.PANTING    -> 1.18f
        Emotion.MOANING    -> 0.80f   // slow, drawn out
        Emotion.STRAINED   -> 0.92f
        Emotion.GRAVELLY   -> 0.95f
        Emotion.RASPY      -> 0.97f
        Emotion.HUSKY      -> 0.93f
        Emotion.WHISPERY   -> 0.90f
        Emotion.MURMURED   -> 0.88f
        Emotion.HUSHED     -> 0.87f
        Emotion.BREATHY    -> 0.92f
        Emotion.SULTRY     -> 0.85f
        Emotion.TENDER     -> 0.90f
        Emotion.VELVETY    -> 0.88f
        Emotion.DISGUST    -> 1.05f
        else               -> 1.00f
    }

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl  8) or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
}
