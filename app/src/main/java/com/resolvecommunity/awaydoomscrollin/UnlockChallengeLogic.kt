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

/**
 * Picks unlock games like a shuffled deck: every game once, in random order,
 * then a fresh shuffle. A new round never starts with the game that ended the
 * last one, so the same game never shows twice in a row.
 */
object GameBag {
    /** The game to show and the bag left after it. Ids no longer offered are dropped. */
    fun draw(all: List<String>, bag: List<String>, last: String?, random: Random): Pair<String, List<String>> {
        var remaining = bag.filter { it in all }.distinct()
        if (remaining.isEmpty()) {
            remaining = all.shuffled(random)
            if (remaining.size > 1 && remaining.first() == last) remaining = remaining.drop(1) + remaining.first()
        }
        return remaining.first() to remaining.drop(1)
    }
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

/**
 * 2048: swipe to slide every tile; two equal tiles that meet merge into their
 * sum, once per move. Each move that changes the board adds a 2 (or, 1 time in
 * 10, a 4). Reaching [TARGET] unlocks.
 */
class Game2048(private val random: Random = Random.Default) {
    companion object {
        const val SIZE = 4
        const val TARGET = 2048
    }

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    /** Row-major, 0 for an empty cell. */
    val cells = IntArray(SIZE * SIZE)
    var score = 0
        private set

    val won: Boolean
        get() = cells.any { it >= TARGET }

    val bestTile: Int
        get() = cells.maxOrNull() ?: 0

    /** No empty cell and no two equal neighbours: nothing can move. */
    val over: Boolean
        get() = cells.none { it == 0 } && (0 until SIZE).none { r ->
            (0 until SIZE).any { c ->
                val v = cells[r * SIZE + c]
                (c + 1 < SIZE && cells[r * SIZE + c + 1] == v) ||
                    (r + 1 < SIZE && cells[(r + 1) * SIZE + c] == v)
            }
        }

    init {
        restart()
    }

    fun restart() {
        cells.fill(0)
        score = 0
        lastMoves = emptyList()
        lastMerged = emptySet()
        lastSpawn = -1
        spawn()
        spawn()
    }

    /** One tile's trip in the last move, as cell indices, with the value it had while moving. */
    data class TileMove(val from: Int, val to: Int, val value: Int)

    /** Tiles that moved or stayed in the last move, for the slide animation. */
    var lastMoves: List<TileMove> = emptyList()
        private set
    /** Cells that hold a merged tile after the last move. */
    var lastMerged: Set<Int> = emptySet()
        private set
    /** Cell of the tile added after the last move, or -1. */
    var lastSpawn = -1
        private set

    /** Slides the board; returns false (and adds nothing) when nothing moved. */
    fun move(direction: Direction): Boolean {
        val moves = mutableListOf<TileMove>()
        val merged = mutableSetOf<Int>()
        var moved = false
        for (line in 0 until SIZE) {
            val indices = lineIndices(direction, line)
            val values = indices.map { cells[it] }
            val result = slideLine(values)
            for ((source, target) in result.destinations) {
                moves += TileMove(indices[source], indices[target], values[source])
            }
            result.mergedAt.forEach { merged += indices[it] }
            if (result.values != values) {
                moved = true
                indices.forEachIndexed { i, index -> cells[index] = result.values[i] }
            }
        }
        if (!moved) return false
        score += merged.sumOf { cells[it] }
        lastMoves = moves
        lastMerged = merged
        lastSpawn = spawn()
        return true
    }

    /** Cell indices of one row/column, ordered from the side tiles slide toward. */
    private fun lineIndices(direction: Direction, line: Int): List<Int> = (0 until SIZE).map { i ->
        when (direction) {
            Direction.LEFT -> line * SIZE + i
            Direction.RIGHT -> line * SIZE + (SIZE - 1 - i)
            Direction.UP -> i * SIZE + line
            Direction.DOWN -> (SIZE - 1 - i) * SIZE + line
        }
    }

    internal class LineResult(
        val values: List<Int>,
        /** Source position to target position, for every tile in the line. */
        val destinations: List<Pair<Int, Int>>,
        val mergedAt: List<Int>
    )

    /** One line toward index 0: compact, merge equal pairs once, compact. */
    internal fun slideLine(values: List<Int>): LineResult {
        val tiles = values.indices.filter { values[it] != 0 }
        val out = mutableListOf<Int>()
        val destinations = mutableListOf<Pair<Int, Int>>()
        val mergedAt = mutableListOf<Int>()
        var i = 0
        while (i < tiles.size) {
            val target = out.size
            if (i + 1 < tiles.size && values[tiles[i]] == values[tiles[i + 1]]) {
                out += values[tiles[i]] * 2
                destinations += tiles[i] to target
                destinations += tiles[i + 1] to target
                mergedAt += target
                i += 2
            } else {
                out += values[tiles[i]]
                destinations += tiles[i] to target
                i++
            }
        }
        while (out.size < SIZE) out += 0
        return LineResult(out, destinations, mergedAt)
    }

    internal fun slide(values: List<Int>): List<Int> = slideLine(values).values

    /** Adds a 2 (or, 1 time in 10, a 4) on a random empty cell; returns it, or -1. */
    private fun spawn(): Int {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return -1
        val cell = empty.random(random)
        cells[cell] = if (random.nextInt(10) == 0) 4 else 2
        return cell
    }
}

/**
 * Sudoku: fill the 9x9 grid so every row, column and 3x3 box holds 1 to 9.
 * Puzzles are 81 digits row by row, 0 for an empty cell. Givens are fixed.
 */
class SudokuBoard(puzzle: String, private val solution: String) {
    companion object {
        const val SIZE = 9
    }

    private val givens = BooleanArray(SIZE * SIZE) { puzzle[it] != '0' }
    /** Current digits, 0 for empty. */
    val cells = IntArray(SIZE * SIZE) { puzzle[it] - '0' }

    init {
        require(puzzle.length == SIZE * SIZE && solution.length == SIZE * SIZE)
    }

    fun isGiven(index: Int): Boolean = givens[index]

    /** Sets (or with 0 clears) a non-given cell; false for a given. */
    fun set(index: Int, digit: Int): Boolean {
        if (givens[index] || digit !in 0..9) return false
        cells[index] = digit
        return true
    }

    /** True when the cell's digit repeats in its row, column or box. */
    fun conflicts(index: Int): Boolean {
        val digit = cells[index]
        if (digit == 0) return false
        val row = index / SIZE
        val col = index % SIZE
        return peers(row, col).any { it != index && cells[it] == digit }
    }

    val isSolved: Boolean
        get() = cells.indices.all { cells[it] == solution[it] - '0' }

    val filled: Int
        get() = cells.count { it != 0 }

    private fun peers(row: Int, col: Int): List<Int> {
        val boxRow = row / 3 * 3
        val boxCol = col / 3 * 3
        return (0 until SIZE).map { row * SIZE + it } +
            (0 until SIZE).map { it * SIZE + col } +
            (0 until 9).map { (boxRow + it / 3) * SIZE + boxCol + it % 3 }
    }
}

/** Generated puzzles (34 givens) with their unique solutions, checked by the tests. */
object SudokuPuzzles {
    val all: List<Pair<String, String>> = listOf(
        "009060508060091700000050000000406021080903000020015006500007683007500190408039007" to
            "379264518865391742241758369953476821186923475724815936592147683637582194418639257",
        "092010000531780204000900003320150709000603005000020300000009000710034000269500430" to
            "492315687531786294687942153326158749174693825958427316843269571715834962269571438",
        "130020900076094002092360540000600701000009630013200400040000190057030200300040000" to
            "134725986576894312892361547489653721725419638613287459248576193957138264361942875",
        "300200005002059006000083240080400702000320080000008401064000953009504100100930004" to
            "371246895842759316695183247983415762416327589527698431264871953739564128158932674",
        "000080600000000480400290003000420800709500234000310905070140000801950700504800096" to
            "395784621127635489486291573653429817719568234248317965972146358861953742534872196",
        "043070150005000070070302060320907400400006000050800390500700001800000639032068040" to
            "243679158695481273178352964326917485489536712751824396564793821817245639932168547",
        "070810040001000003089604120060325008520000074000000500902000481807900206006000700" to
            "275813649641792853389654127764325918528169374193487562932576481857941236416238795",
        "407695000100430570630012000003504017000009800000300200046020091002040008800906000" to
            "487695123129438576635712489263584917714269835958371264546823791392147658871956342",
        "020350600800040000000002973300400108000200005000095700937680001560901347402000000" to
            "721359684893746512645812973359467128176238495284195736937684251568921347412573869",
        "900000710106037002520600003300050200250100000801000064090421005010009408000800170" to
            "983542716146937852527618943369754281254186397871293564798421635615379428432865179",
        "000013004003070980900000601604008302000405006100006450800009017020501003031207000" to
            "586913724213674985947852631654198372378425196192736458865349217729561843431287569",
        "089750000175062000603004570840070050030900164000400000000049317090008000504000600" to
            "489751236175362849623894571846173952732985164951426783268549317397618425514237698",
        "000004735000090600000000029900830004180047060750102003310750240000021300400006050" to
            "869214735247593618531678429926835174183947562754162893318759246695421387472386951",
        "060301000000400620730080005000013006080690002603002800100030004048025701207004050" to
            "462351987815479623739286145924813576581697432673542819156738294348925761297164358",
        "708261040004007069003905287000030800026019304400020050002006908005000006000002000" to
            "798261543254387169163945287517634892826519374439728651372456918985173426641892735",
        "080790061000600030016003750003000005090026080804000096000032540000409618540100000" to
            "382795461975614832416283759623948175791526384854371296169832547237459618548167923",
        "020000005090376001416090008950108000108020000670500100000000307200453010340700060" to
            "723814695895376421416295738954138276138627954672549183581962347267453819349781562",
        "001790006206300700700200053050002000003070610007000820374921500060050030900030070" to
            "531794286286315749749286153658142397423879615197563824374921568862457931915638472",
        "370000019000037600064000073000000008036050002005100736048279060000001900093800240" to
            "372486519851937624964512873719623458436758192285194736548279361627341985193865247",
        "803160400020070030490005010934020070010008020200040560300007000158600700002000301" to
            "873162459521479836496385217934526178615738924287941563349217685158693742762854391",
        "342000065000409300800365100709080514200001600010906270070008000000000006004000859" to
            "342817965651429387897365142769283514285741693413956278976538421528194736134672859",
        "821003470005004010004108030012406090070082301000301004900000047000000920240000003" to
            "821563479635794812794128635312456798476982351589371264968235147153847926247619583",
        "098010200307006001050040000001008956000901020020600047200000400400782095070054010" to
            "698517234347296581152843769731428956564971328829635147285169473413782695976354812",
        "906108700000046000402903080001700820620005003007682000009860040000007010000394200" to
            "956128734783546192412973685541739826628415973397682451239861547864257319175394268",
        "000400600207560039080003000870605200096000050005000070009030025000724901760051040" to
            "953417682217568439684293517871645293396172854425389176149836725538724961762951348",
        "085000010000804020760010040807350100003020085200008904000197050500243001000000270" to
            "485732619139864527762915348847359162693421785251678934328197456576243891914586273",
        "608501009597420000001008547900706800860000023000830005006240000000000000410060908" to
            "648571239597423186231698547923756814865914723174832695786249351359187462412365978",
        "002005170050726009006000500001060437060248950504000020040050000610002000083091000" to
            "432985176158726349976314582821569437367248951594173628249657813615832794783491265",
        "006490803000003190009000005564800007000304906100270000070002600020060040048030729" to
            "216495873457683192839127465564819237782354916193276584371942658925768341648531729",
        "001342000260015900400080000014037569059400017000100300000000602086003001000074800" to
            "591342786268715934437986125814237569359468217672159348743891652986523471125674893",
    )

    fun random(random: Random = Random.Default): Pair<String, String> = all.random(random)
}

/**
 * Stack: a block slides back and forth above the tower, along x and z in
 * turn; a tap drops it. The overhang is cut off, so the tower narrows with
 * every miss; a drop within [PERFECT] keeps the full size. Missing the tower
 * entirely ends the run. Reaching [TARGET] floors unlocks. Units are an
 * abstract floor plan; the view projects it in 3D.
 */
class StackGame(private val random: Random = Random.Default) {
    companion object {
        const val BASE_SIZE = 100f
        const val RANGE = 130f
        const val PERFECT = 4f
        const val TARGET = 40
        const val BASE_SPEED = 110f
        const val SPEED_STEP = 3f
        const val MAX_SPEED = 240f
    }

    enum class State { READY, PLAYING, DEAD, WON }

    /** A placed floor: centre and size on the floor plan. */
    data class Block(val x: Float, val z: Float, val width: Float, val depth: Float)

    /** The part cut off by the last drop, at the level it was cut, or null. */
    data class Cut(val block: Block, val level: Int)

    var state = State.READY
        private set
    val blocks = mutableListOf<Block>()
    /** The sliding block; its level is [height] + 1. */
    var moving: Block = Block(0f, 0f, BASE_SIZE, BASE_SIZE)
        internal set
    /** Whether the sliding block moves along x (else along z). */
    var alongX = true
        private set
    private var direction = 1f
    var lastCut: Cut? = null
        private set
    var lastPerfect = false
        private set
    /** Consecutive perfect drops, for the view's flourish. */
    var combo = 0
        private set

    /** Floors stacked on the base. */
    val height: Int
        get() = blocks.size - 1

    val speed: Float
        get() = (BASE_SPEED + height * SPEED_STEP).coerceAtMost(MAX_SPEED)

    init {
        reset()
    }

    /** Tap: start, drop, or (after a fall) get ready for another run. */
    fun tap() {
        when (state) {
            State.READY -> {
                state = State.PLAYING
                spawn()
            }
            State.PLAYING -> drop()
            State.DEAD -> reset()
            State.WON -> Unit
        }
    }

    fun step(seconds: Float) {
        if (state != State.PLAYING) return
        val top = blocks.last()
        val centre = if (alongX) top.x else top.z
        var position = (if (alongX) moving.x else moving.z) + direction * speed * seconds.coerceIn(0f, 0.1f)
        if (position > centre + RANGE) {
            position = centre + RANGE
            direction = -1f
        } else if (position < centre - RANGE) {
            position = centre - RANGE
            direction = 1f
        }
        moving = if (alongX) moving.copy(x = position) else moving.copy(z = position)
    }

    private fun drop() {
        val top = blocks.last()
        val level = height + 1
        val delta = if (alongX) moving.x - top.x else moving.z - top.z
        val size = if (alongX) moving.width else moving.depth
        lastPerfect = false
        val placed: Block
        if (kotlin.math.abs(delta) <= PERFECT) {
            placed = top.copy()
            lastPerfect = true
            combo++
            lastCut = null
        } else {
            val overlap = size - kotlin.math.abs(delta)
            combo = 0
            if (overlap <= 0f) {
                lastCut = Cut(moving, level)
                state = State.DEAD
                return
            }
            val sign = if (delta > 0) 1f else -1f
            placed = if (alongX) {
                moving.copy(x = top.x + delta / 2, width = overlap)
            } else {
                moving.copy(z = top.z + delta / 2, depth = overlap)
            }
            // The overhang, beyond the tower's edge on the side it slid past.
            val cutSize = kotlin.math.abs(delta)
            val edge = (if (alongX) top.x else top.z) + sign * size / 2
            val cutCentre = edge + sign * cutSize / 2
            lastCut = Cut(if (alongX) moving.copy(x = cutCentre, width = cutSize)
                else moving.copy(z = cutCentre, depth = cutSize), level)
        }
        blocks += placed
        if (height >= TARGET) {
            state = State.WON
            return
        }
        alongX = !alongX
        spawn()
    }

    /** The next block enters from a random side, the size of the top floor. */
    private fun spawn() {
        val top = blocks.last()
        val side = if (random.nextBoolean()) 1f else -1f
        direction = -side
        moving = if (alongX) top.copy(x = top.x + side * RANGE) else top.copy(z = top.z + side * RANGE)
    }

    private fun reset() {
        state = State.READY
        blocks.clear()
        blocks += Block(0f, 0f, BASE_SIZE, BASE_SIZE)
        alongX = true
        lastCut = null
        lastPerfect = false
        combo = 0
        moving = Block(0f, 0f, BASE_SIZE, BASE_SIZE)
    }
}
