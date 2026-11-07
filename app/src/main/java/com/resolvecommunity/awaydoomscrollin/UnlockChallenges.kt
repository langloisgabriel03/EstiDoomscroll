package com.resolvecommunity.awaydoomscrollin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Reels Wait fork: a mini-game on the wait screen. Solving it unlocks Reels
 * straight away instead of waiting for the countdown.
 *
 * To add a game: implement this interface and add it, with a short id, to
 * [UnlockChallenges.factories]. Keep its rules in UnlockChallengeLogic.kt so
 * they can be unit tested.
 */
internal interface UnlockChallenge {
    val title: String

    /** Builds the game's view. Call [onSolved] once, when the game is won. */
    fun createView(ui: ChallengeUi, onSolved: () -> Unit): View

    /** Stops timers when the wait screen closes. */
    fun dispose() {}
}

internal object UnlockChallenges {
    private const val KEY_BAG = "unlock_game_bag"
    private const val KEY_LAST = "unlock_game_last"

    /** Ids are stored in the game bag; keep them stable. */
    private val factories: List<Pair<String, () -> UnlockChallenge>> = listOf(
        "rushhour" to ::RushHourChallenge,
        "2048" to ::Game2048Challenge,
        "sudoku" to ::SudokuChallenge,
        "stack" to ::StackChallenge,
        "papertoss" to ::PaperTossChallenge
    )

    val count: Int
        get() = factories.size

    /**
     * The next game from a shuffled bag kept in the app's preferences: every
     * game once, in random order, before any repeats. Closing the wait screen
     * does not reroll it; the next screen simply takes the next game.
     */
    fun next(context: Context, random: Random = Random.Default): UnlockChallenge {
        val prefs = context.getSharedPreferences(ProtectionPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val bag = prefs.getString(KEY_BAG, null).orEmpty().split(',').filter { it.isNotEmpty() }
        val (id, rest) = GameBag.draw(factories.map { it.first }, bag, prefs.getString(KEY_LAST, null), random)
        prefs.edit().putString(KEY_BAG, rest.joinToString(",")).putString(KEY_LAST, id).apply()
        return factories.first { it.first == id }.second()
    }

    /** One fresh instance of every game, for tests. */
    fun all(): List<UnlockChallenge> = factories.map { it.second() }
}

/** Shared look for the wait screen and its games. */
internal class ChallengeUi(val context: Context) {
    /**
     * Set by the wait screen: makes its window focusable. It starts
     * unfocusable so it never takes key focus (and Back) from Instagram until
     * a text box is tapped.
     */
    var onNeedKeyboard: () -> Unit = {}

    val dim = Color.argb(180, 255, 255, 255)
    val green = Color.rgb(110, 231, 150)
    val red = Color.rgb(255, 107, 107)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun text(value: String, sizeSp: Float = 15f, color: Int = Color.WHITE, style: Int = Typeface.NORMAL) =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    fun button(label: String, filled: Boolean, onClick: () -> Unit) =
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

    @SuppressLint("ClickableViewAccessibility")
    fun input(hint: String, inputType: Int) = EditText(context).apply {
        setOnTouchListener { field, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) showKeyboard(field as EditText)
            false
        }
        this.hint = hint
        this.inputType = inputType
        textSize = 17f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.argb(110, 255, 255, 255))
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(40, 255, 255, 255))
        }
    }

    fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    fun params(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    fun showKeyboard(field: EditText) {
        onNeedKeyboard()
        field.post {
            field.requestFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

/**
 * A game's clock and its record: the fastest win, kept in the app's
 * preferences on this phone. Only for games that are the same every time
 * (2048, Stack), so records compare like with like. Shows "Time m:ss · Record m:ss" and announces
 * a new record with a toast, since winning usually closes the wait screen.
 */
internal class RecordClock(private val ui: ChallengeUi, private val key: String) {
    private val prefs = ui.context.getSharedPreferences(ProtectionPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    private var recordMs = prefs.getLong(key, 0L)
    private var startedAt = 0L
    private var finishedMs = -1L
    private var newRecord = false
    private val handler = Handler(Looper.getMainLooper())
    val label: TextView = ui.text("", 15f, style = Typeface.BOLD)

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500L)
        }
    }

    init {
        handler.post(tick)
    }

    /** Starts the clock if it is not running yet. */
    fun start() {
        if (startedAt == 0L && finishedMs < 0) startedAt = SystemClock.uptimeMillis()
        render()
    }

    /** Back to 0:00, waiting for [start]. */
    fun reset() {
        startedAt = 0L
        finishedMs = -1L
        render()
    }

    /** Stops the clock on a win and keeps the time if it beats the record. */
    fun finish() {
        if (startedAt == 0L || finishedMs >= 0) return
        finishedMs = SystemClock.uptimeMillis() - startedAt
        if (recordMs == 0L || finishedMs < recordMs) {
            recordMs = finishedMs
            newRecord = true
            prefs.edit().putLong(key, recordMs).apply()
            android.widget.Toast.makeText(ui.context, "New record: ${clock(recordMs)}!",
                android.widget.Toast.LENGTH_SHORT).show()
        }
        render()
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    private fun clock(ms: Long) = ReelsWaitConfig.formatCountdown(ms / 1000)

    private fun render() {
        val elapsed = when {
            finishedMs >= 0 -> finishedMs
            startedAt > 0 -> SystemClock.uptimeMillis() - startedAt
            else -> 0L
        }
        label.text = "Time ${clock(elapsed)}  ·  Record ${if (recordMs > 0) clock(recordMs) else "--:--"}" +
            if (newRecord) "  ·  New record!" else ""
    }
}
