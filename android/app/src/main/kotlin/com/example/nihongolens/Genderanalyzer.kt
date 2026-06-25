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
 * GenderAnalyzer v5 — dedicated USAGE_MEDIA internal audio capture + YIN pitch detection.
 *
 * ARCHITECTURE:
 *   Owns its own AudioRecord using AudioPlaybackCaptureConfiguration(USAGE_MEDIA).
 *   This captures ONLY the media player audio stream — no TTS, no mic, no notifications.
 *   Works identically with speakers or headphones because the capture happens inside
 *   Android's audio mixer, before audio reaches any output device.
 *
 * USAGE_MEDIA is captured. USAGE_ASSISTANT (our Hindi TTS) is NOT — excluded at OS level.
 *
 * STARTUP:
 *   Audio Capture mode: SpeechCaptureService calls start(sharedProjection)
 *   Live Captions mode: LiveCaptionReader calls start(MainActivity.lcProjection)
 *   If no projection yet: start() queues and retries when projection arrives via setProjection()
 *
 * GENDER SWITCH:
 *   HIST=1 — switches immediately on every single voiced frame.
 *   No majority voting lag. If a female voice frame is detected, voice switches to female instantly.
 */
object GenderAnalyzer {

    private const val TAG           = "GenderAnalyzer"
    private const val SR            = 16_000
    private const val WIN           = 2048        // 128ms window
    private const val TAU_MIN       = (SR / 300.0).toInt()   // 53 — max detectable F0 = 300Hz
    private const val TAU_MAX       = (SR / 60.0).toInt()    // 266 — min detectable F0 = 60Hz
    private const val YIN_THRESHOLD = 0.35f       // CMNDF threshold — lower = stricter
    private const val RMS_FLOOR     = 80f         // skip near-silence frames
    private const val HIST          = 1           // immediate switch on single frame

    @Volatile var enabled = false

    // YIN buffers (reused across frames)
    private val accum = ShortArray(WIN)
    private var accumFill = 0
    private val history = ArrayDeque<HindiTtsService.Gender>()

    // Dedicated capture resources
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job?         = null
    private var captureRec: AudioRecord? = null

    // Diagnostics
    private var frameCount   = 0
    private var analyzeCount = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start the dedicated media audio capture for gender detection.
     * projection: MediaProjection that authorizes AudioPlaybackCaptureConfiguration.
     * Call from SpeechCaptureService (Audio Capture mode) or LiveCaptionReader (LC mode).
     */
    fun start(projection: MediaProjection?) {
        stop()  // stop any previous capture cleanly
        if (projection == null) {
            CaptionLogger.log(TAG, "start() — no projection, gender detection unavailable")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            CaptionLogger.log(TAG, "start() — API < Q, AudioPlaybackCapture unavailable")
            return
        }
        enabled = true
        history.clear()
        accumFill = 0
        frameCount = 0; analyzeCount = 0
        captureJob = scope.launch { captureLoop(projection) }
        CaptionLogger.log(TAG, ">>> STARTED dedicated USAGE_MEDIA capture SR=$SR WIN=$WIN thresh=$YIN_THRESHOLD <<<")
    }

    fun stop() {
        enabled = false
        captureJob?.cancel(); captureJob = null
        try { captureRec?.stop()    } catch (_: Exception) {}
        try { captureRec?.release() } catch (_: Exception) {}
        captureRec = null
        history.clear()
        CaptionLogger.log(TAG, "stopped")
    }

    // ── Dedicated USAGE_MEDIA capture loop ────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLoop(projection: MediaProjection) = withContext(Dispatchers.IO) {
        // AudioPlaybackCaptureConfiguration captures ONLY the media player stream.
        // USAGE_ASSISTANT (Hindi TTS) is NOT listed → excluded at OS audio mixer level.
        // This capture is completely independent of SpeechCaptureService's AudioRecord.
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(WIN * 2)

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
            CaptionLogger.log(TAG, "AudioRecord.Builder failed: ${e.message}")
            return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            enabled = false
            rec.release()
            CaptionLogger.log(TAG, "AudioRecord not initialized — STATE=${rec.state}")
            return@withContext
        }

        captureRec = rec
        rec.startRecording()
        CaptionLogger.log(TAG, "capture started SR=$SR WIN=$WIN TAU_MIN=$TAU_MIN TAU_MAX=$TAU_MAX")

        val buf = ByteArray(WIN * 2)   // exactly WIN samples per read
        try {
            while (currentCoroutineContext().isActive && enabled) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    readCount++
                    if (readCount == 1) CaptionLogger.log(TAG, "FIRST read! $read bytes — audio flowing")
                    ingestPcm(buf, read)
                } else if (read < 0) {
                    CaptionLogger.log(TAG, "rec.read error=$read — stopping")
                    break
                }
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            captureRec = null
            CaptionLogger.log(TAG, "captureLoop ended")
        }
    }

    // ── PCM ingestion ─────────────────────────────────────────────────────────

    private fun ingestPcm(bytes: ByteArray, count: Int) {
        var i = 0
        while (i + 1 < count) {
            if (accumFill < WIN) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt() and 0xFF
                accum[accumFill++] = ((hi shl 8) or lo).toShort()
            }
            i += 2
            if (accumFill >= WIN) {
                analyze()
                accumFill = 0
            }
        }
    }

    /**
     * External PCM feed — called by SpeechCaptureService as a fallback.
     * Only used if captureLoop is not running (e.g. projection not yet granted).
     */
    fun feedPcm(bytes: ByteArray, count: Int) {
        if (!enabled || captureJob?.isActive == true) return   // own loop running, ignore
        ingestPcm(bytes, count)
    }

    // ── YIN pitch detection ───────────────────────────────────────────────────

    private var readCount = 0  // counts captureLoop rec.read() calls

    private fun analyze() {
        analyzeCount++

        // Log every 5 frames to confirm data is flowing
        if (analyzeCount % 5 == 0)
            CaptionLogger.log(TAG, "frame #$analyzeCount reads=$readCount enabled=$enabled")

        // RMS energy check — skip silence
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) {
            if (analyzeCount % 5 == 0)
                CaptionLogger.log(TAG, "SILENT rms=${rms.toInt()} floor=$RMS_FLOOR")
            return
        }

        // YIN step 1 — difference function d(tau)
        // Uses raw normalized PCM — no Hann window (Hann is for FFT, not YIN)
        val halfWin = WIN / 2
        val d = FloatArray(TAU_MAX + 1)
        for (tau in 1..TAU_MAX) {
            var sum = 0.0f
            for (j in 0 until halfWin) {
                val a = accum[j].toFloat() / 32768f
                val b = accum[j + tau].toFloat() / 32768f
                val diff = a - b
                sum += diff * diff
            }
            d[tau] = sum
        }

        // YIN step 2 — cumulative mean normalized difference (CMNDF)
        val cmndf = FloatArray(TAU_MAX + 1)
        cmndf[0] = 1f
        var runSum = 0f
        for (tau in 1..TAU_MAX) {
            runSum += d[tau]
            cmndf[tau] = if (runSum > 0f) d[tau] * tau / runSum else 1f
        }

        // YIN step 3 — find first dip below threshold
        var tau = TAU_MIN
        while (tau < TAU_MAX - 1) {
            if (cmndf[tau] < YIN_THRESHOLD) {
                // Step 4 — pick better neighbor (sub-sample precision)
                val best = if (tau + 1 < TAU_MAX && cmndf[tau + 1] < cmndf[tau]) tau + 1 else tau
                onPitchDetected(SR.toFloat() / best, rms)
                return
            }
            tau++
        }

        // No confident pitch — log diagnostics every 10 frames
        if (analyzeCount % 10 == 0) {
            var minVal = 1f; var minTau = TAU_MIN
            for (t in TAU_MIN until TAU_MAX) { if (cmndf[t] < minVal) { minVal = cmndf[t]; minTau = t } }
            CaptionLogger.log(TAG, "noPitch #$analyzeCount rms=${rms.toInt()} " +
                "minCMNDF=${String.format("%.3f", minVal)} " +
                "f0est=${(SR.toFloat() / minTau).toInt()}Hz thr=$YIN_THRESHOLD")
        }
    }

    // ── Gender classification ─────────────────────────────────────────────────

    private fun onPitchDetected(f0: Float, rms: Float) {
        frameCount++

        // Log every voiced frame (HIST=1 means every frame matters)
        CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz rms=${rms.toInt()} " +
            "→ ${if (f0 >= 165f) "FEMALE" else "MALE"} cur=${HindiTtsService.detectedGender}")

        val detected = if (f0 >= 165f) HindiTtsService.Gender.FEMALE
                       else             HindiTtsService.Gender.MALE

        // HIST=1: push to history, switch immediately on any single frame
        history.addLast(detected)
        if (history.size > HIST) history.removeFirst()

        if (detected != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = detected
            // Clear spoken token dedup — next sentence speaks in new voice immediately
            HindiTtsService.spokenTokens.clear()
            Log.d(TAG, "Gender→$detected F0=${f0.toInt()}Hz rms=${rms.toInt()}")
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $detected F0=${f0.toInt()}Hz <<<")
        }
    }
}
