package com.durga.emergency.ui.gesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.durga.emergency.sensors.GesturePoint

/**
 * Interactive touch canvas for recording and testing custom pattern gestures.
 */
class GestureDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val rawPoints = mutableListOf<GesturePoint>()

    private val strokePaint = Paint().apply {
        color = 0xFFFF3366.toInt() // Durga vibrant emergency accent
        style = Paint.Style.STROKE
        strokeWidth = 12f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val backgroundPaint = Paint().apply {
        color = 0xFF1E2430.toInt() // Dark card background
        style = Paint.Style.FILL
    }

    var onDrawingFinished: ((List<GesturePoint>) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        canvas.drawPath(path, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                rawPoints.clear()
                path.moveTo(x, y)
                recordPoint(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(x, y)
                recordPoint(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                recordPoint(x, y)
                invalidate()
                val normalized = getNormalizedPoints()
                onDrawingFinished?.invoke(normalized)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun recordPoint(x: Float, y: Float) {
        rawPoints.add(GesturePoint(x, y, System.currentTimeMillis()))
    }

    fun getNormalizedPoints(): List<GesturePoint> {
        if (width <= 0 || height <= 0 || rawPoints.isEmpty()) return emptyList()

        return rawPoints.map {
            GesturePoint(
                x = (it.x / width).coerceIn(0f, 1f),
                y = (it.y / height).coerceIn(0f, 1f),
                timestamp = it.timestamp
            )
        }
    }

    fun setPathPoints(points: List<GesturePoint>) {
        path.reset()
        rawPoints.clear()
        if (points.isEmpty() || width <= 0 || height <= 0) {
            invalidate()
            return
        }

        val first = points.first()
        path.moveTo(first.x * width, first.y * height)
        for (i in 1 until points.size) {
            val p = points[i]
            path.lineTo(p.x * width, p.y * height)
        }
        invalidate()
    }

    fun setStrokeColor(colorInt: Int) {
        strokePaint.color = colorInt
        invalidate()
    }

    fun clear() {
        path.reset()
        rawPoints.clear()
        invalidate()
    }
}
