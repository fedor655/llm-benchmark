package com.example.snake.claude

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game: model, rendering and input in one custom View.
 * Movement is tick-based on a grid, but drawn interpolated between ticks so the snake glides.
 */
class GameView(context: Context) : View(context) {

    private enum class State { READY, PLAYING, PAUSED, OVER }

    private enum class Dir(val dx: Int, val dy: Int) {
        UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

        fun isOpposite(o: Dir) = dx == -o.dx && dy == -o.dy
    }

    private data class Cell(val x: Int, val y: Int)

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float,
    )

    private val dp = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("snake", Context.MODE_PRIVATE)

    // ---- board geometry ----
    private val cols = 17
    private var rows = 0
    private var cell = 0f
    private val board = RectF()
    private val boardPath = Path()
    private var insetTop = 0
    private var insetBottom = 0

    // ---- game state ----
    private val snake = ArrayList<Cell>()   // head is index 0
    private var prev = ArrayList<Cell>()    // snake before the last step, for interpolation
    private var dir = Dir.RIGHT
    private val queue = ArrayDeque<Dir>()   // buffered turns, so quick double-swipes are not lost
    private var food = Cell(0, 0)
    private var score = 0
    private var best = prefs.getInt("best", 0)
    private var newBest = false
    private var state = State.READY
    private var tickMs = START_TICK
    private var lastTick = 0L
    private var pausedElapsed = 0L
    private var overAt = 0L
    private var foodBornAt = 0L
    private var lastFrame = 0L
    private val particles = ArrayList<Particle>()

    // ---- paints ----
    private val bgPaint = Paint()
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF121826.toInt() }
    private val checkPaint = Paint().apply { color = 0xFF161D2C.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0xFF27324A.toInt()
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF0B0F18.toInt()
    }
    private val overlayPaint = Paint()
    private val titlePaint = textPaint(30f, Color.WHITE, bold = true)
    private val subPaint = textPaint(15f, 0xFFA9B4C8.toInt(), bold = false)
    private val accentPaint = textPaint(16f, 0xFFFFC857.toInt(), bold = true)
    private val hintPaint = textPaint(13f, 0xFF5D6A84.toInt(), bold = false)
    private val hudLabel = textPaint(11f, 0xFF7D8AA3.toInt(), bold = true).apply { letterSpacing = 0.15f }
    private val hudValue = textPaint(26f, Color.WHITE, bold = true)
    private val piece = Path()

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            readInsets(insets)
            layoutBoard(width, height)
            insets
        }
    }

    private fun textPaint(sp: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp * dp
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    @Suppress("DEPRECATION")
    private fun readInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = i.top
            insetBottom = i.bottom
        } else {
            insetTop = insets.systemWindowInsetTop
            insetBottom = insets.systemWindowInsetBottom
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutBoard(w, h)

    private fun layoutBoard(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val margin = 14 * dp
        val top = insetTop + 88 * dp
        val bottom = h - insetBottom - 44 * dp
        val availW = w - 2 * margin
        val availH = bottom - top
        cell = floor(availW / cols)
        val newRows = max(10, floor(availH / cell).toInt())
        val bw = cell * cols
        val bh = cell * newRows
        val left = (w - bw) / 2f
        val t = top + (availH - bh) / 2f
        board.set(left, t, left + bw, t + bh)
        boardPath.reset()
        boardPath.addRoundRect(board, 14 * dp, 14 * dp, Path.Direction.CW)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), 0xFF101624.toInt(), 0xFF070A11.toInt(), Shader.TileMode.CLAMP,
        )
        if (newRows != rows) {
            rows = newRows
            reset()
        }
        invalidate()
    }

    // ================= game logic =================

    private fun reset() {
        snake.clear()
        val cy = rows / 2
        for (i in 0 until 4) snake.add(Cell(4 - i, cy))
        prev = ArrayList(snake)
        dir = Dir.RIGHT
        queue.clear()
        score = 0
        newBest = false
        tickMs = START_TICK
        particles.clear()
        spawnFood()
        state = State.READY
    }

    private fun start(initial: Dir?) {
        state = State.PLAYING
        lastTick = SystemClock.uptimeMillis()
        if (initial != null) enqueue(initial)
    }

    fun pause() {
        if (state != State.PLAYING) return
        pausedElapsed = SystemClock.uptimeMillis() - lastTick
        state = State.PAUSED
        invalidate()
    }

    private fun resume() {
        lastTick = SystemClock.uptimeMillis() - pausedElapsed
        state = State.PLAYING
    }

    private fun enqueue(d: Dir) {
        val last = queue.lastOrNull() ?: dir
        if (d == last || d.isOpposite(last)) return
        if (queue.size < 3) queue.addLast(d)
    }

    private fun spawnFood() {
        val occupied = snake.toHashSet()
        val free = ArrayList<Cell>()
        for (y in 0 until rows) for (x in 0 until cols) {
            val c = Cell(x, y)
            if (c !in occupied) free.add(c)
        }
        if (free.isEmpty()) {   // board full: you won
            gameOver()
            return
        }
        food = free[Random.nextInt(free.size)]
        foodBornAt = SystemClock.uptimeMillis()
    }

    private fun step() {
        queue.removeFirstOrNull()?.let { dir = it }
        val head = snake[0]
        val next = Cell(head.x + dir.dx, head.y + dir.dy)
        val eating = next == food
        // the tail moves away this tick unless we grow, so it is not an obstacle
        val solid = if (eating) snake.size else snake.size - 1
        val hitWall = next.x !in 0 until cols || next.y !in 0 until rows
        var hitSelf = false
        for (i in 0 until solid) if (snake[i] == next) { hitSelf = true; break }

        prev = ArrayList(snake)
        if (hitWall || hitSelf) {
            gameOver()
            return
        }
        snake.add(0, next)
        if (eating) {
            score++
            tickMs = max(MIN_TICK, tickMs - TICK_STEP)
            burst(next, FOOD_COLOR, 14)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            spawnFood()
        } else {
            snake.removeAt(snake.lastIndex)
        }
    }

    private fun gameOver() {
        state = State.OVER
        overAt = SystemClock.uptimeMillis()
        if (score > best) {
            best = score
            newBest = true
            prefs.edit().putInt("best", best).apply()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        burst(snake[0], HEAD_COLOR, 26)
    }

    private fun burst(c: Cell, color: Int, n: Int) {
        val cx = cx(c.x.toFloat())
        val cy = cy(c.y.toFloat())
        repeat(n) {
            val a = Random.nextFloat() * 6.2832f
            val speed = (60 + Random.nextFloat() * 220) * dp
            val life = 0.45f + Random.nextFloat() * 0.45f
            particles.add(
                Particle(cx, cy, cos(a) * speed, sin(a) * speed, life, life, color, (1.5f + Random.nextFloat() * 2.5f) * dp),
            )
        }
    }

    // ================= input =================

    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; swiped = false
            }
            MotionEvent.ACTION_MOVE -> {
                // re-anchor after each turn so you can chain turns without lifting the finger
                if (trySwipe(e)) { downX = e.x; downY = e.y; swiped = true }
            }
            // a very fast flick may arrive as DOWN+UP with no MOVE in between
            MotionEvent.ACTION_UP -> if (!trySwipe(e) && !swiped) onTap()
        }
        return true
    }

    private fun trySwipe(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (max(abs(dx), abs(dy)) <= SWIPE_DP * dp) return false
        val d = if (abs(dx) > abs(dy)) {
            if (dx > 0) Dir.RIGHT else Dir.LEFT
        } else {
            if (dy > 0) Dir.DOWN else Dir.UP
        }
        onSwipe(d)
        return true
    }

    private fun onSwipe(d: Dir) {
        when (state) {
            State.READY -> start(d)
            State.PLAYING -> enqueue(d)
            State.PAUSED -> { resume(); enqueue(d) }
            State.OVER -> Unit
        }
    }

    private fun onTap() {
        when (state) {
            State.READY -> start(null)
            State.PLAYING -> pause()
            State.PAUSED -> resume()
            State.OVER -> if (SystemClock.uptimeMillis() - overAt > 600) {
                reset()
                start(null)
            }
        }
    }

    // ================= rendering =================

    private fun cx(x: Float) = board.left + (x + 0.5f) * cell
    private fun cy(y: Float) = board.top + (y + 0.5f) * cell

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (snake.isEmpty()) {
            postInvalidateOnAnimation()
            return
        }

        if (state == State.PLAYING) {
            while (state == State.PLAYING && now - lastTick >= tickMs) {
                lastTick += tickMs
                step()
            }
        }
        val t = when (state) {
            State.PLAYING -> ((now - lastTick).toFloat() / tickMs).coerceIn(0f, 1f)
            State.PAUSED -> (pausedElapsed.toFloat() / tickMs).coerceIn(0f, 1f)
            else -> 1f
        }

        drawHud(canvas)

        // screen shake right after death
        canvas.save()
        val sinceOver = now - overAt
        if (state == State.OVER && sinceOver < 380) {
            val amp = 7 * dp * (1f - sinceOver / 380f)
            canvas.translate((Random.nextFloat() - 0.5f) * 2 * amp, (Random.nextFloat() - 0.5f) * 2 * amp)
        }

        drawBoard(canvas)
        drawFood(canvas, now)
        drawSnake(canvas, t)
        drawParticles(canvas, dt)
        canvas.restore()

        canvas.drawText("Свайп — поворот   •   Тап — пауза", board.centerX(), board.bottom + 28 * dp, hintPaint)
        drawOverlay(canvas, now)

        postInvalidateOnAnimation()
    }

    private fun drawHud(canvas: Canvas) {
        val labelY = insetTop + 30 * dp
        val valueY = insetTop + 62 * dp
        val third = board.width() / 3f
        val speed = String.format(Locale.US, "%.1f×", START_TICK.toFloat() / tickMs)
        val items = listOf("СЧЁТ" to score.toString(), "СКОРОСТЬ" to speed, "РЕКОРД" to best.toString())
        items.forEachIndexed { i, (label, value) ->
            val x = board.left + third * (i + 0.5f)
            canvas.drawText(label, x, labelY, hudLabel)
            canvas.drawText(value, x, valueY, hudValue)
        }
    }

    private fun drawBoard(canvas: Canvas) {
        canvas.drawPath(boardPath, boardPaint)
        canvas.save()
        canvas.clipPath(boardPath)
        for (y in 0 until rows) for (x in 0 until cols) {
            if ((x + y) % 2 == 0) {
                val l = board.left + x * cell
                val tp = board.top + y * cell
                canvas.drawRect(l, tp, l + cell, tp + cell, checkPaint)
            }
        }
        canvas.restore()
        canvas.drawPath(boardPath, borderPaint)
    }

    private fun drawFood(canvas: Canvas, now: Long) {
        val x = cx(food.x.toFloat())
        val y = cy(food.y.toFloat())
        // pop-in with a little overshoot, then a gentle pulse
        val p = ((now - foodBornAt) / 260f).coerceIn(0f, 1f)
        val pop = if (p < 1f) sin(p * 2.2f) / sin(2.2f) else 1f
        val pulse = 1f + 0.07f * sin(now / 170f)
        val r = cell * 0.36f * pop * pulse

        glowPaint.shader = RadialGradient(
            x, y, cell * 1.2f, 0x66FF5A6E, 0x00FF5A6E, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, cell * 1.2f * pop, glowPaint)
        fillPaint.color = FOOD_COLOR
        canvas.drawCircle(x, y, r, fillPaint)
        fillPaint.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.28f, fillPaint)
    }

    private fun drawSnake(canvas: Canvas, t: Float) {
        val n = snake.size
        val px = FloatArray(n)
        val py = FloatArray(n)
        for (i in 0 until n) {
            val to = snake[i]
            val from = prev.getOrNull(i) ?: to
            px[i] = cx(from.x + (to.x - from.x) * t)
            py[i] = cy(from.y + (to.y - from.y) * t)
        }

        // Body: piece i goes p[i] -> corner (cell snake[i]) -> p[i-1], so turns follow the grid exactly.
        for (i in n - 1 downTo 1) {
            val f = 1f - i.toFloat() / n
            bodyPaint.color = blend(TAIL_COLOR, HEAD_COLOR, f)
            bodyPaint.strokeWidth = cell * (0.56f + 0.2f * f)
            piece.reset()
            piece.moveTo(px[i], py[i])
            piece.lineTo(cx(snake[i].x.toFloat()), cy(snake[i].y.toFloat()))
            piece.lineTo(px[i - 1], py[i - 1])
            canvas.drawPath(piece, bodyPaint)
        }

        // Head with eyes looking where we go
        val hx = px[0]
        val hy = py[0]
        fillPaint.color = HEAD_COLOR
        canvas.drawCircle(hx, hy, cell * 0.43f, fillPaint)

        val fx = dir.dx.toFloat()
        val fy = dir.dy.toFloat()
        val sx = -fy
        val sy = fx
        for (side in intArrayOf(-1, 1)) {
            val ex = hx + fx * cell * 0.12f + sx * side * cell * 0.19f
            val ey = hy + fy * cell * 0.12f + sy * side * cell * 0.19f
            if (state == State.OVER) {
                val k = cell * 0.08f
                eyeLinePaint.strokeWidth = cell * 0.06f
                canvas.drawLine(ex - k, ey - k, ex + k, ey + k, eyeLinePaint)
                canvas.drawLine(ex - k, ey + k, ex + k, ey - k, eyeLinePaint)
            } else {
                fillPaint.color = Color.WHITE
                canvas.drawCircle(ex, ey, cell * 0.11f, fillPaint)
                fillPaint.color = 0xFF0B0F18.toInt()
                canvas.drawCircle(ex + fx * cell * 0.035f, ey + fy * cell * 0.035f, cell * 0.06f, fillPaint)
            }
        }
    }

    private fun drawParticles(canvas: Canvas, dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.92f
            p.vy = p.vy * 0.92f + 300 * dp * dt
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            fillPaint.color = (p.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            canvas.drawCircle(p.x, p.y, p.size * (0.5f + 0.5f * a), fillPaint)
        }
    }

    private fun drawOverlay(canvas: Canvas, now: Long) {
        val title: String
        val lines = ArrayList<Pair<String, Paint>>()
        var alpha = 1f
        when (state) {
            State.PLAYING -> return
            State.READY -> {
                title = "ЗМЕЙКА"
                lines += "Свайпни или тапни, чтобы начать" to subPaint
                if (best > 0) lines += "Рекорд: $best" to accentPaint
            }
            State.PAUSED -> {
                title = "ПАУЗА"
                lines += "Тапни, чтобы продолжить" to subPaint
            }
            State.OVER -> {
                alpha = ((now - overAt - 250) / 300f).coerceIn(0f, 1f)
                if (alpha <= 0f) return
                title = "ИГРА ОКОНЧЕНА"
                lines += "Счёт: $score" to subPaint
                if (newBest) lines += "Новый рекорд!" to accentPaint
                if (now - overAt > 600) lines += "Тапни, чтобы сыграть ещё" to subPaint
            }
        }
        overlayPaint.color = (((0xC8 * alpha).toInt()) shl 24) or 0x0B0F18
        canvas.save()
        canvas.clipPath(boardPath)
        canvas.drawRect(board, overlayPaint)
        canvas.restore()

        val a = (alpha * 255).toInt()
        val cyMid = board.centerY() - 20 * dp
        titlePaint.alpha = a
        canvas.drawText(title, board.centerX(), cyMid, titlePaint)
        lines.forEachIndexed { i, (text, paint) ->
            val old = paint.alpha
            paint.alpha = a
            canvas.drawText(text, board.centerX(), cyMid + (36 + i * 28) * dp, paint)
            paint.alpha = old
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    companion object {
        private const val START_TICK = 170L
        private const val MIN_TICK = 62L
        private const val TICK_STEP = 4L
        private const val SWIPE_DP = 22f
        private const val HEAD_COLOR = 0xFF7CF29A.toInt()
        private const val TAIL_COLOR = 0xFF1A7F66.toInt()
        private const val FOOD_COLOR = 0xFFFF5A6E.toInt()
    }
}
