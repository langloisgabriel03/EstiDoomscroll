package com.resolvecommunity.awaydoomscrollin

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Reels Wait fork: full-screen countdown shown after a blocked Instagram Reel.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY from the enabled accessibility service, so it
 * needs no SYSTEM_ALERT_WINDOW permission and is not subject to Android's
 * background activity-start limits. The window is not focusable: system Back
 * still reaches Instagram, and leaving Instagram dismisses the screen.
 */
internal class ReelsWaitOverlay(
    private val service: AccessibilityService,
    private val onUnlock: () -> Unit,
    private val onDismiss: () -> Unit
) {
    companion object {
        private const val TAG = "ReelsWaitOverlay"
        private const val TICK_MS = 500L
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private var root: View? = null
    private var countdown: TextView? = null
    private var unlockButton: TextView? = null
    private var deadline = 0L

    val isVisible: Boolean
        get() = root != null

    private val tick = object : Runnable {
        override fun run() {
            if (root == null) return
            // Nothing else reports leaving Instagram while the screen is up:
            // the service only receives events from the protected apps.
            val foreground = foregroundAppPackage()
            if (foreground != null && foreground != INSTAGRAM_PACKAGE) {
                dismiss()
                return
            }
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * The app window underneath. Not rootInActiveWindow: once this screen is
     * touched (or right after a tap on a guard window) the active window is our
     * own overlay, which would read as "left Instagram" and close the screen.
     */
    private fun foregroundAppPackage(): String? {
        val apps = try {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (_: Exception) {
            return null
        }
        val window = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive }
        return window?.root?.packageName?.toString()
    }

    /** Returns false when the screen is already visible or cannot be added. */
    fun show(): Boolean {
        if (root != null) return false
        val view = buildView()
        return try {
            windowManager?.addView(view, layoutParams()) ?: return false
            root = view
            deadline = SystemClock.uptimeMillis() + ReelsWaitConfig.waitSeconds() * 1000L
            render()
            handler.postDelayed(tick, TICK_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Wait screen could not be added", e)
            false
        }
    }

    /** Removes the screen and reports a dismissal ("Never mind" or leaving Instagram). */
    fun dismiss() {
        if (remove()) onDismiss()
    }

    /** Removes the screen without a callback, for service teardown. */
    fun hide() {
        remove()
    }

    private fun remove(): Boolean {
        val view = root ?: return false
        root = null
        countdown = null
        unlockButton = null
        handler.removeCallbacks(tick)
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        return true
    }

    private fun render() {
        val left = ((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
        countdown?.text = if (left > 0L) ReelsWaitConfig.formatCountdown(left) else "Ready"
        unlockButton?.visibility = if (left > 0L) View.INVISIBLE else View.VISIBLE
    }

    private fun buildView(): View {
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), 0, dp(32), 0)
        }
        column.addView(text("Wait before Reels", 22f, Color.WHITE, Typeface.BOLD))
        column.addView(text(
            "Take a breath. Do you still want to watch Reels?",
            15f, Color.argb(180, 255, 255, 255), Typeface.NORMAL
        ).apply { setPadding(0, dp(8), 0, dp(24)) })
        countdown = text("", 64f, Color.WHITE, Typeface.BOLD)
        column.addView(countdown)
        unlockButton = button(
            "Open Reels for ${ReelsWaitConfig.WINDOW_MINUTES} minutes",
            filled = false
        ) {
            // The button stays hidden until the countdown ends; guard anyway.
            if (SystemClock.uptimeMillis() >= deadline && remove()) onUnlock()
        }.apply { visibility = View.INVISIBLE }
        column.addView(unlockButton, buttonParams(top = dp(32)))
        column.addView(button("Never mind", filled = true) { dismiss() }, buttonParams(top = dp(12)))

        return FrameLayout(service).apply {
            setBackgroundColor(Color.argb(250, 12, 12, 16))
            // Consume every touch so nothing reaches Instagram underneath.
            isClickable = true
            addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
    }

    private fun text(value: String, sizeSp: Float, color: Int, style: Int) =
        TextView(service).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) =
        text(label, 16f, if (filled) Color.BLACK else Color.WHITE, Typeface.BOLD).apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                if (filled) setColor(Color.WHITE)
                else setStroke(dp(1), Color.argb(160, 255, 255, 255))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun buttonParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Reels Wait countdown"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
