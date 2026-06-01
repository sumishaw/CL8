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
import java.util.concurrent.atomic.AtomicLong

/**
 * OverlayService — Hindi subtitle overlay
 *
 * FIFO + Token design:
 *   - Every translation pushed to queue gets a monotonically increasing token
 *   - Display loop only shows item whose token == expectedToken
 *   - On clearQueue(), expectedToken is advanced past all pending tokens
 *     → stale items silently discarded when dequeued, no duplicate/stale display
 *   - One advance() timer at a time — token on the timer's Runnable must
 *     still match expectedToken when it fires, otherwise it's a stale timer
 *
 * READ_MS = 4s — comfortable reading pace, matches live speech rhythm
 * SILENCE_MS = 8s — fade after speech ends
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback:  ((String, String) -> Unit)? = null
        @Volatile private var clearCallback: (() -> Unit)?               = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }

        fun clearQueue() { clearCallback?.invoke() }
    }

    // Token counter — each queued item gets a unique token
    private val tokenCounter  = AtomicLong(0)
    // Only display items whose token >= expectedToken
    // Advancing expectedToken past pending tokens discards stale items
    private var expectedToken = 0L

    data class QueueItem(val token: Long, val text: String)

    private val queue       = ArrayDeque<QueueItem>()
    private var currentText = ""
    private var showing     = false

    // READ_MS: how long each subtitle stays visible
    // 4s = comfortable reading time for 1-2 lines of Hindi
    // Backlog (3+ waiting): 2s so we don't fall too far behind
    private val READ_MS_NORMAL  = 4_000L
    private val READ_MS_BACKLOG = 2_000L
    private val SILENCE_MS      = 8_000L

    // Active advance timer — carries its own token to detect stale timers
    private var timerToken:      Long     = -1L
    private var readRunnable:    Runnable? = null
    private var silenceRunnable: Runnable? = null

    private var windowManager: WindowManager?              = null
    private var textView:      TextView?                   = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null
    private val handler        = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { if (running) buildOverlay() }
        pushCallback  = { _, hindi -> handler.post { onNewHindi(hindi) } }
        clearCallback = { handler.post { onClearQueue() } }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        pushCallback  = null
        clearCallback = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Queue management ──────────────────────────────────────────────────────

    private fun onNewHindi(hindi: String) {
        if (hindi.isBlank()) return
        val t = hindi.trim()

        // Skip exact duplicate of what's on screen if queue empty
        if (t == currentText && queue.isEmpty()) {
            rescheduleSilence(); return
        }

        val token = tokenCounter.incrementAndGet()
        queue.addLast(QueueItem(token, t))   // FIFO
        rescheduleSilence()

        // Kick display only if idle
        if (!showing) advance()
    }

    /**
     * Discard all queued items when LC window disappears.
     * Advances expectedToken past all pending tokens — any already-scheduled
     * advance() timer will see its token < expectedToken and skip silently.
     */
    private fun onClearQueue() {
        val dropped = queue.size
        expectedToken = tokenCounter.get() + 1
        queue.clear()
        cancelTimer()
        showing = false
        android.util.Log.d("OverlayService", "clearQueue: dropped=$dropped expectedToken=$expectedToken")
    }

    // ── Display loop ──────────────────────────────────────────────────────────

    /**
     * Show next valid item from queue.
     * Items with token < expectedToken are stale — skip them.
     * Creates exactly one readRunnable timer carrying the displayed item's token.
     */
    private fun advance() {
        cancelTimer()

        // Drain stale items
        while (queue.isNotEmpty() && queue.first().token < expectedToken) {
            queue.removeFirst()
        }

        if (queue.isEmpty()) {
            // Nothing queued — mark idle so next onNewHindi() kicks display
            showing = false
            return
        }

        val item = queue.removeFirst()
        if (item.token < expectedToken) { showing = false; return }

        currentText = item.text
        showing     = true
        display(item.text)

        val capturedToken = item.token
        timerToken        = capturedToken
        val waitMs        = if (queue.size >= 3) READ_MS_BACKLOG else READ_MS_NORMAL
        readRunnable = Runnable {
            readRunnable = null
            if (!running) return@Runnable
            if (capturedToken < expectedToken) { showing = false; return@Runnable }
            advance()
        }
        handler.postDelayed(readRunnable!!, waitMs)
    }

    private fun cancelTimer() {
        readRunnable?.let { handler.removeCallbacks(it) }
        readRunnable = null
        timerToken   = -1L
    }

    private fun display(text: String) {
        val tv = textView ?: return
        tv.animate().cancel()
        tv.alpha = 0f
        tv.text  = text
        tv.animate().alpha(1f).setDuration(150).start()
    }

    private fun rescheduleSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running) return@Runnable
            if (queue.isNotEmpty()) return@Runnable  // new content arrived
            cancelTimer()
            textView?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
                currentText = ""; showing = false
            }?.start()
        }
        handler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels
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
                    setColor(Color.argb(185, 0, 0, 0))
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
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

            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            tv.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ix = p.x; iy = p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix + (ev.rawX - sx).toInt()
                        p.y = iy - (ev.rawY - sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView, p) }
                        catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
