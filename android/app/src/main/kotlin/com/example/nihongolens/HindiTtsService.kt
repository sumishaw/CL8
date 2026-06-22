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
import kotlin.math.sqrt

/**
 * HindiTtsService — speaks translated Hindi subtitles with emotion + gender
 *
 * Pipeline:
 *   speak(text) → emotion detect → enqueue
 *   Worker A: fetches WAV from sherpa-onnx server (parallel, non-blocking)
 *   Worker B: plays WAV sequentially via MediaPlayer
 *
 * Gender detection: text-based from LC source language cues (reliable, no mic needed)
 *   + optional ZCR pitch fallback
 *
 * Female voice: pitch=1.15 (soft Indian female), speed=0.90 (gentle pace)
 * Male voice:   pitch=1.00 (natural), speed=1.00
 */
object HindiTtsService {

    private const val TAG     = "HindiTTS"
    private const val TTS_URL = "http://127.0.0.1:8766/tts"

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled        = false
    @JvmField @Volatile var selectedGender = Gender.AUTO

    @Volatile var detectedGender  = Gender.MALE
    @Volatile var isSpeaking      = false
    @Volatile private var speakingUntilMs = 0L

    // TTS dedup — don't speak same sentence twice
    private var lastSpokenNorm = ""

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class TtsItem(val text: String, val speed: Float, val pitch: Float)
    data class WavItem(val wav: ByteArray, val pitch: Float)

    private val fetchQueue = java.util.concurrent.LinkedBlockingQueue<TtsItem>(3)
    private val playQueue  = java.util.concurrent.LinkedBlockingQueue<WavItem>(2)

    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────────

    fun init(context: Context) {
        startFetchWorker()
        startPlayWorker()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            fetchQueue.clear(); playQueue.clear()
            lastSpokenNorm = ""
            stopMediaPlayer()
        }
    }

    fun setGender(gender: Gender) { selectedGender = gender }

    fun speak(hindiText: String) {
        if (!enabled || hindiText.isBlank()) return

        // Dedup — skip if same as last spoken
        val n = hindiText.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n

        val emotion        = detectEmotion(hindiText)
        val (speed, pitch) = emotionAndGenderParams(emotion)
        // Drop oldest if full — stay current
        if (fetchQueue.size >= 3) fetchQueue.poll()
        fetchQueue.offer(TtsItem(hindiText, speed, pitch))
    }

    fun isSuppressed(): Boolean =
        isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun destroy() {
        fetchWorker?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopMediaPlayer(); scope.cancel()
    }

    // ── Stage 1: Fetch worker (generates WAV in background) ───────────────────
    // Runs independently of playback — pre-fetches next sentence while current plays

    private fun startFetchWorker() {
        fetchWorker = scope.launch {
            while (isActive) {
                val item = fetchQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (!enabled) continue
                try {
                    val wav = requestTts(item.text, 0, item.speed)
                    if (wav != null && wav.size > 44) {
                        // Wait if play queue is full (don't get too far ahead)
                        while (playQueue.size >= 2 && isActive) delay(200)
                        playQueue.offer(WavItem(wav, item.pitch))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fetch error: ${e.message}")
                }
            }
        }
    }

    // ── Stage 2: Play worker (plays WAV sequentially) ─────────────────────────

    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                val item = playQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (!enabled) continue
                try {
                    isSpeaking = true
                    playWav(item.wav, item.pitch)
                    speakingUntilMs = System.currentTimeMillis() + 1_500L
                } catch (e: Exception) {
                    Log.e(TAG, "Play error: ${e.message}")
                } finally {
                    isSpeaking = false
                }
            }
        }
    }

    // ── Emotion + gender params ───────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        val sad    = listOf("दुखी","उदास","दर्द","रोया","गम","sad","cry","pain","sorry","miss")
        val angry  = listOf("गुस्सा","क्रोध","नफरत","angry","hate","stupid","damn","liar")
        val happy  = listOf("खुश","धन्यवाद","प्यार","शुक्रिया","happy","love","thanks","great")
        val excite = listOf("वाह","कमाल","शानदार","wow","amazing","awesome","incredible")
        if (sad.any    { l.contains(it) }) return Emotion.SAD
        if (angry.any  { l.contains(it) }) return Emotion.ANGRY
        if (excite.any { l.contains(it) }) return Emotion.EXCITED
        if (happy.any  { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionAndGenderParams(emotion: Emotion): Pair<Float, Float> {
        val gender = if (selectedGender == Gender.AUTO) detectedGender else selectedGender

        val (baseSpeed, basePitch) = when (emotion) {
            Emotion.EXCITED -> Pair(1.10f, 1.05f)
            Emotion.HAPPY   -> Pair(1.05f, 1.03f)
            Emotion.CURIOUS -> Pair(0.97f, 1.01f)
            Emotion.SAD     -> Pair(0.82f, 0.96f)
            Emotion.ANGRY   -> Pair(1.08f, 1.00f)
            Emotion.NEUTRAL -> Pair(1.05f, 1.00f)
        }

        // Female voice: pitch=0.80 = distinctly lower/softer than male 1.0
        // This creates natural Indian women voice quality
        // speed=0.92 = slightly measured pace, characteristic of soft female speech
        // Male: natural at 1.0 pitch, slightly faster
        return when (gender) {
            Gender.FEMALE -> Pair(baseSpeed * 0.92f, basePitch * 0.80f)
            else          -> Pair(baseSpeed * 1.02f, basePitch * 1.00f)
        }
    }

    // ── HTTP request to sherpa-onnx server ────────────────────────────────────

    private suspend fun requestTts(text: String, sid: Int, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc    = java.net.URLEncoder.encode(text, "UTF-8")
                val url    = "$TTS_URL?text=$enc&speaker_id=$sid&speed=$speed"
                conn       = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout    = 25_000
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } catch (e: Exception) { null }
            finally { try { conn?.disconnect() } catch (_: Exception) {} }
        }

    // ── MediaPlayer playback ──────────────────────────────────────────────────

    private var mediaPlayer: android.media.MediaPlayer? = null

    private fun stopMediaPlayer() {
        mainHandler.post {
            try { mediaPlayer?.stop()    } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    private suspend fun playWav(wavBytes: ByteArray, pitch: Float) {
        val latch = java.util.concurrent.CountDownLatch(1)
        withContext(Dispatchers.Main) {
            try {
                mediaPlayer?.let { try { it.release() } catch (_: Exception) {} }
                mediaPlayer = null

                val mp = android.media.MediaPlayer()
                // USAGE_ASSISTANT: excluded from Live Captions capture
                // Live Captions only captures USAGE_MEDIA / USAGE_GAME
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                mp.setVolume(1.0f, 1.0f)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    mp.setDataSource(ByteArrayMediaDataSource(wavBytes))
                } else {
                    val tmp = java.io.File("/data/local/tmp/tts_hindi.wav")
                    tmp.parentFile?.mkdirs(); tmp.writeBytes(wavBytes)
                    mp.setDataSource(tmp.absolutePath)
                }

                mp.setOnCompletionListener {
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    latch.countDown()
                }
                mp.setOnErrorListener { it, w, x ->
                    Log.e(TAG, "MP error w=$w x=$x")
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null; latch.countDown(); true
                }
                mp.prepare()

                // Apply pitch (gender + emotion combined)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    mp.playbackParams = mp.playbackParams.setPitch(pitch)
                }

                mp.start()
                mediaPlayer = mp
                Log.d(TAG, "TTS pitch=$pitch gender=${if(selectedGender==Gender.AUTO) detectedGender else selectedGender} dur=${mp.duration}ms")
            } catch (e: Exception) {
                Log.e(TAG, "playWav: ${e.message}"); latch.countDown()
            }
        }
        withContext(Dispatchers.IO) { latch.await(30, java.util.concurrent.TimeUnit.SECONDS) }
    }

    fun stopCurrent() {
        fetchQueue.clear(); playQueue.clear()
        stopMediaPlayer(); isSpeaking = false
    }
}

// ── Gender detection from translated text ─────────────────────────────────────
// More reliable than mic ZCR for internal video audio.
// Called from LiveCaptionReader with the source language text to detect speaker gender.

object GenderDetector {
    private const val HISTORY = 8
    private val history = ArrayDeque<HindiTtsService.Gender>()

    // Detect gender from source language text patterns + pronouns
    fun detect(text: String, lang: String): HindiTtsService.Gender {
        val t = text.lowercase()

        val isFemale = when (lang) {
            "ja" -> t.any { c -> "わよね".contains(c) } ||
                    t.contains("私は") || t.contains("あたし") || t.contains("彼女")
            "zh" -> t.contains("她") || t.contains("姐") || t.contains("妈") || t.contains("女")
            "ko" -> t.contains("그녀") || t.contains("언니") || t.contains("여자")
            "ru" -> t.contains("она") || t.contains("её") || t.contains("сестра")
            "es" -> t.contains(" ella ") || t.contains("señora") || t.contains("mamá")
            "fr" -> t.contains(" elle ") || t.contains("madame") || t.contains("maman")
            "de" -> t.contains(" sie ") || t.contains("frau") || t.contains("mutter")
            "en", "latin_en" ->
                t.contains(" she ") || t.contains(" her ") || t.contains("woman") ||
                t.contains("girl") || t.contains("lady") || t.contains("mrs") ||
                t.contains("miss ") || t.contains("mother") || t.contains("sister")
            else -> false
        }

        val isMale = when (lang) {
            "ja" -> t.contains("俺") || t.contains("僕") || t.contains("彼は") || t.contains("彼氏")
            "zh" -> t.contains("他") || t.contains("哥") || t.contains("爸") || t.contains("男")
            "ko" -> t.contains("그는") || t.contains("오빠") || t.contains("남자")
            "ru" -> t.contains("он ") || t.contains("его") || t.contains("брат")
            "es" -> t.contains(" él ") || t.contains("señor") || t.contains("papá")
            "fr" -> t.contains(" il ") || t.contains("monsieur") || t.contains("papa")
            "de" -> t.contains(" er ") || t.contains(" herr") || t.contains("vater")
            "en", "latin_en" ->
                t.contains(" he ") || t.contains(" his ") || t.contains(" him ") ||
                t.contains("man ") || t.contains("boy ") || t.contains("mr ") ||
                t.contains("father") || t.contains("brother")
            else -> false
        }

        val detected = when {
            isFemale && !isMale -> HindiTtsService.Gender.FEMALE
            isMale && !isFemale -> HindiTtsService.Gender.MALE
            else -> null  // ambiguous — keep history
        }

        detected?.let {
            history.addLast(it)
            if (history.size > HISTORY) history.removeFirst()
        }

        val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
        return if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
               else HindiTtsService.Gender.MALE
    }
}

// ── ByteArrayMediaDataSource ──────────────────────────────────────────────────

@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.M)
class ByteArrayMediaDataSource(private val data: ByteArray) :
    android.media.MediaDataSource() {
    override fun readAt(pos: Long, buf: ByteArray, off: Int, size: Int): Int {
        if (pos >= data.size) return -1
        val end = minOf(pos + size, data.size.toLong()).toInt()
        val read = end - pos.toInt()
        data.copyInto(buf, off, pos.toInt(), end)
        return read
    }
    override fun getSize() = data.size.toLong()
    override fun close() {}
}
