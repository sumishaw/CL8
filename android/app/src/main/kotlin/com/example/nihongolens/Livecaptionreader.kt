package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * LiveCaptionReader — Live Captions → Hindi translation → Overlay
 *
 * CRITICAL FIX v2: Near-realtime translation
 * - READ_TIMEOUT reduced to 6s (was 35s) — cuts worst-case delay 6x
 * - SKIP-AHEAD: if queue has >1 item, always process the LATEST (skip old ones)
 * - Translation cache: identical/prefix-match sentences return instantly
 * - Stale threshold: 6s (was 15s) — drops sentences speaker has already passed
 * - Queue cap: 3 (was 8) — never build up more than 1 sentence of backlog
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG              = "LCReader"
        private const val TRANSLATE_URL    = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT  = 3_000
        private const val READ_TIMEOUT     = 6_000
        private const val DEBOUNCE_MS      = 400L
        private const val WATCHDOG_MS      = 2_000L
        private const val STARTUP_GRACE_MS = 1_000L
        private const val LANG_CONFIRM     = 3
        private const val QUEUE_CAP        = 3
        private const val STALE_MS         = 5_000L   // drop sentences speaker said >5s ago

        // ── SENTENCE COMPLETION SILENCE GAP ──────────────────────────────────
        // How long LC must be SILENT (no new text) before we treat current buffer as complete sentence.
        // 900ms: catches natural speaker pauses between sentences without cutting mid-sentence.
        // Previously 600ms was too short — partial clauses got translated mid-thought.
        private const val SENTENCE_SILENCE_MS_PRIMARY = 900L   // after hard punctuation or long sentence
        private const val SENTENCE_SILENCE_MS_SOFT    = 1_400L // after soft comma/clause — wait for more

        // ── MULTILINGUAL HARD SENTENCE-END MARKERS ───────────────────────────
        // These characters definitively end a sentence in their respective languages.
        // Translation is triggered IMMEDIATELY (100ms debounce) when text ends with one of these.
        //
        // Language coverage:
        //   English/European:   . ! ?
        //   Hindi/Devanagari:   । ॥ ! ? (danda = sentence end)
        //   Chinese/Japanese:   。！？ (fullwidth)
        //   Korean:             。 ! ?  (uses CJK period + ASCII punct)
        //   Arabic:             ؟ ۔ (Arabic question mark, Urdu full stop)
        //   Thai:               ๆ ฯ (though Thai rarely uses sentence-final punct)
        //   Hebrew:             . ! ?
        //   Russian/Cyrillic:   . ! ?
        //   Greek:              . ! ; (Greek semicolon = question mark)
        //   Spanish/French:     . ! ? … ¡ ¿ (inverted punct = sentence START but we check end)
        //   German:             . ! ?
        //   Portuguese:         . ! ?
        //   Indonesian/Malay:   . ! ?
        //   Turkish:            . ! ?
        //   All languages:      … (ellipsis = sentence end with trailing thought)
        val HARD_END_CHARS = setOf(
            // ── ASCII (en, es, fr, de, pt, ru, tr, id, ko) ──────────────────
            '.', '!', '?',

            // ── Fullwidth CJK (zh, ja, ko) ───────────────────────────────────
            '。', '！', '？',

            // ── Ellipsis variants ─────────────────────────────────────────────
            '…',       // U+2026 horizontal ellipsis (all languages)
            '⋯',       // U+22EF midline ellipsis (ja, zh variant)
            '‼', '⁇', '⁈', '⁉',   // double/combined punct

            // ── Hindi / Devanagari (hi, mr, ne, sa) ──────────────────────────
            '।', '॥',             // danda, double danda
            '\u0964', '\u0965',   // same by Unicode codepoint

            // ── Arabic script (ar, ur, fa, ps) ───────────────────────────────
            '؟',       // U+061F Arabic question mark
            '۔',       // U+06D4 Urdu full stop

            // ── Ethiopic (am — Amharic, not in our list but safe to add) ─────
            '\u1362', '\u1367',

            // ── Sundanese / Balinese (id regional scripts) ───────────────────
            '᪨', '᭞',
        )

        val SOFT_END_CHARS = setOf(
            // ── ASCII clause separators (all Latin-script languages) ──────────
            ',', ';', ':', '-',

            // ── Arabic clause separators ──────────────────────────────────────
            '،',       // U+060C Arabic comma
            '؛',       // U+061B Arabic semicolon

            // ── CJK clause separators (zh, ja, ko) ───────────────────────────
            '、',       // U+3001 ideographic comma (ja, zh)
            '，',       // U+FF0C fullwidth comma (zh)
            '；',       // U+FF1B fullwidth semicolon
            '：',       // U+FF1A fullwidth colon (zh — ends a clause, not sentence)
            '・',       // U+30FB katakana middle dot (ja)

            // ── Dashes (ru, de, fr, es — em/en dash as clause separator) ─────
            '—', '–',

            // ── Newline (LC sometimes uses for clause separation) ─────────────
            '\n',
        )

        // ── MINIMUM WORDS FOR TRANSLATION ────────────────────────────────────
        private const val MIN_WORDS_HARD    = 2   // after hard punctuation (lowered: "Do you?" = 2 words, must translate)
        private const val MIN_WORDS_SOFT    = 6   // after soft punctuation
        private const val MIN_WORDS_SILENCE = 5   // after silence gap

        // ── FORCE / COOLDOWN THRESHOLDS ───────────────────────────────────────
        // MAX_WORDS_BEFORE_FORCE: raised 15→20. At 15, a 60-word paragraph triggered
        // FORCE every 6 words = 10 submissions of the same sentence → CT2 flood.
        private const val MAX_WORDS_BEFORE_FORCE = 20

        // FORCE_MIN_NEW_WORDS: raised 6→12. Previously wordsSinceSubmit=7 bypassed
        // cooldown, causing same text to be submitted every 7 words (3s = 1+ per second).
        private const val FORCE_MIN_NEW_WORDS = 12

        // FORCE_COOLDOWN_MS: hard time-based lock after any FORCE submission.
        // Even if 12 new words arrive in 1 second, don't force-submit again.
        private const val FORCE_COOLDOWN_MS  = 5_000L

        private val LC_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:   Job? = null
    private var translateJob: Job? = null
    private var watchdogJob:  Job? = null

    // FIFO + tokens
    private data class QItem(val seq: Long, val text: String, val enqMs: Long = System.currentTimeMillis())
    private val queue      = LinkedBlockingQueue<QItem>()
    private val seqCounter = AtomicLong(0)
    // Thread-safe set: tracks texts currently being translated by any worker
    // ConcurrentHashMap.newKeySet() is thread-safe unlike mutableSetOf() (LinkedHashSet)
    // synchronized{} with LinkedHashSet can still throw ConcurrentModificationException
    // across coroutine dispatch threads → workerLoop dies silently → no translations
    private val activeTranslations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var expectedSeq = 0L

    // Translation LRU cache — avoid re-translating same sentence
    // Key: normalized English, Value: Hindi result
    private val translationCache = LinkedHashMap<String, String>(32, 0.75f, true)
    private val CACHE_MAX = 50

    // Simple dedup — only exact match
    private var lastEnqueued  = ""
    private var lastHindiOut  = ""
    private var lastHindiTime = 0L
    private val HINDI_DEDUP_MS = 4_000L

    // Language tracking
    private var confirmedLang = ""
    private var pendingLang   = ""
    private var pendingCount  = 0

    // Window state
    private var lastRawFull           = ""
    private var lastSentText          = ""
    private var lastEnqueuedSents     = mutableSetOf<String>()
    private var lcVisible             = false
    private var startupTime           = 0L

    // Stats
    private val evtCount = AtomicLong(0)
    private val enqCount = AtomicLong(0)
    private val okCount  = AtomicLong(0)
    private val errCount = AtomicLong(0)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance    = this
        isRunning   = true
        startupTime = System.currentTimeMillis()

        serviceInfo = serviceInfo?.also {
            it.eventTypes = (AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            it.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            it.notificationTimeout = 100
            it.flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
            it.packageNames = null
        }

        resetAll()
        startWorker()
        startWatchdog()
        startStats()
        CaptionLogger.log(TAG, "=== Connected (READ_TIMEOUT=${READ_TIMEOUT}ms QUEUE_CAP=$QUEUE_CAP STALE=${STALE_MS}ms) ===")
        GenderAnalyzer.start(MainActivity.lcProjection)
        scope.launch(Dispatchers.Main) { MainActivity.instance?.onLiveCaptionReaderConnected() }
    }

    override fun onInterrupt() { CaptionLogger.log(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        GenderAnalyzer.stop()
        pendingJob?.cancel()
        watchdogJob?.cancel(); translateJob?.cancel(); translateJob2?.cancel()
        queue.clear(); scope.cancel()
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
        OverlayService.updateText("", "")
        CaptionLogger.stop()
        super.onDestroy()
    }

    // ── Events ────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) return
        evtCount.incrementAndGet()
        val text = readWindow() ?: return
        schedule(text)
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            var tick = 0L
            while (isActive && isRunning) {
                delay(WATCHDOG_MS)
                if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) continue
                tick++
                val text = withContext(Dispatchers.Main) {
                    try { readWindow() } catch (_: Exception) { null }
                } ?: run {
                    if (tick % 20L == 0L)
                        CaptionLogger.log(TAG, "WD null tick=$tick vis=$lcVisible")
                    return@run null
                } ?: continue
                schedule(text)
            }
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun startStats() {
        scope.launch {
            while (isActive && isRunning) {
                delay(30_000L)
                CaptionLogger.log(TAG, "STATS evt=${evtCount.get()} enq=${enqCount.get()} " +
                    "ok=${okCount.get()} err=${errCount.get()} q=${queue.size} " +
                    "vis=$lcVisible lang=$confirmedLang seq=$expectedSeq cache=${translationCache.size}")
            }
        }
    }

    // ── Window reader ─────────────────────────────────────────────────────────

    private fun readWindow(): String? {
        val wins = try { windows } catch (_: Exception) { return null }

        var root: AccessibilityNodeInfo? = null
        wins?.forEach { w ->
            if (root != null) return@forEach
            val r = try { w.root } catch (_: Exception) { null } ?: return@forEach
            if (r.packageName?.toString() in LC_PACKAGES) root = r else r.recycle()
        }

        if (root == null) {
            if (lcVisible) {
                lcVisible             = false
                lastRawFull           = ""
                lastEnqueued          = ""
                lastSentText          = ""
                lastEnqueuedSents.clear()
                val dropped = queue.size
                queue.clear()
                expectedSeq = seqCounter.get() + 1
                pendingJob?.cancel(); pendingJob = null
                if (dropped > 0) CaptionLogger.log(TAG, "LC gone dropped=$dropped")
                else              CaptionLogger.log(TAG, "LC gone")
                OverlayService.clearQueue()
                HindiTtsService.stopAndClear()
                sentenceTimerJob?.cancel(); sentenceTimerJob = null
                sentenceBuffer = ""; lastBufferEnqueued = ""; lastEnqueuedWordCount = 0
                lastEnqueuedText = ""; lastSubmitTotalWords = 0; lastSubmitMs = 0L
                // Keep lastForcedMs — prevents immediate FORCE when next speaker appears
                // (FORCE_COOLDOWN_MS=5s still applies across LC gone/appear transitions)
            }
            return null
        }

        val nodes = mutableListOf<String>()
        collectText(root, nodes)
        root.recycle()

        val full = nodes.filter { validCaption(it) && !uiLabel(it) }
            .maxByOrNull { it.length }?.trim() ?: return null

        if (!lcVisible) {
            lcVisible   = true
            lastRawFull = ""
            lastEnqueuedSents.clear()
            CaptionLogger.log(TAG, "LC appeared '${full.take(60)}'")
        }

        // No change — nothing to process
        if (full == lastRawFull) return null

        val prev    = lastRawFull
        lastRawFull = full

        // KEY FIX: Return the FULL window text to schedule(), not just the delta.
        //
        // PREVIOUS (broken): returned only newText = full.substring(prev.length)
        // This caused schedule() to see tiny fragments: "a big break", ", wait about"
        // Each fragment triggered a 900ms silence timer → 5+ separate translations
        // for what should have been ONE complete sentence.
        //
        // NOW: return the FULL current LC text. schedule() accumulates it in
        // sentenceBuffer and the sentence-completion detector (punctuation/silence)
        // decides when to translate. The FULL text means the translation gets the
        // complete grammatical sentence — accurate Hindi output.
        //
        // LC text that doesn't start where we left off = new sentence block started
        // (LC scrolled, new speaker, etc.) — in that case still return the full new text.
        return if (full.length >= 4) full else null
    }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val parts  = text.split(Regex("""(?<=[。！？\n.!?])\s*"""))
        for (part in parts) {
            val t = part.trim()
            if (t.length >= 4) result.add(t)
        }
        if (result.isEmpty() && text.trim().length >= 4) result.add(text.trim())
        return result
    }

    private fun directEnqueue(text: String) {
        if (text.isBlank() || text.length < 4) return
        val n = norm(text)
        if (n == lastEnqueued) return
        lastEnqueued = n
        val seq = seqCounter.incrementAndGet()
        if (queue.size >= QUEUE_CAP) {
            val oldest = queue.peek()
            val age = if (oldest != null) System.currentTimeMillis() - oldest.enqMs else 9999L
            if (age > STALE_MS) queue.poll()
        }
        queue.offer(QItem(seq, text))
        enqCount.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ-S seq=$seq q=${queue.size} '${text.take(60)}'")
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun norm(t: String) = t.trim().replace(Regex("\\s+"), " ")
    private fun wc(t: String)   = t.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

    // State
    private var sentenceBuffer      = ""
    private var lastBufferEnqueued  = ""
    private var lastLCChangeMs      = 0L
    private var sentenceTimerJob: Job? = null
    private var lastEnqueuedWordCount = 0
    private var lastEnqueuedText      = ""
    // Cooldown: track total LC word count and time at last submission
    private var lastSubmitTotalWords  = 0
    private var lastSubmitMs          = 0L
    // Separate FORCE cooldown — tracks last time RULE 3 (FORCE) fired
    private var lastForcedMs          = 0L

    private fun schedule(text: String) {
        // ── Language detection ────────────────────────────────────────────────
        val script = detectScript(text)
        if (script != confirmedLang) {
            if (script == pendingLang) {
                if (++pendingCount >= LANG_CONFIRM) {
                    CaptionLogger.log(TAG, "LANG $confirmedLang->$script")
                    confirmedLang = script; pendingLang = ""; pendingCount = 0
                    lastEnqueued = ""; lastRawFull = ""; lastEnqueuedSents.clear()
                    sentenceBuffer = ""; lastBufferEnqueued = ""
                    lastEnqueuedWordCount = 0; lastEnqueuedText = ""
                    lastSubmitTotalWords = 0; lastSubmitMs = 0L; lastForcedMs = 0L
                    queue.clear(); expectedSeq = seqCounter.get() + 1
                }
            } else { pendingLang = script; pendingCount = 1 }
        } else { pendingLang = ""; pendingCount = 0 }

        val fullText   = text.trim()
        val totalWords = wc(fullText)
        lastLCChangeMs = System.currentTimeMillis()

        // ── Extract UNTRANSLATED TAIL (cumulative word-count tracking) ────────
        // PREVIOUS APPROACH (broken): match last 6 words of lastEnqueuedText in fullText.
        // Problem: 'is go' appears in multiple places in a 100-word paragraph → wrong match
        // → same sentence re-submitted 3 times as seq=33, 34, 36.
        //
        // NEW APPROACH: track how many total LC words existed when we last submitted.
        // untranslatedStart = lastSubmitTotalWords (total words at last submit time)
        // untranslated      = words from position lastSubmitTotalWords onward
        // This is O(1) and immune to word-content collisions.
        val untranslated: String = run {
            if (lastSubmitTotalWords <= 0) return@run fullText
            val fullWords = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }
            val startIdx  = lastSubmitTotalWords.coerceAtMost(fullWords.size)
            if (startIdx >= fullWords.size) return@run ""
            fullWords.subList(startIdx, fullWords.size).joinToString(" ")
        }

        val newWords = wc(untranslated)

        // ── COOLDOWN: Prevent re-submitting same text repeatedly ──────────────
        // CT2 TIMEOUT cascade: without this, same sentence submitted 10x → 50s stuck
        // Gate: only submit when 4+ NEW total words have arrived since last submission
        // OR more than 3s has passed (allows retry after silence)
        val wordsSinceSubmit = totalWords - lastSubmitTotalWords
        val timeSinceSubmit  = System.currentTimeMillis() - lastSubmitMs
        if (lastSubmitMs > 0L && wordsSinceSubmit < 4 && timeSinceSubmit < 3_000L) {
            sentenceBuffer = untranslated
            // Still run silence timer so we catch end-of-speech
            sentenceTimerJob?.cancel()
            if (newWords >= 5) {
                sentenceTimerJob = scope.launch {
                    delay(SENTENCE_SILENCE_MS_PRIMARY)
                    val t = sentenceBuffer.trim()
                    if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= 5) {
                        CaptionLogger.log(TAG, "SILENCE-COOLDOWN wc=${wc(t)}")
                        doSubmit(t, totalWords)
                    }
                }
            }
            return
        }

        if (untranslated.isBlank() || newWords < 2) {
            sentenceBuffer = untranslated; return
        }

        sentenceBuffer = untranslated
        val lastChar = untranslated.lastOrNull() ?: return

        // ── RULE 1: HARD sentence-end ─────────────────────────────────────────
        if (lastChar in HARD_END_CHARS && newWords >= MIN_WORDS_HARD) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(80)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= MIN_WORDS_HARD) {
                    CaptionLogger.log(TAG, "HARD-END '$lastChar' wc=${wc(t)}")
                    doSubmit(t, totalWords)
                }
            }
            return
        }

        // ── RULE 2: SOFT clause-end — wait for more ───────────────────────────
        if (lastChar in SOFT_END_CHARS && newWords >= 8) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            sentenceTimerJob = scope.launch {
                delay(SENTENCE_SILENCE_MS_SOFT)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= 6) {
                    CaptionLogger.log(TAG, "SOFT-END '$lastChar' wc=${wc(t)}")
                    doSubmit(t, totalWords)
                }
            }
            return
        }

        // ── RULE 3: FORCE — run-on sentence, no punctuation ──────────────────
        // Only fires when BOTH conditions met:
        //   a) 20+ new untranslated words (was 15 — too low for 60-word paragraphs)
        //   b) 12+ new total words since last FORCE AND 5s since last FORCE
        // This dual gate prevents the "same sentence submitted 10 times" cascade.
        val timeSinceForce = System.currentTimeMillis() - lastForcedMs
        if (newWords >= MAX_WORDS_BEFORE_FORCE &&
            wordsSinceSubmit >= FORCE_MIN_NEW_WORDS &&
            timeSinceForce  >= FORCE_COOLDOWN_MS) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(150)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= MIN_WORDS_SILENCE) {
                    CaptionLogger.log(TAG, "FORCE wc=${wc(t)} new=$wordsSinceSubmit")
                    lastForcedMs = System.currentTimeMillis()
                    doSubmit(t, totalWords)
                }
            }
            return
        }

        // ── RULE 4: SILENCE GAP ───────────────────────────────────────────────
        sentenceTimerJob?.cancel(); pendingJob?.cancel()
        if (newWords >= 5) {
            sentenceTimerJob = scope.launch {
                delay(SENTENCE_SILENCE_MS_PRIMARY)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= 5) {
                    CaptionLogger.log(TAG, "SILENCE ${SENTENCE_SILENCE_MS_PRIMARY}ms wc=${wc(t)}")
                    doSubmit(t, totalWords)
                }
            }
        }
    }

    private fun doSubmit(text: String, currentTotalWords: Int) {
        lastEnqueuedText      = text
        lastEnqueuedWordCount = wc(text)
        lastSubmitTotalWords  = currentTotalWords
        lastSubmitMs          = System.currentTimeMillis()
        sentenceBuffer        = ""
        enqueue(text)
    }

    private fun enqueue(text: String) {
        if (text.isBlank() || text.length < 4) return

        val devanagariCount = text.count { it.code in 0x0900..0x097F }
        if (devanagariCount > 0) {
            CaptionLogger.log(TAG, "SKIP: Devanagari detected (TTS loop guard)")
            return
        }

        // HARD QUEUE CAP: If queue already has QUEUE_CAP items, drop ALL existing items
        // and only keep the newest (current) text. This prevents CT2 from being asked to
        // translate 10+ stale sentences — the speaker has moved on.
        val currentSize = queue.size
        if (currentSize >= QUEUE_CAP) {
            queue.clear()
            CaptionLogger.log(TAG, "QUEUE-FLUSH: had $currentSize items, keeping only latest")
        }

        val wordCount = text.trim().split(Regex("\\s+")).size
        if (wordCount < 2) {
            CaptionLogger.log(TAG, "SKIP: too short ($wordCount words)")
            return
        }
        val lower = text.lowercase()
        val romanizedHindi = listOf(
            "sunkar","muskura","heto","hain","nahin","theek","aapko",
            "tumhe","kijiye","karein","chahiye","matlab","lekin",
            "parantu","isliye","kyunki","waise","raha","rahi","rahe",
            "bolta","bolti","kehta","kehti","sunta","sunti"
        )
        if (romanizedHindi.any { lower.contains(it) } && text.split(" ").size < 10) {
            CaptionLogger.log(TAG, "SKIP: romanized Hindi detected '${text.take(30)}'")
            return
        }

        val n = norm(text)
        if (n == lastEnqueued || n == norm(lastSentText)) {
            CaptionLogger.log(TAG, "SKIP dup")
            return
        }

        if (n.startsWith(lastEnqueued) && lastEnqueued.length > 10) {
            queue.removeIf { norm(it.text) == lastEnqueued }
            CaptionLogger.log(TAG, "SUPERSEDE: removed shorter prefix")
        }

        lastEnqueued = n
        val seq = seqCounter.incrementAndGet()

        // CRITICAL FIX: Skip-ahead — if queue already has items older than 3s,
        // drop them all and only keep this latest sentence
        // This prevents accumulating a backlog of sentences that will never be timely
        if (queue.size >= QUEUE_CAP) {
            val oldest = queue.peek()
            val oldestAge = if (oldest != null) System.currentTimeMillis() - oldest.enqMs else 0L
            if (oldestAge > STALE_MS) {
                val cleared = queue.size
                queue.clear()
                CaptionLogger.log(TAG, "SKIP-AHEAD: cleared $cleared stale items, going to latest")
            } else {
                // Queue not stale yet — just block new item if full
                // (3-item cap means this is only 1-2 sentences max)
                CaptionLogger.log(TAG, "CAP: queue full q=${queue.size}")
                return
            }
        }

        queue.offer(QItem(seq, text))
        enqCount.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ seq=$seq q=${queue.size} '${text.take(60)}'")
    }

    // ── Translation worker ────────────────────────────────────────────────────

    private var translateJob2: Job? = null

    private fun startWorker() {
        // 2 parallel workers — doubles throughput
        translateJob  = scope.launch { workerLoop("W1") }
        translateJob2 = scope.launch { workerLoop("W2") }
    }

    private suspend fun workerLoop(name: String) {
        while (currentCoroutineContext().isActive) {
            val item = withContext(Dispatchers.IO) {
                try { queue.poll(2, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { null }
            } ?: continue

            val seq   = item.seq
            val text  = item.text
            val ageMs = System.currentTimeMillis() - item.enqMs

            if (seq < expectedSeq) { CaptionLogger.log(TAG, "STALE $seq"); continue }
            // CRITICAL FIX: Drop sentences >5s old (was 15s)
            if (ageMs > STALE_MS) {
                CaptionLogger.log(TAG, "EXPIRED $seq age=${ageMs/1000}s")
                continue
            }

            // CRITICAL FIX: Check cache FIRST before any HTTP call
            val nText = norm(text)
            val cached = synchronized(translationCache) { translationCache[nText] }
            if (cached != null) {
                CaptionLogger.log(TAG, "CACHE-HIT[$name] $seq '${cached.take(40)}'")
                deliverHindi(seq, text, cached, name, 0L)
                continue
            }

            val t0     = System.currentTimeMillis()
            lastSentText = text

            // Dedup: if the same text is already being translated by another worker,
            // skip this item — the other worker's result will be cached and reused.
            // Prevents W1 and W2 both spending 5s on the same sentence.
            val alreadyRunning = !activeTranslations.add(nText)  // atomic add; returns false if already present
            if (alreadyRunning) {
                CaptionLogger.log(TAG, "DEDUP[$name] $seq — waiting for other worker's result")
                // Wait up to 9s for the other worker to populate cache
                // If W1 hits CT2 timeout (8s), cache stays empty → W2 SKIPS (not retries)
                // Previously: W2 would fall through to callServer → double CT2 timeout = 16s blocked
                var waited = 0
                var gotResult = false
                while (waited < 9_000) {
                    delay(200); waited += 200
                    val cached2 = synchronized(translationCache) { translationCache[nText] }
                    if (cached2 != null) {
                        CaptionLogger.log(TAG, "DEDUP-HIT[$name] ${waited}ms '${cached2.take(30)}'")
                        deliverHindi(seq, text, cached2, name, waited.toLong())
                        gotResult = true
                        break
                    }
                    // If other worker finished (no longer active) and cache still empty → it failed
                    if (!activeTranslations.contains(nText)) {
                        CaptionLogger.log(TAG, "DEDUP-SKIP[$name] other worker failed, skipping")
                        break
                    }
                }
                if (!gotResult)
                    CaptionLogger.log(TAG, "DEDUP-SKIP[$name] ${waited}ms no result, moving on")
                continue  // always skip — never retry with callServer
            }

            val result = callServer(text)
            activeTranslations.remove(nText)
            val ms     = System.currentTimeMillis() - t0

            // CRITICAL FIX: With 6s timeout, if it still takes >4s the sentence is too old
            if (ms > 4_000L && seq < expectedSeq) {
                CaptionLogger.log(TAG, "DISCARD-SLOW $seq ${ms}ms (too old)")
                continue
            }

            if (seq < expectedSeq) { CaptionLogger.log(TAG, "DISCARD $seq ${ms}ms"); continue }

            if (result == null) {
                errCount.incrementAndGet()
                CaptionLogger.log(TAG, "ERR ${ms}ms '${text.take(40)}'")
                continue
            }

            val (hindi, serverLang) = result

            // Cache the result
            synchronized(translationCache) {
                if (translationCache.size >= CACHE_MAX) {
                    translationCache.remove(translationCache.keys.first())
                }
                translationCache[nText] = hindi
            }

            deliverHindi(seq, text, hindi, name, ms)
        }
    }

    private fun deliverHindi(seq: Long, text: String, hindi: String, workerName: String, ms: Long) {
        val now   = System.currentTimeMillis()
        val hNorm = norm(hindi)
        if (hNorm == norm(lastHindiOut) && (now - lastHindiTime) < HINDI_DEDUP_MS) {
            CaptionLogger.log(TAG, "SKIP dup Hindi")
            lastEnqueued = ""; lastRawFull = ""
            return
        }
        lastHindiOut  = hindi
        lastHindiTime = now

        okCount.incrementAndGet()
        CaptionLogger.log(TAG, "OK[$workerName] $seq ${ms}ms '${hindi.take(50)}'")
        SpeechCaptureService.latestHindi   = hindi
        SpeechCaptureService.latestEnglish = text

        // FIFO TOKEN FIX:
        // Previously: OverlayService.updateText() was called immediately when translation
        // arrived → subtitle refreshed every 1-2s regardless of TTS state → subtitles
        // flashed too fast, TTS was always playing an old sentence.
        //
        // Now: subtitle display is driven EXCLUSIVELY by HindiTtsService play worker.
        // Flow: speak() → fetchQueue → synthesize → playQueue → PLAY → showTtsText()
        // The subtitle only appears when TTS is about to speak it (right before playWav).
        // When TTS finishes, clearTtsText() is called → subtitle hidden briefly.
        // Next sentence in playQueue → showTtsText() → subtitle updates.
        //
        // This creates perfect audio+subtitle sync with FIFO ordering.
        // The only direct overlay call is clearing on LC gone (line ~210).

        // Send to Flutter UI counter (does NOT touch overlay)
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onTranslation(text, hindi, hindi)
        }

        // Queue for TTS — play worker controls overlay display
        HindiTtsService.speak(hindi, text)
    }

    private fun callServer(text: String): Pair<String, String>? {
        if (text.trim().length < 4) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            // CRITICAL FIX: 6s read timeout (was 35s)
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"auto","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                CaptionLogger.log(TAG, "HTTP ${conn.responseCode}"); return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val hindi = json.optString("text", "").trim()
            val lang  = json.optString("detected_lang", confirmedLang)
            if (hindi.isBlank()) null else Pair(hindi, lang)
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "server ex: ${e.javaClass.simpleName}: ${e.message}"); null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetAll() {
        lastEnqueued = ""; lastRawFull = ""; lastSentText = ""
        lastEnqueuedSents.clear()
        confirmedLang = ""; pendingLang = ""; pendingCount = 0
        lcVisible = false; expectedSeq = 0L
        lastHindiOut = ""; lastHindiTime = 0L
        translationCache.clear()
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && it != out.lastOrNull() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    private fun uiLabel(t: String): Boolean {
        val l = t.lowercase()
        if (l == "live caption" || l == "live captions") return true
        if (l.startsWith("live caption") && t.length < 30) return true
        if (l.contains("united states") || l.contains("united kingdom")) return true
        if (l.contains("simplified") || l.contains("traditional")) return true
        if (t == "Hide" || t == "Settings" || t == "Feedback") return true
        return false
    }

    private fun validCaption(t: String): Boolean {
        if (t.length < 2 || t.length > 500) return false
        if (t.count { it.isLetter() } < 2) return false
        if (t.contains("com.android") || t.contains("com.google")) return false
        if (t.contains("http") || t.contains("www.")) return false
        return true
    }

    private fun detectScript(text: String): String {
        var ja = 0; var zh = 0; var ko = 0; var ar = 0; var ru = 0; var hi = 0
        for (c in text) when (c.code) {
            in 0x3040..0x30FF -> ja++
            in 0x4E00..0x9FFF -> zh++
            in 0xAC00..0xD7AF -> ko++
            in 0x0600..0x06FF -> ar++
            in 0x0400..0x04FF -> ru++
            in 0x0900..0x097F -> hi++
        }
        val nonLatin = maxOf(ja, zh, ko, ar, ru, hi)
        if (nonLatin > 0) return when (nonLatin) {
            ja -> "ja"; ko -> "ko"; hi -> "hi"; ar -> "ar"; ru -> "ru"; else -> "zh"
        }
        return if (text.any { it.isLetter() && it.code in 0x00C0..0x024F })
            "latin_foreign" else "latin_en"
    }
}
