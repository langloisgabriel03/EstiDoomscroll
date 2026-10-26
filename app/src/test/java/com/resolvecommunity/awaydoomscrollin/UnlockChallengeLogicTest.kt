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
    fun `flappy waits for the first tap`() {
        val game = FlappyGame(Random(1))
        val startY = game.birdY
        game.step(1f)
        assertEquals(FlappyGame.State.READY, game.state)
        assertEquals(startY, game.birdY, 0f)
        game.flap()
        assertEquals(FlappyGame.State.PLAYING, game.state)
        assertEquals(FlappyGame.FLAP_VELOCITY, game.velocity, 0f)
    }

    @Test
    fun `flappy falls to the ground without taps and retries on the next tap`() {
        val game = FlappyGame(Random(1))
        game.flap()
        repeat(600) { game.step(1f / 60f) }
        assertEquals(FlappyGame.State.DEAD, game.state)
        game.flap()
        assertEquals(FlappyGame.State.READY, game.state)
        assertEquals(0, game.score)
    }

    @Test
    fun `flappy crashes into a pipe`() {
        val game = FlappyGame(Random(1))
        game.flap()
        game.pipes.clear()
        // A pipe right at the bird with its gap far above it.
        game.pipes += FlappyGame.Pipe(FlappyGame.BIRD_X - 10f, gapTop = 0f)
        game.birdY = 300f
        game.velocity = 0f
        game.step(1f / 60f)
        assertEquals(FlappyGame.State.DEAD, game.state)
    }

    @Test
    fun `flappy is winnable on every level`() {
        // A simple autopilot aims for the middle of the next gap. If it can
        // win every seed, the pipes never ask for something impossible.
        for (seed in 1..30) {
            val game = FlappyGame(Random(seed))
            game.flap()
            var seconds = 0f
            while (game.state == FlappyGame.State.PLAYING && seconds < 120f) {
                val next = game.pipes.first {
                    it.x + FlappyGame.PIPE_WIDTH >= FlappyGame.BIRD_X - FlappyGame.BIRD_RADIUS
                }
                if (game.birdY > next.gapTop + FlappyGame.GAP / 2 + 15f && game.velocity >= 0f) game.flap()
                game.step(1f / 60f)
                seconds += 1f / 60f
            }
            assertEquals("seed $seed", FlappyGame.State.WON, game.state)
            assertEquals(FlappyGame.PIPES_TO_WIN, game.score)
        }
    }

    @Test
    fun `every game is registered`() {
        assertEquals(4, UnlockChallenges.count)
    }
}
