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
