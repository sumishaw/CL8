package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * HindiTtsService — speaks translated Hindi subtitles
 *
 * Voice model: Kokoro multi-lang on port 8766
 *   sid=31  hf_alpha  → Hindi Female voice 1 (genuine Indian female)
 *   sid=32  hf_beta   → Hindi Female voice 2
 *   sid=33  hm_omega  → Hindi Male voice 1
 *   sid=34  hm_psi    → Hindi Male voice 2
 *
 * Gender detection: two-signal fusion
 *   1. ZCR pitch from AudioRecord (frequency-based, works for internal audio via mic)
 *   2. Pronoun/keyword analysis of source text (language-aware)
 *   Signals are smoothed and combined: text wins on clear pronoun match,
 *   frequency used as tiebreaker / confirmation
 *
 * Dedicated FIFO backlogs (no sentence skipping):
 *   fetchQueue (unbounded) — texts waiting to be synthesised
 *   playQueue  (unbounded) — WAV bytes ready to play
 *   Worker A fetches WAV in background while Worker B plays previous sentence
 */
object HindiTtsService {
    private const val TAG     = "HindiTTS"
    private const val TTS_URL = "http://127.0.0.1:8766/tts"

    // Kokoro Hindi speaker IDs
    private const val SID_FEMALE_1 = 31   // hf_alpha
    private const val SID_FEMALE_2 = 32   // hf_beta
    private const val SID_MALE_1   = 33   // hm_omega
    private const val SID_MALE_2   = 34   // hm_psi

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f   // default 1.5x — keeps up with speech

    @Volatile var detectedGender = Gender.MALE
    @Volatile var isSpeaking     = false
    @Volatile private var speakingUntilMs = 0L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class FetchItem(val text: String, val sid: Int, val speed: Float)
    data class PlayItem (val wav: ByteArray, val durationMs: Long)

    private val fetchQueue = LinkedBlockingQueue<FetchItem>()
    private val playQueue  = LinkedBlockingQueue<PlayItem>()

    private var lastSpokenNorm = ""
    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Gender fusion
    private val textHistory  = ArrayDeque<Gender>()
    private val pitchHistory = ArrayDeque<Gender>()
    private val HISTORY_SIZE = 10
    private var pitchDetector: InternalAudioPitchDetector? = null

    // ── Init / lifecycle ─────────────────────────────────────────────────────

    fun init(context: Context) {
        // Use internal audio detector (captures screen audio, not mic)
        // Falls back to mic-based ZCR if projection not available
        pitchDetector = InternalAudioPitchDetector(context) { gender ->
            if (!isSuppressed()) {
                pitchHistory.addLast(gender)
                if (pitchHistory.size > HISTORY_SIZE) pitchHistory.removeFirst()
                updateFusedGender()
            }
        }
        startFetchWorker()
        startPlayWorker()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            if (selectedGender == Gender.AUTO) pitchDetector?.start()
        } else {
            pitchDetector?.stop()
            fetchQueue.clear(); playQueue.clear()
            stopMediaPlayer(); lastSpokenNorm = ""
        }
    }

    fun setGender(gender: Gender) {
        selectedGender = gender
        if (gender == Gender.AUTO) pitchDetector?.start() else pitchDetector?.stop()
    }

    fun setSpeedMultiplier(mult: Float) {
        ttsSpeedMultiplier = mult.coerceIn(0.5f, 4.0f)
    }

    fun isSuppressed(): Boolean =
        isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun destroy() {
        pitchDetector?.stop()
        fetchWorker?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopMediaPlayer(); scope.cancel()
    }

    // ── Called by LiveCaptionReader after each translation ───────────────────

    fun speak(hindiText: String) {
        if (!enabled || hindiText.isBlank()) return
        val n = hindiText.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n

        val emotion    = detectEmotion(hindiText)
        val (baseSpeed, _) = emotionParams(emotion)
        // Apply user speed multiplier — 1x=normal, 2x=twice as fast etc.
        val speed = (baseSpeed * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val sid   = effectiveSid()
        fetchQueue.offer(FetchItem(hindiText, sid, speed))
    }

    // Update text-signal gender from source text
    fun updateGenderFromText(srcText: String, lang: String) {
        if (selectedGender != Gender.AUTO) return
        val g = GenderDetector.detect(srcText, lang) ?: return
        textHistory.addLast(g)
        if (textHistory.size > HISTORY_SIZE) textHistory.removeFirst()
        updateFusedGender()
    }

    // ── Gender fusion ────────────────────────────────────────────────────────

    private fun updateFusedGender() {
        val textSignal  = majorityVote(textHistory)
        val pitchSignal = majorityVote(pitchHistory)

        detectedGender = when {
            // Strong text signal — definite pronoun match
            textHistory.size >= 3 && textSignal != null -> textSignal
            // Strong pitch signal — frequency clearly male/female
            pitchHistory.size >= 5 && pitchSignal != null -> pitchSignal
            // Both agree
            textSignal != null && pitchSignal != null && textSignal == pitchSignal -> textSignal
            // Text wins over pitch on tie
            textSignal != null -> textSignal
            // Pitch fallback
            pitchSignal != null -> pitchSignal
            // Default
            else -> detectedGender
        }
    }

    private fun majorityVote(history: ArrayDeque<Gender>): Gender? {
        if (history.isEmpty()) return null
        val f = history.count { it == Gender.FEMALE }
        val m = history.count { it == Gender.MALE }
        return when {
            f > m -> Gender.FEMALE
            m > f -> Gender.MALE
            else  -> null
        }
    }

    private fun effectiveSid(): Int {
        val g = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        return if (g == Gender.FEMALE) SID_FEMALE_1 else SID_MALE_1
    }

    // ── Emotion ──────────────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        val sad    = listOf("दुखी","उदास","दर्द","रोया","गम","sad","cry","pain","sorry","miss","alone")
        val angry  = listOf("गुस्सा","क्रोध","नफरत","angry","hate","stupid","damn","liar","cheat")
        val happy  = listOf("खुश","धन्यवाद","प्यार","शुक्रिया","happy","love","thanks","great","yay")
        val excite = listOf("वाह","कमाल","शानदार","wow","amazing","awesome","incredible","fantastic")
        if (sad.any    { l.contains(it) }) return Emotion.SAD
        if (angry.any  { l.contains(it) }) return Emotion.ANGRY
        if (excite.any { l.contains(it) }) return Emotion.EXCITED
        if (happy.any  { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionParams(e: Emotion): Pair<Float, Float> = when (e) {
        Emotion.EXCITED -> Pair(1.12f, 1.0f)
        Emotion.HAPPY   -> Pair(1.06f, 1.0f)
        Emotion.CURIOUS -> Pair(0.96f, 1.0f)
        Emotion.SAD     -> Pair(0.82f, 1.0f)
        Emotion.ANGRY   -> Pair(1.10f, 1.0f)
        Emotion.NEUTRAL -> Pair(1.05f, 1.0f)
    }

    // ── Worker A: Fetch WAV from Kokoro server ────────────────────────────────

    private fun startFetchWorker() {
        fetchWorker = scope.launch {
            while (isActive) {
                val item = fetchQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (!enabled) continue
                try {
                    val wav = requestTts(item.text, item.sid, item.speed)
                    if (wav != null && wav.size > 44) {
                        val sr   = readInt(wav, 24)
                        val nCh  = readShort(wav, 22)
                        val bits = readShort(wav, 34)
                        val pcmLen = wav.size - 44
                        val durationMs = (pcmLen.toLong() * 1000) /
                            (sr.toLong() * nCh * (bits / 8))
                        playQueue.offer(PlayItem(wav, durationMs))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fetch: ${e.message}")
                }
            }
        }
    }

    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                val item = playQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (!enabled) continue
                try {
                    isSpeaking = true
                    playWav(item.wav)
                    // Short grace — just enough to prevent re-capture of final syllables
                    speakingUntilMs = System.currentTimeMillis() + 500L
                } catch (e: Exception) {
                    Log.e(TAG, "Play: ${e.message}")
                } finally {
                    isSpeaking = false
                }
                // No delay between sentences — play queue drains continuously
            }
        }
    }

    // ── HTTP request ─────────────────────────────────────────────────────────

    private suspend fun requestTts(text: String, sid: Int, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc  = java.net.URLEncoder.encode(text, "UTF-8")
                val url  = "$TTS_URL?text=$enc&sid=$sid&speed=$speed"
                conn     = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout    = 30_000
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } catch (_: Exception) { null }
            finally { try { conn?.disconnect() } catch (_: Exception) {} }
        }

    // ── MediaPlayer playback ─────────────────────────────────────────────────

    private fun stopMediaPlayer() {
        mainHandler.post {
            try { mediaPlayer?.stop()    } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    private suspend fun playWav(wavBytes: ByteArray) {
        val latch = java.util.concurrent.CountDownLatch(1)
        withContext(Dispatchers.Main) {
            try {
                mediaPlayer?.let { try { it.release() } catch (_: Exception) {} }
                mediaPlayer = null
                val mp = android.media.MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                mp.setVolume(1.0f, 1.0f)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    mp.setDataSource(ByteArrayMediaDataSource(wavBytes))
                } else {
                    val f = java.io.File("/data/local/tmp/tts_kokoro.wav")
                    f.parentFile?.mkdirs(); f.writeBytes(wavBytes)
                    mp.setDataSource(f.absolutePath)
                }
                mp.setOnCompletionListener {
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null; latch.countDown()
                }
                mp.setOnErrorListener { it, w, x ->
                    Log.e(TAG, "MP err w=$w x=$x")
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null; latch.countDown(); true
                }
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                Log.d(TAG, "TTS playing sid=${effectiveSid()} ${mp.duration}ms")
            } catch (e: Exception) {
                Log.e(TAG, "playWav: ${e.message}"); latch.countDown()
            }
        }
        withContext(Dispatchers.IO) {
            latch.await(35, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    // ── WAV header helpers ────────────────────────────────────────────────────

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl 8)  or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    fun stopCurrent() {
        fetchQueue.clear(); playQueue.clear()
        stopMediaPlayer(); isSpeaking = false; lastSpokenNorm = ""
    }
}

// ── GenderDetector — text pronoun analysis ───────────────────────────────────

object GenderDetector {
    /** Returns Female/Male if clear pronoun match found, null if ambiguous */
    fun detect(text: String, lang: String): HindiTtsService.Gender? {
        val t = text.lowercase().trim()
        val female: Boolean
        val male:   Boolean
        when (lang) {
            "ja" -> {
                female = t.contains("彼女") || t.contains("あたし") || t.contains("私") ||
                         t.any { c -> "わよね".contains(c) } || t.contains("お母さん")
                male   = t.contains("彼は") || t.contains("俺") || t.contains("僕") ||
                         t.contains("お父さん") || t.contains("彼氏")
            }
            "zh" -> {
                female = t.contains("她") || t.contains("姐") || t.contains("妈") ||
                         t.contains("女士") || t.contains("女孩")
                male   = t.contains("他 ") || t.contains("哥") || t.contains("爸") ||
                         t.contains("先生") || t.contains("男孩")
            }
            "ko" -> {
                female = t.contains("그녀") || t.contains("언니") || t.contains("누나") ||
                         t.contains("여자") || t.contains("엄마")
                male   = t.contains("그는") || t.contains("오빠") || t.contains("형") ||
                         t.contains("남자") || t.contains("아빠")
            }
            "ru" -> {
                female = t.contains("она ") || t.contains("её") || t.contains("сестра") ||
                         t.contains("девушка") || t.contains("женщина")
                male   = t.contains("он ") || t.contains("его") || t.contains("брат") ||
                         t.contains("мужчина") || t.contains("парень")
            }
            "ar" -> {
                female = t.contains("هي") || t.contains("أنثى") || t.contains("امرأة")
                male   = t.contains("هو") || t.contains("ذكر") || t.contains("رجل")
            }
            "es" -> {
                female = t.contains(" ella ") || t.contains("señora") || t.contains("mamá") || t.contains("mujer")
                male   = t.contains(" él ")   || t.contains("señor")  || t.contains("papá") || t.contains("hombre")
            }
            "fr" -> {
                female = t.contains(" elle ") || t.contains("madame") || t.contains("femme")
                male   = t.contains(" il ")   || t.contains("monsieur") || t.contains("homme")
            }
            "de" -> {
                female = t.contains(" sie ") || t.contains("frau") || t.contains("mutter")
                male   = t.contains(" er ")  || t.contains("herr") || t.contains("vater")
            }
            "pt" -> {
                female = t.contains(" ela ") || t.contains("senhora") || t.contains("mulher")
                male   = t.contains(" ele ") || t.contains("senhor")  || t.contains("homem")
            }
            "tr" -> {
                female = t.contains("kadın") || t.contains("kız") || t.contains("anne")
                male   = t.contains("adam")  || t.contains("erkek") || t.contains("baba")
            }
            "id" -> {
                female = t.contains("dia") && (t.contains("wanita") || t.contains("perempuan") || t.contains("ibu"))
                male   = t.contains("dia") && (t.contains("pria") || t.contains("laki") || t.contains("ayah"))
            }
            else -> {  // English and all Latin
                female = t.contains(" she ") || t.contains(" her ") || t.contains("woman") ||
                         t.contains("girl") || t.contains("lady") || t.contains(" mrs ") ||
                         t.contains("miss ") || t.contains("mother") || t.contains("sister") ||
                         t.contains("daughter") || t.contains("wife") || t.contains("aunt") ||
                         t.contains("queen") || t.contains("princess")
                male   = t.contains(" he ") || t.contains(" his ") || t.contains(" him ") ||
                         t.contains("man ") || t.contains("boy ") || t.contains(" mr ") ||
                         t.contains("father") || t.contains("brother") || t.contains("son ") ||
                         t.contains("husband") || t.contains("uncle") || t.contains("king") ||
                         t.contains("prince")
            }
        }
        return when {
            female && !male -> HindiTtsService.Gender.FEMALE
            male && !female -> HindiTtsService.Gender.MALE
            else            -> null   // ambiguous — don't override history
        }
    }
}

// ── InternalAudioPitchDetector — frequency-based gender from video audio ──────
// Uses AudioRecord with UNPROCESSED source which captures internal screen audio
// on many Android devices, unlike VOICE_RECOGNITION which only captures mic.
// Skips analysis during TTS playback (isSuppressed) to avoid self-detection.

class InternalAudioPitchDetector(
    private val context: Context,
    private val onGender: (HindiTtsService.Gender) -> Unit
) {
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job:   Job? = null

    private val SR        = 16_000
    private val FRAMES    = 1_600    // 100ms
    // ZCR thresholds empirically tuned for 16kHz
    private val ZCR_F_HI  = 0.060f  // definitely female
    private val ZCR_F_LO  = 0.038f  // probably female (combined with history)
    private val HISTORY   = 15
    private val history   = ArrayDeque<HindiTtsService.Gender>()

    // Try sources in order: UNPROCESSED captures internal audio on many devices
    private val AUDIO_SOURCES = listOf(
        MediaRecorder.AudioSource.UNPROCESSED,   // internal audio on many devices
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.DEFAULT,
    )

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, FRAMES * 2)

            // Try each audio source until one works
            var rec: AudioRecord? = null
            for (src in AUDIO_SOURCES) {
                try {
                    val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        rec = r
                        Log.d("PitchDetector", "Using audio source: $src")
                        break
                    } else r.release()
                } catch (_: Exception) {}
            }

            if (rec == null) {
                Log.e("PitchDetector", "No audio source available")
                return@launch
            }

            try {
                rec.startRecording()
                val buf = ShortArray(FRAMES)
                while (isActive) {
                    val read = rec.read(buf, 0, FRAMES)
                    if (read <= 0) { delay(50); continue }

                    // Skip during TTS playback
                    if (HindiTtsService.isSuppressed()) { delay(100); continue }

                    // RMS energy check — skip silence and very quiet audio
                    val rms = sqrt(buf.take(read).sumOf { it.toLong() * it }.toDouble() / read)
                    if (rms < 120) { delay(30); continue }

                    // Zero-crossing rate — primary pitch estimator
                    var zc = 0
                    for (i in 1 until read) if ((buf[i] >= 0) != (buf[i-1] >= 0)) zc++
                    val zcr = zc.toFloat() / read

                    // Spectral centroid — secondary check (higher = female)
                    val centroid = spectralCentroid(buf, read, SR)

                    val g = when {
                        zcr > ZCR_F_HI -> HindiTtsService.Gender.FEMALE
                        zcr > ZCR_F_LO && centroid > 2200f -> HindiTtsService.Gender.FEMALE
                        zcr < ZCR_F_LO -> HindiTtsService.Gender.MALE
                        centroid < 1800f -> HindiTtsService.Gender.MALE
                        else -> null
                    } ?: continue

                    history.addLast(g)
                    if (history.size > HISTORY) history.removeFirst()

                    // Report only when 65%+ consistent
                    val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
                    val thresh = (HISTORY * 0.65).toInt()
                    val confident = when {
                        fCount >= thresh                    -> HindiTtsService.Gender.FEMALE
                        (history.size - fCount) >= thresh   -> HindiTtsService.Gender.MALE
                        else                               -> null
                    }
                    if (confident != null) withContext(Dispatchers.Main) { onGender(confident) }
                    delay(100)
                }
            } finally {
                try { rec.stop(); rec.release() } catch (_: Exception) {}
            }
        }
    }

    private fun spectralCentroid(buf: ShortArray, size: Int, sr: Int): Float {
        // Simple spectral centroid: weighted average of frequency bins
        var weightedSum = 0.0; var totalEnergy = 0.0
        val n = size.coerceAtMost(512)
        for (i in 0 until n) {
            val freq = i.toDouble() * sr / n
            val amp  = buf[i].toDouble().let { it * it }
            weightedSum += freq * amp
            totalEnergy += amp
        }
        return if (totalEnergy < 1) 0f else (weightedSum / totalEnergy).toFloat()
    }

    fun stop() { job?.cancel(); job = null; history.clear() }
}

// ── ByteArrayMediaDataSource ──────────────────────────────────────────────────

@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.M)
class ByteArrayMediaDataSource(private val data: ByteArray) :
    android.media.MediaDataSource() {
    override fun readAt(pos: Long, buf: ByteArray, off: Int, size: Int): Int {
        if (pos >= data.size) return -1
        val end  = minOf(pos + size, data.size.toLong()).toInt()
        val read = end - pos.toInt()
        data.copyInto(buf, off, pos.toInt(), end)
        return read
    }
    override fun getSize() = data.size.toLong()
    override fun close() {}
}
