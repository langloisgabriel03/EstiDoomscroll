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
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Reels Wait fork: full-screen countdown shown after a blocked Instagram Reel.
 * Under the countdown sits a random mini-game ([UnlockChallenges]); winning it
 * unlocks Reels right away, otherwise the unlock button appears at 0:00.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY from the enabled accessibility service, so it
 * needs no SYSTEM_ALERT_WINDOW permission and is not subject to Android's
 * background activity-start limits. The window is focusable so the games can
 * use the keyboard; Back acts as "Never mind", and leaving Instagram dismisses
 * the screen.
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
    private val ui = ChallengeUi(service)
    private var root: View? = null
    private var countdown: TextView? = null
    private var unlockButton: TextView? = null
    private var challenge: UnlockChallenge? = null
    private var deadline = 0L

    val isVisible: Boolean
        get() = root != null

    private val tick = object : Runnable {
        override fun run() {
            if (root == null) return
            // Nothing else reports leaving Instagram while the screen is up:
            // the service only receives events from the protected apps.
            val foreground = topAppPackage()
            if (foreground != null && foreground != INSTAGRAM_PACKAGE) {
                dismiss()
                return
            }
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * The top-most app window. Not rootInActiveWindow, and not input focus:
     * this screen is our own focusable overlay, which would read as "left
     * Instagram" and close itself.
     */
    private fun topAppPackage(): String? = try {
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    /** Returns false when the screen is already visible or cannot be added. */
    fun show(): Boolean {
        if (root != null) return false
        val game = UnlockChallenges.random()
        val view = buildView(game)
        return try {
            windowManager?.addView(view, layoutParams()) ?: return false
            root = view
            challenge = game
            deadline = SystemClock.uptimeMillis() + ReelsWaitConfig.waitSeconds() * 1000L
            render()
            handler.postDelayed(tick, TICK_MS)
            true
        } catch (e: Exception) {
            game.dispose()
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

    private fun unlock() {
        if (remove()) onUnlock()
    }

    private fun remove(): Boolean {
        val view = root ?: return false
        root = null
        countdown = null
        unlockButton = null
        challenge?.dispose()
        challenge = null
        handler.removeCallbacks(tick)
        service.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        return true
    }

    private fun render() {
        val left = ((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
        countdown?.text = if (left > 0L) ReelsWaitConfig.formatCountdown(left) else "Ready"
        unlockButton?.visibility = if (left > 0L) View.GONE else View.VISIBLE
    }

    private fun buildView(game: UnlockChallenge): View {
        val column = ui.column().apply {
            setPadding(ui.dp(28), ui.dp(56), ui.dp(28), ui.dp(32))
        }
        column.addView(ui.text("Wait before Reels", 22f, style = Typeface.BOLD))
        column.addView(ui.text(
            "Wait it out, or beat the game below to unlock now.", 14f, ui.dim
        ).apply { setPadding(0, ui.dp(6), 0, ui.dp(8)) })
        countdown = ui.text("", 48f, style = Typeface.BOLD)
        column.addView(countdown)
        unlockButton = ui.button(
            "Open Reels for ${ReelsWaitConfig.WINDOW_MINUTES} minutes",
            filled = false
        ) {
            // The button only shows once the countdown ends; guard anyway.
            if (SystemClock.uptimeMillis() >= deadline) unlock()
        }.apply { visibility = View.GONE }
        column.addView(unlockButton, ui.params(top = ui.dp(12)))

        val card = ui.column().apply {
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18))
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(20).toFloat()
                setColor(Color.argb(28, 255, 255, 255))
            }
        }
        card.addView(ui.text(game.title, 17f, style = Typeface.BOLD))
        card.addView(game.createView(ui) { unlock() }, ui.params(top = ui.dp(12)))
        column.addView(card, ui.params(top = ui.dp(20)))

        column.addView(ui.button("Never mind", filled = true) { dismiss() }, ui.params(top = ui.dp(20)))

        val scroll = ScrollView(service).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return object : FrameLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.argb(250, 12, 12, 16))
            // Consume every touch so nothing reaches Instagram underneath.
            isClickable = true
            addView(scroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.TOP
            ))
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Focusable (no FLAG_NOT_FOCUSABLE) so the games can use the keyboard.
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Reels Wait countdown"
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
