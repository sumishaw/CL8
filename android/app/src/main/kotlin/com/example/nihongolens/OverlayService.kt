package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * OverlayService — 2-line Hindi subtitle overlay
 *
 * Display model:
 *  - Each translation result = one display unit (never split into fragments)
 *  - Unit shown across up to 2 wrapped lines of text
 *  - Unit stays visible for READ_MS (3s) minimum so user can read it
 *  - Next unit from FIFO queue shown after READ_MS
 *  - If queue empty after READ_MS, current unit stays until SILENCE_MS (8s) then fades
 *  - FIFO queue — never drops any translation
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback: ((String, String) -> Unit)? = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }
    }

    // Maximum time each translation unit stays visible before advancing to next
    private val READ_MS      = 7_000L
    // After last speech, fade overlay after this silence
    private val SILENCE_MS   = 10_000L

    // FIFO queue of translation units (each = full Hindi result, not split)
    private val displayQueue  = ArrayDeque<String>()

    private var windowManager:  WindowManager?              = null
    private var overlayView:    View?                       = null
    private var textView:       TextView?                   = null
    private var params:         WindowManager.LayoutParams? = null
    private val mainHandler     = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private var currentText      = ""
    private var readRunnable:    Runnable? = null
    private var silenceRunnable: Runnable? = null
    private var isShowing        = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }
        pushCallback = { _, hindi -> mainHandler.post { onNewText(hindi) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running      = false
        pushCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        displayQueue.clear()
        currentText = ""
        isShowing   = false
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Text update ───────────────────────────────────────────────────────────

    private fun onNewText(hindi: String) {
        if (hindi.isBlank()) return

        val trimmed = hindi.trim()
        // Don't add exact duplicate of what's currently showing if queue is empty
        if (trimmed == currentText && displayQueue.isEmpty()) {
            rescheduleSilence(); return
        }

        // Add to FIFO queue — never drop
        displayQueue.addLast(trimmed)

        // Only kick display if not already showing — prevents multiple timers stacking
        if (!isShowing) showNext()

        rescheduleSilence()
    }

    // Show the next item from the queue
    private fun showNext() {
        // Cancel any pending read timer first — only one timer at a time
        readRunnable?.let { mainHandler.removeCallbacks(it) }
        readRunnable = null

        if (displayQueue.isEmpty()) {
            // Nothing more — stay on current text until silence timer fires
            return
        }

        val text = displayQueue.removeFirst()
        currentText = text
        isShowing   = true
        showText(text, animate = true)

        // Schedule advance to next after READ_MS
        // If backlog building (3+ waiting), use half time to catch up
        val waitMs = if (displayQueue.size >= 3) READ_MS / 2 else READ_MS
        readRunnable = Runnable {
            readRunnable = null
            if (!running) return@Runnable
            if (displayQueue.isNotEmpty()) {
                showNext()
            }
            // else: stay on current text, silence timer will clear eventually
        }
        mainHandler.postDelayed(readRunnable!!, waitMs)
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun showText(text: String, animate: Boolean) {
        val tv = textView ?: return
        if (animate) {
            tv.animate().cancel()
            tv.alpha = 0f
            tv.text  = text
            tv.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        } else {
            tv.text  = text
            tv.alpha = 1f
        }
    }

    private fun fadeOut(then: (() -> Unit)? = null) {
        val tv = textView ?: run { then?.invoke(); return }
        tv.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                currentText = ""
                isShowing   = false
                then?.invoke()
            }
            .start()
    }

    private fun rescheduleSilence() {
        silenceRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running) return@Runnable
            // Only clear if no new content arrived while we were waiting
            if (displayQueue.isEmpty()) {
                readRunnable?.let { mainHandler.removeCallbacks(it) }
                readRunnable = null
                fadeOut()
            }
        }
        mainHandler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels

            // Single TextView — 2 lines max, wraps naturally
            // No splitting, no containers, no complex logic
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, Color.BLACK)
                maxLines  = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(180, 0, 0, 0))
                }
                setPadding(dp(14), dp(8), dp(14), dp(8))
                alpha = 0f
                text  = ""
            }
            textView    = tv
            overlayView = tv

            params = WindowManager.LayoutParams(
                (sw * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(90)
            }

            // Draggable
            var startRawX = 0f; var startRawY = 0f
            var initX = 0;      var initY = 0
            tv.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX = p.x;         initY = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initX + (ev.rawX - startRawX).toInt()
                        p.y = initY - (ev.rawY - startRawY).toInt()
                        if (viewAdded) try {
                            windowManager?.updateViewLayout(overlayView, p)
                        } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
