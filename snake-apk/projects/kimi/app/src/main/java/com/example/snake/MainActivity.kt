package com.example.snake

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var snakeView: SnakeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        snakeView = SnakeView(this)
        setContentView(snakeView)
    }

    override fun onPause() {
        super.onPause()
        snakeView.pauseGame()
    }

    override fun onResume() {
        super.onResume()
        snakeView.resumeGame()
    }
}
