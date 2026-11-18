package com.example.joywalk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Round joystick. dx/dy are in [-1, 1]: dx = right, dy = down (screen coords).
 * With [cruise] on, the knob stays where you let go so you keep walking hands-free.
 */
class JoystickView(context: Context) : View(context) {

    var dx = 0f
        private set
    var dy = 0f
        private set
    var cruise = false

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * context.resources.displayMetrics.density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.FILL
    }

    private fun radius() = min(width, height) / 2f * 0.95f
    private fun knobRadius() = radius() * 0.38f
    private fun travel() = radius() - knobRadius()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius(), basePaint)
        canvas.drawCircle(cx, cy, radius(), ringPaint)
        canvas.drawCircle(cx + dx * travel(), cy + dy * travel(), knobRadius(), knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var x = (e.x - width / 2f) / travel()
                var y = (e.y - height / 2f) / travel()
                val m = hypot(x, y)
                if (m > 1f) { x /= m; y /= m }
                dx = x
                dy = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!cruise) center()
            }
        }
        return true
    }

    fun center() {
        dx = 0f
        dy = 0f
        invalidate()
    }
}
