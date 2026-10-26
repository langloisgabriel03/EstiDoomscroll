package com.resolvecommunity.awaydoomscrollin

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Reels Wait fork: pure rules for the unlock mini-games, free of Android types
 * so they are unit tested. The views live in UnlockChallenges.kt.
 */

/** Typing test: copy a sentence exactly, mistakes must be fixed. */
object TypingCheck {
    enum class CharState { CORRECT, WRONG, PENDING }

    val sentences = listOf(
        "I choose to watch Reels for five minutes and then stop.",
        "This is my time and I decide where it goes today.",
        "Five minutes of Reels, then back to what matters.",
        "I am opening Reels on purpose, not out of habit.",
        "Scrolling can wait, but I am choosing it right now."
    )

    fun states(target: String, typed: String): List<CharState> =
        target.indices.map { i ->
            when {
                i >= typed.length -> CharState.PENDING
                typed[i] == target[i] -> CharState.CORRECT
                else -> CharState.WRONG
            }
        }

    fun isComplete(target: String, typed: String): Boolean = typed == target

    /** Length of the correctly typed start of [typed]. */
    fun correctPrefix(target: String, typed: String): Int {
        var i = 0
        while (i < typed.length && i < target.length && typed[i] == target[i]) i++
        return i
    }

    /** Words per minute, using the standard five characters per word. */
    fun wpm(correctChars: Int, elapsedMs: Long): Int =
        if (elapsedMs <= 0L) 0 else ((correctChars / 5.0) / (elapsedMs / 60_000.0)).roundToInt()

    /** Percent of keystrokes that typed the right character. */
    fun accuracy(correctKeys: Int, totalKeys: Int): Int =
        if (totalKeys <= 0) 100 else (correctKeys * 100.0 / totalKeys).roundToInt()
}

/** Mental math: three easy sums. A wrong answer swaps in a new problem. */
object MathCheck {
    const val PROBLEMS_TO_SOLVE = 3

    data class Problem(val a: Int, val b: Int, val op: Char) {
        val answer: Int
            get() = when (op) {
                '+' -> a + b
                '-' -> a - b
                else -> a * b
            }
        val text: String
            get() = "$a ${if (op == '*') '×' else if (op == '-') '−' else '+'} $b"
    }

    fun generate(random: Random = Random.Default): Problem = when (random.nextInt(3)) {
        0 -> Problem(random.nextInt(12, 90), random.nextInt(12, 90), '+')
        1 -> {
            val a = random.nextInt(30, 100)
            Problem(a, random.nextInt(11, a), '-')
        }
        else -> Problem(random.nextInt(3, 10), random.nextInt(11, 20), '*')
    }

    fun isCorrect(problem: Problem, input: String): Boolean =
        input.trim().toIntOrNull() == problem.answer
}

/** Hold to unlock: keep a finger down without lifting. */
object HoldCheck {
    const val HOLD_SECONDS = 180

    fun progress(heldMs: Long): Float =
        (heldMs / (HOLD_SECONDS * 1000f)).coerceIn(0f, 1f)

    fun isDone(heldMs: Long): Boolean = heldMs >= HOLD_SECONDS * 1000L
}

/**
 * Flappy Bird clone: tap to flap through [PIPES_TO_WIN] pipes in one run.
 * Logical units on a [WIDTH] x [HEIGHT] board; the view only scales and draws.
 * It gets harder as the score climbs: pipes speed up and gaps narrow, up to a
 * ceiling, and a gap never moves further from the previous one than a few
 * flaps can follow, so every run stays winnable.
 */
class FlappyGame(private val random: Random = Random.Default) {
    companion object {
        const val WIDTH = 360f
        const val HEIGHT = 480f
        const val GROUND = 440f
        const val BIRD_X = 90f
        const val BIRD_RADIUS = 12f
        const val GRAVITY = 1400f
        const val FLAP_VELOCITY = -420f
        const val MAX_FALL_VELOCITY = 600f
        const val PIPE_WIDTH = 56f
        const val PIPE_SPACING = 200f
        const val GAP_MARGIN = 50f
        const val PIPES_TO_WIN = 50

        const val BASE_GAP = 140f
        const val MIN_GAP = 115f
        const val GAP_STEP = 1f
        const val BASE_SPEED = 140f
        const val MAX_SPEED = 210f
        const val SPEED_STEP = 2f
        const val MAX_GAP_SHIFT = 70f

        /** Longest step simulated at once, so a stalled frame cannot tunnel through a pipe. */
        private const val MAX_STEP_SECONDS = 1f / 30f
    }

    enum class State { READY, PLAYING, DEAD, WON }

    class Pipe(var x: Float, val gapTop: Float, val gap: Float = BASE_GAP) {
        var passed = false
        val gapBottom: Float
            get() = gapTop + gap
    }

    var state = State.READY
        private set
    var birdY = 0f
        internal set
    var velocity = 0f
        internal set
    var score = 0
        private set
    val pipes = mutableListOf<Pipe>()
    private var spawned = 0

    /** Pipe speed: faster with every pipe passed, up to [MAX_SPEED]. */
    val speed: Float
        get() = (BASE_SPEED + score * SPEED_STEP).coerceAtMost(MAX_SPEED)

    init {
        reset()
    }

    /** Tap: start, flap, or (after a crash) get ready for another run. */
    fun flap() {
        when (state) {
            State.READY -> {
                state = State.PLAYING
                velocity = FLAP_VELOCITY
            }
            State.PLAYING -> velocity = FLAP_VELOCITY
            State.DEAD -> reset()
            State.WON -> Unit
        }
    }

    fun step(seconds: Float) {
        var left = seconds.coerceAtLeast(0f)
        while (left > 0f && state == State.PLAYING) {
            val dt = minOf(left, MAX_STEP_SECONDS)
            advance(dt)
            left -= dt
        }
    }

    private fun advance(dt: Float) {
        velocity = (velocity + GRAVITY * dt).coerceAtMost(MAX_FALL_VELOCITY)
        birdY += velocity * dt
        if (birdY - BIRD_RADIUS < 0f) {
            birdY = BIRD_RADIUS
            velocity = 0f
        }
        val move = speed * dt
        pipes.forEach { it.x -= move }
        pipes.removeAll { it.x + PIPE_WIDTH < 0f }
        if (pipes.isEmpty() || pipes.last().x < WIDTH - PIPE_SPACING) {
            pipes += newPipe(maxOf(WIDTH, (pipes.lastOrNull()?.x ?: 0f) + PIPE_SPACING))
        }
        for (pipe in pipes) {
            if (!pipe.passed && pipe.x + PIPE_WIDTH < BIRD_X - BIRD_RADIUS) {
                pipe.passed = true
                score++
                if (score >= PIPES_TO_WIN) {
                    state = State.WON
                    return
                }
            }
        }
        if (birdY + BIRD_RADIUS >= GROUND || pipes.any(::hits)) state = State.DEAD
    }

    private fun hits(pipe: Pipe): Boolean {
        val overlapsX = BIRD_X + BIRD_RADIUS > pipe.x && BIRD_X - BIRD_RADIUS < pipe.x + PIPE_WIDTH
        return overlapsX && (birdY - BIRD_RADIUS < pipe.gapTop || birdY + BIRD_RADIUS > pipe.gapBottom)
    }

    private fun newPipe(x: Float): Pipe {
        val gap = (BASE_GAP - spawned * GAP_STEP).coerceAtLeast(MIN_GAP)
        spawned++
        var low = GAP_MARGIN
        var high = GROUND - gap - GAP_MARGIN
        pipes.lastOrNull()?.let { previous ->
            // Centre this gap within reach of the previous one.
            val aligned = previous.gapTop + previous.gap / 2 - gap / 2
            low = maxOf(low, aligned - MAX_GAP_SHIFT)
            high = minOf(high, aligned + MAX_GAP_SHIFT)
        }
        return Pipe(x, low + random.nextFloat() * (high - low), gap)
    }

    private fun reset() {
        state = State.READY
        birdY = HEIGHT * 0.4f
        velocity = 0f
        score = 0
        spawned = 0
        pipes.clear()
        pipes += newPipe(WIDTH + 40f)
    }
}

/**
 * Rush Hour: slide cars (2 long) and trucks (3 long) along their lanes on a
 * 6x6 board until the red car ('A') drives out of the exit on row 3.
 * Layouts are 36 characters, row by row: 'o' is empty, a letter is a vehicle.
 */
class RushHourBoard(layout: String) {
    companion object {
        const val SIZE = 6
        const val EXIT_ROW = 2
        const val TARGET = 'A'
    }

    /** [lane] is the row of a horizontal vehicle or the column of a vertical one. */
    class Vehicle(val id: Char, val horizontal: Boolean, val length: Int, val lane: Int, position: Int) {
        /** Leftmost column (horizontal) or topmost row (vertical). */
        var position = position
            internal set
        val isTarget: Boolean
            get() = id == TARGET
        val row: Int
            get() = if (horizontal) lane else position
        val col: Int
            get() = if (horizontal) position else lane

        fun covers(r: Int, c: Int): Boolean =
            if (horizontal) r == lane && c >= position && c < position + length
            else c == lane && r >= position && r < position + length
    }

    val vehicles: List<Vehicle>
    var moves = 0
        private set

    init {
        require(layout.length == SIZE * SIZE) { "Layout must have ${SIZE * SIZE} cells" }
        vehicles = layout.indices.filter { layout[it].isUpperCase() }
            .groupBy { layout[it] }
            .map { (id, cells) ->
                val horizontal = cells.size > 1 && cells[1] == cells[0] + 1
                val row = cells[0] / SIZE
                val col = cells[0] % SIZE
                Vehicle(id, horizontal, cells.size, if (horizontal) row else col, if (horizontal) col else row)
            }
        require(vehicles.any { it.isTarget && it.horizontal && it.lane == EXIT_ROW }) {
            "The red car must sit horizontally on the exit row"
        }
    }

    val target: Vehicle
        get() = vehicles.first { it.isTarget }

    val isSolved: Boolean
        get() = target.position == SIZE - target.length

    fun vehicleAt(row: Int, col: Int): Vehicle? = vehicles.firstOrNull { it.covers(row, col) }

    /** Every position [vehicle] can slide to in one move, its own included. */
    fun range(vehicle: Vehicle): IntRange {
        var low = vehicle.position
        while (low > 0 && isFree(vehicle, low - 1)) low--
        var high = vehicle.position
        while (high + vehicle.length < SIZE && isFree(vehicle, high + vehicle.length)) high++
        return low..high
    }

    /** Slides [vehicle] to [position] as one move; false if blocked or unchanged. */
    fun moveTo(vehicle: Vehicle, position: Int): Boolean {
        if (position == vehicle.position || position !in range(vehicle)) return false
        vehicle.position = position
        moves++
        return true
    }

    private fun isFree(vehicle: Vehicle, along: Int): Boolean =
        if (vehicle.horizontal) vehicleAt(vehicle.lane, along) == null
        else vehicleAt(along, vehicle.lane) == null
}

/** Generated levels with their minimum move counts (checked by a solver in the tests). */
object RushHourLevels {
    val all: List<Pair<String, Int>> = listOf(
        "ooEJIoooEJIoAAEoIKoooBBKGCoFFDGCHHoD" to 14,
        "FFooEoCCKKELMAANoLMDDNooooJBBGIIJHHG" to 14,
        "ooEEEoKKIIMLAAGBMLDDGBMFHoJJJFHCCooo" to 15,
        "GGGLCoooELCJAAEooJIoBBBFIDDDoFIKKoHH" to 15,
        "oooIIBooCEEBAACooBGJCFHHGJoFKoGDDDKo" to 15,
        "DKKoGoDELLGIoEAAGIoEoFooJooFHHJCCBBo" to 15,
        "JoHoGGJoHoCIAAHoCIooEEEIKDooFFKDBBoo" to 15,
        "IEEoooIoJoCoAAJDCBHHHDFBGGoDFoooooFo" to 16,
        "JJoBBBIIEEFooAAKFCNHHKoCNoGKDDMMGLLo" to 16,
        "GKKJJoGNNHDoGAAHDoBILLLCBIMEECFFMooo" to 16,
        "oMMILJGGoILJoDAALoBDCCoHBDEKKHFFENNo" to 16,
        "EEEoCHLJJoCHLAABCooIoBoooIFKKoDDFGGo" to 17,
        "oDDDBHoooEBHAAoEBJGoIEoJGoICCCooFFFo" to 17,
        "ooDIIoLoDHoELAAHCEoJJoCKGGGMMKBBBoFF" to 17,
        "oLFFBooLCCBKAAoDoKIIIDoHJooDEHJGGGEo" to 17,
        "KKEooIooEBBIFAAoCIFDDoCoGGGoJJoHHLLL" to 17,
        "KoooDDKCCooBAAFLoBooFLoGIIIJJGHHEEEo" to 18,
        "MKBBGHMKoLGHAACLoHFFCEEoDoCoIIDooJJo" to 20,
        "LoEEooLGGoJBAACoJBHHCKIoDDDKIFoooooF" to 20,
        "LIIIGGLFFFoEAAoJHEDDDJHEKKBBHoMMoCCo" to 22,
        "BBooHGoIICHGAAFCoGDoFCJJDoFooooooEEo" to 25,
        "KKKoDooLLHDEoAAHDEoIFoMMGIFBBJGooCCJ" to 26,
        "oLLLoEooBHGEAABHGoJJDDGoKCCCoFKoIIoF" to 27,
        "LLLJCoGooJCEGAAoCEHHDIoEooDIKKoBBFFo" to 29,
    )

    fun random(random: Random = Random.Default): Pair<String, Int> = all.random(random)
}
