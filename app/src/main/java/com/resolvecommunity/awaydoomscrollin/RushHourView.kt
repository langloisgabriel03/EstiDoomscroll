package com.resolvecommunity.awaydoomscrollin

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Rush Hour: free the red car; one solved board unlocks. */
internal class RushHourChallenge : UnlockChallenge {
    override val title = "Rush Hour: free the red car"
    private var board: RushHourView? = null

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val box = ui.column()
        val stats = ui.text("", 13f, ui.dim)
        val view = RushHourView(ui.context, onSolved) { moves, par ->
            stats.text = "Moves: $moves  ·  Best possible: $par"
        }
        board = view
        val (layout, par) = RushHourLevels.random()
        view.load(layout, par)

        val buttons = LinearLayout(ui.context).apply { orientation = LinearLayout.HORIZONTAL }
        val half = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        buttons.addView(ui.button("Reset", filled = false) { view.restart() },
            LinearLayout.LayoutParams(half).apply { marginEnd = ui.dp(6) })
        buttons.addView(ui.button("New puzzle", filled = false) {
            val (next, nextPar) = RushHourLevels.random()
            view.load(next, nextPar)
        }, LinearLayout.LayoutParams(half).apply { marginStart = ui.dp(6) })

        box.addView(stats, ui.params())
        box.addView(view, ui.params(top = ui.dp(8)))
        box.addView(ui.text("Drag cars and trucks along their lanes", 13f, ui.dim),
            ui.params(top = ui.dp(6)))
        box.addView(buttons, ui.params(top = ui.dp(10)))
        return box
    }

    override fun dispose() {
        board?.stop()
        board = null
    }
}

/**
 * Draws a [RushHourBoard] in the style of the board game: a dark plastic tray
 * with recessed slots and top-down vehicles shaded to look raised (drop shadow,
 * rounded body gradient, glass, lights, wheels). Vehicles are dragged along
 * their lane, stop at obstacles and snap to a slot on release.
 */
internal class RushHourView(
    context: Context,
    private val onWin: () -> Unit,
    private val onMovesChanged: (moves: Int, par: Int) -> Unit
) : View(context) {

    private var layout = ""
    private var par = 0
    private var board: RushHourBoard? = null

    private var dragged: RushHourBoard.Vehicle? = null
    private var dragRange = 0..0
    private var dragStart = 0f
    private var dragOffset = 0f
    private var exitProgress = 0f
    private var exitAnimator: ValueAnimator? = null
    private var won = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()

    init {
        isClickable = true
        contentDescription = "Rush Hour board. Drag vehicles to free the red car."
    }

    fun load(newLayout: String, newPar: Int) {
        layout = newLayout
        par = newPar
        restart()
    }

    fun restart() {
        if (won) return
        board = RushHourBoard(layout)
        dragged = null
        dragOffset = 0f
        notifyMoves()
        invalidate()
    }

    fun stop() {
        exitAnimator?.cancel()
        exitAnimator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun notifyMoves() = onMovesChanged(board?.moves ?: 0, par)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width)
    }

    // Geometry: the tray border, then six slots per side.
    private val border: Float get() = width * 0.07f
    private val cell: Float get() = (width - 2 * border) / RushHourBoard.SIZE

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = board ?: return false
        if (won) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val col = ((event.x - border) / cell).toInt()
                val row = ((event.y - border) / cell).toInt()
                if (event.x < border || event.y < border || col >= RushHourBoard.SIZE ||
                    row >= RushHourBoard.SIZE) return true
                val vehicle = current.vehicleAt(row, col) ?: return true
                // Keep the wait screen's scroll view from stealing the drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                dragged = vehicle
                dragRange = current.range(vehicle)
                dragStart = if (vehicle.horizontal) event.x else event.y
                dragOffset = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val vehicle = dragged ?: return true
                val raw = (if (vehicle.horizontal) event.x else event.y) - dragStart
                dragOffset = raw.coerceIn((dragRange.first - vehicle.position) * cell,
                    (dragRange.last - vehicle.position) * cell)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val vehicle = dragged ?: return true
                val target = (vehicle.position + (dragOffset / cell).roundToInt())
                    .coerceIn(dragRange.first, dragRange.last)
                if (current.moveTo(vehicle, target)) notifyMoves()
                dragged = null
                dragOffset = 0f
                invalidate()
                if (current.isSolved) driveOut()
            }
        }
        return true
    }

    /** The red car rolls out through the exit, then the board reports the win. */
    private fun driveOut() {
        won = true
        exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550L
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                exitProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // After this frame: unlocking removes the window this view is in.
                    post { onWin() }
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = board ?: return
        drawTray(canvas)
        // The dragged vehicle is drawn last so it passes over its neighbours' shadows.
        current.vehicles.filter { it !== dragged }.forEach { drawVehicle(canvas, it, 0f) }
        dragged?.let { drawVehicle(canvas, it, dragOffset) }
    }

    private fun drawTray(canvas: Canvas) {
        val w = width.toFloat()
        val radius = w * 0.05f
        // Tray body with a soft vertical sheen.
        paint.shader = LinearGradient(0f, 0f, 0f, w,
            Color.rgb(70, 76, 88), Color.rgb(30, 33, 40), Shader.TileMode.CLAMP)
        rect.set(0f, 0f, w, w)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        stroke.strokeWidth = w * 0.006f
        stroke.color = Color.argb(70, 255, 255, 255)
        rect.inset(stroke.strokeWidth, stroke.strokeWidth)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        // Exit gap on the right of the exit row, cut through the tray wall.
        val exitTop = border + RushHourBoard.EXIT_ROW * cell
        paint.color = Color.rgb(48, 53, 62)
        canvas.drawRect(w - border - 2f, exitTop + cell * 0.06f, w, exitTop + cell * 0.94f, paint)
        paint.color = Color.rgb(239, 68, 68)
        path.reset()
        val ax = w - border * 0.5f
        val ay = exitTop + cell / 2
        val arrow = border * 0.28f
        path.moveTo(ax - arrow * 0.6f, ay - arrow)
        path.lineTo(ax + arrow * 0.7f, ay)
        path.lineTo(ax - arrow * 0.6f, ay + arrow)
        path.close()
        canvas.drawPath(path, paint)

        // Floor and recessed slots.
        paint.color = Color.rgb(48, 53, 62)
        rect.set(border - 4f, border - 4f, w - border + 4f, w - border + 4f)
        canvas.drawRoundRect(rect, cell * 0.12f, cell * 0.12f, paint)
        val inset = cell * 0.06f
        for (r in 0 until RushHourBoard.SIZE) for (c in 0 until RushHourBoard.SIZE) {
            val left = border + c * cell + inset
            val top = border + r * cell + inset
            rect.set(left, top, left + cell - 2 * inset, top + cell - 2 * inset)
            paint.color = Color.rgb(36, 40, 47)
            canvas.drawRoundRect(rect, cell * 0.1f, cell * 0.1f, paint)
            // Lower lip catches the light: the slot reads as pressed in.
            stroke.strokeWidth = cell * 0.025f
            stroke.color = Color.argb(45, 255, 255, 255)
            canvas.drawLine(rect.left + cell * 0.1f, rect.bottom, rect.right - cell * 0.1f, rect.bottom, stroke)
        }
    }

    private fun colorFor(vehicle: RushHourBoard.Vehicle): Int {
        if (vehicle.isTarget) return Color.rgb(220, 38, 38)
        val cars = intArrayOf(
            Color.rgb(132, 204, 22), Color.rgb(249, 115, 22), Color.rgb(56, 189, 248),
            Color.rgb(236, 72, 153), Color.rgb(139, 92, 246), Color.rgb(21, 128, 61),
            Color.rgb(214, 180, 120), Color.rgb(250, 204, 21), Color.rgb(120, 113, 108),
            Color.rgb(13, 148, 136), Color.rgb(244, 114, 182), Color.rgb(101, 163, 13)
        )
        val trucks = intArrayOf(
            Color.rgb(234, 179, 8), Color.rgb(124, 58, 237), Color.rgb(37, 99, 235),
            Color.rgb(15, 118, 110)
        )
        val palette = if (vehicle.length >= 3) trucks else cars
        return palette[(vehicle.id - 'B').coerceAtLeast(0) % palette.size]
    }

    private fun drawVehicle(canvas: Canvas, vehicle: RushHourBoard.Vehicle, offset: Float) {
        var x = border + vehicle.col * cell
        var y = border + vehicle.row * cell
        if (vehicle.horizontal) x += offset else y += offset
        if (vehicle.isTarget && won) x += exitProgress * cell * 3f
        val pad = cell * 0.09f
        val spanLong = vehicle.length * cell - 2 * pad
        val spanWide = cell - 2 * pad
        val cx = x + if (vehicle.horizontal) vehicle.length * cell / 2 else cell / 2
        val cy = y + if (vehicle.horizontal) cell / 2 else vehicle.length * cell / 2
        val lifted = vehicle === dragged
        val color = colorFor(vehicle)

        // Shadow in screen space (light from the top left); larger when lifted.
        val drop = cell * if (lifted) 0.13f else 0.07f
        val shadowW = if (vehicle.horizontal) spanLong else spanWide
        val shadowH = if (vehicle.horizontal) spanWide else spanLong
        rect.set(cx - shadowW / 2 + drop * 0.6f, cy - shadowH / 2 + drop,
            cx + shadowW / 2 + drop * 0.6f, cy + shadowH / 2 + drop)
        paint.shader = null
        paint.color = Color.argb(if (lifted) 110 else 90, 0, 0, 0)
        canvas.drawRoundRect(rect, cell * 0.24f, cell * 0.24f, paint)

        // Draw every vehicle as horizontal, nose to +x; vertical ones are turned
        // to point up so their lit side stays on the left like the others.
        canvas.save()
        canvas.translate(cx, cy)
        if (!vehicle.horizontal) canvas.rotate(-90f)
        if (lifted) canvas.scale(1.03f, 1.03f)
        val half = spanLong / 2
        val side = spanWide / 2
        if (vehicle.length >= 3) drawTruck(canvas, half, side, color) else drawCar(canvas, half, side, color, vehicle.isTarget)
        canvas.restore()
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).coerceIn(0f, 255f).toInt(),
        (Color.green(color) * factor).coerceIn(0f, 255f).toInt(),
        (Color.blue(color) * factor).coerceIn(0f, 255f).toInt())

    private fun tint(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt())

    private fun drawWheels(canvas: Canvas, half: Float, side: Float, at: FloatArray) {
        paint.shader = null
        paint.color = Color.rgb(17, 17, 20)
        val wheelLong = cell * 0.2f
        val wheelOut = cell * 0.035f
        for (fraction in at) {
            val wx = -half + fraction * 2 * half
            rect.set(wx - wheelLong / 2, -side - wheelOut, wx + wheelLong / 2, -side + cell * 0.08f)
            canvas.drawRoundRect(rect, cell * 0.04f, cell * 0.04f, paint)
            rect.set(wx - wheelLong / 2, side - cell * 0.08f, wx + wheelLong / 2, side + wheelOut)
            canvas.drawRoundRect(rect, cell * 0.04f, cell * 0.04f, paint)
        }
    }

    /** Body: rounded, lit from one side, darker toward the other, with a rim highlight. */
    private fun drawBody(canvas: Canvas, left: Float, right: Float, side: Float, color: Int, radius: Float) {
        paint.shader = LinearGradient(0f, -side, 0f, side,
            intArrayOf(tint(color, 0.35f), color, shade(color, 0.62f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        rect.set(left, -side, right, side)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        stroke.shader = null
        stroke.strokeWidth = cell * 0.02f
        stroke.color = shade(color, 0.5f)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        stroke.color = Color.argb(90, 255, 255, 255)
        canvas.drawLine(left + radius, -side + stroke.strokeWidth * 1.5f,
            right - radius, -side + stroke.strokeWidth * 1.5f, stroke)
    }

    private fun drawGlass(canvas: Canvas, left: Float, right: Float, halfWidth: Float) {
        paint.shader = LinearGradient(left, -halfWidth, right, halfWidth,
            Color.rgb(148, 197, 233), Color.rgb(30, 41, 59), Shader.TileMode.CLAMP)
        rect.set(left, -halfWidth, right, halfWidth)
        canvas.drawRoundRect(rect, cell * 0.06f, cell * 0.06f, paint)
        paint.shader = null
        // A streak of reflected sky.
        stroke.strokeWidth = cell * 0.025f
        stroke.color = Color.argb(120, 255, 255, 255)
        canvas.drawLine(left + (right - left) * 0.3f, -halfWidth * 0.7f,
            left + (right - left) * 0.6f, halfWidth * 0.2f, stroke)
    }

    private fun drawLights(canvas: Canvas, half: Float, side: Float) {
        val r = cell * 0.05f
        paint.shader = RadialGradient(0f, 0f, r, Color.WHITE, Color.rgb(253, 224, 71), Shader.TileMode.CLAMP)
        for (sign in floatArrayOf(-1f, 1f)) {
            canvas.save()
            canvas.translate(half - r * 1.4f, sign * side * 0.62f)
            canvas.drawCircle(0f, 0f, r, paint)
            canvas.restore()
        }
        paint.shader = null
        paint.color = Color.rgb(185, 28, 28)
        for (sign in floatArrayOf(-1f, 1f)) {
            rect.set(-half + cell * 0.02f, sign * side * 0.62f - r, -half + cell * 0.07f, sign * side * 0.62f + r)
            canvas.drawRoundRect(rect, r * 0.4f, r * 0.4f, paint)
        }
    }

    private fun drawCar(canvas: Canvas, half: Float, side: Float, color: Int, target: Boolean) {
        drawWheels(canvas, half, side, floatArrayOf(0.2f, 0.8f))
        drawBody(canvas, -half, half, side, color, cell * 0.28f)
        if (target) {
            // Racing stripe on the hero car.
            paint.shader = null
            paint.color = Color.argb(150, 255, 255, 255)
            rect.set(-half + cell * 0.08f, -side * 0.12f, half - cell * 0.08f, side * 0.12f)
            canvas.drawRect(rect, paint)
        }
        val length = 2 * half
        drawGlass(canvas, -half + length * 0.22f, -half + length * 0.34f, side * 0.62f) // rear window
        // Roof, slightly raised: a lighter panel between the windows.
        paint.shader = LinearGradient(0f, -side, 0f, side, tint(color, 0.45f), shade(color, 0.8f), Shader.TileMode.CLAMP)
        rect.set(-half + length * 0.35f, -side * 0.66f, -half + length * 0.6f, side * 0.66f)
        canvas.drawRoundRect(rect, cell * 0.08f, cell * 0.08f, paint)
        paint.shader = null
        drawGlass(canvas, -half + length * 0.61f, -half + length * 0.76f, side * 0.68f) // windshield
        drawLights(canvas, half, side)
    }

    private fun drawTruck(canvas: Canvas, half: Float, side: Float, color: Int) {
        drawWheels(canvas, half, side, floatArrayOf(0.12f, 0.3f, 0.86f))
        val length = 2 * half
        val cabStart = -half + length * 0.74f
        // Cargo box: a lighter, flat-topped container with ribs.
        drawBody(canvas, -half, cabStart - cell * 0.03f, side, tint(color, 0.15f), cell * 0.1f)
        stroke.strokeWidth = cell * 0.018f
        stroke.color = Color.argb(70, 0, 0, 0)
        var rib = -half + length * 0.08f
        while (rib < cabStart - cell * 0.1f) {
            canvas.drawLine(rib, -side * 0.82f, rib, side * 0.82f, stroke)
            rib += length * 0.07f
        }
        // Cab with its windshield.
        drawBody(canvas, cabStart, half, side * 0.92f, color, cell * 0.22f)
        drawGlass(canvas, cabStart + (half - cabStart) * 0.35f, cabStart + (half - cabStart) * 0.7f, side * 0.66f)
        drawLights(canvas, half, side * 0.92f)
    }
}
