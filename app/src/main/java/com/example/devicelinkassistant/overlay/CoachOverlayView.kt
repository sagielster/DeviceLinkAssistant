package com.example.devicelinkassistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Minimal overlay: dims the screen and highlights the current tap target.
 * You can replace this with circles/arrows later, but this makes it obvious in AS that it's working.
 */
class CoachOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val target = Rect()
    private var label: String = ""

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 90
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        alpha = 220
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        alpha = 230
    }

    fun setTarget(r: Rect, label: String) {
        target.set(r)
        this.label = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (target.isEmpty) return

        // Dim the whole screen a bit.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Highlight target with a padded rounded-rect.
        val pad = 16
        val rr = RectF(
            (target.left - pad).toFloat(),
            (target.top - pad).toFloat(),
            (target.right + pad).toFloat(),
            (target.bottom + pad).toFloat()
        )
        val radius = 22f
        canvas.drawRoundRect(rr, radius, radius, strokePaint)

        // Label above if possible, otherwise below.
        val lineHeight = max(42f, textPaint.textSize + 6f)
        val preferredY = rr.top - 18f
        val y = if (preferredY - lineHeight > 0) preferredY else rr.bottom + lineHeight
        val x = max(18f, rr.left)
        canvas.drawText(label, x, y, textPaint)
    }
}
