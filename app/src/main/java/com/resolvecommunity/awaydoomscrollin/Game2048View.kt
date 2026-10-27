package com.resolvecommunity.awaydoomscrollin

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

/** 2048: reach the target tile to unlock. */
internal class Game2048Challenge : UnlockChallenge {
    override val title = "2048: reach ${Game2048.TARGET}"
    private var board: Game2048View? = null

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val box = ui.column()
        val stats = ui.text("", 13f, ui.dim)
        val view = Game2048View(ui.context, Game2048(), onSolved) { score, best ->
            stats.text = "Score: $score  ·  Best tile: $best / ${Game2048.TARGET}"
        }
        board = view
        box.addView(stats, ui.params())
        box.addView(view, ui.params(top = ui.dp(8)))
        box.addView(ui.text("Swipe to slide the tiles", 13f, ui.dim), ui.params(top = ui.dp(6)))
        box.addView(ui.button("New game", filled = false) { view.newGame() }, ui.params(top = ui.dp(10)))
        return box
    }

    override fun dispose() {
        board?.stop()
        board = null
    }
}

/**
 * Draws [Game2048] like the original: a warm board with raised, rounded
 * tiles (darker base edge, lit top face) in the classic colours. Swipes move
 * the tiles; new and merged tiles pop in.
 */
internal class Game2048View(
    context: Context,
    private val game: Game2048,
    private val onWin: () -> Unit,
    private val onScore: (score: Int, best: Int) -> Unit
) : View(context) {
    companion object {
        private const val SLIDE_MS = 100L
        private const val POP_MS = 160L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()
    private var downX = 0f
    private var downY = 0f
    private var popped = BooleanArray(Game2048.SIZE * Game2048.SIZE)
    private var pop = 1f
    /** 0..1 while tiles glide to their new cells; 1 when the board is at rest. */
    private var slide = 1f
    private var sliding: List<Game2048.TileMove> = emptyList()
    private var animator: ValueAnimator? = null
    private var reported = false

    init {
        isClickable = true
        contentDescription = "2048 board. Swipe to slide the tiles."
        report()
    }

    fun newGame() {
        if (reported) return
        game.restart()
        sliding = emptyList()
        popped = BooleanArray(popped.size) { game.cells[it] != 0 }
        animate(withSlide = false)
        report()
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun report() = onScore(game.score, game.bestTile)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the wait screen's scroll view from taking vertical swipes.
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (reported || game.over) return true
                val dx = event.x - downX
                val dy = event.y - downY
                val min = 24 * resources.displayMetrics.density
                if (maxOf(abs(dx), abs(dy)) < min) return true
                val direction = when {
                    abs(dx) > abs(dy) && dx > 0 -> Game2048.Direction.RIGHT
                    abs(dx) > abs(dy) -> Game2048.Direction.LEFT
                    dy > 0 -> Game2048.Direction.DOWN
                    else -> Game2048.Direction.UP
                }
                if (game.move(direction)) {
                    // Glide every tile, then pop the merged ones and the new one.
                    sliding = game.lastMoves
                    popped = BooleanArray(popped.size) { it in game.lastMerged || it == game.lastSpawn }
                    animate(withSlide = true)
                    report()
                    if (game.won) {
                        reported = true
                        // Let the winning tile show, then unlock (which removes this window).
                        postDelayed({ onWin() }, 450L)
                    }
                }
            }
        }
        return true
    }

    private fun animate(withSlide: Boolean) {
        animator?.cancel()
        val slideShare = if (withSlide) SLIDE_MS.toFloat() / (SLIDE_MS + POP_MS) else 0f
        val overshoot = OvershootInterpolator(2f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (if (withSlide) SLIDE_MS else 0L) + POP_MS
            interpolator = null
            addUpdateListener {
                val t = it.animatedValue as Float
                if (t < slideShare) {
                    slide = DecelerateInterpolator().getInterpolation(t / slideShare)
                    pop = 0f
                } else {
                    slide = 1f
                    pop = 0.4f + 0.6f * overshoot.getInterpolation((t - slideShare) / (1f - slideShare))
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    slide = 1f
                    pop = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    private fun drawTile(canvas: Canvas, value: Int, left: Float, top: Float, cell: Float,
        radius: Float, depth: Float, scale: Float) {
        if (scale <= 0f) return
        canvas.save()
        canvas.scale(scale, scale, left + cell / 2, top + cell / 2)
        val color = tileColor(value)
        // Base edge: the tile's side, seen below its face.
        paint.shader = null
        paint.color = shade(color, 0.72f)
        rect.set(left, top + depth, left + cell, top + cell)
        canvas.drawRoundRect(rect, radius, radius, paint)
        // Face: lit from above (opaque paint under the shader).
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, top, 0f, top + cell - depth,
            color, shade(color, 0.92f), Shader.TileMode.CLAMP)
        rect.set(left, top, left + cell, top + cell - depth)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        paint.color = Color.argb(70, 255, 255, 255)
        rect.set(left + radius, top + depth * 0.4f, left + cell - radius, top + depth * 1.2f)
        canvas.drawRoundRect(rect, depth, depth, paint)

        text.color = if (value <= 4) Color.rgb(119, 110, 101) else Color.WHITE
        text.textSize = cell * when {
            value < 100 -> 0.45f
            value < 1000 -> 0.38f
            else -> 0.3f
        }
        val baseline = top + (cell - depth) / 2 - (text.descent() + text.ascent()) / 2
        canvas.drawText(value.toString(), left + cell / 2, baseline, text)
        canvas.restore()
    }

    private fun tileColor(value: Int): Int = when (value) {
        2 -> Color.rgb(238, 228, 218)
        4 -> Color.rgb(237, 224, 200)
        8 -> Color.rgb(242, 177, 121)
        16 -> Color.rgb(245, 149, 99)
        32 -> Color.rgb(246, 124, 95)
        64 -> Color.rgb(246, 94, 59)
        128 -> Color.rgb(237, 207, 114)
        256 -> Color.rgb(237, 204, 97)
        512 -> Color.rgb(237, 200, 80)
        1024 -> Color.rgb(237, 197, 63)
        else -> Color.rgb(237, 194, 46)
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(),
        (Color.blue(color) * factor).toInt())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val gap = w * 0.03f
        val cell = (w - gap * (Game2048.SIZE + 1)) / Game2048.SIZE
        val radius = cell * 0.1f
        val depth = cell * 0.05f

        // Board: warm brown with a soft vertical sheen. The paint is reset to
        // opaque first: a shader is drawn with the paint's alpha.
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, 0f, 0f, w,
            Color.rgb(196, 182, 168), Color.rgb(170, 155, 141), Shader.TileMode.CLAMP)
        rect.set(0f, 0f, w, w)
        canvas.drawRoundRect(rect, w * 0.03f, w * 0.03f, paint)
        paint.shader = null

        fun cellLeft(index: Int) = gap + (index % Game2048.SIZE) * (cell + gap)
        fun cellTop(index: Int) = gap + (index / Game2048.SIZE) * (cell + gap)

        // Empty slots, slightly sunken.
        paint.color = Color.rgb(205, 193, 180)
        for (index in game.cells.indices) {
            rect.set(cellLeft(index), cellTop(index), cellLeft(index) + cell, cellTop(index) + cell)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        if (slide < 1f) {
            // Mid-move: each tile glides from its old cell with its old value.
            for (move in sliding) {
                val left = cellLeft(move.from) + (cellLeft(move.to) - cellLeft(move.from)) * slide
                val top = cellTop(move.from) + (cellTop(move.to) - cellTop(move.from)) * slide
                drawTile(canvas, move.value, left, top, cell, radius, depth, 1f)
            }
        } else {
            for (index in game.cells.indices) {
                val value = game.cells[index]
                if (value == 0) continue
                drawTile(canvas, value, cellLeft(index), cellTop(index), cell, radius, depth,
                    if (popped[index]) pop else 1f)
            }
        }

        val message = when {
            reported -> "${Game2048.TARGET}! Unlocked"
            game.over -> "No moves left · tap New game"
            else -> null
        }
        if (message != null) {
            paint.color = Color.argb(150, 250, 248, 239)
            rect.set(0f, 0f, w, w)
            canvas.drawRoundRect(rect, w * 0.03f, w * 0.03f, paint)
            text.color = Color.rgb(119, 110, 101)
            text.textSize = w * 0.065f
            canvas.drawText(message, w / 2, w / 2, text)
        }
    }
}
