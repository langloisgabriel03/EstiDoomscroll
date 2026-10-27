package com.resolvecommunity.awaydoomscrollin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Sudoku: fill the grid correctly to unlock. */
internal class SudokuChallenge : UnlockChallenge {
    override val title = "Sudoku: fill the grid"
    private var clock: RecordClock? = null

    override fun dispose() {
        clock?.stop()
    }

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val box = ui.column()
        val record = RecordClock(ui, "record_ms_sudoku").also { clock = it }
        record.start()
        box.addView(record.label, ui.params())
        val stats = ui.text("", 13f, ui.dim)
        val (puzzle, solution) = SudokuPuzzles.random()
        val board = SudokuView(ui.context, SudokuBoard(puzzle, solution), {
            record.finish()
            onSolved()
        }) { filled ->
            stats.text = "Filled $filled / ${SudokuBoard.SIZE * SudokuBoard.SIZE}  ·  Red means a clash"
        }
        box.addView(stats, ui.params(top = ui.dp(4)))
        box.addView(board, ui.params(top = ui.dp(8)))

        // Number pad: 1-5 and 6-9 plus erase, sized like keys.
        fun key(label: String, digit: Int) = TextView(ui.context).apply {
            text = label
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(10).toFloat()
                setColor(Color.argb(45, 255, 255, 255))
            }
            isClickable = true
            setOnClickListener { board.enter(digit) }
        }
        for (row in listOf((1..5).map { it.toString() to it }, (6..9).map { it.toString() to it } + ("⌫" to 0))) {
            val line = LinearLayout(ui.context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (label, digit) ->
                line.addView(key(label, digit), LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = ui.dp(3)
                    marginEnd = ui.dp(3)
                })
            }
            box.addView(line, ui.params(top = ui.dp(8)))
        }
        return box
    }
}

/**
 * Draws a [SudokuBoard] on a paper-white grid: thick box lines, the selected
 * cell's row, column and box shaded, matching digits marked, givens in dark
 * ink, entries in blue and clashes in red.
 */
internal class SudokuView(
    context: Context,
    private val board: SudokuBoard,
    private val onWin: () -> Unit,
    private val onFilled: (Int) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var selected = -1
    private var won = false

    init {
        isClickable = true
        contentDescription = "Sudoku board. Tap a cell, then a number."
        onFilled(board.filled)
    }

    /** Puts [digit] (0 erases) in the selected cell. */
    fun enter(digit: Int) {
        if (won || selected < 0 || !board.set(selected, digit)) return
        onFilled(board.filled)
        invalidate()
        if (board.isSolved) {
            won = true
            // Let the finished grid show, then unlock (which removes this window).
            postDelayed({ onWin() }, 600L)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP && !won) {
            val cell = width / SudokuBoard.SIZE.toFloat()
            val col = (event.x / cell).toInt().coerceIn(0, SudokuBoard.SIZE - 1)
            val row = (event.y / cell).toInt().coerceIn(0, SudokuBoard.SIZE - 1)
            selected = row * SudokuBoard.SIZE + col
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val cell = w / SudokuBoard.SIZE
        paint.shader = null
        paint.color = Color.rgb(253, 252, 247)
        rect.set(0f, 0f, w, w)
        canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, paint)

        val selRow = if (selected >= 0) selected / SudokuBoard.SIZE else -1
        val selCol = if (selected >= 0) selected % SudokuBoard.SIZE else -1
        val selDigit = if (selected >= 0) board.cells[selected] else 0
        for (index in board.cells.indices) {
            val row = index / SudokuBoard.SIZE
            val col = index % SudokuBoard.SIZE
            val shade = when {
                index == selected -> Color.rgb(187, 222, 251)
                board.conflicts(index) -> Color.rgb(254, 226, 226)
                selDigit != 0 && board.cells[index] == selDigit -> Color.rgb(207, 232, 252)
                row == selRow || col == selCol ||
                    (selected >= 0 && row / 3 == selRow / 3 && col / 3 == selCol / 3) -> Color.rgb(232, 240, 248)
                else -> 0
            }
            if (shade != 0) {
                paint.color = shade
                canvas.drawRect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell, paint)
            }
            val digit = board.cells[index]
            if (digit == 0) continue
            text.textSize = cell * 0.58f
            text.typeface = Typeface.create(Typeface.DEFAULT,
                if (board.isGiven(index)) Typeface.BOLD else Typeface.NORMAL)
            text.color = when {
                board.isGiven(index) -> Color.rgb(31, 41, 55)
                board.conflicts(index) -> Color.rgb(220, 38, 38)
                else -> Color.rgb(37, 99, 235)
            }
            val baseline = row * cell + cell / 2 - (text.descent() + text.ascent()) / 2
            canvas.drawText(digit.toString(), col * cell + cell / 2, baseline, text)
        }

        for (i in 1 until SudokuBoard.SIZE) {
            val thick = i % 3 == 0
            paint.color = if (thick) Color.rgb(55, 65, 81) else Color.rgb(203, 213, 225)
            paint.strokeWidth = if (thick) cell * 0.045f else cell * 0.015f
            canvas.drawLine(i * cell, 0f, i * cell, w, paint)
            canvas.drawLine(0f, i * cell, w, i * cell, paint)
        }

        if (won) {
            paint.color = Color.argb(150, 253, 252, 247)
            rect.set(0f, 0f, w, w)
            canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, paint)
            text.color = Color.rgb(22, 163, 74)
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textSize = w * 0.09f
            canvas.drawText("Solved!", w / 2, w / 2, text)
        }
    }
}

/** Stack: tap to drop each floor; reach the target height to unlock. */
internal class StackChallenge : UnlockChallenge {
    override val title = "Stack: build ${StackGame.TARGET} floors"
    private var view: StackView? = null
    private var clock: RecordClock? = null

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val record = RecordClock(ui, "record_ms_stack").also { clock = it }
        val board = StackView(ui.context, StackGame(), {
            record.finish()
            onSolved()
        }) { before ->
            // The clock times one run: from its first tap; a retry restarts it.
            when (before) {
                StackGame.State.READY -> record.start()
                StackGame.State.DEAD -> record.reset()
                else -> Unit
            }
        }
        view = board
        val box = ui.column()
        box.addView(record.label, ui.params())
        box.addView(board, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(420)))
        box.addView(ui.text("Tap to drop the block. Overhangs are cut off.", 13f, ui.dim),
            ui.params(top = ui.dp(8)))
        return box
    }

    override fun dispose() {
        clock?.stop()
        view?.stop()
        view = null
    }
}

/**
 * Draws [StackGame] as an isometric 3D tower: each floor is a slab with a lit
 * top and two shaded sides, colours drift with height, cut-off pieces tumble
 * away, and the camera rises with the tower.
 */
internal class StackView(
    context: Context,
    private val game: StackGame,
    private val onWin: () -> Unit,
    /** Each tap, with the state the game was in before it. */
    private val onTap: (before: StackGame.State) -> Unit = {}
) : View(context) {
    private companion object {
        const val FLOOR = 14f
        const val COS30 = 0.8660254f
        const val SIN30 = 0.5f
        const val HUE_START = 190f
        const val HUE_STEP = 9f
    }

    private class Debris(val block: StackGame.Block, val level: Int, var drop: Float, var speed: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.WHITE
        setShadowLayer(6f, 0f, 2f, Color.argb(90, 0, 0, 0))
    }
    private val path = Path()
    private val debris = mutableListOf<Debris>()
    private var camera = 0f
    private var lastFrame = 0L
    private var running = false
    private var perfectUntil = 0L
    private var reported = false
    private val hsv = FloatArray(3)

    private val frame = Choreographer.FrameCallback { now -> onFrame(now) }

    init {
        isClickable = true
        contentDescription = "Stack game. Tap to drop each block."
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (running || !isAttachedToWindow) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val settled: Boolean
        get() = debris.isEmpty() && kotlin.math.abs(camera - game.height * FLOOR) < 0.5f

    private fun onFrame(now: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000_000f
        lastFrame = now
        game.step(dt)
        camera += (game.height * FLOOR - camera) * (1f - kotlin.math.exp(-8f * dt))
        debris.forEach {
            it.speed += 900f * dt
            it.drop += it.speed * dt
        }
        debris.removeAll { it.drop > 600f }
        invalidate()
        if (game.state == StackGame.State.PLAYING || !settled || SystemClock.uptimeMillis() < perfectUntil) {
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            running = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        val before = game.state
        val wasPlaying = before == StackGame.State.PLAYING
        val wasDead = before == StackGame.State.DEAD
        game.tap()
        onTap(before)
        if (wasPlaying) {
            game.lastCut?.let { debris += Debris(it.block, it.level, 0f, 0f) }
            if (game.lastPerfect) perfectUntil = SystemClock.uptimeMillis() + 700L
        }
        if (wasDead) {
            debris.clear()
            camera = 0f
        }
        if (game.state == StackGame.State.WON && !reported) {
            reported = true
            // Let the last floor land, then unlock (which removes this window).
            postDelayed({ onWin() }, 700L)
        }
        start()
        invalidate()
        return true
    }

    private fun color(level: Int, value: Float, saturation: Float): Int {
        hsv[0] = (HUE_START + level * HUE_STEP) % 360f
        hsv[1] = saturation
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val level = game.height
        // Sky: soft gradient tinted by the current height.
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            color(level + 6, 0.98f, 0.18f), color(level, 0.82f, 0.32f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val scale = w / 330f
        val cx = w / 2
        val baseY = h * 0.68f + camera * scale

        fun sx(x: Float, z: Float) = cx + (x - z) * COS30 * scale
        fun sy(x: Float, y: Float, z: Float) = baseY - ((x + z) * SIN30 + y) * scale

        fun slab(block: StackGame.Block, bottom: Float, top: Float, tint: Int, alpha: Int = 255) {
            val x0 = block.x - block.width / 2
            val x1 = block.x + block.width / 2
            val z0 = block.z - block.depth / 2
            val z1 = block.z + block.depth / 2
            // Lower-left side (x = x0).
            path.reset()
            path.moveTo(sx(x0, z0), sy(x0, top, z0))
            path.lineTo(sx(x0, z1), sy(x0, top, z1))
            path.lineTo(sx(x0, z1), sy(x0, bottom, z1))
            path.lineTo(sx(x0, z0), sy(x0, bottom, z0))
            path.close()
            paint.color = color(tint, 0.78f, 0.55f)
            paint.alpha = alpha
            canvas.drawPath(path, paint)
            // Lower-right side (z = z0).
            path.reset()
            path.moveTo(sx(x0, z0), sy(x0, top, z0))
            path.lineTo(sx(x1, z0), sy(x1, top, z0))
            path.lineTo(sx(x1, z0), sy(x1, bottom, z0))
            path.lineTo(sx(x0, z0), sy(x0, bottom, z0))
            path.close()
            paint.color = color(tint, 0.64f, 0.6f)
            paint.alpha = alpha
            canvas.drawPath(path, paint)
            // Top.
            path.reset()
            path.moveTo(sx(x0, z0), sy(x0, top, z0))
            path.lineTo(sx(x1, z0), sy(x1, top, z0))
            path.lineTo(sx(x1, z1), sy(x1, top, z1))
            path.lineTo(sx(x0, z1), sy(x0, top, z1))
            path.close()
            paint.color = color(tint, 0.97f, 0.42f)
            paint.alpha = alpha
            canvas.drawPath(path, paint)
        }

        // The base reaches down off-screen like a pillar.
        val blocks = game.blocks
        val first = (level - 22).coerceAtLeast(0)
        for (i in first until blocks.size) {
            val bottom = if (i == 0) -FLOOR * 30 else (i - 1) * FLOOR
            slab(blocks[i], bottom, i * FLOOR, i)
        }
        if (game.state == StackGame.State.PLAYING) {
            slab(game.moving, level * FLOOR, (level + 1) * FLOOR, level + 1)
        }
        for (piece in debris) {
            val alpha = (255 * (1f - piece.drop / 600f)).toInt().coerceIn(0, 255)
            slab(piece.block, (piece.level - 1) * FLOOR - piece.drop, piece.level * FLOOR - piece.drop,
                piece.level, alpha)
        }
        paint.alpha = 255

        text.textSize = w * 0.16f
        canvas.drawText(level.toString(), cx, h * 0.17f, text)
        text.textSize = w * 0.045f
        canvas.drawText("of ${StackGame.TARGET}", cx, h * 0.17f + w * 0.07f, text)
        val message = when (game.state) {
            StackGame.State.READY -> "Tap to start"
            StackGame.State.DEAD -> "Fell off! Tap to retry"
            StackGame.State.WON -> "Unlocked!"
            StackGame.State.PLAYING -> if (SystemClock.uptimeMillis() < perfectUntil) {
                if (game.combo > 1) "Perfect ×${game.combo}" else "Perfect!"
            } else null
        }
        if (message != null) {
            text.textSize = w * 0.07f
            canvas.drawText(message, cx, h * 0.92f, text)
        }
    }
}
