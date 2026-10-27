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
    fun `game bag shows every game once per round and never twice in a row`() {
        val all = listOf("typing", "math", "flappy", "rushhour", "2048")
        for (seed in 1..50) {
            val random = Random(seed)
            var bag = emptyList<String>()
            var last: String? = null
            val shown = mutableListOf<String>()
            repeat(all.size * 4) {
                val (id, rest) = GameBag.draw(all, bag, last, random)
                shown += id
                bag = rest
                last = id
            }
            shown.chunked(all.size).forEach { round -> assertEquals(all.toSet(), round.toSet()) }
            assertTrue(shown.zipWithNext().none { (a, b) -> a == b })
        }
    }

    @Test
    fun `game bag drops games that no longer exist`() {
        val (id, rest) = GameBag.draw(listOf("a", "b"), listOf("hold", "b", "a"), last = null, Random(1))
        assertEquals("b", id)
        assertEquals(listOf("a"), rest)
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
        for (seed in 1..50) {
            val game = FlappyGame(Random(seed))
            game.flap()
            var seconds = 0f
            while (game.state == FlappyGame.State.PLAYING && seconds < 300f) {
                val next = game.pipes.first {
                    it.x + FlappyGame.PIPE_WIDTH >= FlappyGame.BIRD_X - FlappyGame.BIRD_RADIUS
                }
                // Flap just low enough that the top of the jump clears the upper pipe.
                val rise = FlappyGame.FLAP_VELOCITY * FlappyGame.FLAP_VELOCITY / (2 * FlappyGame.GRAVITY)
                if (game.birdY > next.gapTop + FlappyGame.BIRD_RADIUS + rise + 4f && game.velocity >= 0f) game.flap()
                game.step(1f / 60f)
                seconds += 1f / 60f
            }
            assertEquals("seed $seed", FlappyGame.State.WON, game.state)
            assertEquals(FlappyGame.PIPES_TO_WIN, game.score)
        }
    }

    @Test
    fun `flappy speeds up and narrows its gaps up to a ceiling`() {
        val game = FlappyGame(Random(3))
        assertEquals(FlappyGame.BASE_SPEED, game.speed, 0f)
        assertEquals(FlappyGame.BASE_GAP, game.pipes.first().gap, 0f)
        game.flap()
        val gaps = mutableListOf<Float>()
        var previous: FlappyGame.Pipe? = null
        repeat(20_000) {
            // Hold the bird in the middle of each pipe so the run continues.
            val next = game.pipes.first { it.x + FlappyGame.PIPE_WIDTH >= FlappyGame.BIRD_X - FlappyGame.BIRD_RADIUS }
            game.birdY = next.gapTop + next.gap / 2
            game.velocity = 0f
            game.step(1f / 60f)
            val last = game.pipes.last()
            if (last !== previous) {
                gaps += last.gap
                previous?.let { prev ->
                    val shift = (last.gapTop + last.gap / 2) - (prev.gapTop + prev.gap / 2)
                    assertTrue("gap jumped $shift", kotlin.math.abs(shift) <= FlappyGame.MAX_GAP_SHIFT + 0.01f)
                }
                previous = last
            }
            if (game.state != FlappyGame.State.PLAYING) return@repeat
        }
        assertEquals(FlappyGame.State.WON, game.state)
        assertEquals(FlappyGame.MAX_SPEED, game.speed, 0f)
        assertTrue(gaps.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(FlappyGame.MIN_GAP, gaps.last(), 0f)
    }

    @Test
    fun `rush hour parses cars and trucks and slides them to obstacles`() {
        val board = RushHourBoard(
            "ooooBo" +
            "ooooBo" +
            "AAooBo" +
            "oooooo" +
            "CCCooo" +
            "oooooo")
        val red = board.target
        val truck = board.vehicleAt(0, 4)!!
        val lorry = board.vehicleAt(4, 0)!!
        assertTrue(red.horizontal)
        assertFalse(truck.horizontal)
        assertEquals(3, truck.length)
        assertEquals(3, lorry.length)
        assertEquals(0..2, board.range(red))
        assertFalse(board.moveTo(red, 4))
        assertTrue(board.moveTo(truck, 3))
        assertEquals(1, board.moves)
        assertEquals(0..4, board.range(red))
        assertTrue(board.moveTo(red, 4))
        assertTrue(board.isSolved)
    }

    @Test
    fun `every rush hour level is solvable in its stated minimum`() {
        assertTrue(RushHourLevels.all.size >= 20)
        for ((layout, par) in RushHourLevels.all) {
            assertEquals(layout, par, rushHourMinMoves(layout))
            assertTrue(layout, par >= 10)
        }
    }

    /** Breadth-first search over vehicle positions; a slide of any length is one move. */
    private fun rushHourMinMoves(layout: String): Int? {
        val board = RushHourBoard(layout)
        val vehicles = board.vehicles
        fun place(state: List<Int>) = vehicles.forEachIndexed { i, v -> v.position = state[i] }
        val start = vehicles.map { it.position }
        val seen = hashMapOf(start to 0)
        val queue = mutableListOf(start)
        var head = 0
        while (head < queue.size) {
            val state = queue[head++]
            val depth = seen.getValue(state)
            place(state)
            if (board.isSolved) return depth
            vehicles.forEachIndexed { i, v ->
                place(state)
                for (p in board.range(v)) {
                    if (p == state[i]) continue
                    val next = state.toMutableList().also { it[i] = p }
                    if (next !in seen) {
                        seen[next] = depth + 1
                        queue += next
                    }
                }
            }
        }
        return null
    }

    @Test
    fun `2048 merges each pair once per move`() {
        val game = Game2048(Random(1))
        assertEquals(listOf(4, 4, 0, 0), game.slide(listOf(2, 2, 2, 2)))
        assertEquals(listOf(4, 4, 0, 0), game.slide(listOf(2, 2, 4, 0)))
        assertEquals(listOf(8, 8, 0, 0), game.slide(listOf(4, 4, 8, 0)))
        assertEquals(listOf(2, 4, 2, 0), game.slide(listOf(0, 2, 4, 2)))
        assertEquals(listOf(4, 0, 0, 0), game.slide(listOf(2, 0, 0, 2)))
    }

    @Test
    fun `2048 starts with two tiles and adds one per real move`() {
        val game = Game2048(Random(2))
        assertEquals(2, game.cells.count { it != 0 })
        game.cells.fill(0)
        game.cells[0] = 2
        // Already against the left wall: nothing moves, nothing is added.
        assertFalse(game.move(Game2048.Direction.LEFT))
        assertEquals(1, game.cells.count { it != 0 })
        assertTrue(game.move(Game2048.Direction.RIGHT))
        assertEquals(2, game.cells[3])
        assertEquals(2, game.cells.count { it != 0 })
    }

    @Test
    fun `2048 moves in all four directions and scores merges`() {
        val game = Game2048(Random(3))
        game.cells.fill(0)
        game.cells[0] = 4
        game.cells[12] = 4
        assertTrue(game.move(Game2048.Direction.DOWN))
        assertEquals(8, game.cells[12])
        assertEquals(8, game.score)
        game.cells.fill(0)
        game.cells[15] = 16
        assertTrue(game.move(Game2048.Direction.UP))
        assertEquals(16, game.cells[3])
    }

    @Test
    fun `2048 is won at the target and over when nothing can move`() {
        val game = Game2048(Random(4))
        game.cells.fill(0)
        game.cells[0] = Game2048.TARGET / 2
        game.cells[1] = Game2048.TARGET / 2
        assertFalse(game.won)
        game.move(Game2048.Direction.LEFT)
        assertTrue(game.won)
        assertEquals(Game2048.TARGET, game.bestTile)

        // A checkerboard of 2s and 4s has no equal neighbours.
        for (i in game.cells.indices) game.cells[i] = if ((i / 4 + i % 4) % 2 == 0) 2 else 4
        assertTrue(game.over)
        game.cells[1] = 2
        assertFalse(game.over)
        game.restart()
        assertFalse(game.over)
        assertEquals(0, game.score)
    }

    @Test
    fun `2048 reports where every tile slid for the animation`() {
        val game = Game2048(Random(5))
        val line = game.slideLine(listOf(0, 2, 2, 4))
        assertEquals(listOf(4, 4, 0, 0), line.values)
        assertEquals(listOf(1 to 0, 2 to 0, 3 to 1), line.destinations)
        assertEquals(listOf(0), line.mergedAt)

        game.cells.fill(0)
        game.cells[1] = 2
        game.cells[2] = 2
        game.cells[3] = 4
        assertTrue(game.move(Game2048.Direction.LEFT))
        assertEquals(listOf(Game2048.TileMove(1, 0, 2), Game2048.TileMove(2, 0, 2), Game2048.TileMove(3, 1, 4)),
            game.lastMoves)
        assertEquals(setOf(0), game.lastMerged)
        assertEquals(4, game.score)
        assertTrue(game.lastSpawn in 2..15)
        assertTrue(game.cells[game.lastSpawn] in setOf(2, 4))
    }

    @Test
    fun `every sudoku has one solution that matches its givens`() {
        assertTrue(SudokuPuzzles.all.size >= 20)
        for ((puzzle, solution) in SudokuPuzzles.all) {
            for (i in puzzle.indices) assertTrue(puzzle, puzzle[i] == '0' || puzzle[i] == solution[i])
            val grid = solution.map { it - '0' }
            for (k in 0 until 9) {
                assertEquals(solution, (1..9).toSet(), (0 until 9).map { grid[k * 9 + it] }.toSet())
                assertEquals(solution, (1..9).toSet(), (0 until 9).map { grid[it * 9 + k] }.toSet())
                assertEquals(solution, (1..9).toSet(),
                    (0 until 9).map { grid[(k / 3 * 3 + it / 3) * 9 + k % 3 * 3 + it % 3] }.toSet())
            }
            assertEquals(puzzle, 1, sudokuSolutions(puzzle.map { it - '0' }.toIntArray(), limit = 2))
        }
    }

    /** Counts solutions up to [limit] by backtracking on the most constrained cell. */
    private fun sudokuSolutions(grid: IntArray, limit: Int): Int {
        var best = -1
        var bestOptions: List<Int> = emptyList()
        for (i in grid.indices) {
            if (grid[i] != 0) continue
            val r = i / 9
            val c = i % 9
            val used = (0 until 9).flatMap { listOf(grid[r * 9 + it], grid[it * 9 + c],
                grid[(r / 3 * 3 + it / 3) * 9 + c / 3 * 3 + it % 3]) }.toSet()
            val options = (1..9).filter { it !in used }
            if (best < 0 || options.size < bestOptions.size) {
                best = i
                bestOptions = options
            }
        }
        if (best < 0) return 1
        var count = 0
        for (digit in bestOptions) {
            grid[best] = digit
            count += sudokuSolutions(grid, limit - count)
            grid[best] = 0
            if (count >= limit) break
        }
        return count
    }

    @Test
    fun `sudoku keeps givens, flags clashes and knows when it is solved`() {
        val (puzzle, solution) = SudokuPuzzles.all.first()
        val board = SudokuBoard(puzzle, solution)
        val given = puzzle.indexOfFirst { it != '0' }
        val empty = puzzle.indexOf('0')
        assertFalse(board.set(given, 5))
        // The same digit as a given in the empty cell's row clashes.
        val row = empty / 9
        val rowGiven = (0 until 9).map { row * 9 + it }.first { puzzle[it] != '0' }
        assertTrue(board.set(empty, puzzle[rowGiven] - '0'))
        assertTrue(board.conflicts(empty))
        assertFalse(board.isSolved)
        for (i in puzzle.indices) if (puzzle[i] == '0') board.set(i, solution[i] - '0')
        assertTrue(board.isSolved)
        assertTrue(board.cells.indices.none { board.conflicts(it) })
        assertEquals(81, board.filled)
    }

    @Test
    fun `stack is won by perfect drops and speeds up`() {
        val game = StackGame(Random(1))
        game.tap()
        assertEquals(StackGame.State.PLAYING, game.state)
        var seconds = 0f
        while (game.state == StackGame.State.PLAYING && seconds < 600f) {
            val top = game.blocks.last()
            val offset = if (game.alongX) game.moving.x - top.x else game.moving.z - top.z
            if (kotlin.math.abs(offset) <= 2f) {
                game.tap()
                assertTrue(game.lastPerfect)
            }
            game.step(1f / 120f)
            seconds += 1f / 120f
        }
        assertEquals(StackGame.State.WON, game.state)
        assertEquals(StackGame.TARGET, game.height)
        assertEquals(StackGame.BASE_SIZE, game.blocks.last().width, 0f)
        assertEquals(minOf(StackGame.MAX_SPEED, StackGame.BASE_SPEED + StackGame.TARGET * StackGame.SPEED_STEP),
            game.speed, 0f)
        assertTrue(game.speed > StackGame.BASE_SPEED)
    }

    @Test
    fun `stack cuts the overhang and falls when it misses`() {
        val game = StackGame(Random(2))
        game.tap()
        // Drop 30 units off along x: the floor keeps 70, the 30 overhang is cut.
        game.moving = game.moving.copy(x = 30f, z = 0f)
        game.tap()
        val floor = game.blocks.last()
        assertEquals(70f, floor.width, 0.01f)
        assertEquals(15f, floor.x, 0.01f)
        assertEquals(30f, game.lastCut!!.block.width, 0.01f)
        assertEquals(65f, game.lastCut!!.block.x, 0.01f)
        assertFalse(game.alongX)
        // Next floor slides along z; a drop beyond its whole depth misses.
        game.moving = game.moving.copy(z = 120f)
        game.tap()
        assertEquals(StackGame.State.DEAD, game.state)
        game.tap()
        assertEquals(StackGame.State.READY, game.state)
        assertEquals(0, game.height)
    }

    @Test
    fun `every game is registered`() {
        assertEquals(8, UnlockChallenges.count)
    }

    private fun PaperTossGame.flyOut() {
        var seconds = 0f
        while (state == PaperTossGame.State.FLYING && seconds < 5f) {
            step(1f / 60f)
            seconds += 1f / 60f
        }
    }

    @Test
    fun `paper toss aimed into the wind wins ten in a row`() {
        repeat(20) { seed ->
            val game = PaperTossGame(Random(seed))
            repeat(PaperTossGame.STREAK_TO_WIN) {
                assertTrue(game.throwAt(game.perfectAim()))
                game.flyOut()
                game.next()
            }
            assertEquals(PaperTossGame.State.WON, game.state)
            assertEquals(PaperTossGame.STREAK_TO_WIN, game.streak)
        }
    }

    @Test
    fun `paper toss thrown way off misses and resets the streak`() {
        val game = PaperTossGame(Random(3))
        game.throwAt(game.perfectAim())
        game.flyOut()
        assertEquals(PaperTossGame.State.SCORED, game.state)
        assertEquals(1, game.streak)
        game.next()
        game.throwAt(game.perfectAim() + 20f)
        game.flyOut()
        assertEquals(PaperTossGame.State.MISSED, game.state)
        assertEquals(0, game.streak)
        assertEquals(1, game.best)
    }

    @Test
    fun `paper toss wind pushes a straight throw sideways`() {
        val game = PaperTossGame(Random(1))
        var tries = 0
        while (kotlin.math.abs(game.wind) < 0.8f && tries++ < 50) {
            game.throwAt(game.perfectAim()); game.flyOut(); game.next()
        }
        // A strong gust: aiming dead ahead drifts off target.
        if (kotlin.math.abs(game.wind) >= 0.8f) {
            val before = game.wind
            game.throwAt(0f)
            game.flyOut()
            assertTrue(game.x * before > 0f)
        }
    }

    @Test
    fun `paper toss clipping the rim bounces out`() {
        val game = PaperTossGame(Random(5))
        val perfect = game.perfectAim()
        // Sweep the aim outward until a throw grazes the rim.
        var rim = false
        var offset = 0f
        while (!rim && offset < 6f) {
            offset += 0.05f
            val g = PaperTossGame(Random(5))
            g.throwAt(perfect + offset)
            g.flyOut()
            if (g.state == PaperTossGame.State.MISSED && g.hitRim) rim = true
        }
        assertTrue(rim)
    }
}
