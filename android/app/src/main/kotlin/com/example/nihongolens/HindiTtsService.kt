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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * HindiTtsService — Hindi TTS with real gender detection from device mic
 *
 * Gender: AudioRecord from VOICE_RECOGNITION captures mic audio.
 *   When video plays on speaker, mic picks it up.
 *   YIN pitch detection finds fundamental frequency (F0):
 *     F0 < 165 Hz  → male voice
 *     F0 >= 165 Hz → female voice
 *   Smoothed over 8 windows. Paused during TTS playback.
 *
 * TTS pipeline:
 *   speak() → fetchQueue (cap 4) → fetchWorker (Kokoro HTTP) → playQueue → playWorker
 *   Both workers run independently — fetch pre-buffers while play is active.
 *   playWav uses duration-exact wait — no extra silence between sentences.
 *   1 second gap between sentences (configurable).
 */
object HindiTtsService {
    private const val TAG     = "HindiTTS"
    private const val TTS_URL = "http://127.0.0.1:8766/tts"
    private const val GENDER_PAUSE_MS = 1_000L   // gap between sentences

    private const val SID_FEMALE = 31   // hf_alpha — Indian female
    private const val SID_MALE   = 33   // hm_omega — Indian male

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false
    @Volatile private var speakingUntilMs     = 0L

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cacheDir: java.io.File? = null

    data class FetchItem(val text: String, val sid: Int, val speed: Float)
    data class PlayItem (val wav: ByteArray, val durMs: Long)

    // Cap 4 — if more than 4 waiting, too far behind, drop oldest
    private val fetchQueue = LinkedBlockingQueue<FetchItem>(4)
    private val playQueue  = LinkedBlockingQueue<PlayItem>(4)

    private var lastSpokenNorm = ""
    private var fetchJob: Job? = null
    private var playJob:  Job? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    // ── Gender detector ───────────────────────────────────────────────────────
    private var pitchJob: Job? = null
    private val pitchHistory = ArrayDeque<Gender>()
    private val PITCH_HISTORY = 8

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        cacheDir = context.cacheDir
        startFetchWorker()
        startPlayWorker()
        // Start mic-based pitch detection — picks up speaker audio from device speaker
        startPitchDetector()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) { fetchQueue.clear(); playQueue.clear(); stopMp(); lastSpokenNorm = "" }
    }

    fun setGender(g: Gender) {
        selectedGender = g
        if (g != Gender.AUTO) { pitchJob?.cancel(); pitchJob = null; pitchHistory.clear() }
        else startPitchDetector()
    }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun destroy() {
        pitchJob?.cancel(); fetchJob?.cancel(); playJob?.cancel()
        fetchQueue.clear(); playQueue.clear(); stopMp(); scope.cancel()
    }

    // ── Pitch detection via mic (picks up speaker audio) ─────────────────────

    private fun startPitchDetector() {
        if (pitchJob?.isActive == true) return
        pitchJob = scope.launch {
            val SR = 16_000; val BUF = 4096
            val minBuf = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var rec: AudioRecord? = null
            try {
                // Try UNPROCESSED first (may capture internal audio on some devices)
                // then fall back to VOICE_RECOGNITION (mic)
                for (src in listOf(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT)) {
                    try {
                        val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, BUF * 2))
                        if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                        r.release()
                    } catch (_: Exception) {}
                }
                if (rec == null) return@launch
                rec.startRecording()
                val buf = ShortArray(BUF)
                while (isActive && selectedGender == Gender.AUTO) {
                    val read = rec.read(buf, 0, BUF)
                    if (read <= 0) { delay(50); continue }
                    if (isSuppressed()) { delay(100); continue }  // skip during TTS

                    // RMS — skip silence
                    val rms = sqrt(buf.take(read).sumOf { it.toLong() * it }.toDouble() / read)
                    if (rms < 100) { delay(30); continue }

                    // YIN-lite pitch estimation
                    val f0 = estimateF0(buf, read, SR)
                    if (f0 <= 0f) { delay(50); continue }

                    // Male: 85-165Hz, Female: 165-300Hz
                    val g = if (f0 < 165f) Gender.MALE else Gender.FEMALE

                    pitchHistory.addLast(g)
                    if (pitchHistory.size > PITCH_HISTORY) pitchHistory.removeFirst()

                    // Majority vote — require 62.5%+ consistency
                    val fCount = pitchHistory.count { it == Gender.FEMALE }
                    val thresh = (PITCH_HISTORY * 0.625).toInt()
                    val prev = detectedGender
                    detectedGender = if (fCount >= thresh) Gender.FEMALE
                                     else if ((pitchHistory.size - fCount) >= thresh) Gender.MALE
                                     else detectedGender
                    if (detectedGender != prev)
                        Log.d(TAG, "Gender → $detectedGender (F0=${f0.toInt()}Hz)")
                    delay(150)
                }
            } finally { try { rec?.stop(); rec?.release() } catch (_: Exception) {} }
        }
    }

    /** YIN-lite: find lag with minimum autocorrelation difference → fundamental period */
    private fun estimateF0(buf: ShortArray, size: Int, sr: Int): Float {
        val n   = minOf(size, 2048)
        val tau = IntRange(sr / 350, sr / 70)  // 70-350 Hz range
        var bestTau = -1; var bestVal = Float.MAX_VALUE
        for (t in tau) {
            if (t >= n) break
            var diff = 0.0
            for (i in 0 until n - t) {
                val d = buf[i].toDouble() - buf[i + t].toDouble()
                diff += d * d
            }
            val normalized = (diff / (n - t)).toFloat()
            if (normalized < bestVal) { bestVal = normalized; bestTau = t }
        }
        return if (bestTau > 0 && bestVal < 1e8f) sr.toFloat() / bestTau else 0f
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindiText: String) {
        if (!enabled || hindiText.isBlank()) return
        val n = hindiText.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n

        val emotion = detectEmotion(hindiText)
        val speed   = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val sid     = if ((if (selectedGender == Gender.AUTO) detectedGender else selectedGender)
                          == Gender.FEMALE) SID_FEMALE else SID_MALE

        // If queue full — drop oldest (stay current with subtitles)
        if (!fetchQueue.offer(FetchItem(n, sid, speed))) {
            fetchQueue.poll()
            fetchQueue.offer(FetchItem(n, sid, speed))
        }
        Log.d(TAG, "ENQ sid=$sid spd=$speed fq=${fetchQueue.size} '${n.take(30)}'")
    }

    // ── Workers ───────────────────────────────────────────────────────────────

    private fun startFetchWorker() {
        fetchJob = scope.launch {
            while (isActive) {
                val item = fetchQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (!enabled) continue
                try {
                    val wav = httpGetWav(item.text, item.sid, item.speed)
                    if (wav != null && wav.size > 44) {
                        val durMs = wavDuration(wav)
                        // If play queue full, drop oldest
                        if (!playQueue.offer(PlayItem(wav, durMs))) {
                            playQueue.poll()
                            playQueue.offer(PlayItem(wav, durMs))
                        }
                        Log.d(TAG, "WAV ${durMs}ms pq=${playQueue.size}")
                    } else Log.w(TAG, "Empty WAV '${item.text.take(25)}'")
                } catch (e: Exception) { Log.e(TAG, "Fetch: ${e.message}") }
            }
        }
    }

    private fun startPlayWorker() {
        playJob = scope.launch {
            while (isActive) {
                val item = playQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (!enabled) continue
                isSpeaking = true
                try { playWav(item.wav, item.durMs) }
                catch (e: Exception) { Log.e(TAG, "Play: ${e.message}") }
                finally {
                    isSpeaking = false
                    // 1 second gap between sentences
                    speakingUntilMs = System.currentTimeMillis() + GENDER_PAUSE_MS
                    delay(GENDER_PAUSE_MS)
                }
            }
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private suspend fun httpGetWav(text: String, sid: Int, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc = java.net.URLEncoder.encode(text, "UTF-8")
                conn = URL("$TTS_URL?text=$enc&sid=$sid&speed=$speed")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout    = 30_000
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } catch (_: Exception) { null }
            finally { try { conn?.disconnect() } catch (_: Exception) {} }
        }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun wavDuration(wav: ByteArray): Long {
        if (wav.size < 44) return 2000
        val sr    = readInt(wav, 24).coerceAtLeast(8000).toLong()
        val nCh   = readShort(wav, 22).coerceAtLeast(1).toLong()
        val bits  = readShort(wav, 34).coerceAtLeast(8).toLong()
        val pcmLen = (wav.size - 44).toLong().coerceAtLeast(0)
        return (pcmLen * 1000) / (sr * nCh * (bits / 8))
    }

    private suspend fun playWav(wavBytes: ByteArray, durMs: Long) {
        val latch = java.util.concurrent.CountDownLatch(1)
        withContext(Dispatchers.Main) {
            try {
                mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
                mediaPlayer = null

                val f = java.io.File(cacheDir ?: return@withContext,
                    "tts_${System.currentTimeMillis()}.wav")
                f.writeBytes(wavBytes)

                val mp = android.media.MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                mp.setVolume(1.0f, 1.0f)
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener {
                    try { it.release() } catch (_: Exception) {}
                    try { f.delete() } catch (_: Exception) {}
                    mediaPlayer = null; latch.countDown()
                }
                mp.setOnErrorListener { it, w, x ->
                    Log.e(TAG, "MP err $w/$x")
                    try { it.release() } catch (_: Exception) {}
                    try { f.delete() } catch (_: Exception) {}
                    mediaPlayer = null; latch.countDown(); true
                }
                mp.setOnPreparedListener { it.start(); mediaPlayer = it }
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "playWav: ${e.message}"); latch.countDown()
            }
        }
        // Wait exactly durMs + small buffer — then move to next sentence
        withContext(Dispatchers.IO) {
            latch.await(durMs + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun stopMp() {
        mainHandler.post {
            try { mediaPlayer?.stop()    } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    // ── Emotion ───────────────────────────────────────────────────────────────

    private fun detectEmotion(t: String): Emotion {
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","दर्द","sad","sorry","pain").any { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn").any            { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","amazing","awesome").any           { l.contains(it) }) return Emotion.EXCITED
        if (listOf("खुश","thanks","love","great").any             { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED -> 1.10f; Emotion.HAPPY -> 1.05f
        Emotion.SAD     -> 0.82f; Emotion.ANGRY -> 1.08f
        else            -> 1.00f
    }

    // ── WAV header helpers ─────────────────────────────────────────────────────

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl 8)  or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
}
