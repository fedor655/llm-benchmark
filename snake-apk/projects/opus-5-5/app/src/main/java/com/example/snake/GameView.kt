package com.example.snake

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders and drives the game. The board is drawn as a centred grid of squares
 * inside the available space; the HUD sits in a fixed band above it.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { READY, RUNNING, PAUSED, OVER }

    private val prefs = context.getSharedPreferences("snake", Context.MODE_PRIVATE)

    var game = SnakeGame()
        private set
    var state: State = State.READY
        private set

    var best: Int = prefs.getInt(KEY_BEST, 0)
        private set

    private var newBest = false
    private var foodPulseStart = 0L
    private var popStart = 0L
    private var popX = 0f
    private var popY = 0f
    private var pausedByLifecycle = false

    // --- layout, recomputed in onSizeChanged ---
    private var cell = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardW = 0f
    private var boardH = 0f

    // --- input ---
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private val swipeSlop = dp(22f)

    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (state != State.RUNNING) return
            when (game.step()) {
                StepResult.ATE -> onEat()
                StepResult.DIED -> {
                    onDeath()
                    return
                }
                StepResult.MOVED -> Unit
            }
            invalidate()
            scheduleTick()
        }
    }

    // --- paints ---
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BG }
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BOARD }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_CELL }
    private val gridPaint = Paint().apply {
        color = C_GRID
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_HEAD }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_EYE }
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_FOOD }
    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_LEAF }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val popPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C_HEAD
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val bigTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply { color = 0xB2000000.toInt() }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_PANEL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val hudHeight = dp(56f)
        val pad = dp(10f)
        val availW = w - pad * 2
        val availH = h - hudHeight - pad * 2
        if (availW <= 0 || availH <= 0) return

        cell = min(availW / game.cols, availH / game.rows)
        boardW = cell * game.cols
        boardH = cell * game.rows
        boardLeft = (w - boardW) / 2f
        boardTop = hudHeight + pad + (availH - boardH) / 2f
        gridPaint.strokeWidth = dp(1f).coerceAtLeast(1f)
    }

    // ---------------------------------------------------------------- lifecycle

    fun startGame() {
        game = SnakeGame()
        newBest = false
        state = State.RUNNING
        pausedByLifecycle = false
        popStart = 0L
        foodPulseStart = SystemClock.uptimeMillis()
        invalidate()
        scheduleTick()
    }

    fun togglePause() {
        when (state) {
            State.RUNNING -> pause()
            State.PAUSED -> resume()
            else -> Unit
        }
    }

    fun pause() {
        if (state != State.RUNNING) return
        state = State.PAUSED
        handler.removeCallbacks(tickRunnable)
        invalidate()
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.RUNNING
        pausedByLifecycle = false
        invalidate()
        scheduleTick()
    }

    fun onHostPause() {
        if (state == State.RUNNING) {
            pausedByLifecycle = true
            pause()
        }
    }

    fun onHostResume() {
        if (state == State.PAUSED && pausedByLifecycle) resume()
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
    }

    // ------------------------------------------------------------------ gameplay

    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, game.stepMillis().toLong())
    }

    private fun onEat() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        foodPulseStart = SystemClock.uptimeMillis()

        val h = game.head()
        popX = boardLeft + (game.xOf(h) + 0.5f) * cell
        popY = boardTop + (game.yOf(h) + 0.5f) * cell
        popStart = SystemClock.uptimeMillis()
    }

    private fun onDeath() {
        handler.removeCallbacks(tickRunnable)
        state = State.OVER
        if (game.score > best) {
            best = game.score
            newBest = true
            prefs.edit().putInt(KEY_BEST, best).apply()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    // --------------------------------------------------------------------- input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiped = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (hypot(dx, dy) >= swipeSlop) {
                    if (state == State.READY || state == State.OVER) {
                        startGame()
                    } else if (state == State.PAUSED) {
                        resume()
                    }
                    game.queueDirection(Direction.fromSwipe(dx, dy))
                    downX = event.x
                    downY = event.y
                    swiped = true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!swiped) handleTap()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap() {
        when (state) {
            State.READY, State.OVER -> startGame()
            State.RUNNING -> pause()
            State.PAUSED -> resume()
        }
    }

    // ---------------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (cell > 0f) {
            drawBoard(canvas)
            drawFood(canvas)
            drawSnake(canvas)
            drawPops(canvas)
        }
        drawHud(canvas)
        drawOverlay(canvas)

        if (state == State.RUNNING) postInvalidateOnAnimation()
    }

    private fun drawBoard(canvas: Canvas) {
        canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, boardPaint)

        // Subtle checkerboard so the grid reads without shouting.
        for (y in 0 until game.rows) {
            for (x in 0 until game.cols) {
                if ((x + y) % 2 == 0) continue
                rect.set(
                    boardLeft + x * cell,
                    boardTop + y * cell,
                    boardLeft + (x + 1) * cell,
                    boardTop + (y + 1) * cell
                )
                canvas.drawRect(rect, cellPaint)
            }
        }

        for (x in 1 until game.cols) {
            val px = boardLeft + x * cell
            canvas.drawLine(px, boardTop, px, boardTop + boardH, gridPaint)
        }
        for (y in 1 until game.rows) {
            val py = boardTop + y * cell
            canvas.drawLine(boardLeft, py, boardLeft + boardW, py, gridPaint)
        }
    }

    private fun drawSnake(canvas: Canvas) {
        val radius = cell * 0.28f
        val n = game.length
        for (i in n - 1 downTo 0) {
            val cellIdx = game[i]
            val cx = boardLeft + game.xOf(cellIdx) * cell
            val cy = boardTop + game.yOf(cellIdx) * cell
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1).toFloat()

            val paint = if (i == 0) {
                headPaint
            } else {
                bodyPaint.color = blend(C_BODY_NEAR, C_BODY_FAR, t)
                bodyPaint
            }

            val inset = if (i == 0) cell * 0.03f else cell * 0.07f
            rect.set(cx + inset, cy + inset, cx + cell - inset, cy + cell - inset)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        drawEyes(canvas)
    }

    private fun drawEyes(canvas: Canvas) {
        if (game.length == 0) return
        val head = game.head()
        val hx = boardLeft + game.xOf(head) * cell
        val hy = boardTop + game.yOf(head) * cell
        val r = cell * 0.075f
        val off = cell * 0.26f
        val shift = cell * 0.10f
        val c = cell / 2f

        // Eyes sit perpendicular to travel, nudged toward the direction of travel.
        val ax: Float
        val ay: Float
        val sx: Float
        val sy: Float
        when (game.direction) {
            Direction.UP -> {
                ax = off; ay = 0f; sx = 0f; sy = -shift
            }
            Direction.DOWN -> {
                ax = off; ay = 0f; sx = 0f; sy = shift
            }
            Direction.LEFT -> {
                ax = 0f; ay = off; sx = -shift; sy = 0f
            }
            Direction.RIGHT -> {
                ax = 0f; ay = off; sx = shift; sy = 0f
            }
        }

        canvas.drawCircle(hx + c + ax + sx, hy + c + ay + sy, r, eyePaint)
        canvas.drawCircle(hx + c - ax + sx, hy + c - ay + sy, r, eyePaint)
    }

    private fun drawFood(canvas: Canvas) {
        val f = game.food
        if (f < 0 || !game.alive) return

        val cx = boardLeft + (game.xOf(f) + 0.5f) * cell
        val cy = boardTop + (game.yOf(f) + 0.5f) * cell

        val elapsed = (SystemClock.uptimeMillis() - foodPulseStart) % 900L
        val angle = elapsed / 900.0 * 2 * Math.PI
        val pulse = 1f + 0.07f * sin(angle).toFloat()
        val r = cell * 0.30f * pulse

        canvas.drawCircle(cx, cy, r, foodPaint)
        canvas.drawCircle(cx - r * 0.3f, cy - r * 0.32f, r * 0.28f, shinePaint)

        // Stalk + leaf so the fruit reads as an apple rather than a dot.
        rect.set(cx - r * 0.10f, cy - r * 1.55f, cx + r * 0.12f, cy - r * 0.85f)
        canvas.drawRoundRect(rect, r * 0.1f, r * 0.1f, leafPaint)
        rect.set(cx + r * 0.05f, cy - r * 1.55f, cx + r * 0.85f, cy - r * 1.0f)
        canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, leafPaint)
    }

    private fun drawPops(canvas: Canvas) {
        if (popStart == 0L) return
        val age = SystemClock.uptimeMillis() - popStart
        if (age > 420L) {
            popStart = 0L
            return
        }
        val t = age / 420f
        popPaint.strokeWidth = cell * 0.16f * (1f - t)
        popPaint.alpha = ((1f - t) * 220).toInt().coerceIn(0, 255)
        canvas.drawCircle(popX, popY, cell * (0.45f + 0.9f * t), popPaint)
        popPaint.alpha = 255
    }

    private fun drawHud(canvas: Canvas) {
        val pad = dp(16f)
        val baseline = dp(24f)

        textPaint.textSize = dp(15f)
        textPaint.color = C_LABEL
        canvas.drawText("SCORE", pad, baseline, textPaint)
        canvas.drawText("BEST", width - pad - textPaint.measureText("BEST"), baseline, textPaint)

        textPaint.textSize = dp(26f)
        textPaint.color = C_HEAD
        canvas.drawText(game.score.toString(), pad, baseline + dp(26f), textPaint)

        textPaint.color = C_LABEL
        val bestText = best.toString()
        canvas.drawText(
            bestText,
            width - pad - textPaint.measureText(bestText),
            baseline + dp(26f),
            textPaint
        )

        // Speed meter: ten dots, filled up to the current level.
        val level = game.speedLevel()
        val dotR = dp(3.5f)
        val gap = dp(11f)
        var dx = (width - gap * 9) / 2f
        val dy = dp(38f)
        for (i in 0 until 10) {
            dotPaint.color = if (i < level) C_HEAD else C_DOT
            canvas.drawCircle(dx, dy, dotR, dotPaint)
            dx += gap
        }
    }

    private fun drawOverlay(canvas: Canvas) {
        if (state == State.RUNNING) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val panelW = min(width * 0.82f, dp(340f))
        val panelH = dp(if (state == State.PAUSED) 150f else 210f)
        val left = (width - panelW) / 2f
        val top = (height - panelH) / 2f
        rect.set(left, top, left + panelW, top + panelH)
        canvas.drawRoundRect(rect, dp(20f), dp(20f), panelPaint)

        val cx = width / 2f
        var y = top + dp(46f)

        when (state) {
            State.READY -> {
                bigTextPaint.textSize = dp(24f)
                bigTextPaint.color = C_HEAD
                canvas.drawText("SNAKE", cx, y, bigTextPaint)
                y += dp(34f)

                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = dp(15f)
                textPaint.color = C_LABEL
                canvas.drawText("Swipe to steer", cx, y, textPaint)
                y += dp(24f)
                canvas.drawText("Tap to pause", cx, y, textPaint)
                y += dp(40f)

                bigTextPaint.textSize = dp(17f)
                bigTextPaint.color = C_HEAD
                canvas.drawText("TAP TO START", cx, y, bigTextPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }

            State.PAUSED -> {
                bigTextPaint.textSize = dp(23f)
                bigTextPaint.color = C_HEAD
                canvas.drawText("PAUSED", cx, y, bigTextPaint)
                y += dp(38f)

                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = dp(15f)
                textPaint.color = C_LABEL
                canvas.drawText("Score ${game.score}", cx, y, textPaint)
                y += dp(40f)

                bigTextPaint.textSize = dp(17f)
                bigTextPaint.color = C_HEAD
                canvas.drawText("TAP TO RESUME", cx, y, bigTextPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }

            State.OVER -> {
                bigTextPaint.textSize = dp(24f)
                bigTextPaint.color = C_FOOD
                canvas.drawText("GAME OVER", cx, y, bigTextPaint)
                y += dp(42f)

                bigTextPaint.textSize = dp(40f)
                bigTextPaint.color = C_HEAD
                canvas.drawText(game.score.toString(), cx, y, bigTextPaint)
                y += dp(26f)

                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = dp(15f)
                textPaint.color = C_LABEL
                canvas.drawText("Best $best", cx, y, textPaint)
                y += dp(26f)

                if (newBest) {
                    textPaint.color = C_HEAD
                    canvas.drawText("NEW BEST!", cx, y, textPaint)
                    y += dp(26f)
                }

                y += dp(8f)
                bigTextPaint.textSize = dp(17f)
                bigTextPaint.color = C_HEAD
                canvas.drawText("TAP TO PLAY AGAIN", cx, y, bigTextPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }

            State.RUNNING -> Unit
        }
    }

    // -------------------------------------------------------------------- utils

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun blend(from: Int, to: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * tt).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * tt).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * tt).toInt()
        return Color.rgb(r, g, b)
    }

    private companion object {
        const val KEY_BEST = "best"

        val C_BG = Color.parseColor("#FF0E141A")
        val C_BOARD = Color.parseColor("#FF16202B")
        val C_CELL = Color.parseColor("#FF192532")
        val C_GRID = Color.parseColor("#FF1E2B3A")
        val C_HEAD = Color.parseColor("#FF7CE87C")
        val C_BODY_NEAR = Color.parseColor("#FF62DC63")
        val C_BODY_FAR = Color.parseColor("#FF2E8F4E")
        val C_EYE = Color.parseColor("#FF0E141A")
        val C_FOOD = Color.parseColor("#FFFF6B5B")
        val C_LEAF = Color.parseColor("#FF4CAF50")
        val C_LABEL = Color.parseColor("#FF8FA3B5")
        val C_DOT = Color.parseColor("#FF2B3A4A")
        val C_PANEL = Color.parseColor("#FF1B2733")
    }
}
