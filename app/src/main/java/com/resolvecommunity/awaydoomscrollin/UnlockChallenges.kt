package com.resolvecommunity.awaydoomscrollin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Reels Wait fork: a mini-game on the wait screen. Solving it unlocks Reels
 * straight away instead of waiting for the countdown.
 *
 * To add a game: implement this interface and add its constructor to
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
    private val factories: List<() -> UnlockChallenge> = listOf(
        ::ReasonChallenge,
        ::TypingChallenge,
        ::MathChallenge,
        ::HoldChallenge
    )

    val count: Int
        get() = factories.size

    fun random(random: Random = Random.Default): UnlockChallenge = factories.random(random)()

    /** One fresh instance of every game, for tests. */
    fun all(): List<UnlockChallenge> = factories.map { it() }
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

/** "Why am I here?": pick a reason and write a sentence about it. */
internal class ReasonChallenge : UnlockChallenge {
    override val title = "Why am I here?"

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val box = ui.column()
        var chosen: String? = null
        val chips = mutableListOf<TextView>()
        val detail = ui.input("Say a bit more…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val status = ui.text("", 13f, ui.dim)
        val unlock = ui.button("Unlock", filled = false) {
            if (ReasonCheck.isValid(chosen, detail.text.toString())) onSolved()
        }

        fun refresh() {
            chips.forEach { chip ->
                val selected = chip.text.toString() == chosen
                (chip.background as GradientDrawable).setColor(
                    if (selected) Color.argb(90, 255, 255, 255) else Color.TRANSPARENT)
            }
            val missing = ReasonCheck.charsMissing(detail.text.toString())
            status.text = when {
                chosen == null -> "Pick the honest answer"
                missing > 0 -> "$missing more characters"
                else -> "Thanks for being honest"
            }
            unlock.alpha = if (ReasonCheck.isValid(chosen, detail.text.toString())) 1f else 0.35f
        }

        ReasonCheck.reasons.forEach { reason ->
            val chip = ui.button(reason, filled = false) {
                chosen = reason
                refresh()
            }
            chip.textSize = 14f
            chip.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            chips += chip
            box.addView(chip, ui.params(top = ui.dp(6)))
        }
        detail.addTextChangedListener(afterChanged { refresh() })
        box.addView(detail, ui.params(top = ui.dp(12)))
        box.addView(status, ui.params(top = ui.dp(6)))
        box.addView(unlock, ui.params(top = ui.dp(10)))
        refresh()
        return box
    }
}

/** Typing test: copy the sentence exactly, letters turn green or red as you type. */
internal class TypingChallenge : UnlockChallenge {
    override val title = "Type it exactly"
    private val target = TypingCheck.sentences.random()

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val box = ui.column()
        val sentence = ui.text("", 19f).apply {
            gravity = Gravity.START
            setLineSpacing(0f, 1.25f)
        }
        val field = ui.input("Tap here and start typing…",
            // Visible-password turns off autocorrect, so every letter is typed.
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        field.filters = arrayOf(InputFilter.LengthFilter(target.length))
        val stats = ui.text("0 WPM · 100% accuracy", 13f, ui.dim)
        var startedAt = 0L
        var totalKeys = 0
        var correctKeys = 0
        var previous = ""
        var solved = false

        fun render(typed: String) {
            val spans = SpannableStringBuilder(target)
            TypingCheck.states(target, typed).forEachIndexed { i, state ->
                val color = when (state) {
                    TypingCheck.CharState.CORRECT -> ui.green
                    TypingCheck.CharState.WRONG -> ui.red
                    TypingCheck.CharState.PENDING -> ui.dim
                }
                spans.setSpan(ForegroundColorSpan(color), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (state == TypingCheck.CharState.WRONG) {
                    spans.setSpan(BackgroundColorSpan(Color.argb(70, 255, 80, 80)),
                        i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (typed.length < target.length) {
                // Cursor: the next letter to type.
                spans.setSpan(BackgroundColorSpan(Color.argb(90, 255, 255, 255)),
                    typed.length, typed.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sentence.text = spans
            val elapsed = if (startedAt == 0L) 0L else SystemClock.uptimeMillis() - startedAt
            stats.text = "${TypingCheck.wpm(TypingCheck.correctPrefix(target, typed), elapsed)} WPM · " +
                "${TypingCheck.accuracy(correctKeys, totalKeys)}% accuracy"
        }

        field.addTextChangedListener(afterChanged { editable ->
            val typed = editable.toString()
            if (typed.length > previous.length) {
                if (startedAt == 0L) startedAt = SystemClock.uptimeMillis()
                for (i in previous.length until typed.length) {
                    totalKeys++
                    if (i < target.length && typed[i] == target[i]) correctKeys++
                }
            }
            previous = typed
            render(typed)
            if (!solved && TypingCheck.isComplete(target, typed)) {
                solved = true
                onSolved()
            }
        })
        box.addView(sentence, ui.params())
        box.addView(field, ui.params(top = ui.dp(14)))
        box.addView(stats, ui.params(top = ui.dp(8)))
        render("")
        return box
    }
}

/** Mental math: solve three sums; a wrong answer swaps in a new one. */
internal class MathChallenge : UnlockChallenge {
    override val title = "Solve ${MathCheck.PROBLEMS_TO_SOLVE} sums"

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val box = ui.column()
        var problem = MathCheck.generate()
        var solvedCount = 0
        val question = ui.text("", 34f, style = Typeface.BOLD)
        val progress = ui.text("", 13f, ui.dim)
        val field = ui.input("Tap to answer", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
            .apply {
                gravity = Gravity.CENTER
                imeOptions = EditorInfo.IME_ACTION_DONE
            }

        fun render(message: String? = null) {
            question.text = "${problem.text} = ?"
            progress.text = message ?: "$solvedCount / ${MathCheck.PROBLEMS_TO_SOLVE} solved"
        }

        fun check() {
            if (MathCheck.isCorrect(problem, field.text.toString())) {
                solvedCount++
                if (solvedCount >= MathCheck.PROBLEMS_TO_SOLVE) {
                    onSolved()
                    return
                }
                problem = MathCheck.generate()
                render()
            } else {
                problem = MathCheck.generate()
                render("Not quite, here's a new one · $solvedCount / ${MathCheck.PROBLEMS_TO_SOLVE}")
            }
            field.setText("")
        }

        field.setOnEditorActionListener { _, _, event ->
            // A hardware Enter reports down and up; check once.
            if (event == null || event.action == KeyEvent.ACTION_DOWN) check()
            true
        }
        box.addView(question, ui.params())
        box.addView(field, ui.params(top = ui.dp(12)))
        box.addView(ui.button("Check", filled = false) { check() }, ui.params(top = ui.dp(10)))
        box.addView(progress, ui.params(top = ui.dp(8)))
        render()
        return box
    }
}

/** Hold to unlock: keep a finger on the button; lifting it starts over. */
internal class HoldChallenge : UnlockChallenge {
    override val title = "Hold for ${HoldCheck.HOLD_SECONDS} seconds"
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val box = ui.column()
        val label = ui.text("Press and hold", 18f, style = Typeface.BOLD)
        val bar = View(ui.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(4).toFloat()
                setColor(ui.green)
            }
            pivotX = 0f
            scaleX = 0f
        }
        val pad = ui.text("Hold here", 16f, ui.dim).apply {
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(24).toFloat()
                setStroke(ui.dp(2), Color.argb(160, 255, 255, 255))
            }
        }
        var downAt = 0L
        var solved = false

        fun update() {
            val held = if (downAt == 0L) 0L else SystemClock.uptimeMillis() - downAt
            bar.scaleX = HoldCheck.progress(held)
            val left = ((HoldCheck.HOLD_SECONDS * 1000L - held).coerceAtLeast(0L) + 999L) / 1000L
            label.text = if (downAt == 0L) "Press and hold" else "Keep holding… ${left}s"
            if (!solved && HoldCheck.isDone(held)) {
                solved = true
                dispose()
                onSolved()
            }
        }

        val loop = object : Runnable {
            override fun run() {
                update()
                if (downAt != 0L && !solved) handler.postDelayed(this, 100L)
            }
        }
        tick = loop
        pad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    handler.removeCallbacks(loop)
                    handler.post(loop)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    downAt = 0L
                    handler.removeCallbacks(loop)
                    if (!solved) update()
                }
            }
            true
        }
        box.addView(label, ui.params())
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(8))
            .apply { topMargin = ui.dp(12) })
        box.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(140))
            .apply { topMargin = ui.dp(14) })
        return box
    }

    override fun dispose() {
        tick?.let { handler.removeCallbacks(it) }
    }
}

private fun afterChanged(block: (Editable) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable) = block(s)
}
