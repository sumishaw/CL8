package com.example.nihongolens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * GenderAnalyzer v8 — USAGE_MEDIA internal audio capture + YIN pitch detection.
 *
 * ONLY uses AudioPlaybackCaptureConfiguration(USAGE_MEDIA).
 * - Captures the media player digital audio stream directly from Android's audio mixer
 * - Works with speakers AND headphones (capture happens before output device)
 * - Zero mic usage — no ambient noise, no room acoustics
 * - Hindi TTS (USAGE_ASSISTANT) is excluded at the OS level — never analyzed
 *
 * Requires: MediaProjection from SpeechCaptureService.sharedProjection
 * The projection is obtained via the normal screen capture permission dialog
 * which SpeechCaptureService already handles.
 */
object GenderAnalyzer {

    private const val TAG          = "GenderAnalyzer"
    private const val SR           = 16_000
    private const val WIN          = 2048        // 128ms window
    private const val F0_FEMALE    = 165f        // Hz — above = female
    private const val YIN_THRESH   = 0.25f       // stricter threshold for clean internal audio
    private const val RMS_FLOOR    = 80f         // out of 32768 — skip silence
    private const val HIST         = 3           // 2/3 majority to switch

    @Volatile var enabled    = false
    @Volatile var lastStatus = "waiting for screen capture permission"
    @Volatile var lastBgSeq  = 0   // incremented each time BG audio is sent to tts_server

    private val history   = ArrayDeque<HindiTtsService.Gender>()
    private val accum     = ShortArray(WIN)
    private var accumFill = 0

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job?         = null
    private var captureRec: AudioRecord? = null

    private var frameCount   = 0
    private var analyzeCount = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start internal audio capture for gender detection.
     * Must be called with a valid MediaProjection from SpeechCaptureService.
     */
    fun start(projection: MediaProjection? = null, context: android.content.Context? = null) {
        if (enabled) {
            CaptionLogger.log(TAG, "already running — skipping start()")
            return
        }
        if (projection == null) {
            lastStatus = "no projection — grant screen capture permission"
            CaptionLogger.log(TAG, "start() called but projection=null — need screen capture permission")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastStatus = "API < Q — AudioPlaybackCapture not supported"
            CaptionLogger.log(TAG, "API < Q — cannot use AudioPlaybackCaptureConfiguration")
            return
        }
        stop()
        CaptionLogger.log(TAG, "start() — creating USAGE_MEDIA AudioPlaybackCaptureConfiguration")
        captureJob = scope.launch { captureLoop(projection) }
    }

    fun stop() {
        enabled = false
        captureJob?.cancel(); captureJob = null
        try { captureRec?.stop()    } catch (_: Exception) {}
        try { captureRec?.release() } catch (_: Exception) {}
        captureRec = null
        history.clear()
        accumFill = 0
        if (lastStatus != "waiting for screen capture permission")
            CaptionLogger.log(TAG, "stopped")
    }

    // ── USAGE_MEDIA capture loop ──────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLoop(projection: MediaProjection) = withContext(Dispatchers.IO) {
        // USAGE_MEDIA = video/music player stream ONLY
        // USAGE_ASSISTANT (Hindi TTS) is NOT listed → excluded at OS audio mixer level
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        } catch (e: Exception) {
            enabled = false
            lastStatus = "capture config failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioPlaybackCaptureConfiguration failed: ${e.message}")
            return@withContext
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(WIN * 4)

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            enabled = false
            lastStatus = "AudioRecord failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioRecord.Builder failed: ${e.message}")
            return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            enabled = false
            rec.release()
            lastStatus = "AudioRecord state=${rec.state} — not initialized"
            CaptionLogger.log(TAG, "AudioRecord not initialized state=${rec.state}")
            return@withContext
        }

        captureRec = rec
        enabled    = true
        frameCount = 0; analyzeCount = 0
        lastStatus = "capturing USAGE_MEDIA SR=${SR}Hz"
        rec.startRecording()
        CaptionLogger.log(TAG, ">>> INTERNAL AUDIO CAPTURE STARTED SR=${SR}Hz <<<")

        val buf = ByteArray(WIN * 2)
        var readCount = 0
        try {
            while (currentCoroutineContext().isActive && enabled) {
                val n = rec.read(buf, 0, buf.size)
                when {
                    n > 0 -> {
                        readCount++
                        if (readCount == 1)
                            CaptionLogger.log(TAG, "FIRST internal audio read: $n bytes — media audio flowing!")
                        ingest(buf, n)
                    }
                    n < 0 -> {
                        CaptionLogger.log(TAG, "read error=$n — stopping")
                        break
                    }
                }
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            captureRec = null
            enabled = false
            CaptionLogger.log(TAG, "captureLoop ended reads=$readCount")
        }
    }

    // ── PCM ingestion ─────────────────────────────────────────────────────────

    private fun ingest(bytes: ByteArray, count: Int) {
        var i = 0
        while (i + 1 < count) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            accum[accumFill++] = ((hi shl 8) or lo).toShort()
            i += 2
            if (accumFill >= WIN) { analyze(); accumFill = 0 }
        }
    }

    // ── YIN pitch detection ───────────────────────────────────────────────────

    private fun analyze() {
        analyzeCount++

        // RMS — skip silence
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) {
            if (analyzeCount % 30 == 0)
                CaptionLogger.log(TAG, "silent rms=${rms.toInt()} floor=$RMS_FLOOR analyzed=$analyzeCount")
            return
        }

        val tauMin = (SR / 300).coerceAtLeast(1)
        val tauMax = (SR / 60).coerceAtMost(WIN / 2 - 1)
        val half   = WIN / 2

        // YIN difference function — raw PCM, no windowing
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var s = 0f
            for (j in 0 until half) {
                val diff = accum[j].toFloat() / 32768f - accum[j + tau].toFloat() / 32768f
                s += diff * diff
            }
            d[tau] = s
        }

        // CMNDF
        val c = FloatArray(tauMax + 1); c[0] = 1f
        var rs = 0f
        for (tau in 1..tauMax) {
            rs += d[tau]
            c[tau] = if (rs > 0f) d[tau] * tau / rs else 1f
        }

        // First dip below threshold
        var tau = tauMin
        while (tau < tauMax - 1) {
            if (c[tau] < YIN_THRESH) {
                val best = if (tau + 1 < tauMax && c[tau + 1] < c[tau]) tau + 1 else tau
                onPitch(SR.toFloat() / best, rms)
                return
            }
            tau++
        }

        if (analyzeCount % 15 == 0) {
            var mv = 1f; var mt = tauMin
            for (t in tauMin until tauMax) if (c[t] < mv) { mv = c[t]; mt = t }
            CaptionLogger.log(TAG, "noPitch rms=${rms.toInt()} minCMNDF=${"%.3f".format(mv)} f0est=${SR/mt}Hz thr=$YIN_THRESH")
        }
    }

    private fun onPitch(f0: Float, rms: Float) {
        frameCount++
        val gender = if (f0 >= F0_FEMALE) HindiTtsService.Gender.FEMALE
                     else                  HindiTtsService.Gender.MALE

        if (frameCount % 5 == 0)
            CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz rms=${rms.toInt()} → $gender")

        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()

        val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
        val maj    = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
                     else                            HindiTtsService.Gender.MALE

        if (maj != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = maj
            HindiTtsService.spokenTokens.clear()
            lastStatus = "MEDIA audio → $maj (F0=${f0.toInt()}Hz)"
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $maj F0=${f0.toInt()}Hz <<<")
            Log.d(TAG, "Gender→$maj F0=${f0.toInt()} rms=${rms.toInt()}")
        }
    }
}
