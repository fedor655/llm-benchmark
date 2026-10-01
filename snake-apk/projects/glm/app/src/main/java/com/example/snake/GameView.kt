package com.example.snake

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val TAG = "GameView"
        private const val KEY_HIGH_SCORE = "high_score"

        private const val TARGET_FPS = 60f
        private const val MAX_FRAME_DELTA_MS = 100f

        private const val INITIAL_TICK_MS = 190f
        private const val MIN_TICK_MS = 72f
        private const val TICK_STEP_MS = 13f
        private const val FOODS_PER_LEVEL = 4
        private const val TIME_PER_LEVEL_MS = 14_000f
        private const val START_LENGTH = 4
        private const val RESTART_LOCKOUT_MS = 500L
        private const val MAX_DIR_QUEUE = 2

        private val COLOR_BG = 0xFF0A1410.toInt()
        private val COLOR_CELL_A = 0xFF0F2019.toInt()
        private val COLOR_CELL_B = 0xFF0C1A15.toInt()
        private val COLOR_BORDER = 0xFF2C4A36.toInt()
        private val COLOR_SNAKE_HEAD = 0xFFA4E34A.toInt()
        private val COLOR_SNAKE_TAIL = 0xFF2F6D1E.toInt()
        private val COLOR_DEAD = 0xFFB23A2E.toInt()
        private val COLOR_EYE = 0xFFF3F9EC.toInt()
        private val COLOR_PUPIL = 0xFF113318.toInt()
        private val COLOR_STEM = 0xFF795548.toInt()
        private val COLOR_LEAF = 0xFF66BB6A.toInt()
        private val COLOR_TEXT_LABEL = 0xFF7FA98B.toInt()
        private val COLOR_TEXT_VALUE = 0xFFF2F7F0.toInt()
        private val COLOR_SPEED = 0xFFE8C15A.toInt()
        private val COLOR_TITLE = 0xFFA4E34A.toInt()
        private val COLOR_OVER = 0xFFEF7B70.toInt()
        private val COLOR_BODY_TEXT = 0xFFE8F3E6.toInt()
        private val COLOR_PROMPT = 0xFFCFE8CF.toInt()
        private val COLOR_GOLD = 0xFFFFD54F.toInt()
        private val COLOR_PANEL = 0xF20E1B14.toInt()
        private val COLOR_PANEL_STROKE = 0x59FFFFFF.toInt()
    }

    enum class Dir(val dx: Int, val dy: Int) {
        UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0)
    }

    private enum class State { READY, RUNNING, PAUSED, GAME_OVER }

    private data class Cell(val x: Int, val y: Int)

    private val lock = Any()
    private val prefs = context.getSharedPreferences("snake_prefs", Context.MODE_PRIVATE)

    // Board metrics
    private var viewW = 0
    private var viewH = 0
    private var cell = 40f
    private var cols = 17
    private var rows = 24
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardW = 0f
    private var boardH = 0f
    private var boardBitmap: Bitmap? = null
    private val boardRect = RectF()
    private val panelRect = RectF()

    // Game state
    private var state = State.READY
    private var body = ArrayDeque<Cell>()
    private var prevBody: List<Cell> = emptyList()
    private var dir = Dir.UP
    private val dirQueue = ArrayDeque<Dir>()
    private var food = Cell(0, 0)
    private var score = 0
    private var highScore = 0
    private var bestAtStart = 0
    private var newBest = false
    private var won = false
    private var foodsEaten = 0
    private var playTimeMs = 0f
    private var tickInterval = INITIAL_TICK_MS
    private var accumulator = 0f
    private var gameOverAtMs = 0L
    private var frameTimeMs = 0f

    private val speedLevel: Int
        get() = 1 + foodsEaten / FOODS_PER_LEVEL + (playTimeMs / TIME_PER_LEVEL_MS).toInt()

    // Loop
    @Volatile
    private var running = false
    private var loopThread: Thread? = null

    // Paints
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val snakePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val promptPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            onSwipe(velocityX, velocityY)
            return true
        }
    })

    init {
        holder.addCallback(this)
        highScore = prefs.getInt(KEY_HIGH_SCORE, 0)
        bestAtStart = highScore
        dimPaint.color = Color.BLACK
    }

    // ---------------------------------------------------------------- lifecycle

    fun pause() {
        synchronized(lock) {
            if (state == State.RUNNING) state = State.PAUSED
        }
        stopLoop()
    }

    fun resume() {
        if (holder.surface.isValid) startLoop()
    }

    private fun startLoop() {
        if (loopThread?.isAlive == true) return
        running = true
        loopThread = Thread(this, "SnakeLoop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        loopThread?.let {
            try {
                it.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        loopThread = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) { setupBoard(width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    private fun onTap() {
        synchronized(lock) {
            when (state) {
                State.READY -> startGame()
                State.RUNNING -> state = State.PAUSED
                State.PAUSED -> state = State.RUNNING
                State.GAME_OVER -> {
                    if (SystemClock.uptimeMillis() - gameOverAtMs > RESTART_LOCKOUT_MS) {
                        resetGame()
                        startGame()
                    }
                }
            }
        }
    }

    private fun onSwipe(vx: Float, vy: Float) {
        val newDir = if (abs(vx) > abs(vy)) {
            if (vx > 0) Dir.RIGHT else Dir.LEFT
        } else {
            if (vy > 0) Dir.DOWN else Dir.UP
        }
        synchronized(lock) {
            if (state == State.READY) startGame()
            if (state == State.RUNNING) queueDirection(newDir)
        }
    }

    private fun queueDirection(newDir: Dir) {
        val last = dirQueue.lastOrNull() ?: dir
        if (newDir == last || (newDir.dx == -last.dx && newDir.dy == -last.dy)) return
        if (dirQueue.size >= MAX_DIR_QUEUE) dirQueue.removeLast()
        dirQueue.addLast(newDir)
    }

    // ---------------------------------------------------------------- game logic

    private fun setupBoard(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        viewW = w
        viewH = h
        cell = min(w / 17f, h / 27f)
        val topBar = cell * 2f
        cols = (w / cell).toInt().coerceAtLeast(10)
        rows = ((h - topBar) / cell).toInt().coerceAtLeast(12)
        boardW = cols * cell
        boardH = rows * cell
        boardLeft = (w - boardW) / 2f
        boardTop = topBar + (h - topBar - boardH) / 2f
        boardBitmap = createBoardBitmap()
        applyPaintSizes()
        if (state == State.READY) {
            resetGame()
        } else {
            val ok = body.all { it.x in 0 until cols && it.y in 0 until rows } &&
                    food.x in 0 until cols && food.y in 0 until rows
            if (!ok) resetGame()
        }
    }

    private fun createBoardBitmap(): Bitmap {
        val bw = max(1, boardW.toInt())
        val bh = max(1, boardH.toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paintA = Paint()
        val paintB = Paint()
        paintA.color = COLOR_CELL_A
        paintB.color = COLOR_CELL_B
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                c.drawRect(
                    x * cell, y * cell, (x + 1) * cell, (y + 1) * cell,
                    if ((x + y) % 2 == 0) paintA else paintB
                )
            }
        }
        return bmp
    }

    private fun resetGame() {
        score = 0
        foodsEaten = 0
        playTimeMs = 0f
        newBest = false
        won = false
        tickInterval = INITIAL_TICK_MS
        accumulator = 0f
        dir = Dir.UP
        dirQueue.clear()
        val cx = cols / 2
        val cy = rows / 2
        body = ArrayDeque()
        for (i in 0 until START_LENGTH) body.addLast(Cell(cx, cy + i))
        prevBody = body.toList()
        bestAtStart = highScore
        spawnFood()
        state = State.READY
    }

    private fun startGame() {
        accumulator = 0f
        state = State.RUNNING
    }

    private fun updateSpeed() {
        tickInterval = (INITIAL_TICK_MS - (speedLevel - 1) * TICK_STEP_MS).coerceAtLeast(MIN_TICK_MS)
    }

    private fun spawnFood() {
        val occupied = body.toHashSet()
        val freeCount = cols * rows - occupied.size
        if (freeCount <= 0) {
            food = body.first()
            won = true
            gameOver()
            return
        }
        var idx = (Math.random() * freeCount).toInt()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val c = Cell(x, y)
                if (c in occupied) continue
                if (idx == 0) {
                    food = c
                    updateFoodPaint()
                    return
                }
                idx--
            }
        }
    }

    private fun updateFoodPaint() {
        val cx = boardLeft + (food.x + 0.5f) * cell
        val cy = boardTop + (food.y + 0.5f) * cell
        foodPaint.shader = RadialGradient(
            cx - cell * 0.1f, cy - cell * 0.12f, cell * 0.55f,
            0xFFFF7A66.toInt(), 0xFFB71C1C.toInt(), Shader.TileMode.CLAMP
        )
    }

    private fun tick() {
        if (dirQueue.isNotEmpty()) {
            val nd = dirQueue.removeFirst()
            if (!(nd.dx == -dir.dx && nd.dy == -dir.dy)) dir = nd
        }
        val head = body.first()
        val nh = Cell(head.x + dir.dx, head.y + dir.dy)
        val grow = nh == food

        if (nh.x !in 0 until cols || nh.y !in 0 until rows) {
            gameOver()
            return
        }
        val limit = if (grow) body.size else body.size - 1
        for (i in 0 until limit) {
            if (body[i] == nh) {
                gameOver()
                return
            }
        }

        prevBody = body.toList()
        body.addFirst(nh)
        if (grow) {
            foodsEaten++
            updateSpeed()
            score += 10 + (speedLevel - 1) * 5
            if (score > highScore) {
                highScore = score
                newBest = true
            }
            spawnFood()
            post { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        } else {
            body.removeLast()
        }
    }

    private fun gameOver() {
        state = State.GAME_OVER
        gameOverAtMs = SystemClock.uptimeMillis()
        accumulator = 0f
        if (score > highScore) {
            highScore = score
            newBest = true
        }
        newBest = score > bestAtStart && score > 0
        prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply()
        post { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }

    // ---------------------------------------------------------------- loop

    override fun run() {
        var lastNs = System.nanoTime()
        while (running) {
            val nowNs = System.nanoTime()
            var dtMs = (nowNs - lastNs) / 1_000_000f
            lastNs = nowNs
            if (dtMs < 0f) dtMs = 0f
            dtMs = min(dtMs, MAX_FRAME_DELTA_MS)
            frameTimeMs = SystemClock.uptimeMillis().toFloat()

            synchronized(lock) {
                if (state == State.RUNNING) {
                    playTimeMs += dtMs
                    updateSpeed()
                    accumulator += dtMs
                    while (accumulator >= tickInterval && state == State.RUNNING) {
                        accumulator -= tickInterval
                        tick()
                    }
                }
                val interp = if (state == State.RUNNING) accumulator / tickInterval else 1f
                render(interp.coerceIn(0f, 1f))
            }

            val spentMs = (System.nanoTime() - nowNs) / 1_000_000f
            val sleepMs = 1000f / TARGET_FPS - spentMs
            if (sleepMs > 1f) SystemClock.sleep(sleepMs.toLong())
        }
    }

    private fun render(interp: Float) {
        if (!holder.surface.isValid) return
        val canvas = try {
            holder.lockCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "lockCanvas failed", e)
            null
        } ?: return
        try {
            drawFrame(canvas, interp)
        } catch (e: Exception) {
            Log.e(TAG, "draw error", e)
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun drawFrame(c: Canvas, t: Float) {
        c.drawColor(COLOR_BG)
        drawHud(c)
        boardBitmap?.let { c.drawBitmap(it, boardLeft, boardTop, null) }
        boardRect.set(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH)
        c.drawRoundRect(boardRect, cell * 0.6f, cell * 0.6f, borderPaint)
        if (state != State.GAME_OVER || !won) drawFood(c)
        drawSnake(c, t)
        drawStateOverlay(c)
    }

    private fun drawHud(c: Canvas) {
        labelPaint.textAlign = Paint.Align.LEFT
        c.drawText("SCORE", boardLeft + cell * 0.1f, cell * 0.6f, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        c.drawText("BEST", boardLeft + boardW - cell * 0.1f, cell * 0.6f, labelPaint)

        valuePaint.textAlign = Paint.Align.LEFT
        valuePaint.color = COLOR_TEXT_VALUE
        c.drawText(score.toString(), boardLeft + cell * 0.1f, cell * 1.55f, valuePaint)
        valuePaint.textAlign = Paint.Align.RIGHT
        c.drawText(highScore.toString(), boardLeft + boardW - cell * 0.1f, cell * 1.55f, valuePaint)

        val mult = INITIAL_TICK_MS / tickInterval
        speedPaint.textAlign = Paint.Align.CENTER
        speedPaint.color = if (mult >= 1.5f) COLOR_SPEED else COLOR_TEXT_LABEL
        c.drawText(String.format(Locale.US, "SPEED x%.1f", mult), viewW / 2f, cell * 1.05f, speedPaint)
    }

    private fun drawFood(c: Canvas) {
        val cx = boardLeft + (food.x + 0.5f) * cell
        val cy = boardTop + (food.y + 0.5f) * cell
        val pulse = 1f + 0.07f * sin(frameTimeMs / 240f)
        val r = cell * 0.36f * pulse
        c.drawCircle(cx, cy, r, foodPaint)
        shinePaint.color = 0x59FFFFFF
        c.drawCircle(cx - r * 0.35f, cy - r * 0.4f, r * 0.3f, shinePaint)
        stemPaint.color = COLOR_STEM
        c.drawRoundRect(
            cx - cell * 0.035f, cy - r - cell * 0.16f,
            cx + cell * 0.035f, cy - r + cell * 0.02f,
            cell * 0.05f, cell * 0.05f, stemPaint
        )
        leafPaint.color = COLOR_LEAF
        c.drawOval(
            cx + r * 0.1f, cy - r - cell * 0.15f,
            cx + r * 0.55f, cy - r + cell * 0.02f, leafPaint
        )
    }

    private fun drawSnake(c: Canvas, t: Float) {
        val n = body.size
        if (n == 0) return
        val dead = state == State.GAME_OVER && !won
        val inset = cell * 0.07f
        val r = cell * 0.34f
        var headX = 0f
        var headY = 0f
        var headFrom = body.first()
        for (i in n - 1 downTo 0) {
            val to = body[i]
            val from = prevBody.getOrNull(i) ?: to
            val x = from.x + (to.x - from.x) * t
            val y = from.y + (to.y - from.y) * t
            if (i == 0) {
                headX = x
                headY = y
                headFrom = from
            }
            var color = lerpColor(COLOR_SNAKE_HEAD, COLOR_SNAKE_TAIL, if (n <= 1) 0f else i.toFloat() / (n - 1))
            if (dead) color = lerpColor(color, COLOR_DEAD, 0.45f)
            snakePaint.color = color
            val left = boardLeft + x * cell + inset
            val top = boardTop + y * cell + inset
            c.drawRoundRect(left, top, left + cell - 2 * inset, top + cell - 2 * inset, r, r, snakePaint)
        }
        drawEyes(c, headX, headY, headFrom, body.first())
    }

    private fun drawEyes(c: Canvas, hx: Float, hy: Float, from: Cell, to: Cell) {
        var dx = (to.x - from.x).toFloat()
        var dy = (to.y - from.y).toFloat()
        if (dx == 0f && dy == 0f) {
            dx = dir.dx.toFloat()
            dy = dir.dy.toFloat()
        }
        val cx = boardLeft + (hx + 0.5f) * cell
        val cy = boardTop + (hy + 0.5f) * cell
        val px = -dy
        val py = dx
        val eyeOff = cell * 0.19f
        val fwd = cell * 0.12f
        for (s in intArrayOf(-1, 1)) {
            val ex = cx + px * eyeOff * s + dx * fwd
            val ey = cy + py * eyeOff * s + dy * fwd
            eyePaint.color = COLOR_EYE
            c.drawCircle(ex, ey, cell * 0.115f, eyePaint)
            pupilPaint.color = COLOR_PUPIL
            c.drawCircle(ex + dx * cell * 0.045f, ey + dy * cell * 0.045f, cell * 0.06f, pupilPaint)
        }
    }

    private fun drawStateOverlay(c: Canvas) {
        when (state) {
            State.READY -> {
                dim(0.55f)
                c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), dimPaint)
                drawPanel(c, cell * 3.0f)
                val cy = viewH / 2f
                drawCentered(c, "SNAKE", cy - cell * 1.15f, titlePaint, COLOR_TITLE)
                if (highScore > 0) {
                    drawCentered(c, "BEST  $highScore", cy + cell * 0.15f, bodyPaint, COLOR_BODY_TEXT)
                }
                drawCentered(c, "SWIPE TO STEER - TAP TO START", cy + cell * 1.55f, promptPaint, COLOR_PROMPT, blink = true)
            }
            State.PAUSED -> {
                dim(0.7f)
                c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), dimPaint)
                drawPanel(c, cell * 1.9f)
                val cy = viewH / 2f
                drawCentered(c, "PAUSED", cy - cell * 0.25f, bigPaint, COLOR_BODY_TEXT)
                drawCentered(c, "TAP TO RESUME", cy + cell * 1.0f, promptPaint, COLOR_PROMPT, blink = true)
            }
            State.GAME_OVER -> {
                val age = (SystemClock.uptimeMillis() - gameOverAtMs).coerceAtMost(250L)
                dim(age / 250f * 0.72f)
                c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), dimPaint)
                drawPanel(c, cell * 3.2f)
                val cy = viewH / 2f
                if (won) {
                    drawCentered(c, "YOU WIN!", cy - cell * 1.95f, titlePaint, COLOR_GOLD)
                } else {
                    drawCentered(c, "GAME OVER", cy - cell * 1.95f, bigPaint, COLOR_OVER)
                }
                drawCentered(c, "SCORE  $score", cy - cell * 0.55f, bigPaint, COLOR_BODY_TEXT)
                if (newBest) {
                    drawCentered(c, "NEW BEST!", cy + cell * 0.6f, goldPaint, COLOR_GOLD, blink = true)
                } else {
                    drawCentered(c, "BEST  $highScore", cy + cell * 0.6f, bodyPaint, COLOR_TEXT_LABEL)
                }
                if (SystemClock.uptimeMillis() - gameOverAtMs > RESTART_LOCKOUT_MS) {
                    drawCentered(c, "TAP TO PLAY AGAIN", cy + cell * 1.8f, promptPaint, COLOR_PROMPT, blink = true)
                }
            }
            State.RUNNING -> {
                // no overlay while playing
            }
        }
    }

    private fun dim(alpha: Float) {
        dimPaint.color = Color.BLACK
        dimPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
    }

    private fun drawPanel(c: Canvas, halfH: Float) {
        val halfW = min(viewW * 0.44f, boardW * 0.47f)
        panelRect.set(viewW / 2f - halfW, viewH / 2f - halfH, viewW / 2f + halfW, viewH / 2f + halfH)
        panelPaint.color = COLOR_PANEL
        c.drawRoundRect(panelRect, cell * 0.8f, cell * 0.8f, panelPaint)
        panelStrokePaint.color = COLOR_PANEL_STROKE
        c.drawRoundRect(panelRect, cell * 0.8f, cell * 0.8f, panelStrokePaint)
    }

    private fun drawCentered(c: Canvas, text: String, y: Float, paint: Paint, color: Int, blink: Boolean = false) {
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        if (blink) {
            val a = 0.55f + 0.45f * sin(frameTimeMs / 380f)
            paint.alpha = (a.coerceIn(0.3f, 1f) * 255f).toInt()
        } else {
            paint.alpha = 255
        }
        c.drawText(text, viewW / 2f, y, paint)
        paint.alpha = 255
    }

    private fun applyPaintSizes() {
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        labelPaint.textSize = cell * 0.34f
        labelPaint.color = COLOR_TEXT_LABEL
        valuePaint.textSize = cell * 0.78f
        valuePaint.typeface = bold
        speedPaint.textSize = cell * 0.5f
        speedPaint.typeface = bold
        titlePaint.textSize = cell * 1.25f
        titlePaint.typeface = bold
        bigPaint.textSize = cell * 0.85f
        bigPaint.typeface = bold
        bodyPaint.textSize = cell * 0.6f
        promptPaint.textSize = cell * 0.5f
        promptPaint.typeface = bold
        goldPaint.textSize = cell * 0.62f
        goldPaint.typeface = bold

        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = max(2f, cell * 0.09f)
        borderPaint.color = COLOR_BORDER

        panelStrokePaint.style = Paint.Style.STROKE
        panelStrokePaint.strokeWidth = max(1.5f, cell * 0.045f)

        eyePaint.color = COLOR_EYE
        pupilPaint.color = COLOR_PUPIL
    }

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        val ar = Color.red(a)
        val ag = Color.green(a)
        val ab = Color.blue(a)
        val br = Color.red(b)
        val bg = Color.green(b)
        val bb = Color.blue(b)
        return Color.argb(
            255,
            (ar + (br - ar) * t).toInt(),
            (ag + (bg - ag) * t).toInt(),
            (ab + (bb - ab) * t).toInt()
        )
    }
}
