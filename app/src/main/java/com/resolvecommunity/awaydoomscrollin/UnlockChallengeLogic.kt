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
    const val HOLD_SECONDS = 45

    fun progress(heldMs: Long): Float =
        (heldMs / (HOLD_SECONDS * 1000f)).coerceIn(0f, 1f)

    fun isDone(heldMs: Long): Boolean = heldMs >= HOLD_SECONDS * 1000L
}

/**
 * Flappy Bird clone: tap to flap through [PIPES_TO_WIN] pipes in one run.
 * Logical units on a [WIDTH] x [HEIGHT] board; the view only scales and draws.
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
        const val GAP = 140f
        const val PIPE_SPEED = 140f
        const val PIPE_SPACING = 200f
        const val GAP_MARGIN = 50f
        const val PIPES_TO_WIN = 10

        /** Longest step simulated at once, so a stalled frame cannot tunnel through a pipe. */
        private const val MAX_STEP_SECONDS = 1f / 30f
    }

    enum class State { READY, PLAYING, DEAD, WON }

    class Pipe(var x: Float, val gapTop: Float) {
        var passed = false
        val gapBottom: Float
            get() = gapTop + GAP
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
        pipes.forEach { it.x -= PIPE_SPEED * dt }
        pipes.removeAll { it.x + PIPE_WIDTH < 0f }
        if (pipes.isEmpty() || pipes.last().x < WIDTH - PIPE_SPACING) {
            pipes += Pipe(maxOf(WIDTH, (pipes.lastOrNull()?.x ?: 0f) + PIPE_SPACING), randomGapTop())
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

    private fun randomGapTop(): Float =
        GAP_MARGIN + random.nextFloat() * (GROUND - GAP - 2 * GAP_MARGIN)

    private fun reset() {
        state = State.READY
        birdY = HEIGHT * 0.4f
        velocity = 0f
        score = 0
        pipes.clear()
        pipes += Pipe(WIDTH + 40f, randomGapTop())
    }
}
