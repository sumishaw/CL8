package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * LiveCaptionReader — continuously reads Live Captions overlay and
 * translates to Hindi via whisper_server.py CT2 pipeline.
 *
 * Two read paths run in parallel:
 *  1. onAccessibilityEvent — event-driven, fires on UI changes (fast)
 *  2. Watchdog poller      — active poll every 1.5s regardless of events (resilient)
 *
 * This guarantees translation never stops as long as Live Captions is visible,
 * even if Android throttles accessibility events during heavy CPU load.
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000
        private const val DEBOUNCE_MS     = 500L
        private const val MAX_WAIT_MS     = 3_000L

        // Watchdog polls Live Captions window directly at this interval.
        // Catches updates missed when Android throttles accessibility events.
        private const val WATCHDOG_MS     = 1_500L

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:      Job? = null
    private var forceJob:        Job? = null
    private var translateJob:    Job? = null
    private var watchdogJob:     Job? = null
    private var lastSentText     = ""
    private var lastHindiOut     = ""
    private var lastDetectedLang = ""

    // Unbounded FIFO — every sentence reaches the overlay, never dropped
    private val translateQueue = LinkedBlockingQueue<String>()

    // Window reader state
    private var lastTranslatedSentence = ""
    private var lastRawCaption         = ""
    private var lastSentText2          = ""
    private var captionWasVisible      = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            info.packageNames = null  // receive events from ALL packages
        }

        resetState()
        startTranslateWorker()
        startWatchdog()
        Log.i(TAG, "LiveCaptionReader connected — event + watchdog active")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false
        instance  = null
        pendingJob?.cancel()
        forceJob?.cancel()
        watchdogJob?.cancel()
        translateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── Event-driven read path ────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        // No package filter — TYPE_WINDOWS_CHANGED fires with foreground app pkg
        val sendText = readFromCaptionWindow() ?: return
        Log.d(TAG, "Event→ ${sendText.take(60)}")
        scheduleTranslation(sendText)
    }

    // ── Watchdog read path ────────────────────────────────────────────────────

    /**
     * Polls the Live Captions window every WATCHDOG_MS regardless of events.
     * Recovers from Android event throttling, CPU spikes, or any gap where
     * onAccessibilityEvent stops firing while Live Captions is still running.
     */
    private fun startWatchdog() {
        watchdogJob = scope.launch {
            Log.i(TAG, "Watchdog started — polling every ${WATCHDOG_MS}ms")
            while (isActive && isRunning) {
                delay(WATCHDOG_MS)
                val sendText = withContext(Dispatchers.Main) {
                    try { readFromCaptionWindow() } catch (_: Exception) { null }
                } ?: continue
                Log.d(TAG, "Watchdog→ ${sendText.take(60)}")
                scheduleTranslation(sendText)
            }
            Log.i(TAG, "Watchdog stopped")
        }
    }

    // ── Window reader ─────────────────────────────────────────────────────────

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (_: Exception) { return null }

        // Find the Live Captions window
        var captionRoot: AccessibilityNodeInfo? = null
        if (!allWindows.isNullOrEmpty()) {
            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { continue } ?: continue
                if (root.packageName?.toString() in LIVE_CAPTION_PACKAGES) {
                    captionRoot = root; break
                }
                root.recycle()
            }
        }

        // Window not visible → silence → reset state
        if (captionRoot == null) {
            if (captionWasVisible) {
                captionWasVisible = false
                lastRawCaption    = ""
                lastSentText2     = ""
                Log.d(TAG, "LC gone — state reset")
            }
            return null
        }

        // Collect all text nodes
        val nodes = mutableListOf<String>()
        collectAllText(captionRoot, nodes)
        captionRoot.recycle()

        val fullText = nodes
            .filter  { isValidCaption(it) }
            .filter  { !isStaticUiLabel(it) }
            .maxByOrNull { it.length }
            ?.trim() ?: return null

        // Fresh session — LC window reappeared after silence
        if (!captionWasVisible) {
            captionWasVisible = true
            lastRawCaption    = ""
            lastSentText2     = ""
            Log.d(TAG, "LC appeared — fresh session")
        }

        // No raw change since last read
        if (fullText == lastRawCaption) return null

        // Extract only the new suffix appended since last read
        val prevFull   = lastSentText2
        lastRawCaption = fullText

        val newPart: String = if (prevFull.isNotEmpty() && fullText.startsWith(prevFull))
            fullText.substring(prevFull.length).trim()   // pure append — exact new suffix
        else
            fullText.takeLast(150).trim()                // correction/reset — send tail for context

        lastSentText2 = fullText

        if (newPart.length < 3) return null
        return newPart
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun scheduleTranslation(sendText: String) {
        // Detect language script switch — reset dedup state so first line of
        // new language is never blocked by state from the previous language
        val scriptNow = detectScript(sendText)
        if (scriptNow != lastDetectedLang && lastDetectedLang.isNotEmpty()) {
            lastSentText           = ""
            lastTranslatedSentence = ""
            lastHindiOut           = ""
            lastRawCaption         = ""
            lastSentText2          = ""
            captionWasVisible      = false
        }
        lastDetectedLang = scriptNow

        // Debounce: 500ms wait lets LC finish word-correction on current line
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            enqueueForTranslation(lastSentText2.ifBlank { sendText })
        }

        // Force-send: if speech is continuous and debounce keeps getting
        // cancelled, guarantee a send every MAX_WAIT_MS anyway
        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                pendingJob?.cancel()
                enqueueForTranslation(lastSentText2.ifBlank { sendText })
            }
        }
    }

    private fun enqueueForTranslation(text: String) {
        forceJob?.cancel()
        forceJob = null
        if (text.isBlank() || text == lastSentText) return
        lastSentText = text
        translateQueue.offer(text)   // FIFO — always add, never drop
    }

    // ── Translation worker ────────────────────────────────────────────────────

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank()) continue

                Log.i(TAG, "✓ ${text.take(40)} → ${hindi.take(40)}")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"auto","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Translate error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetState() {
        lastSentText            = ""
        lastHindiOut            = ""
        lastTranslatedSentence  = ""
        lastDetectedLang        = ""
        lastRawCaption          = ""
        lastSentText2           = ""
        captionWasVisible       = false
        SpeechCaptureService.latestHindi   = ""
        SpeechCaptureService.latestEnglish = ""
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) out.add(text)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        val lower = text.lowercase()
        if (text.matches(Regex("[A-Za-zÀ-ÿ ]+\\([A-Za-zÀ-ÿ ]+\\)")) && text.length < 60) return true
        if (lower.contains("united states") || lower.contains("united kingdom")) return true
        if (lower.contains("simplified") || lower.contains("traditional")) return true
        if (!text.contains(" ") && text.length < 15) return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 3 || text.length > 400) return false
        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.35) return false
        if (text.contains("http") || text.contains("www.")) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        if (text.matches(Regex(".*\\(.*\\).*")) && text.length < 50) return false
        return true
    }

    private fun detectScript(text: String): String {
        for (c in text) {
            val cp = c.code
            if (cp in 0x3040..0x30FF) return "ja"
            if (cp in 0x4E00..0x9FFF) return "zh"
            if (cp in 0xAC00..0xD7AF) return "ko"
            if (cp in 0x0600..0x06FF) return "ar"
            if (cp in 0x0400..0x04FF) return "ru"
            if (cp in 0x0900..0x097F) return "hi"
        }
        return "latin"
    }
}
