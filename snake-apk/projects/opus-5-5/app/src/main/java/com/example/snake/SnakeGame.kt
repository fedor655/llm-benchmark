package com.example.snake

import kotlin.collections.ArrayDeque
import kotlin.random.Random

/** The four movement directions the snake can travel in. */
enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    fun isOpposite(other: Direction): Boolean = dx + other.dx == 0 && dy + other.dy == 0

    companion object {
        /** Picks the direction that best matches a swipe vector. */
        fun fromSwipe(dx: Float, dy: Float): Direction =
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                if (dx > 0) RIGHT else LEFT
            } else {
                if (dy > 0) DOWN else UP
            }
    }
}

/** Outcome of a single simulation tick. */
enum class StepResult { MOVED, ATE, DIED }

/**
 * Pure game logic for Snake: no Android types in here, so it can be reasoned
 * about (and tested) on its own.
 *
 * Cells are packed into a single Int as `y * cols + x`, which keeps the body
 * deque cheap and lets collision checks compare plain ints.
 */
class SnakeGame(val cols: Int = 16, val rows: Int = 28) {

    companion object {
        /** Milliseconds per step at the start of a run. */
        const val BASE_STEP_MS = 230f

        /** Fastest the snake is ever allowed to move. */
        const val MIN_STEP_MS = 70f
    }

    private val body = ArrayDeque<Int>()
    private val pending = ArrayDeque<Direction>()

    var direction: Direction = Direction.RIGHT
        private set

    /** Packed index of the current fruit, or -1 when the board is full. */
    var food: Int = -1
        private set

    var score: Int = 0
        private set

    var ticks: Int = 0
        private set

    var alive: Boolean = true
        private set

    val length: Int get() = body.size

    init {
        reset()
    }

    private fun index(x: Int, y: Int) = y * cols + x

    fun xOf(cell: Int) = cell % cols
    fun yOf(cell: Int) = cell / cols

    operator fun get(i: Int): Int = body[i]

    fun head(): Int = body.first()

    fun reset() {
        body.clear()
        pending.clear()
        score = 0
        ticks = 0
        alive = true
        direction = Direction.RIGHT

        val cy = rows / 2
        val cx = (cols / 2).coerceAtLeast(2)
        body.addLast(index(cx, cy))
        body.addLast(index(cx - 1, cy))
        body.addLast(index(cx - 2, cy))
        spawnFood()
    }

    /**
     * Queues a turn. Reversals onto the snake's own neck are dropped, and at
     * most two turns are buffered so a fast double-swipe is not lost.
     */
    fun queueDirection(next: Direction) {
        if (!alive) return
        val last = if (pending.isEmpty()) direction else pending.last()
        if (next == last || next.isOpposite(last)) return
        if (pending.size < 2) pending.addLast(next)
    }

    /** Milliseconds between steps right now. Shrinks with score and with time. */
    fun stepMillis(): Float {
        val fromScore = score * 7f
        val fromTime = ticks * 1.5f
        return (BASE_STEP_MS - fromScore - fromTime).coerceAtLeast(MIN_STEP_MS)
    }

    /** 1..10, used by the HUD speed meter. */
    fun speedLevel(): Int {
        val span = BASE_STEP_MS - MIN_STEP_MS
        val t = ((BASE_STEP_MS - stepMillis()) / span).coerceIn(0f, 1f)
        return 1 + (t * 9f).toInt()
    }

    fun step(): StepResult {
        if (!alive) return StepResult.DIED

        if (pending.isNotEmpty()) direction = pending.removeFirst()

        val head = head()
        val nx = xOf(head) + direction.dx
        val ny = yOf(head) + direction.dy

        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) {
            alive = false
            return StepResult.DIED
        }

        val newHead = index(nx, ny)
        val growing = newHead == food

        // The tail cell is vacated on the same tick, unless the snake is growing.
        for (i in 0 until body.size) {
            if (body[i] == newHead && (i != body.size - 1 || growing)) {
                alive = false
                return StepResult.DIED
            }
        }

        body.addFirst(newHead)
        ticks++

        if (growing) {
            score++
            spawnFood()
            return StepResult.ATE
        }

        body.removeLast()
        return StepResult.MOVED
    }

    private fun spawnFood() {
        val total = cols * rows
        if (body.size >= total) {
            food = -1
            return
        }
        // Pick uniformly among free cells rather than rejection-sampling, so a
        // crowded board never spins.
        val free = ArrayList<Int>(total - body.size)
        for (c in 0 until total) {
            var taken = false
            for (i in 0 until body.size) {
                if (body[i] == c) {
                    taken = true
                    break
                }
            }
            if (!taken) free.add(c)
        }
        food = if (free.isEmpty()) -1 else free[Random.nextInt(free.size)]
    }
}
