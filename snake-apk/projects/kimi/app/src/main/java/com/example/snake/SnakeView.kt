package com.example.snake

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * The whole game in one view: board, snake, food, score HUD,
 * swipe controls, game-over/restart handling and the game loop.
 */
class SnakeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Dir { UP, DOWN, LEFT, RIGHT }
    private enum class State { RUNNING, PAUSED, GAME_OVER }
    private data class Cell(val x: Int, val y: Int)

    // ---- board geometry ----
    private val cols = 22
    private var rows = 24
    private var cell = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardRight = 0f
    private var boardBottom = 0f
    private var topBar = 0f

    // ---- game state ----
    private val snake = ArrayList<Cell>()
    private var dir = Dir.RIGHT
    private var pendingDir = Dir.RIGHT
    private var food = Cell(5, 5)
    private var score = 0
    private var state = State.PAUSED
    private var startedAt = 0L
    private var initialized = false

    private val prefs = context.getSharedPreferences("snake_prefs", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)

    private val density = resources.displayMetrics.density

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (state != State.RUNNING) return
            step()
            invalidate()
            handler.postDelayed(this, tickDelay())
        }
    }

    // ---- paints ----
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(19, 26, 36) }
    private val gridPaint = Paint().apply { color = Color.rgb(27, 36, 50); strokeWidth = 1f }
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 82, 82) }
    private val foodShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val snakePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val eyePupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 20, 27) }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 240, 248)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 140, 165) }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 240, 248)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply { color = Color.argb(190, 8, 11, 16) }
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f

    // ---- layout ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        topBar = density * 64
        val availW = w.toFloat()
        val availH = h - topBar - density * 8
        cell = min(availW / cols, availH / cols)
        rows = (availH / cell).toInt().coerceAtLeast(8)
        boardLeft = (w - cols * cell) / 2f
        boardTop = topBar + (availH - rows * cell) / 2f
        boardRight = boardLeft + cols * cell
        boardBottom = boardTop + rows * cell
        if (!initialized) {
            initialized = true
            startGame()
        }
    }

    // ---- game logic ----

    private fun startGame() {
        snake.clear()
        val cy = rows / 2
        val cx = cols / 2
        snake.add(Cell(cx, cy))
        snake.add(Cell(cx - 1, cy))
        snake.add(Cell(cx - 2, cy))
        dir = Dir.RIGHT
        pendingDir = Dir.RIGHT
        score = 0
        startedAt = System.currentTimeMillis()
        spawnFood()
        state = State.RUNNING
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        invalidate()
    }

    private fun spawnFood() {
        val free = ArrayList<Cell>()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val c = Cell(x, y)
                if (!snake.contains(c)) free.add(c)
            }
        }
        if (free.isEmpty()) {
            gameOver()
            return
        }
        food = free[Random.nextInt(free.size)]
    }

    private fun step() {
        dir = pendingDir
        val head = snake[0]
        val next = when (dir) {
            Dir.UP -> Cell(head.x, head.y - 1)
            Dir.DOWN -> Cell(head.x, head.y + 1)
            Dir.LEFT -> Cell(head.x - 1, head.y)
            Dir.RIGHT -> Cell(head.x + 1, head.y)
        }
        if (next.x < 0 || next.x >= cols || next.y < 0 || next.y >= rows) {
            gameOver()
            return
        }
        val grows = next == food
        // when not growing the tail cell vacates this tick, so it is not an obstacle
        val limit = if (grows) snake.size else snake.size - 1
        for (i in 0 until limit) {
            if (snake[i] == next) {
                gameOver()
                return
            }
        }
        snake.add(0, next)
        if (grows) {
            score++
            if (score > best) {
                best = score
                prefs.edit().putInt("best", best).apply()
            }
            spawnFood()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            snake.removeAt(snake.size - 1)
        }
    }

    private fun gameOver() {
        state = State.GAME_OVER
        if (score > best) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
        handler.removeCallbacks(ticker)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    // speed increases with score and with elapsed time
    private fun tickDelay(): Long {
        val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
        val d = 320L - score * 6L - (elapsedSec / 15) * 6L
        return if (d < 80L) 80L else d
    }

    fun pauseGame() {
        if (state == State.RUNNING) {
            state = State.PAUSED
            invalidate()
        }
        handler.removeCallbacks(ticker)
    }

    fun resumeGame() {
        if (state == State.PAUSED && initialized) {
            state = State.RUNNING
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            invalidate()
        }
    }

    // ---- input ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                when (state) {
                    State.GAME_OVER -> startGame()
                    State.PAUSED -> resumeGame()
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (state == State.RUNNING) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val minSwipe = density * 20
                    if (abs(dx) >= minSwipe || abs(dy) >= minSwipe) {
                        if (abs(dx) >= abs(dy)) {
                            steer(if (dx > 0) Dir.RIGHT else Dir.LEFT)
                        } else {
                            steer(if (dy > 0) Dir.DOWN else Dir.UP)
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun steer(d: Dir) {
        val opposite = when (dir) {
            Dir.UP -> d == Dir.DOWN
            Dir.DOWN -> d == Dir.UP
            Dir.LEFT -> d == Dir.RIGHT
            Dir.RIGHT -> d == Dir.LEFT
        }
        if (!opposite) pendingDir = d
    }

    // ---- rendering ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(13, 18, 25))

        // board
        rect.set(boardLeft, boardTop, boardRight, boardBottom)
        canvas.drawRoundRect(rect, cell * 0.3f, cell * 0.3f, boardPaint)

        // grid
        var gx = boardLeft + cell
        while (gx < boardRight - 1) {
            canvas.drawLine(gx, boardTop, gx, boardBottom, gridPaint)
            gx += cell
        }
        var gy = boardTop + cell
        while (gy < boardBottom - 1) {
            canvas.drawLine(boardLeft, gy, boardRight, gy, gridPaint)
            gy += cell
        }

        // food
        val fx = boardLeft + (food.x + 0.5f) * cell
        val fy = boardTop + (food.y + 0.5f) * cell
        canvas.drawCircle(fx, fy, cell * 0.38f, foodPaint)
        canvas.drawCircle(fx - cell * 0.12f, fy - cell * 0.12f, cell * 0.1f, foodShine)

        // snake (tail first so head is on top)
        val headColor = Color.rgb(139, 224, 90)
        val tailColor = Color.rgb(46, 125, 50)
        val inset = cell * 0.08f
        val radius = cell * 0.28f
        val n = snake.size
        for (i in n - 1 downTo 0) {
            val c = snake[i]
            val f = if (n <= 1) 0f else i.toFloat() / (n - 1)
            snakePaint.color = blend(headColor, tailColor, f)
            rect.set(
                boardLeft + c.x * cell + inset,
                boardTop + c.y * cell + inset,
                boardLeft + (c.x + 1) * cell - inset,
                boardTop + (c.y + 1) * cell - inset
            )
            canvas.drawRoundRect(rect, radius, radius, snakePaint)
        }

        // eyes
        if (snake.isNotEmpty()) {
            val h = snake[0]
            val cx = boardLeft + (h.x + 0.5f) * cell
            val cy = boardTop + (h.y + 0.5f) * cell
            val off = cell * 0.16f
            val fwd = cell * 0.1f
            val eyes = when (dir) {
                Dir.RIGHT -> floatArrayOf(cx + fwd, cy - off, cx + fwd, cy + off)
                Dir.LEFT -> floatArrayOf(cx - fwd, cy - off, cx - fwd, cy + off)
                Dir.UP -> floatArrayOf(cx - off, cy - fwd, cx + off, cy - fwd)
                Dir.DOWN -> floatArrayOf(cx - off, cy + fwd, cx + off, cy + fwd)
            }
            canvas.drawCircle(eyes[0], eyes[1], cell * 0.09f, eyeWhite)
            canvas.drawCircle(eyes[2], eyes[3], cell * 0.09f, eyeWhite)
            canvas.drawCircle(eyes[0], eyes[1], cell * 0.045f, eyePupil)
            canvas.drawCircle(eyes[2], eyes[3], cell * 0.045f, eyePupil)
        }

        // score bar
        labelPaint.textSize = density * 13
        scorePaint.textSize = density * 22
        val pad = density * 16
        canvas.drawText("SCORE", pad, topBar / 2 - density * 4, labelPaint)
        canvas.drawText(score.toString(), pad, topBar / 2 + density * 20, scorePaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        scorePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("BEST", width - pad, topBar / 2 - density * 4, labelPaint)
        canvas.drawText(best.toString(), width - pad, topBar / 2 + density * 20, scorePaint)
        labelPaint.textAlign = Paint.Align.LEFT
        scorePaint.textAlign = Paint.Align.LEFT

        // early hint
        if (state == State.RUNNING && score == 0 &&
            System.currentTimeMillis() - startedAt < 6000
        ) {
            centerPaint.textSize = density * 15
            centerPaint.color = Color.argb(160, 200, 214, 230)
            val hy = if (height - boardBottom > density * 40) {
                boardBottom + (height - boardBottom) / 2 + density * 5
            } else {
                boardBottom - cell * 0.6f
            }
            canvas.drawText("Swipe to steer", width / 2f, hy, centerPaint)
        }

        // overlays
        if (state == State.PAUSED) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            centerPaint.color = Color.rgb(232, 240, 248)
            centerPaint.textSize = density * 34
            canvas.drawText("PAUSED", width / 2f, height / 2f - density * 10, centerPaint)
            centerPaint.textSize = density * 16
            canvas.drawText("Tap to resume", width / 2f, height / 2f + density * 26, centerPaint)
        } else if (state == State.GAME_OVER) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            centerPaint.color = Color.rgb(255, 107, 107)
            centerPaint.textSize = density * 40
            canvas.drawText("GAME OVER", width / 2f, height / 2f - density * 40, centerPaint)
            centerPaint.color = Color.rgb(232, 240, 248)
            centerPaint.textSize = density * 20
            canvas.drawText(
                "Score: $score    Best: $best",
                width / 2f, height / 2f + density * 2, centerPaint
            )
            centerPaint.textSize = density * 16
            centerPaint.color = Color.rgb(150, 170, 195)
            canvas.drawText("Tap to restart", width / 2f, height / 2f + density * 40, centerPaint)
        }
    }

    private fun blend(c1: Int, c2: Int, f: Float): Int {
        val r = Color.red(c1) + ((Color.red(c2) - Color.red(c1)) * f).toInt()
        val g = Color.green(c1) + ((Color.green(c2) - Color.green(c1)) * f).toInt()
        val b = Color.blue(c1) + ((Color.blue(c2) - Color.blue(c1)) * f).toInt()
        return Color.rgb(r, g, b)
    }
}
