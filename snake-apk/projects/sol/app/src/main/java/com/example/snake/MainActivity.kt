package com.example.snake

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(13, 29, 23)
        window.navigationBarColor = Color.rgb(9, 20, 17)
        setContentView(SnakeView(this))
    }

    private class SnakeView(context: Context) : View(context) {
        private data class Cell(val x: Int, val y: Int)
        private enum class Direction { UP, DOWN, LEFT, RIGHT }
        private val bg = Color.rgb(9, 20, 17)
        private val panel = Color.rgb(16, 38, 29)
        private val board = Color.rgb(12, 29, 23)
        private val grid = Color.rgb(23, 51, 39)
        private val green = Color.rgb(89, 214, 138)
        private val bright = Color.rgb(129, 239, 164)
        private val coral = Color.rgb(255, 121, 105)
        private val light = Color.rgb(226, 242, 231)
        private val muted = Color.rgb(145, 177, 157)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val snake = ArrayList<Cell>()
        private val cols = 18
        private val rows = 24
        private var food = Cell(12, 12)
        private var direction = Direction.RIGHT
        private var queued = Direction.RIGHT
        private var score = 0
        private var over = false
        private var downX = 0f
        private var downY = 0f
        private var left = 0f
        private var top = 0f
        private var cell = 0f
        private var lastStep = 0L
        private val ticker = object : Runnable {
            override fun run() {
                if (!over) {
                    val now = System.currentTimeMillis()
                    if (now - lastStep >= delay()) { move(); lastStep = now }
                    invalidate()
                    postDelayed(this, 16)
                }
            }
        }

        init { setBackgroundColor(bg); restart() }
        private fun delay() = (175L - score / 4 * 9L).coerceAtLeast(72L)

        private fun restart() {
            snake.clear()
            snake.add(Cell(5, 12)); snake.add(Cell(4, 12)); snake.add(Cell(3, 12))
            direction = Direction.RIGHT; queued = Direction.RIGHT; score = 0; over = false
            placeFood(); lastStep = System.currentTimeMillis()
            removeCallbacks(ticker); post(ticker); invalidate()
        }
        private fun placeFood() {
            do { food = Cell(Random.nextInt(cols), Random.nextInt(rows)) } while (snake.contains(food))
        }
        private fun move() {
            direction = queued
            val h = snake[0]
            val next = when (direction) {
                Direction.UP -> Cell(h.x, h.y - 1)
                Direction.DOWN -> Cell(h.x, h.y + 1)
                Direction.LEFT -> Cell(h.x - 1, h.y)
                Direction.RIGHT -> Cell(h.x + 1, h.y)
            }
            if (next.x !in 0 until cols || next.y !in 0 until rows || snake.contains(next)) {
                over = true; invalidate(); return
            }
            snake.add(0, next)
            if (next == food) { score++; placeFood() } else snake.removeAt(snake.lastIndex)
        }
        private fun setDirection(new: Direction) {
            val opposite = when (direction) {
                Direction.UP -> Direction.DOWN; Direction.DOWN -> Direction.UP
                Direction.LEFT -> Direction.RIGHT; Direction.RIGHT -> Direction.LEFT
            }
            if (new != opposite) queued = new
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(bg)
            val d = resources.displayMetrics.density
            val w = width.toFloat(); val h = height.toFloat()
            paint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
            paint.color = light; paint.textSize = 28f * d
            c.drawText("SNAKE", 24f * d, 42f * d, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT; paint.color = muted; paint.textSize = 12f * d
            c.drawText("SWIPE TO MOVE", 24f * d, 64f * d, paint)
            val side = min(w - 32f * d, h - 142f * d)
            cell = side / cols; left = (w - side) / 2f; top = 88f * d
            val boardHeight = cell * rows
            paint.color = panel
            c.drawRoundRect(RectF(left - 7*d, top - 7*d, left + side + 7*d, top + boardHeight + 7*d), 14*d, 14*d, paint)
            paint.color = board
            c.drawRoundRect(RectF(left, top, left + side, top + boardHeight), 9*d, 9*d, paint)
            paint.color = grid; paint.strokeWidth = 1f
            for (x in 1 until cols) c.drawLine(left + x*cell, top, left + x*cell, top + boardHeight, paint)
            for (y in 1 until rows) c.drawLine(left, top + y*cell, left + side, top + y*cell, paint)
            drawFood(c); drawSnake(c)
            val footer = top + boardHeight + 46*d
            paint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
            paint.color = muted; paint.textSize = 12*d; c.drawText("SCORE", left, footer, paint)
            paint.color = light; paint.textSize = 25*d; c.drawText(score.toString().padStart(2, '0'), left, footer + 27*d, paint)
            paint.color = muted; paint.textSize = 12*d; c.drawText("BEST RUN", left + side - 72*d, footer, paint)
            paint.color = bright; paint.textSize = 25*d; c.drawText(score.toString().padStart(2, '0'), left + side - 72*d, footer + 27*d, paint)
            if (over) drawGameOver(c, d, side, boardHeight)
        }
        private fun drawFood(c: Canvas) {
            val x = left + food.x*cell + cell/2; val y = top + food.y*cell + cell/2
            paint.color = coral; c.drawCircle(x, y, cell*.31f, paint)
            paint.color = Color.rgb(255, 180, 130); c.drawCircle(x-cell*.1f, y-cell*.1f, cell*.08f, paint)
        }
        private fun drawSnake(c: Canvas) {
            snake.forEachIndexed { i, p ->
                val inset = cell*.11f
                val r = RectF(left+p.x*cell+inset, top+p.y*cell+inset, left+(p.x+1)*cell-inset, top+(p.y+1)*cell-inset)
                paint.color = if (i == 0) bright else green
                c.drawRoundRect(r, cell*.2f, cell*.2f, paint)
                if (i == 0) {
                    paint.color = Color.rgb(8, 54, 34); val eye = cell*.075f
                    val ex = if (direction == Direction.LEFT) r.left+r.width()*.28f else r.left+r.width()*.72f
                    c.drawCircle(ex, r.top+r.height()*.32f, eye, paint); c.drawCircle(ex, r.top+r.height()*.68f, eye, paint)
                }
            }
        }
        private fun drawGameOver(c: Canvas, d: Float, side: Float, bh: Float) {
            paint.color = Color.argb(205, 7, 18, 14)
            c.drawRoundRect(RectF(left, top, left+side, top+bh), 9*d, 9*d, paint)
            paint.textAlign = Paint.Align.CENTER; paint.typeface = android.graphics.Typeface.create("sans", 1)
            paint.color = light; paint.textSize = 27*d; c.drawText("RUN OVER", left+side/2, top+bh/2-16*d, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT; paint.color = muted; paint.textSize = 14*d
            c.drawText("Score  $score", left+side/2, top+bh/2+12*d, paint)
            paint.color = bright; paint.textSize = 13*d; c.drawText("TAP TO RESTART", left+side/2, top+bh/2+48*d, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x-downX; val dy = e.y-downY
                    if (over && abs(dx) < 30 && abs(dy) < 30) { restart(); return true }
                    if (abs(dx) > abs(dy) && abs(dx) > 30) setDirection(if (dx > 0) Direction.RIGHT else Direction.LEFT)
                    else if (abs(dy) > 30) setDirection(if (dy > 0) Direction.DOWN else Direction.UP)
                    return true
                }
            }
            return true
        }
    }
}
