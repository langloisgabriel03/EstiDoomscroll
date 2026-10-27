package com.resolvecommunity.awaydoomscrollin

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
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Paper Toss: flick crumpled paper into the bin across the office, aiming
 * into the fan's wind. [PaperTossGame.STREAK_TO_WIN] in a row unlocks. The
 * wind changes every throw, so there is no time record.
 */
internal class PaperTossChallenge : UnlockChallenge {
    override val title = "Paper Toss: ${PaperTossGame.STREAK_TO_WIN} in a row"
    private var view: PaperTossView? = null

    override fun createView(ui: ChallengeUi, onSolved: () -> Unit): View {
        val board = PaperTossView(ui.context, PaperTossGame(), onSolved)
        view = board
        val box = ui.column()
        box.addView(board, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(440)))
        box.addView(ui.text("Flick the paper up toward the bin. Aim into the wind: a miss resets the streak.",
            13f, ui.dim), ui.params(top = ui.dp(8)))
        return box
    }

    override fun dispose() {
        view?.stop()
        view = null
    }
}

/**
 * Draws [PaperTossGame] in perspective from the player's desk: carpet, walls,
 * a window with blinds, a whiteboard and clock, ceiling lights, a filing
 * cabinet with a desk fan blowing the wind, and a wire-mesh bin. The paper
 * ball shrinks with distance, spins, and casts a shadow on the floor.
 */
internal class PaperTossView(
    context: Context,
    private val game: PaperTossGame,
    private val onWin: () -> Unit
) : View(context) {
    private companion object {
        // Camera, in the game's metres: eye height and how far behind the desk.
        const val CAM_Y = 1.3f
        const val CAM_Z = -0.6f
        const val ROOM_HALF_WIDTH = 2.2f
        const val ROOM_HEIGHT = 2.7f
        const val BACK_Z = 7.5f
        const val DESK_Y = 0.94f
        const val DESK_FAR_Z = 0.62f
        const val BIN_BOTTOM_RADIUS = 0.16f
        const val CABINET_X = -1.55f
        const val CABINET_Z = 4.9f
        const val CABINET_HEIGHT = 0.72f
        const val RESULT_MS = 900L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val rect = RectF()

    private var focal = 1f
    private var horizon = 0f
    private var centerX = 0f

    private var running = false
    private var lastFrame = 0L
    private var fanAngle = 0f
    private var spin = 0f
    private var resultUntil = 0L
    private var reported = false
    private var downX = 0f
    private var downY = 0f
    private val frame = Choreographer.FrameCallback { now -> onFrame(now) }

    init {
        isClickable = true
        contentDescription = "Paper Toss. Flick up to throw the paper into the bin."
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

    /** Runs only while the ball flies and its result shows; the fan turns meanwhile. */
    private fun onFrame(now: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now
        fanAngle = (fanAngle + dt * (200f + abs(game.wind) * 500f)) % 360f
        if (game.state == PaperTossGame.State.FLYING) {
            game.step(dt)
            spin = (spin + dt * 540f) % 360f
            if (game.state != PaperTossGame.State.FLYING) onLanded()
        }
        if (resultUntil != 0L && SystemClock.uptimeMillis() >= resultUntil) {
            resultUntil = 0L
            game.next()
        }
        invalidate()
        if (game.state == PaperTossGame.State.FLYING || resultUntil != 0L) {
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            running = false
        }
    }

    private fun onLanded() {
        if (game.state == PaperTossGame.State.WON) {
            if (!reported) {
                reported = true
                // Let the last basket show, then unlock (which removes this window).
                postDelayed({ onWin() }, 800L)
            }
        } else {
            resultUntil = SystemClock.uptimeMillis() + RESULT_MS
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the wait screen's scroll view from taking the flick.
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val up = downY - event.y
                if (up < 40 * resources.displayMetrics.density) return true
                // The flick's direction aims the throw, softened so small
                // wobbles in the swipe don't send the ball into the wall.
                val degrees = Math.toDegrees(atan2(dx, up).toDouble()).toFloat() * 0.6f
                if (game.throwAt(degrees)) {
                    spin = 0f
                    start()
                }
            }
        }
        return true
    }

    // --- Projection -------------------------------------------------------

    private fun sx(x: Float, z: Float) = centerX + x * focal / (z - CAM_Z)
    private fun sy(y: Float, z: Float) = horizon + (CAM_Y - y) * focal / (z - CAM_Z)
    private fun size(metres: Float, z: Float) = metres * focal / (z - CAM_Z)

    /** A quad of four world points (x, y, z), in order. */
    private fun quad(vararg p: Float): Path {
        path.reset()
        for (i in p.indices step 3) {
            val x = sx(p[i], p[i + 2])
            val y = sy(p[i + 1], p[i + 2])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun fill(color: Int) {
        paint.shader = null
        paint.color = color
    }

    /** Paint for a gradient; opaque first, since a shader draws with the paint's alpha. */
    private fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int) {
        paint.color = Color.WHITE
        paint.shader = LinearGradient(x0, y0, x1, y1, from, to, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        focal = w * 0.95f
        horizon = h * 0.3f
        centerX = w / 2

        drawRoom(canvas, w, h)
        drawCabinetAndFan(canvas)

        val ballInside = game.state == PaperTossGame.State.SCORED || game.state == PaperTossGame.State.WON
        val ballBehind = game.z > PaperTossGame.BIN_Z
        drawBin(canvas, front = false)
        if (ballInside || ballBehind) drawBall(canvas)
        drawBin(canvas, front = true)
        drawDesk(canvas, w, h)
        if (!ballInside && !ballBehind) drawBall(canvas)
        drawHud(canvas, w)
    }

    private fun drawRoom(canvas: Canvas, w: Float, h: Float) {
        val wx = ROOM_HALF_WIDTH
        val near = 0.0f
        // Ceiling and its light panels.
        canvas.drawColor(Color.rgb(236, 236, 230))
        fill(Color.rgb(246, 248, 240))
        for (z in listOf(1.6f, 3.6f, 5.6f)) {
            canvas.drawPath(quad(-0.5f, ROOM_HEIGHT, z, 0.5f, ROOM_HEIGHT, z,
                0.5f, ROOM_HEIGHT, z + 0.9f, -0.5f, ROOM_HEIGHT, z + 0.9f), paint)
        }
        stroke.color = Color.rgb(205, 205, 198)
        stroke.strokeWidth = 2f
        for (z in listOf(1.6f, 3.6f, 5.6f)) {
            canvas.drawPath(quad(-0.5f, ROOM_HEIGHT, z, 0.5f, ROOM_HEIGHT, z,
                0.5f, ROOM_HEIGHT, z + 0.9f, -0.5f, ROOM_HEIGHT, z + 0.9f), stroke)
        }

        // Side walls, shaded toward the camera.
        gradient(0f, 0f, sx(-wx, BACK_Z), 0f, Color.rgb(196, 190, 172), Color.rgb(222, 217, 200))
        canvas.drawPath(quad(-wx, 0f, near, -wx, ROOM_HEIGHT, near,
            -wx, ROOM_HEIGHT, BACK_Z, -wx, 0f, BACK_Z), paint)
        gradient(w, 0f, sx(wx, BACK_Z), 0f, Color.rgb(196, 190, 172), Color.rgb(222, 217, 200))
        canvas.drawPath(quad(wx, 0f, near, wx, ROOM_HEIGHT, near,
            wx, ROOM_HEIGHT, BACK_Z, wx, 0f, BACK_Z), paint)

        // Back wall.
        fill(Color.rgb(230, 225, 208))
        canvas.drawPath(quad(-wx, 0f, BACK_Z, wx, 0f, BACK_Z,
            wx, ROOM_HEIGHT, BACK_Z, -wx, ROOM_HEIGHT, BACK_Z), paint)

        // Window with sky and half-open blinds.
        val wl = sx(-1.6f, BACK_Z); val wr = sx(-0.3f, BACK_Z)
        val wt = sy(2.15f, BACK_Z); val wb = sy(1.05f, BACK_Z)
        fill(Color.rgb(250, 250, 250))
        rect.set(wl - 5f, wt - 5f, wr + 5f, wb + 5f)
        canvas.drawRect(rect, paint)
        gradient(0f, wt, 0f, wb, Color.rgb(120, 180, 235), Color.rgb(200, 228, 248))
        rect.set(wl, wt, wr, wb)
        canvas.drawRect(rect, paint)
        // A far skyline.
        fill(Color.rgb(150, 175, 200))
        val bw = (wr - wl) / 7
        for (i in 0 until 7) {
            val top = wb - (wb - wt) * (0.18f + ((i * 37) % 5) * 0.06f)
            rect.set(wl + i * bw, top, wl + (i + 1) * bw - 2f, wb)
            canvas.drawRect(rect, paint)
        }
        fill(Color.argb(200, 245, 245, 240))
        val slat = (wb - wt) * 0.45f / 9
        for (i in 0 until 9) {
            rect.set(wl, wt + i * slat * 1.0f, wr, wt + i * slat + slat * 0.7f)
            canvas.drawRect(rect, paint)
        }
        fill(Color.rgb(250, 250, 250))
        canvas.drawRect((wl + wr) / 2 - 2f, wt, (wl + wr) / 2 + 2f, wb, paint)

        // Whiteboard with a few scribbles.
        val bl = sx(0.35f, BACK_Z); val br = sx(1.75f, BACK_Z)
        val bt = sy(2.0f, BACK_Z); val bb = sy(1.1f, BACK_Z)
        fill(Color.rgb(160, 160, 165))
        rect.set(bl - 4f, bt - 4f, br + 4f, bb + 4f)
        canvas.drawRect(rect, paint)
        fill(Color.WHITE)
        rect.set(bl, bt, br, bb)
        canvas.drawRect(rect, paint)
        stroke.strokeWidth = 2.5f
        stroke.color = Color.rgb(60, 110, 200)
        val lh = (bb - bt) / 6
        canvas.drawLine(bl + (br - bl) * 0.1f, bt + lh, bl + (br - bl) * 0.55f, bt + lh, stroke)
        canvas.drawLine(bl + (br - bl) * 0.1f, bt + lh * 2.2f, bl + (br - bl) * 0.75f, bt + lh * 2.2f, stroke)
        stroke.color = Color.rgb(205, 60, 60)
        canvas.drawCircle(bl + (br - bl) * 0.7f, bt + lh * 4f, lh * 0.9f, stroke)
        canvas.drawLine(bl + (br - bl) * 0.1f, bt + lh * 4.2f, bl + (br - bl) * 0.45f, bt + lh * 3.6f, stroke)
        fill(Color.rgb(150, 150, 155))
        rect.set(bl, bb, br, bb + 5f)
        canvas.drawRect(rect, paint)

        // Wall clock.
        val clockX = sx(-0.95f, BACK_Z)
        val clockY = sy(2.45f, BACK_Z)
        val clockR = size(0.16f, BACK_Z)
        fill(Color.rgb(60, 60, 64))
        canvas.drawCircle(clockX, clockY, clockR + 3f, paint)
        fill(Color.WHITE)
        canvas.drawCircle(clockX, clockY, clockR, paint)
        stroke.color = Color.rgb(40, 40, 40)
        stroke.strokeWidth = 2.5f
        canvas.drawLine(clockX, clockY, clockX, clockY - clockR * 0.7f, stroke)
        canvas.drawLine(clockX, clockY, clockX + clockR * 0.5f, clockY + clockR * 0.1f, stroke)

        // Carpet, darker far away, with tile seams converging on the horizon.
        gradient(0f, sy(0f, BACK_Z), 0f, h, Color.rgb(98, 112, 128), Color.rgb(70, 82, 98))
        canvas.drawPath(quad(-wx, 0f, near, wx, 0f, near, wx, 0f, BACK_Z, -wx, 0f, BACK_Z), paint)
        stroke.color = Color.argb(45, 0, 0, 0)
        stroke.strokeWidth = 1.5f
        var x = -wx
        while (x <= wx + 0.01f) {
            canvas.drawLine(sx(x, near), sy(0f, near), sx(x, BACK_Z), sy(0f, BACK_Z), stroke)
            x += 0.55f
        }
        var z = 0.5f
        while (z < BACK_Z) {
            canvas.drawLine(sx(-wx, z), sy(0f, z), sx(wx, z), sy(0f, z), stroke)
            z += 0.55f
        }
        // Skirting boards.
        fill(Color.rgb(150, 140, 120))
        canvas.drawPath(quad(-wx, 0f, BACK_Z, wx, 0f, BACK_Z, wx, 0.1f, BACK_Z, -wx, 0.1f, BACK_Z), paint)
        canvas.drawPath(quad(-wx, 0f, near, -wx, 0.1f, near, -wx, 0.1f, BACK_Z, -wx, 0f, BACK_Z), paint)
        canvas.drawPath(quad(wx, 0f, near, wx, 0.1f, near, wx, 0.1f, BACK_Z, wx, 0f, BACK_Z), paint)
    }

    /** A grey filing cabinet against the left wall, with a desk fan on top. */
    private fun drawCabinetAndFan(canvas: Canvas) {
        val left = CABINET_X - 0.25f
        val right = CABINET_X + 0.25f
        val front = CABINET_Z - 0.3f
        val back = CABINET_Z + 0.3f
        val top = CABINET_HEIGHT
        // Shadow on the carpet.
        fill(Color.argb(60, 0, 0, 0))
        canvas.drawPath(quad(left, 0f, front - 0.05f, right + 0.1f, 0f, front - 0.05f,
            right + 0.1f, 0f, back, left, 0f, back), paint)
        // Inner side (facing the room centre), top, and front.
        fill(Color.rgb(120, 126, 132))
        canvas.drawPath(quad(right, 0f, front, right, top, front, right, top, back, right, 0f, back), paint)
        fill(Color.rgb(176, 182, 188))
        canvas.drawPath(quad(left, top, front, right, top, front, right, top, back, left, top, back), paint)
        gradient(0f, sy(top, front), 0f, sy(0f, front), Color.rgb(156, 162, 168), Color.rgb(132, 138, 144))
        canvas.drawPath(quad(left, 0f, front, right, 0f, front, right, top, front, left, top, front), paint)
        // Drawers with handles.
        stroke.color = Color.rgb(100, 106, 112)
        stroke.strokeWidth = 2f
        for (i in 1..2) {
            val y = top * i / 3f
            canvas.drawLine(sx(left, front), sy(y, front), sx(right, front), sy(y, front), stroke)
        }
        fill(Color.rgb(210, 214, 218))
        for (i in 0..2) {
            val y = top * (i + 0.6f) / 3f
            val hw = size(0.07f, front)
            rect.set(sx(CABINET_X, front) - hw, sy(y, front) - 2f, sx(CABINET_X, front) + hw, sy(y, front) + 3f)
            canvas.drawRect(rect, paint)
        }

        // Fan: base, neck and a round cage with turning blades.
        val fz = CABINET_Z
        val fx = CABINET_X
        val baseY = sy(top, fz)
        val headY = sy(top + 0.32f, fz)
        val cx = sx(fx, fz)
        fill(Color.rgb(40, 44, 50))
        rect.set(cx - size(0.12f, fz), baseY - size(0.03f, fz), cx + size(0.12f, fz), baseY + size(0.02f, fz))
        canvas.drawOval(rect, paint)
        stroke.color = Color.rgb(40, 44, 50)
        stroke.strokeWidth = size(0.025f, fz)
        canvas.drawLine(cx, baseY, cx, headY, stroke)
        val r = size(0.17f, fz)
        // The head turns toward where the wind blows, so it is drawn as an ellipse.
        val squash = 0.55f + 0.45f * (1f - (abs(game.wind) / PaperTossGame.MAX_WIND).coerceIn(0f, 1f))
        fill(Color.argb(70, 230, 235, 240))
        rect.set(cx - r * squash, headY - r, cx + r * squash, headY + r)
        canvas.drawOval(rect, paint)
        fill(Color.argb(210, 70, 140, 210))
        for (i in 0 until 3) {
            val a = Math.toRadians((fanAngle + i * 120f).toDouble())
            val bx = cx + (cos(a) * r * 0.62f * squash).toFloat()
            val by = headY + (sin(a) * r * 0.62f).toFloat()
            canvas.drawCircle(bx, by, r * 0.3f, paint)
        }
        fill(Color.rgb(40, 44, 50))
        canvas.drawCircle(cx, headY, r * 0.14f, paint)
        stroke.color = Color.rgb(90, 96, 104)
        stroke.strokeWidth = 2f
        canvas.drawOval(rect, stroke)
        val rings = RectF(rect)
        rings.inset(rect.width() * 0.2f, rect.height() * 0.2f)
        canvas.drawOval(rings, stroke)
    }

    /**
     * The wire-mesh bin, in two passes so the ball can sit inside: the back
     * pass draws the dark interior and far rim, the front pass the near mesh
     * and the near rim.
     */
    private fun drawBin(canvas: Canvas, front: Boolean) {
        val z = PaperTossGame.BIN_Z
        val top = PaperTossGame.BIN_HEIGHT
        val rt = PaperTossGame.BIN_RADIUS
        val rb = BIN_BOTTOM_RADIUS
        val cx = sx(0f, z)
        // Rim ellipse: from the near and far points of the rim.
        val rimNear = sy(top, z - rt)
        val rimFar = sy(top, z + rt)
        val rimHalfW = size(rt, z)
        val rimRect = RectF(cx - rimHalfW, rimFar, cx + rimHalfW, rimNear)
        val baseNear = sy(0f, z - rb)
        val baseFar = sy(0f, z + rb)
        val baseHalfW = size(rb, z)
        val baseRect = RectF(cx - baseHalfW, baseFar, cx + baseHalfW, baseNear)

        if (!front) {
            // Shadow on the carpet.
            fill(Color.argb(70, 0, 0, 0))
            rect.set(baseRect)
            rect.inset(-baseHalfW * 0.35f, -baseRect.height() * 0.2f)
            rect.offset(baseHalfW * 0.15f, 0f)
            canvas.drawOval(rect, paint)
            // Interior: the inside of the far wall, dark at the bottom.
            path.reset()
            path.moveTo(rimRect.left, rimRect.centerY())
            path.lineTo(baseRect.left, baseRect.centerY())
            path.arcTo(baseRect, 180f, 180f)
            path.lineTo(rimRect.right, rimRect.centerY())
            path.arcTo(rimRect, 0f, -180f)
            path.close()
            gradient(0f, rimRect.top, 0f, baseRect.bottom, Color.rgb(70, 74, 80), Color.rgb(28, 30, 34))
            canvas.drawPath(path, paint)
            fill(Color.rgb(45, 48, 54))
            canvas.drawOval(rimRect, paint)
            // Far rim.
            stroke.color = Color.rgb(150, 156, 164)
            stroke.strokeWidth = size(0.018f, z)
            canvas.drawArc(rimRect, 180f, 180f, false, stroke)
            return
        }

        // Near mesh body: a lit cylinder from rim to base.
        path.reset()
        path.moveTo(rimRect.left, rimRect.centerY())
        path.arcTo(rimRect, 180f, -180f)
        path.lineTo(baseRect.right, baseRect.centerY())
        path.arcTo(baseRect, 0f, 180f)
        path.close()
        paint.color = Color.WHITE
        paint.shader = LinearGradient(rimRect.left, 0f, rimRect.right, 0f,
            intArrayOf(Color.argb(150, 60, 64, 70), Color.argb(90, 150, 156, 164), Color.argb(170, 40, 42, 48)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        // Mesh: vertical wires around the near half, and two hoops.
        stroke.color = Color.argb(200, 30, 32, 36)
        stroke.strokeWidth = maxOf(1f, size(0.006f, z))
        for (i in 1 until 14) {
            val a = Math.PI * i / 14
            val tx = cx - (cos(a) * rimHalfW).toFloat()
            val ty = rimRect.centerY() + (sin(a) * rimRect.height() / 2).toFloat()
            val bx = cx - (cos(a) * baseHalfW).toFloat()
            val by = baseRect.centerY() + (sin(a) * baseRect.height() / 2).toFloat()
            canvas.drawLine(tx, ty, bx, by, stroke)
        }
        stroke.strokeWidth = maxOf(1.5f, size(0.012f, z))
        stroke.color = Color.rgb(70, 74, 80)
        val mid = RectF(
            (rimRect.left + baseRect.left) / 2, (rimRect.top + baseRect.top) / 2,
            (rimRect.right + baseRect.right) / 2, (rimRect.bottom + baseRect.bottom) / 2)
        canvas.drawArc(mid, 0f, 180f, false, stroke)
        canvas.drawArc(baseRect, 0f, 180f, false, stroke)
        // Near rim, with a highlight.
        stroke.color = Color.rgb(180, 186, 194)
        stroke.strokeWidth = size(0.022f, z)
        canvas.drawArc(rimRect, 0f, 180f, false, stroke)
        stroke.color = Color.argb(160, 255, 255, 255)
        stroke.strokeWidth = size(0.008f, z)
        canvas.drawArc(rimRect, 40f, 60f, false, stroke)
    }

    /** The player's desk: a wooden top with its far edge, across the bottom. */
    private fun drawDesk(canvas: Canvas, w: Float, h: Float) {
        val edgeY = sy(DESK_Y, DESK_FAR_Z)
        val thickness = size(0.04f, DESK_FAR_Z)
        gradient(0f, edgeY, 0f, h, Color.rgb(176, 124, 78), Color.rgb(132, 88, 52))
        rect.set(0f, edgeY, w, h)
        canvas.drawRect(rect, paint)
        // Wood grain.
        stroke.color = Color.argb(35, 60, 30, 10)
        stroke.strokeWidth = 2f
        for (i in 1..6) {
            val y = edgeY + (h - edgeY) * i / 7f
            canvas.drawLine(0f, y, w, y + (i % 3 - 1) * 6f, stroke)
        }
        // The far edge catches the light; a dark lip under it.
        fill(Color.rgb(200, 150, 100))
        rect.set(0f, edgeY, w, edgeY + thickness * 0.35f)
        canvas.drawRect(rect, paint)
        fill(Color.argb(90, 0, 0, 0))
        rect.set(0f, edgeY - thickness * 0.3f, w, edgeY)
        canvas.drawRect(rect, paint)
        // A coffee mug and a sticky note for company.
        val mx = w * 0.84f
        val my = sy(DESK_Y, 0.3f)
        val mr = size(0.05f, 0.3f)
        fill(Color.argb(60, 0, 0, 0))
        rect.set(mx - mr * 1.2f, my - mr * 0.3f, mx + mr * 1.4f, my + mr * 0.4f)
        canvas.drawOval(rect, paint)
        gradient(mx - mr, 0f, mx + mr, 0f, Color.rgb(230, 70, 60), Color.rgb(150, 40, 35))
        rect.set(mx - mr, my - mr * 2.2f, mx + mr, my)
        canvas.drawRect(rect, paint)
        rect.set(mx - mr, my - mr * 0.3f, mx + mr, my + mr * 0.3f)
        canvas.drawOval(rect, paint)
        fill(Color.rgb(60, 35, 20))
        rect.set(mx - mr, my - mr * 2.5f, mx + mr, my - mr * 1.9f)
        canvas.drawOval(rect, paint)
        stroke.color = Color.rgb(170, 45, 40)
        stroke.strokeWidth = mr * 0.25f
        rect.set(mx + mr * 0.7f, my - mr * 1.8f, mx + mr * 1.5f, my - mr * 0.6f)
        canvas.drawArc(rect, -90f, 180f, false, stroke)
        fill(Color.rgb(250, 230, 110))
        canvas.save()
        canvas.rotate(-8f, w * 0.15f, h * 0.88f)
        rect.set(w * 0.08f, h * 0.83f, w * 0.22f, h * 0.93f)
        canvas.drawRect(rect, paint)
        canvas.restore()
    }

    /** Crumpled paper: a shaded ball with creases that spin in flight. */
    private fun drawBall(canvas: Canvas) {
        val x = game.x
        val y = game.y
        val z = game.z
        // Shadow on whatever is below: the desk near the player, else the carpet.
        val onDesk = z < DESK_FAR_Z
        val floorY = if (onDesk) DESK_Y else 0f
        if (y >= floorY) {
            val sr = size(PaperTossGame.BALL_RADIUS, z)
            val lift = ((y - floorY) / 1.5f).coerceIn(0f, 1f)
            fill(Color.argb((90 * (1f - lift * 0.7f)).toInt(), 0, 0, 0))
            val shx = sx(x, z)
            val shy = sy(floorY, z)
            rect.set(shx - sr * 1.1f, shy - sr * 0.35f, shx + sr * 1.1f, shy + sr * 0.35f)
            canvas.drawOval(rect, paint)
        }
        val bx = sx(x, z)
        val by = sy(y, z)
        val r = size(PaperTossGame.BALL_RADIUS, z)
        paint.color = Color.WHITE
        paint.shader = RadialGradient(bx - r * 0.35f, by - r * 0.4f, r * 1.4f,
            Color.rgb(255, 255, 252), Color.rgb(170, 172, 168), Shader.TileMode.CLAMP)
        // A slightly lumpy outline reads as crumpled paper.
        path.reset()
        for (i in 0 until 10) {
            val a = Math.toRadians((spin + i * 36f).toDouble())
            val rr = r * (if (i % 2 == 0) 1f else 0.9f)
            val px = bx + (cos(a) * rr).toFloat()
            val py = by + (sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, paint)
        paint.shader = null
        stroke.color = Color.argb(110, 110, 112, 108)
        stroke.strokeWidth = maxOf(1f, r * 0.06f)
        for (i in 0 until 4) {
            val a = Math.toRadians((spin * 1.3f + i * 90f).toDouble())
            val b = Math.toRadians((spin * 1.3f + i * 90f + 70f).toDouble())
            canvas.drawLine(bx + (cos(a) * r * 0.2f).toFloat(), by + (sin(a) * r * 0.2f).toFloat(),
                bx + (cos(b) * r * 0.75f).toFloat(), by + (sin(b) * r * 0.75f).toFloat(), stroke)
        }
    }

    /** Wind dial top left, like the original; the streak top right; results in the middle. */
    private fun drawHud(canvas: Canvas, w: Float) {
        val d = resources.displayMetrics.density
        val pad = 10 * d
        // Wind panel.
        fill(Color.argb(170, 20, 24, 30))
        rect.set(pad, pad, pad + 120 * d, pad + 46 * d)
        canvas.drawRoundRect(rect, 8 * d, 8 * d, paint)
        text.textAlign = Paint.Align.LEFT
        text.color = Color.rgb(190, 200, 210)
        text.textSize = 11 * d
        canvas.drawText("WIND", pad + 8 * d, pad + 16 * d, text)
        text.color = Color.WHITE
        text.textSize = 18 * d
        canvas.drawText(String.format(java.util.Locale.US, "%.2f", abs(game.wind)),
            pad + 8 * d, pad + 38 * d, text)
        // Arrow: length and colour follow the strength.
        val strength = (abs(game.wind) / PaperTossGame.MAX_WIND).coerceIn(0f, 1f)
        val ax = pad + 88 * d
        val ay = pad + 28 * d
        val len = (10 + 16 * strength) * d
        val dir = if (game.wind >= 0) 1f else -1f
        stroke.color = Color.rgb((120 + 135 * strength).toInt(), (220 - 140 * strength).toInt(), 90)
        stroke.strokeWidth = 4 * d
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(ax - len * dir, ay, ax + len * dir, ay, stroke)
        canvas.drawLine(ax + len * dir, ay, ax + (len - 8 * d) * dir, ay - 7 * d, stroke)
        canvas.drawLine(ax + len * dir, ay, ax + (len - 8 * d) * dir, ay + 7 * d, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        // Streak panel.
        fill(Color.argb(170, 20, 24, 30))
        rect.set(w - pad - 104 * d, pad, w - pad, pad + 46 * d)
        canvas.drawRoundRect(rect, 8 * d, 8 * d, paint)
        text.textAlign = Paint.Align.RIGHT
        text.color = Color.rgb(190, 200, 210)
        text.textSize = 11 * d
        canvas.drawText("STREAK · BEST ${game.best}", w - pad - 8 * d, pad + 16 * d, text)
        text.color = Color.WHITE
        text.textSize = 18 * d
        canvas.drawText("${game.streak} / ${PaperTossGame.STREAK_TO_WIN}", w - pad - 8 * d, pad + 38 * d, text)

        val message = when (game.state) {
            PaperTossGame.State.SCORED -> "Nice shot!"
            PaperTossGame.State.WON -> "${PaperTossGame.STREAK_TO_WIN} in a row! Unlocked"
            PaperTossGame.State.MISSED -> if (game.hitRim) "Off the rim!" else "Missed!"
            else -> null
        }
        if (message != null) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 26 * d
            val y = horizon + 30 * d
            text.color = Color.argb(160, 0, 0, 0)
            canvas.drawText(message, w / 2 + 2 * d, y + 2 * d, text)
            text.color = if (game.state == PaperTossGame.State.MISSED) Color.rgb(255, 120, 110)
                else Color.rgb(140, 240, 140)
            canvas.drawText(message, w / 2, y, text)
        }
        text.textAlign = Paint.Align.CENTER
    }
}
