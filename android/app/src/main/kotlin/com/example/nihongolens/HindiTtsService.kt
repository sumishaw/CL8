package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * HindiTtsService — Android TextToSpeech (hi-IN) + Background Music Mixing
 *
 * VOICE QUALITY:
 *   Uses Android's built-in Google TTS (hi-IN locale) which has:
 *   - Real Indian female voice (pitch=1.15f)
 *   - Real Indian male voice (pitch=0.88f)
 *   - No Piper server needed for voice generation
 *   - Near-zero synthesis latency (Google TTS is highly optimized)
 *
 * BACKGROUND MUSIC:
 *   BackgroundMusicRecorder captures USAGE_MEDIA at 44100Hz stereo
 *   Each 2s chunk is POSTed to server /bg?seq=N
 *   At speak() time: snapshot currentBgSeq
 *   Server mixes bg chunk #N under TTS speech at 28% volume
 *   Result: Hindi voice + original background music/sounds in ONE WAV
 *
 * EMOTION ADAPTATION:
 *   GenderAnalyzer sets currentEmotion on this object
 *   Each emotion → pitch + rate adjustment on Android TTS
 *   No Piper params needed — Android TTS handles it natively
 *
 * FIFO + SUBTITLE SYNC:
 *   speak() → fetchQueue → synthesizeToFile → playQueue → PLAY → showTtsText()
 *   Subtitle shown ONLY when audio starts (FIFO token-locked)
 */
object HindiTtsService {

    private const val TAG = "HindiTTS"

    // ── Emotion enum (26 values, used by GenderAnalyzer) ─────────────────────
    enum class Emotion {
        NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS,
        WARM, FEARFUL, SURPRISED, SIGHING,
        SINGING, GASPING, PANTING, MOANING,
        STRAINED, GRAVELLY, RASPY, HUSKY,
        WHISPERY, MURMURED, HUSHED, BREATHY,
        SULTRY, TENDER, VELVETY, DISGUST
    }

    enum class Gender { AUTO, MALE, FEMALE }

    // ── State ─────────────────────────────────────────────────────────────────
    @JvmField @Volatile var enabled           = true
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.6f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false
    @Volatile var currentEmotion: Emotion     = Emotion.NEUTRAL
    @Volatile private var speakingUntilMs     = 0L

    @JvmField val spokenTokens = ConcurrentHashMap<Int, Boolean>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null

    // ── Android TTS ───────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var cacheDir: File? = null
    private var am: AudioManager? = null

    // Selected voices: Voice II (female) and Voice IV (male) from Hindi (India) voices list
    // Discovered at init time by enumerating tts.voices filtered to hi-IN locale
    // User picked Voice II for female and Voice IV for male (from Settings screenshot)
    @Volatile private var voiceFemale: android.speech.tts.Voice? = null
    @Volatile private var voiceMale:   android.speech.tts.Voice? = null

    // Pending utterance callbacks: utteranceId → resumption function
    // Mutex: Android TTS synthesizeToFile is NOT thread-safe when called concurrently
    // on the same TextToSpeech object. Two workers calling it simultaneously causes:
    //   - Wrong pitch/voice applied (F2 sets voice=male while F1 is synthesizing female)  
    //   - CompletableDeferred callback mismatch (F2 completion triggers F1's deferred)
    //   - Silent failure: synthesizeToFile returns SUCCESS but produces empty WAV
    // Mutex ensures only ONE synthesis at a time. Workers can still fetch/decode in parallel.
    private val synthesizeMutex = Mutex()

    private val pendingUtterances = ConcurrentHashMap<String, () -> Unit>()

    // ── FIFO queues ───────────────────────────────────────────────────────────
    data class FetchItem(
        val text: String,
        val gender: String,
        val pitch: Float,
        val rate: Float,
        val srcText: String = "",
        val emotion: Emotion = Emotion.NEUTRAL,
        val bgSeq: Int = 0,
        val enqMs: Long = System.currentTimeMillis()
    )
    data class PlayItem(val text: String, val wavFile: File, val durMs: Long)

    private val fetchQueue = LinkedBlockingQueue<FetchItem>()
    private val playQueue  = LinkedBlockingQueue<PlayItem>()

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        cacheDir = context.cacheDir
        am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Prevent LC from capturing TTS audio
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            am?.allowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_NONE
        }

        // Initialize Android TTS — discover hi-IN voices and select Voice II (female) / Voice IV (male)
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Enumerate all available hi-IN voices, sorted alphabetically (matches Settings order)
                val hiVoices = tts?.voices
                    ?.filter { v ->
                        (v.locale.language == "hi" || v.locale.toLanguageTag().startsWith("hi")) &&
                        !v.isNetworkConnectionRequired
                    }
                    ?.sortedBy { it.name }
                    ?: emptyList()

                CaptionLogger.log(TAG, "hi-IN voices found: ${hiVoices.size}")
                hiVoices.forEachIndexed { i, v ->
                    CaptionLogger.log(TAG, "  Voice ${i+1}: ${v.name} quality=${v.quality}")
                }

                // User confirmed: Voice II = female (index 1), Voice IV = male (index 3)
                // Indices are 0-based in the sorted list
                voiceFemale = hiVoices.getOrNull(1)  // Voice II
                voiceMale   = hiVoices.getOrNull(3)  // Voice IV

                // Fallback: if fewer than 4 voices, use what's available
                if (voiceFemale == null && hiVoices.isNotEmpty())
                    voiceFemale = hiVoices.getOrNull(0)
                if (voiceMale == null && hiVoices.size >= 2)
                    voiceMale = hiVoices.getOrNull(1)
                    ?: voiceFemale  // last resort: same voice, different pitch

                CaptionLogger.log(TAG, "FEMALE voice: ${voiceFemale?.name ?: "not found"}")
                CaptionLogger.log(TAG, "MALE voice:   ${voiceMale?.name ?: "not found"}")

                // Set default locale as fallback
                val result = tts?.setLanguage(Locale("hi", "IN"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("hi"))
                    CaptionLogger.log(TAG, "TTS: hi-IN not found, using generic hi")
                }

                // Set audio attributes to USAGE_ASSISTANT (excluded from LC capture)
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())

                // Utterance listener for async synthesis completion
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id -> pendingUtterances.remove(id)?.invoke() }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { id -> pendingUtterances.remove(id)?.invoke() }
                    }
                })

                ttsReady = true
            } else {
                CaptionLogger.log(TAG, "TTS init failed: $status")
            }
        }

        startFetchWorker()
        startPlayWorker()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) { fetchQueue.clear(); playQueue.clear(); stopAudio() }
    }
    fun setGender(g: Gender)         { selectedGender = g }
    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }
    fun isSuppressed()               = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun stopAndClear() {
        fetchQueue.clear(); playQueue.clear()
        tts?.stop()
        stopAudio()
        isSpeaking = false
        speakingUntilMs = System.currentTimeMillis() + 300L
        spokenTokens.clear()
    }

    fun destroy() {
        fetchWorker?.cancel(); fetchWorker2?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopAudio()
        tts?.stop(); tts?.shutdown(); tts = null
        scope.cancel()
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String, srcText: String = "") {
        if (!enabled || hindi.isBlank() || !ttsReady) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")

        val isFemale = when (selectedGender) {
            Gender.FEMALE -> true
            Gender.MALE   -> false
            Gender.AUTO   -> (detectedGender == Gender.FEMALE)
        }

        // FIX BUG 1: Include gender in dedup token.
        // Previously token = n.hashCode() — when gender switched on same sentence,
        // putIfAbsent found existing token → skipped → gender never applied.
        // Now token includes gender so re-play occurs on switch.
        val genderBit = if (isFemale) 0x80000000.toInt() else 0
        val token = n.hashCode() xor genderBit
        if (spokenTokens.putIfAbsent(token, true) != null) return
        if (spokenTokens.size > 300) spokenTokens.clear()
        val genderTag = if (isFemale) "female" else "male"

        // Emotion → pitch/rate adjustments
        val (pitchMult, rateMult) = emotionPitchRate(currentEmotion)
        // When specific voices (Voice II/IV) are available, they handle gender identity.
        // BasePitch = 1.0 (neutral) so emotion pitch applies cleanly on top.
        // If no specific voice found (voiceFemale/voiceMale null), use pitch to approximate gender.
        val hasSpecificVoice = if (isFemale) voiceFemale != null else voiceMale != null
        val basePitch = if (hasSpecificVoice) 1.0f
                        else if (isFemale) 1.15f else 0.88f
        val finalPitch = (basePitch * pitchMult).coerceIn(0.5f, 2.0f)
        val finalRate  = (ttsSpeedMultiplier * rateMult).coerceIn(0.5f, 3.0f)

        // Snapshot background music sequence at enqueue time for timing alignment
        val bgSeq = BackgroundMusicRecorder.currentSeq.get()

        CaptionLogger.log(TAG, "SPEAK emo=$currentEmotion spd=${String.format("%.2f", finalRate)} " +
            "pitch=${String.format("%.2f", finalPitch)} $genderTag bg=$bgSeq '${n.take(50)}'")

        fetchQueue.offer(FetchItem(n, genderTag, finalPitch, finalRate, srcText,
            currentEmotion, bgSeq, System.currentTimeMillis()))
    }

    // ── Fetch worker: Android TTS → WAV file ──────────────────────────────────

    private var fetchWorker2: Job? = null   // second fetch worker for parallel pre-synthesis

    private fun startFetchWorker() {
        // TWO parallel fetch workers: while F1 synthesizes sentence N,
        // F2 starts synthesizing sentence N+1 → near-zero gap between sentences.
        fetchWorker  = scope.launch { fetchLoop("F1") }
        fetchWorker2 = scope.launch { fetchLoop("F2") }
    }

    private suspend fun fetchLoop(workerName: String) {
        while (currentCoroutineContext().isActive) {
            val item = fetchQueue.take()
            if (!enabled || !ttsReady) continue

            val ageMs = System.currentTimeMillis() - item.enqMs
            if (ageMs > 10_000L) {
                CaptionLogger.log(TAG, "TTS-SKIP stale ${ageMs/1000}s '${item.text.take(30)}'")
                continue
            }
            if (fetchQueue.size > 2) {
                CaptionLogger.log(TAG, "TTS-SKIP overloaded q=${fetchQueue.size+1}")
                continue
            }

            val t0 = System.currentTimeMillis()
            val textToSpeak = if (item.gender == "female") toFeminineHindi(item.text) else item.text
            if (textToSpeak != item.text)
                CaptionLogger.log(TAG, "FEM-CONV '${item.text.take(30)}' → '${textToSpeak.take(30)}'")

            val spokenItem = item.copy(text = textToSpeak)
            val wavFile = synthesizeToFile(spokenItem) ?: continue
            val ms = System.currentTimeMillis() - t0

            val fileSize = wavFile.length()
            val sr = 22050L
            val durMs = if (fileSize > 44) ((fileSize - 44) * 1000L) / (sr * 2L) else 2000L
            val mixedFile = mixBgMusic(wavFile, item.bgSeq) ?: wavFile

            CaptionLogger.log(TAG, "TTS-WAV[$workerName] ${ms}ms ${durMs}ms bg=${item.bgSeq} '${textToSpeak.take(40)}'")
            playQueue.offer(PlayItem(textToSpeak, mixedFile, durMs))
        }
    }

    // BG_MIX_VOLUME: bg music at 28% — audible ambience without drowning speech
    private val BG_MIX_VOLUME = 0.28f

    private fun mixBgMusic(ttsWav: File, bgSeq: Int): File? {
        if (bgSeq <= 0) return null
        val bgPcm = BackgroundMusicRecorder.getChunk(bgSeq) ?: return null
        try {
            val ttsBytes = ttsWav.readBytes()
            if (ttsBytes.size <= 44) return null

            val sr     = readInt(ttsBytes, 24)
            val nch    = readShort(ttsBytes, 22)
            val bit    = readShort(ttsBytes, 34)
            val ttsPcm = ttsBytes.copyOfRange(44, ttsBytes.size)

            // TTS: mono 22050Hz int16
            val ttsShorts = ShortArray(ttsPcm.size / 2) { i ->
                ((ttsPcm[i*2+1].toInt() shl 8) or (ttsPcm[i*2].toInt() and 0xFF)).toShort()
            }

            // BG: stereo 44100Hz int16 → mono 22050Hz (downsample 2:1 by averaging)
            val bgShorts = ShortArray(bgPcm.size / 2) { i ->
                ((bgPcm[i*2+1].toInt() shl 8) or (bgPcm[i*2].toInt() and 0xFF)).toShort()
            }
            // Stereo channels → mono (every 2 shorts = L+R → average)
            val bgMono44k = ShortArray(bgShorts.size / 2) { i ->
                ((bgShorts[i*2].toInt() + bgShorts[i*2+1].toInt()) / 2).toShort()
            }
            // Downsample 44100 → 22050 (every 2 samples → 1 by averaging)
            val bgMono22k = ShortArray(bgMono44k.size / 2) { i ->
                ((bgMono44k[i*2].toInt() + bgMono44k[i*2+1].toInt()) / 2).toShort()
            }

            // Normalize BG energy to ~20% of TTS energy
            val ttsRms = ttsShorts.map { it.toLong() * it }.average().let { Math.sqrt(it) }
            val bgRms  = bgMono22k.map { it.toLong() * it }.average().let { Math.sqrt(it) }
            val normFactor = if (bgRms > 10) (ttsRms / bgRms * BG_MIX_VOLUME).toFloat() else 0f

            // Mix and clip
            val n = minOf(ttsShorts.size, bgMono22k.size)
            val mixed = ByteArray(ttsShorts.size * 2)
            for (i in ttsShorts.indices) {
                val ttsS = ttsShorts[i].toInt()
                val bgS  = if (i < bgMono22k.size) (bgMono22k[i] * normFactor).toInt() else 0
                val out  = (ttsS + bgS).coerceIn(-32767, 32767).toShort()
                mixed[i*2]   = (out.toInt() and 0xFF).toByte()
                mixed[i*2+1] = (out.toInt() shr 8).toByte()
            }

            // Write mixed WAV
            val outFile = File(cacheDir, "tts_mixed_${bgSeq}.wav")
            writeWav(outFile, mixed, sr, nch, bit)
            ttsWav.delete()

            CaptionLogger.log(TAG, "BG-MIX seq=$bgSeq norm=${String.format("%.2f", normFactor)} merged")
            return outFile
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "BG-MIX failed: ${e.message}")
            return null
        }
    }

    private fun writeWav(file: File, pcm: ByteArray, sr: Int, nch: Int, bit: Int) {
        file.outputStream().use { out ->
            val dataLen = pcm.size
            val header = ByteArray(44)
            fun putInt(buf: ByteArray, off: Int, v: Int) {
                buf[off]   = (v and 0xFF).toByte(); buf[off+1] = (v shr 8 and 0xFF).toByte()
                buf[off+2] = (v shr 16 and 0xFF).toByte(); buf[off+3] = (v shr 24 and 0xFF).toByte()
            }
            fun putShort(buf: ByteArray, off: Int, v: Int) {
                buf[off] = (v and 0xFF).toByte(); buf[off+1] = (v shr 8 and 0xFF).toByte()
            }
            header[0]=82;header[1]=73;header[2]=70;header[3]=70  // RIFF
            putInt(header, 4, 36 + dataLen)
            header[8]=87;header[9]=65;header[10]=86;header[11]=69  // WAVE
            header[12]=102;header[13]=109;header[14]=116;header[15]=32  // fmt
            putInt(header, 16, 16); putShort(header, 20, 1)
            putShort(header, 22, nch); putInt(header, 24, sr)
            putInt(header, 28, sr * nch * bit / 8)
            putShort(header, 32, nch * bit / 8); putShort(header, 34, bit)
            header[36]=100;header[37]=97;header[38]=116;header[39]=97  // data
            putInt(header, 40, dataLen)
            out.write(header); out.write(pcm)
        }
    }
    // ── Android TTS synthesize to file (async with coroutine bridge) ──────────

    private suspend fun synthesizeToFile(item: FetchItem): File? =
        synthesizeMutex.withLock {
            withContext(Dispatchers.IO) {
            val localTts = tts ?: return@withContext null
            val outFile = File(cacheDir, "tts_${UUID.randomUUID()}.wav")

            // GENDER SWITCH FIX: Set the actual voice object (Voice II or Voice IV)
            // Previously only used setPitch() which doesn't change the voice identity —
            // it was always the same default voice with a pitch shift = still sounds male.
            // Now: voiceFemale = Voice II, voiceMale = Voice IV (user-confirmed mapping)
            val isFemale = item.gender == "female"
            val targetVoice = if (isFemale) voiceFemale else voiceMale
            if (targetVoice != null) {
                localTts.voice = targetVoice
                CaptionLogger.log(TAG, "TTS voice=${targetVoice.name} gender=${item.gender}")
            } else {
                // Fallback: use pitch to approximate gender difference
                CaptionLogger.log(TAG, "TTS no specific voice found, using pitch=${item.pitch}")
            }

            // Set emotion-based pitch and rate ON TOP of voice identity
            // Voice identity = who speaks; pitch/rate = how they feel
            localTts.setSpeechRate(item.rate)
            localTts.setPitch(item.pitch)

            // Bridge Android TTS callback to coroutine
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Unit>()

            pendingUtterances[id] = { deferred.complete(Unit) }

            try {
                val result = localTts.synthesizeToFile(
                    item.text,
                    null,
                    outFile,
                    id
                )

                if (result != TextToSpeech.SUCCESS) {
                    pendingUtterances.remove(id)
                    CaptionLogger.log(TAG, "TTS synthesizeToFile failed result=$result")
                    return@withContext null
                }

                // Wait for synthesis to complete (max 8s)
                withTimeoutOrNull(8_000L) { deferred.await() }
                    ?: run {
                        pendingUtterances.remove(id)
                        CaptionLogger.log(TAG, "TTS-TIMEOUT 8s")
                        return@withContext null
                    }

                if (!outFile.exists() || outFile.length() < 100) {
                    CaptionLogger.log(TAG, "TTS output file empty")
                    return@withContext null
                }

                outFile
            } catch (e: Exception) {
                pendingUtterances.remove(id)
                CaptionLogger.log(TAG, "TTS-EXC ${e.message}")
                null
            }
        } } // end withContext + synthesizeMutex.withLock

    // ── Play worker: WAV → AudioTrack ─────────────────────────────────────────

    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                val item = playQueue.take()
                if (!enabled) { item.wavFile.delete(); continue }
                try {
                    isSpeaking = true
                    CaptionLogger.log(TAG, "PLAY ${item.durMs}ms '${item.text.take(40)}'")
                    withContext(Dispatchers.Main) {
                        OverlayService.showTtsText(item.text)
                    }
                    playWavFile(item.wavFile)
                    withContext(Dispatchers.Main) {
                        OverlayService.clearTtsText()
                    }
                    speakingUntilMs = System.currentTimeMillis() + 200L
                } catch (e: Exception) {
                    CaptionLogger.log(TAG, "PLAY-ERR ${e.message}")
                } finally {
                    isSpeaking = false
                    try { item.wavFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    // ── AudioTrack playback ───────────────────────────────────────────────────

    private var audioTrack: AudioTrack? = null

    private fun stopAudio() {
        try { audioTrack?.stop() }  catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private suspend fun playWavFile(file: File) = withContext(Dispatchers.IO) {
        try {
            val bytes = file.readBytes()
            if (bytes.size <= 44) return@withContext

            val sr   = readInt(bytes, 24).coerceAtLeast(8_000)
            val nch  = readShort(bytes, 22)
            val bit  = readShort(bytes, 34)
            val pcm  = bytes.copyOfRange(44, bytes.size)
            val fmt  = if (bit == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
            val chan = if (nch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val dur  = ((pcm.size.toLong() * 1000L) / (sr.toLong() * nch * (bit / 8))).coerceAtLeast(100L)

            val minBuf = AudioTrack.getMinBufferSize(sr, chan, fmt).coerceAtLeast(pcm.size)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
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
            delay(dur + 150L)
            track.stop(); track.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "playWavFile: ${e.message}")
        }
    }

    // ── Emotion → pitch + rate multipliers ───────────────────────────────────
    // FIX BUG 3: Previous values were too subtle (e.g. HAPPY: pitch 1.05 → barely noticeable)
    // Now each emotion has strong, clearly distinguishable vocal character:
    //   pitch: applied ON TOP of base (female=1.15, male=0.88)
    //   rate: multiplied with ttsSpeedMultiplier (default 1.6)
    // Range: pitch 0.70–1.40 (wide), rate 0.70–1.40 (wide)
    private fun emotionPitchRate(e: Emotion): Pair<Float, Float> = when (e) {
        // ── Core emotions (very distinct) ────────────────────────────────────
        Emotion.NEUTRAL    -> Pair(1.00f, 1.00f)   // baseline
        Emotion.HAPPY      -> Pair(1.25f, 1.20f)   // high pitched, fast, upbeat
        Emotion.SAD        -> Pair(0.75f, 0.70f)   // low, slow, heavy
        Emotion.ANGRY      -> Pair(0.85f, 1.30f)   // forceful, clipped, fast
        Emotion.EXCITED    -> Pair(1.35f, 1.35f)   // very high, very fast
        Emotion.CURIOUS    -> Pair(1.15f, 0.90f)   // slightly rising, thoughtful
        // ── Textured emotions ────────────────────────────────────────────────
        Emotion.WARM       -> Pair(0.95f, 0.85f)   // gentle, warm, slower
        Emotion.FEARFUL    -> Pair(1.30f, 1.25f)   // high, fast, tense
        Emotion.SURPRISED  -> Pair(1.40f, 1.15f)   // very high pitch spike
        Emotion.SIGHING    -> Pair(0.80f, 0.72f)   // low, very slow, breathy
        Emotion.SINGING    -> Pair(1.10f, 0.75f)   // melodic, slow
        Emotion.GASPING    -> Pair(1.35f, 1.40f)   // high, very fast
        Emotion.PANTING    -> Pair(1.20f, 1.38f)   // high, rapid
        Emotion.MOANING    -> Pair(0.72f, 0.65f)   // very low, very slow
        Emotion.STRAINED   -> Pair(0.90f, 0.85f)   // strained effort
        Emotion.GRAVELLY   -> Pair(0.70f, 0.90f)   // very low, rough
        Emotion.RASPY      -> Pair(0.75f, 0.92f)   // low, slightly raspy
        Emotion.HUSKY      -> Pair(0.80f, 0.88f)   // husky, low
        Emotion.WHISPERY   -> Pair(0.90f, 0.78f)   // quiet, slow
        Emotion.MURMURED   -> Pair(0.85f, 0.75f)   // very quiet, slow
        Emotion.HUSHED     -> Pair(0.88f, 0.73f)   // hushed whisper
        Emotion.BREATHY    -> Pair(0.92f, 0.82f)   // breathy, slow
        Emotion.SULTRY     -> Pair(0.78f, 0.70f)   // very low, very slow
        Emotion.TENDER     -> Pair(0.95f, 0.80f)   // gentle, soft
        Emotion.VELVETY    -> Pair(0.85f, 0.78f)   // smooth, low, slow
        Emotion.DISGUST    -> Pair(0.88f, 1.10f)   // low, sharp, clipped
    }

    // ── WAV helpers ───────────────────────────────────────────────────────────

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl  8) or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    // ── Pronoun hint (verb forms only) ────────────────────────────────────────

    fun hasFemininePronouns(src: String): Boolean {
        if (src.isBlank()) return false
        val t = " ${src.lowercase()} "
        return t.contains(" she ") || t.contains(" her ") ||
               t.contains(" herself ") || t.contains(" she's ") ||
               t.contains(" she'd ") || t.contains(" she'll ")
    }

    fun toFeminineHindi(text: String): String {
        var t = text

        // ── Pronouns ─────────────────────────────────────────────────────────
        t = t.replace("वह", "वह")          // stays same but triggers fem verbs below
        t = t.replace("मैं", "मैं")         // stays same

        // ── First person present ──────────────────────────────────────────────
        t = t.replace("ता हूँ", "ती हूँ")
        t = t.replace("ता हूं", "ती हूं")
        t = t.replace("रहा हूँ", "रही हूँ")
        t = t.replace("रहा हूं", "रही हूं")
        t = t.replace("सकता हूँ", "सकती हूँ")
        t = t.replace("सकता हूं", "सकती हूं")
        t = t.replace("चाहता हूँ", "चाहती हूँ")
        t = t.replace("चाहता हूं", "चाहती हूं")
        t = t.replace("जाता हूँ", "जाती हूँ")
        t = t.replace("जाता हूं", "जाती हूं")
        t = t.replace("खाता हूँ", "खाती हूँ")
        t = t.replace("खाता हूं", "खाती हूं")
        t = t.replace("आता हूँ", "आती हूँ")
        t = t.replace("आता हूं", "आती हूं")
        t = t.replace("देता हूँ", "देती हूँ")
        t = t.replace("देता हूं", "देती हूं")
        t = t.replace("लेता हूँ", "लेती हूँ")
        t = t.replace("लेता हूं", "लेती हूं")
        t = t.replace("करता हूँ", "करती हूँ")
        t = t.replace("करता हूं", "करती हूं")
        t = t.replace("सोचता हूँ", "सोचती हूँ")
        t = t.replace("सोचता हूं", "सोचती हूं")
        t = t.replace("लगता हूँ", "लगती हूँ")
        t = t.replace("लगता हूं", "लगती हूं")

        // ── Third person present singular ─────────────────────────────────────
        t = t.replace("ता है", "ती है")
        t = t.replace("ते हैं", "ती हैं")
        t = t.replace("रहा है", "रही है")
        t = t.replace("रहे हैं", "रही हैं")
        t = t.replace("सकता है", "सकती है")
        t = t.replace("सकते हैं", "सकती हैं")
        t = t.replace("चाहता है", "चाहती है")
        t = t.replace("चाहते हैं", "चाहती हैं")
        t = t.replace("होता है", "होती है")
        t = t.replace("होते हैं", "होती हैं")
        t = t.replace("लगता है", "लगती है")
        t = t.replace("लगते हैं", "लगती हैं")
        t = t.replace("जाता है", "जाती है")
        t = t.replace("जाते हैं", "जाती हैं")
        t = t.replace("आता है", "आती है")
        t = t.replace("आते हैं", "आती हैं")
        t = t.replace("करता है", "करती है")
        t = t.replace("करते हैं", "करती हैं")
        t = t.replace("मिलता है", "मिलती है")
        t = t.replace("देता है", "देती है")
        t = t.replace("लेता है", "लेती है")
        t = t.replace("सोचता है", "सोचती है")
        t = t.replace("बोलता है", "बोलती है")
        t = t.replace("समझता है", "समझती है")
        t = t.replace("रखता है", "रखती है")

        // ── Past tense (था → थी) ──────────────────────────────────────────────
        t = t.replace("था", "थी")           // USER REQUESTED: था → थी
        t = t.replace("थे", "थीं")
        t = t.replace("ता था", "ती थी")
        t = t.replace("रहा था", "रही थी")
        t = t.replace("सकता था", "सकती थी")
        t = t.replace("चाहता था", "चाहती थी")
        t = t.replace("होता था", "होती थी")
        t = t.replace("जाता था", "जाती थी")
        t = t.replace("आता था", "आती थी")
        t = t.replace("करता था", "करती थी")
        t = t.replace("लगता था", "लगती थी")

        // ── Past participle ────────────────────────────────────────────────────
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
        t = t.replace("लाया", "लाई")
        t = t.replace("सिखाया", "सिखाई")
        t = t.replace("समझाया", "समझाई")
        t = t.replace("भेजा", "भेजी")
        t = t.replace("छोड़ा", "छोड़ी")
        t = t.replace("छोड़े", "छोड़ीं")
        t = t.replace("पकड़ा", "पकड़ी")
        t = t.replace("पढ़ा", "पढ़ी")
        t = t.replace("देखा", "देखी")
        t = t.replace("मारा", "मारी")
        t = t.replace("बुलाया", "बुलाई")
        t = t.replace("खाया", "खाई")
        t = t.replace("पिया", "पी")
        t = t.replace("पहना", "पहनी")
        t = t.replace("ओढ़ा", "ओढ़ी")
        t = t.replace("उठाया", "उठाई")
        t = t.replace("डाला", "डाली")
        t = t.replace("निकाला", "निकाली")
        t = t.replace("बचाया", "बचाई")
        t = t.replace("बुझाया", "बुझाई")

        // ── ि → ी suffix pattern (core feminine marker) ───────────────────────
        // Masculine adjective/verb suffix 'ा' (aa) → 'ी' (ii) for feminine
        // Applied selectively to avoid over-replacement
        t = t.replace("अच्छा", "अच्छी")
        t = t.replace("बुरा", "बुरी")
        t = t.replace("बड़ा", "बड़ी")
        t = t.replace("छोटा", "छोटी")
        t = t.replace("नया", "नई")
        t = t.replace("पुराना", "पुरानी")
        t = t.replace("सुंदर", "सुंदर")   // invariant
        t = t.replace("काला", "काली")
        t = t.replace("लाल", "लाल")        // invariant
        t = t.replace("पीला", "पीली")
        t = t.replace("हरा", "हरी")
        t = t.replace("नीला", "नीली")
        t = t.replace("सफेद", "सफेद")      // invariant
        t = t.replace("मोटा", "मोटी")
        t = t.replace("पतला", "पतली")
        t = t.replace("लंबा", "लंबी")
        t = t.replace("ऊँचा", "ऊँची")
        t = t.replace("नीचा", "नीची")
        t = t.replace("गरम", "गरम")        // invariant
        t = t.replace("ठंडा", "ठंडी")
        t = t.replace("भारी", "भारी")       // already fem
        t = t.replace("हल्का", "हल्की")
        t = t.replace("मेरा", "मेरी")
        t = t.replace("तेरा", "तेरी")
        t = t.replace("हमारा", "हमारी")
        t = t.replace("तुम्हारा", "तुम्हारी")
        t = t.replace("उसका", "उसकी")
        t = t.replace("इसका", "इसकी")
        t = t.replace("उनका", "उनकी")
        t = t.replace("इनका", "इनकी")
        t = t.replace("आपका", "आपकी")

        // ── Adverbs with gender agreement ─────────────────────────────────────
        t = t.replace("तैयार हुआ", "तैयार हुई")
        t = t.replace("खुश हुआ", "खुश हुई")
        t = t.replace("दुखी हुआ", "दुखी हुई")
        t = t.replace("परेशान हुआ", "परेशान हुई")
        t = t.replace("थका हुआ", "थकी हुई")
        t = t.replace("जागा हुआ", "जागी हुई")
        t = t.replace("सोया हुआ", "सोई हुई")
        t = t.replace("बैठा हुआ", "बैठी हुई")
        t = t.replace("खड़ा हुआ", "खड़ी हुई")
        t = t.replace("लेटा हुआ", "लेटी हुई")

        // ── Continuous (rahi/raha) ─────────────────────────────────────────────
        t = t.replace("रहा हूँ", "रही हूँ")   // idempotent guard
        t = t.replace("रहा हूं", "रही हूं")

        return t
    }
}
