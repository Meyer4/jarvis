package com.jarvis.ai.gesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import androidx.core.graphics.withSave
import kotlin.math.min

/** Draws a low-pass-filtered floating cursor and applies the hand shortcut visibility rules. */
class GestureCursorView(
    context: Context,
    private val onPinchTap: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF42D9C8.toInt()
        style = Paint.Style.FILL
        setShadowLayer(14f * density, 0f, 0f, 0xAA42D9C8.toInt())
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F7FA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private var cursor = PointF(-1f, -1f)
    private var hasPosition = false
    private var cursorVisible = true

    /** Updates the smoothed position and handles open-palm, fist, and pinch transitions. */
    fun update(frame: GestureFrame) {
        post {
            frame.point?.let { normalized ->
                val mirrorX = 1f - normalized.x
                val targetX = mirrorX.coerceIn(0f, 1f) * width.coerceAtLeast(1)
                val targetY = normalized.y.coerceIn(0f, 1f) * height.coerceAtLeast(1)
                if (!hasPosition) {
                    cursor.set(targetX, targetY)
                    hasPosition = true
                } else {
                    cursor.x += LOW_PASS_ALPHA * (targetX - cursor.x)
                    cursor.y += LOW_PASS_ALPHA * (targetY - cursor.y)
                }
            }
            when (frame.gesture) {
                HandGesture.FIST -> cursorVisible = false
                HandGesture.OPEN_PALM -> cursorVisible = true
                HandGesture.PINCH_TAP -> {
                    cursorVisible = true
                    onPinchTap()
                }
                HandGesture.NONE -> Unit
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!cursorVisible || !hasPosition) return
        canvas.withSave {
            val radius = min(width, height).coerceAtLeast(1) * CURSOR_RADIUS_FRACTION
            drawCircle(cursor.x, cursor.y, radius, cursorPaint)
            drawCircle(cursor.x, cursor.y, radius + 4f * density, outlinePaint)
            drawCircle(cursor.x, cursor.y, 2.5f * density, outlinePaint)
        }
    }

    private companion object {
        const val LOW_PASS_ALPHA = 0.22f
        const val CURSOR_RADIUS_FRACTION = 0.012f
    }
}
