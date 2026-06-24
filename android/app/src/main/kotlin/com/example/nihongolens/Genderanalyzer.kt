package com.example.nihongolens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.*

/**
 * GenderAnalyzer — detects speaker gender from internal media audio.
 *
 * Uses AudioPlaybackCaptureConfiguration with MediaProjection to capture
 * audio from media players, browsers, YouTube — the same audio stream
 * that Live Captions uses.
 *
 * Method: spectral centroid + F0 band energy ratio on 2048-sample FFT windows.
 *   Female speakers: F0 165-300Hz, spectral centroid > 1800Hz
 *   Male speakers:   F0  80-165Hz, spectral centroid < 1600Hz
 *
 * Smoothed over 8 windows. Updates HindiTtsService.detectedGender.
 * Pauses during TTS playback to avoid self-detection.
 */
object GenderAnalyzer {

    private const val TAG = "GenderAnalyzer"

    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val history  = ArrayDeque<HindiTtsService.Gender>()
    private const val HIST = 8

    fun start(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10+")
            return
        }
        job?.cancel()
        history.clear()
        job = scope.launch { captureLoop(projection) }
        Log.d(TAG, "GenderAnalyzer started")
    }

    fun stop() {
        job?.cancel(); job = null
        history.clear()
        Log.d(TAG, "GenderAnalyzer stopped")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLoop(projection: MediaProjection) {
        val SR      = 16_000
        val N       = 2048
        val minBuf  = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(N * 4)

        // AudioPlaybackCaptureConfiguration — captures USAGE_MEDIA and USAGE_GAME
        // This is exactly what Live Captions uses internally
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val rec = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(minBuf)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            rec.release(); return
        }

        Log.d(TAG, "AudioPlaybackCapture ready SR=$SR N=$N")
        rec.startRecording()

        val buf  = ShortArray(N)
        val re   = FloatArray(N)
        val im   = FloatArray(N)

        try {
            while (currentCoroutineContext().isActive) {
                // Skip while TTS is playing — avoid detecting our own voice
                if (HindiTtsService.isSuppressed()) {
                    delay(300); continue
                }
                // Skip if gender manually set
                if (HindiTtsService.selectedGender != HindiTtsService.Gender.AUTO) {
                    delay(1_000); continue
                }

                val read = rec.read(buf, 0, N)
                if (read < N) { delay(50); continue }

                // RMS — skip silence
                var energy = 0.0
                for (i in 0 until N) energy += buf[i].toLong() * buf[i]
                val rms = sqrt(energy / N)
                if (rms < 50) { delay(30); continue }

                // Hann window + copy to FFT arrays
                for (i in 0 until N) {
                    val w = 0.5f * (1f - cos(2.0 * PI * i / (N - 1)).toFloat())
                    re[i] = buf[i] * w / 32768f
                    im[i] = 0f
                }

                // In-place FFT
                fft(re, im, N)

                // Build magnitude spectrum
                val mag = FloatArray(N / 2) { b ->
                    sqrt(re[b] * re[b] + im[b] * im[b])
                }

                // Spectral centroid over speech range 200-4000 Hz
                val speechLo = (200f * N / SR).toInt()
                val speechHi = (4000f * N / SR).toInt().coerceAtMost(N / 2 - 1)
                var wSum = 0.0; var eSum = 0.0
                for (b in speechLo..speechHi) {
                    val freq = b.toDouble() * SR / N
                    wSum += freq * mag[b]
                    eSum += mag[b]
                }
                val centroid = if (eSum < 1e-4) 0f else (wSum / eSum).toFloat()

                // F0 band energy ratio (80-165 Hz male, 165-300 Hz female)
                val maleLo   = (80f  * N / SR).toInt()
                val maleHi   = (165f * N / SR).toInt()
                val femaleLo = (165f * N / SR).toInt()
                val femaleHi = (300f * N / SR).toInt()
                val maleE    = (maleLo..maleHi).sumOf   { mag[it].toDouble() }.toFloat()
                val femaleE  = (femaleLo..femaleHi).sumOf { mag[it].toDouble() }.toFloat()
                val totalE   = maleE + femaleE + 1e-6f
                val femaleRatio = femaleE / totalE

                // Combined decision
                val detected: HindiTtsService.Gender? = when {
                    centroid > 1900f && femaleRatio > 0.45f -> HindiTtsService.Gender.FEMALE
                    centroid > 1700f && femaleRatio > 0.55f -> HindiTtsService.Gender.FEMALE
                    centroid < 1500f && maleE > femaleE * 1.4f -> HindiTtsService.Gender.MALE
                    centroid < 1700f && maleE > femaleE * 2.0f -> HindiTtsService.Gender.MALE
                    else -> null  // ambiguous
                }

                detected?.let {
                    history.addLast(it)
                    if (history.size > HIST) history.removeFirst()

                    val fCount = history.count { g -> g == HindiTtsService.Gender.FEMALE }
                    // Require 65% majority before switching
                    val majority = when {
                        fCount >= (HIST * 0.65).toInt() -> HindiTtsService.Gender.FEMALE
                        (history.size - fCount) >= (HIST * 0.65).toInt() -> HindiTtsService.Gender.MALE
                        else -> null
                    }
                    majority?.let { m ->
                        if (m != HindiTtsService.detectedGender) {
                            HindiTtsService.detectedGender = m
                            Log.d(TAG, "Gender → $m (centroid=${centroid.toInt()} femaleRatio=${"%.2f".format(femaleRatio)} rms=${rms.toInt()})")
                        }
                    }
                }
                delay(100)
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            Log.d(TAG, "captureLoop ended")
        }
    }

    // Cooley-Tukey radix-2 FFT
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat(); val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var uRe = 1f; var uIm = 0f
                for (k in 0 until len / 2) {
                    val tRe = uRe * re[i+k+len/2] - uIm * im[i+k+len/2]
                    val tIm = uRe * im[i+k+len/2] + uIm * re[i+k+len/2]
                    re[i+k+len/2] = re[i+k] - tRe
                    im[i+k+len/2] = im[i+k] - tIm
                    re[i+k] += tRe; im[i+k] += tIm
                    val nRe = uRe * wRe - uIm * wIm
                    uIm = uRe * wIm + uIm * wRe; uRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
