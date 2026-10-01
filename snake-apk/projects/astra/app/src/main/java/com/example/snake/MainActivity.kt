package com.example.snake

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.Window
import kotlin.math.abs
import kotlin.random.Random

data class Cell(val x: Int, val y: Int)
enum class Direction { UP, DOWN, LEFT, RIGHT }

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = Color.rgb(16, 26, 36)
        window.navigationBarColor = Color.rgb(16, 26, 36)
        setContentView(SnakeView())
    }

    private inner class SnakeView : View(this@MainActivity) {
        private val handler = Handler(Looper.getMainLooper())
        private val random = Random(System.currentTimeMillis())
        private val snake = ArrayList<Cell>()
        private var food = Cell(0, 0)
        private var direction = Direction.RIGHT
        private var nextDirection = Direction.RIGHT
        private var score = 0
        private var best = 0
        private var running = true
        private var touchX = 0f
        private var touchY = 0f

        private val background = Paint(Paint.ANTI_ALIAS_FLAG)
        private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val snakePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val cols = 20
        private val rows = 28
        private var cellSize = 0f
        private var boardLeft = 0f
        private var boardTop = 0f

        private val tick = object : Runnable {
            override fun run() {
                if (running) {
                    advance()
                    invalidate()
                    handler.postDelayed(this, tickDelay())
                }
            }
        }

        init {
            isFocusable = true
            background.color = Color.rgb(16, 26, 36)
            boardPaint.color = Color.rgb(24, 38, 49)
            gridPaint.color = Color.argb(34, 176, 220, 204)
            snakePaint.color = Color.rgb(101, 214, 160)
            headPaint.color = Color.rgb(143, 239, 188)
            foodPaint.color = Color.rgb(255, 112, 104)
            titlePaint.color = Color.rgb(231, 245, 239)
            textPaint.color = Color.rgb(160, 184, 179)
            overlayPaint.color = Color.argb(196, 16, 26, 36)
            startGame()
        }

        private fun startGame() {
            snake.clear()
            snake.add(Cell(cols / 2, rows / 2))
            snake.add(Cell(cols / 2 - 1, rows / 2))
            snake.add(Cell(cols / 2 - 2, rows / 2))
            direction = Direction.RIGHT
            nextDirection = Direction.RIGHT
            score = 0
            running = true
            placeFood()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, tickDelay())
            invalidate()
        }

        private fun tickDelay(): Long = (220L - score * 7L).coerceAtLeast(76L)

        private fun advance() {
            direction = nextDirection
            val head = snake[0]
            val newHead = when (direction) {
                Direction.UP -> Cell(head.x, head.y - 1)
                Direction.DOWN -> Cell(head.x, head.y + 1)
                Direction.LEFT -> Cell(head.x - 1, head.y)
                Direction.RIGHT -> Cell(head.x + 1, head.y)
            }
            val hitWall = newHead.x !in 0 until cols || newHead.y !in 0 until rows
            val ateFood = newHead == food
            val bodyToCheck = if (ateFood) snake else snake.dropLast(1)
            if (hitWall || bodyToCheck.contains(newHead)) {
                running = false
                best = maxOf(best, score)
                invalidate()
                return
            }
            snake.add(0, newHead)
            if (ateFood) {
                score++
                placeFood()
            } else {
                snake.removeAt(snake.lastIndex)
            }
        }

        private fun placeFood() {
            do {
                food = Cell(random.nextInt(cols), random.nextInt(rows))
            } while (snake.contains(food))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val topSpace = 116f * resources.displayMetrics.density
            val sidePadding = 20f * resources.displayMetrics.density
            cellSize = minOf((w - sidePadding * 2f) / cols, (h - topSpace - 24f * resources.displayMetrics.density) / rows)
            boardLeft = (w - cellSize * cols) / 2f
            boardTop = topSpace
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(16, 26, 36))
            val density = resources.displayMetrics.density
            titlePaint.textSize = 27f * density
            titlePaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
            canvas.drawText("SNAKE", 22f * density, 42f * density, titlePaint)

            textPaint.textSize = 12f * density
            textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
            canvas.drawText("SCORE", 22f * density, 71f * density, textPaint)
            canvas.drawText("BEST", 102f * density, 71f * density, textPaint)
            titlePaint.textSize = 21f * density
            canvas.drawText(score.toString(), 22f * density, 94f * density, titlePaint)
            canvas.drawText(best.toString(), 102f * density, 94f * density, titlePaint)

            val right = boardLeft + cellSize * cols
            val bottom = boardTop + cellSize * rows
            canvas.drawRoundRect(RectF(boardLeft - 5f, boardTop - 5f, right + 5f, bottom + 5f), 12f * density, 12f * density, boardPaint)
            for (x in 0..cols) {
                val lineX = boardLeft + x * cellSize
                canvas.drawLine(lineX, boardTop, lineX, bottom, gridPaint)
            }
            for (y in 0..rows) {
                val lineY = boardTop + y * cellSize
                canvas.drawLine(boardLeft, lineY, right, lineY, gridPaint)
            }

            val foodInset = cellSize * .22f
            val foodRect = RectF(
                boardLeft + food.x * cellSize + foodInset,
                boardTop + food.y * cellSize + foodInset,
                boardLeft + (food.x + 1) * cellSize - foodInset,
                boardTop + (food.y + 1) * cellSize - foodInset
            )
            canvas.drawRoundRect(foodRect, cellSize * .25f, cellSize * .25f, foodPaint)

            snake.forEachIndexed { index, cell ->
                val inset = cellSize * .09f
                val rect = RectF(
                    boardLeft + cell.x * cellSize + inset,
                    boardTop + cell.y * cellSize + inset,
                    boardLeft + (cell.x + 1) * cellSize - inset,
                    boardTop + (cell.y + 1) * cellSize - inset
                )
                canvas.drawRoundRect(rect, cellSize * .22f, cellSize * .22f, if (index == 0) headPaint else snakePaint)
            }

            if (!running) drawGameOver(canvas, density, boardLeft, boardTop, right, bottom)
        }

        private fun drawGameOver(canvas: Canvas, density: Float, left: Float, top: Float, right: Float, bottom: Float) {
            canvas.drawRoundRect(RectF(left, top, right, bottom), 8f * density, 8f * density, overlayPaint)
            titlePaint.textSize = 29f * density
            titlePaint.textAlign = Paint.Align.CENTER
            canvas.drawText("GAME OVER", (left + right) / 2f, (top + bottom) / 2f - 14f * density, titlePaint)
            textPaint.textSize = 15f * density
            canvas.drawText("Tap to play again", (left + right) / 2f, (top + bottom) / 2f + 20f * density, textPaint)
            titlePaint.textAlign = Paint.Align.LEFT
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.x
                    touchY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchX
                    val dy = event.y - touchY
                    if (!running && abs(dx) < 40f && abs(dy) < 40f) {
                        startGame()
                        return true
                    }
                    if (maxOf(abs(dx), abs(dy)) >= 36f) {
                        if (abs(dx) > abs(dy)) {
                            if (dx > 0 && direction != Direction.LEFT) nextDirection = Direction.RIGHT
                            if (dx < 0 && direction != Direction.RIGHT) nextDirection = Direction.LEFT
                        } else {
                            if (dy > 0 && direction != Direction.UP) nextDirection = Direction.DOWN
                            if (dy < 0 && direction != Direction.DOWN) nextDirection = Direction.UP
                        }
                    }
                    return true
                }
            }
            return true
        }
    }
}
