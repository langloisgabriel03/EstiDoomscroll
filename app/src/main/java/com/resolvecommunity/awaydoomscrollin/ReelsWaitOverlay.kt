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
 * background activity-start limits. The window only becomes focusable when a
 * game's text box is tapped; leaving Instagram dismisses the screen.
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
        // Window lists settle after the screen is added; do not judge "left
        // Instagram" from the first frames.
        private const val LEAVE_CHECK_GRACE_MS = 1_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private val ui = ChallengeUi(service)
    private var root: View? = null
    private var countdown: TextView? = null
    private var unlockButton: TextView? = null
    private var challenge: UnlockChallenge? = null
    private var deadline = 0L
    private var shownAt = 0L
    private var params: WindowManager.LayoutParams? = null

    init {
        ui.onNeedKeyboard = ::enableKeyboard
    }

    val isVisible: Boolean
        get() = root != null

    private val tick = object : Runnable {
        override fun run() {
            if (root == null) return
            // Nothing else reports leaving Instagram while the screen is up:
            // the service only receives events from the protected apps.
            val other = otherAppOnTop()
            if (other != null && SystemClock.uptimeMillis() - shownAt >= LEAVE_CHECK_GRACE_MS) {
                Log.d(TAG, "Left Instagram for $other")
                toast("Reels Wait: closed, you left Instagram")
                dismiss()
                return
            }
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Another app's package when one is visible and Instagram is not, else
     * null. Not rootInActiveWindow: once this screen is touched the active
     * window is our own overlay, which would read as "left Instagram". A
     * window list without any app window (fully covered) means stay.
     */
    private fun otherAppOnTop(): String? {
        val appWindows = try {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (_: Exception) {
            return null
        }
        // The focused app is the clearest signal (this window is unfocusable
        // unless a game's text box is in use).
        val focused = appWindows.firstOrNull { it.isFocused }?.root?.packageName?.toString()
        if (focused != null && focused != INSTAGRAM_PACKAGE) return focused
        val apps = appWindows.mapNotNull { it.root?.packageName?.toString() }
        if (apps.isEmpty() || INSTAGRAM_PACKAGE in apps) return null
        return apps.first()
    }

    /** Lets the window take key focus so a game's text box can use the keyboard. */
    private fun enableKeyboard() {
        val view = root ?: return
        val current = params ?: return
        if (current.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        current.flags = current.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try { windowManager?.updateViewLayout(view, current) } catch (e: Exception) {
            Log.w(TAG, "Keyboard focus failed", e)
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(service, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** For tests: the mounted screen, or null. */
    internal val viewForTest: View?
        get() = root

    /** Returns false when the screen is already visible or cannot be added. */
    fun show(game: UnlockChallenge = UnlockChallenges.random()): Boolean {
        if (root != null) return false
        return try {
            val view = buildView(game)
            val windowParams = layoutParams()
            windowManager?.addView(view, windowParams) ?: return false
            root = view
            params = windowParams
            challenge = game
            shownAt = SystemClock.uptimeMillis()
            deadline = shownAt + ReelsWaitConfig.waitSeconds() * 1000L
            render()
            handler.postDelayed(tick, TICK_MS)
            true
        } catch (e: Exception) {
            game.dispose()
            Log.w(TAG, "Wait screen could not be added", e)
            toast("Reels Wait: wait screen failed (${e.javaClass.simpleName}: ${e.message})")
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
        params = null
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
        // A sure way out: the home gesture may not reach past a full-screen
        // overlay on every phone.
        column.addView(ui.button("Leave Instagram", filled = false) {
            dismiss()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }, ui.params(top = ui.dp(12)))
        @Suppress("DEPRECATION")
        val version = runCatching {
            service.packageManager.getPackageInfo(service.packageName, 0).versionName
        }.getOrNull().orEmpty()
        column.addView(ui.text("Reels Wait $version", 11f, Color.argb(90, 255, 255, 255)),
            ui.params(top = ui.dp(16)))

        val scroll = ScrollView(service).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return object : FrameLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // Swallow Back once the window has key focus: the service's own
                // Reel exit sends Back too, which must not close this screen.
                // "Never mind" is the way out.
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
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
        // Starts unfocusable, like the original wait screen; enableKeyboard()
        // drops FLAG_NOT_FOCUSABLE when a game's text box is tapped.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
