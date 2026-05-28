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
 * LiveCaptionReader v6 — KEY FIX: use windows list, NOT rootInActiveWindow
 *
 * rootInActiveWindow returns the FOCUSED window (Termux, browser etc.)
 * We must iterate windows list and find the com.google.android.as window specifically.
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
        private const val DEBOUNCE_MS     = 2000L  // 2s lag — lets Live Captions finish correcting

        @Volatile var isRunning       = false
        @Volatile var lastCaptionText = ""
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:    Job? = null
    private var translateJob:  Job? = null
    private var lastSentText   = ""
    private var lastHindiOut   = ""
    private val translateQueue = LinkedBlockingQueue<String>(8)

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
            info.notificationTimeout = 50
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            // Monitor confirmed Live Captions package only
            info.packageNames = LIVE_CAPTION_PACKAGES.toTypedArray()
        }

        startTranslateWorker()
        // Clear cached state from previous session
        lastSentText            = ""
        lastHindiOut            = ""
        lastCaptionText         = ""
        lastTranslatedSentence  = ""
        SpeechCaptureService.latestHindi   = ""
        SpeechCaptureService.latestEnglish = ""
        Log.i(TAG, "LiveCaptionReader v6 connected")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in LIVE_CAPTION_PACKAGES) return

        // CRITICAL FIX: find the com.google.android.as window from the windows list
        // Do NOT use rootInActiveWindow — that returns the focused window (wrong app)
        val captionText = readFromCaptionWindow() ?: return
        if (captionText == lastCaptionText) return
        lastCaptionText = captionText
        Log.d(TAG, "Caption: $captionText")
        scheduleTranslation(captionText)
    }

    private var lastTranslatedSentence = ""

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (_: Exception) { return null }
        if (allWindows.isNullOrEmpty()) return null

        for (window in allWindows) {
            val root = try { window.root } catch (_: Exception) { continue } ?: continue
            val windowPkg = root.packageName?.toString() ?: ""

            if (windowPkg in LIVE_CAPTION_PACKAGES) {
                val textNodes = mutableListOf<String>()
                collectAllText(root, textNodes)
                root.recycle()

                val validTexts = textNodes
                    .filter { isValidCaption(it) }
                    .filter { !isStaticUiLabel(it) }

                if (validTexts.isEmpty()) return null

                // Get the full accumulated text from Live Captions window
                val fullText = validTexts.maxByOrNull { it.length } ?: return null

                // Split by sentence boundaries and take the LAST complete sentence
                val lastSentence = extractLastSentence(fullText) ?: return null

                // Only translate if it's new and long enough to be meaningful
                if (lastSentence == lastTranslatedSentence) return null
                if (lastSentence.length < 4) return null

                lastTranslatedSentence = lastSentence
                return lastSentence
            } else {
                root.recycle()
            }
        }
        return null
    }

    private fun extractLastSentence(text: String): String? {
        // Split on Japanese/English sentence endings
        val sentences = text
            .split(Regex("[。！？!?]+"))
            .map { it.trim() }
            .filter { it.length >= 4 }

        if (sentences.isEmpty()) {
            // No sentence boundaries — return full text if reasonable length
            return if (text.trim().length >= 4) text.trim() else null
        }

        // Return last complete sentence
        return sentences.lastOrNull()
    }

    private fun findNewContent(previous: String, current: String): String = current

    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) out.add(text)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        // Skip language selector labels like "English (United States)"
        val lower = text.lowercase()
        if (lower.contains("united states") || lower.contains("united kingdom")) return true
        if (lower.contains("english") && lower.length < 40) return true
        if (lower.contains("japanese") && lower.length < 40) return true
        if (lower.contains("chinese") || lower.contains("korean")) return true
        if (lower.contains("español") || lower.contains("français")) return true
        // Skip single words that are likely UI buttons
        if (!text.contains(" ") && text.length < 15) return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 4 || text.length > 350) return false
        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.4) return false
        if (text.contains("http") || text.contains("www.")) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        // Reject locale strings like "English (United States)"
        if (text.matches(Regex(".*\\(.*\\).*")) && text.length < 50) return false
        // Reject very short English fragments (single/double words) — UI noise
        val isAllAscii = text.all { it.isAscii() }
        if (isAllAscii && text.split(" ").size <= 2 && text.length < 15) return false
        return true
    }

    private fun scheduleTranslation(text: String) {
        // Cancel previous timer — restart 2s countdown on every new word from Live Captions
        // Timer only fires when Live Captions stops updating (sentence complete + corrected)
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            // Read the caption window AGAIN after 2s — get the fully corrected text
            val finalText = readFromCaptionWindow() ?: lastCaptionText
            if (finalText.isNotBlank() && finalText != lastSentText) {
                lastSentText = finalText
                if (translateQueue.size >= 8) translateQueue.poll()
                translateQueue.offer(finalText)
            }
        }
    }

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank() || hindi == lastHindiOut) continue
                lastHindiOut = hindi

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
            val body = """{"text":${JSONObject.quote(text)},"src":"en","tgt":"hi"}"""
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

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        pendingJob?.cancel(); translateJob?.cancel(); scope.cancel()
        super.onDestroy()
    }
}
