package com.resolvecommunity.awaydoomscrollin

import com.resolvecommunity.awaydoomscrollin.TypingCheck.CharState.CORRECT
import com.resolvecommunity.awaydoomscrollin.TypingCheck.CharState.PENDING
import com.resolvecommunity.awaydoomscrollin.TypingCheck.CharState.WRONG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UnlockChallengeLogicTest {

    @Test
    fun `typing marks each letter correct, wrong or pending`() {
        assertEquals(listOf(CORRECT, WRONG, PENDING, PENDING), TypingCheck.states("abcd", "ax"))
        assertEquals(listOf(PENDING, PENDING), TypingCheck.states("ab", ""))
        assertEquals(1, TypingCheck.correctPrefix("abcd", "axcd"))
    }

    @Test
    fun `typing only completes on an exact match`() {
        val target = "I choose this."
        assertFalse(TypingCheck.isComplete(target, "I choose this"))
        assertFalse(TypingCheck.isComplete(target, "i choose this."))
        assertTrue(TypingCheck.isComplete(target, target))
    }

    @Test
    fun `typing speed and accuracy`() {
        // 50 characters in one minute = 10 words per minute.
        assertEquals(10, TypingCheck.wpm(50, 60_000L))
        assertEquals(0, TypingCheck.wpm(50, 0L))
        assertEquals(90, TypingCheck.accuracy(9, 10))
        assertEquals(100, TypingCheck.accuracy(0, 0))
    }

    @Test
    fun `typing sentences fit on the wait screen`() {
        TypingCheck.sentences.forEach { assertTrue(it, it.length in 20..70) }
    }

    @Test
    fun `math problems are easy and checked exactly`() {
        val random = Random(42)
        repeat(500) {
            val problem = MathCheck.generate(random)
            assertTrue(problem.text, problem.answer in 0..200)
            assertTrue(MathCheck.isCorrect(problem, " ${problem.answer} "))
            assertFalse(MathCheck.isCorrect(problem, (problem.answer + 1).toString()))
            assertFalse(MathCheck.isCorrect(problem, ""))
        }
        assertEquals("7 × 13", MathCheck.Problem(7, 13, '*').text)
        assertEquals(91, MathCheck.Problem(7, 13, '*').answer)
        assertEquals(19, MathCheck.Problem(42, 23, '-').answer)
    }

    @Test
    fun `hold needs the full duration`() {
        val full = HoldCheck.HOLD_SECONDS * 1000L
        assertFalse(HoldCheck.isDone(full - 1L))
        assertTrue(HoldCheck.isDone(full))
        assertEquals(0.5f, HoldCheck.progress(full / 2), 0.001f)
        assertEquals(1f, HoldCheck.progress(full * 2), 0.001f)
    }

    @Test
    fun `every game is registered`() {
        assertEquals(3, UnlockChallenges.count)
    }
}
