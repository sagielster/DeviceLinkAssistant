package com.example.devicelinkassistant

import android.content.Context
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.WindowManager

class CoachPointerOverlay(ctx: Context) : View(ctx) {

    // Default ring diameter (dp). Service may override by calling showAt(x,y,diameterPx).
    private val defaultSizePx = dp(72).coerceAtLeast(dp(60))

    init {
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(4).coerceAtLeast(3), Color.RED)
        }
        background = d
        layoutParams = WindowManager.LayoutParams(defaultSizePx, defaultSizePx)
        alpha = 0.9f
        visibility = GONE
    }

    fun hide() {
        visibility = GONE
    }

    /**
     * Show the ring centered at (x,y) in screen coordinates.
     *
     * IMPORTANT: the caller (ScreenCoachService) should ONLY call this for "locked" targets
     * (stable across multiple frames). For unstable candidates, call hide().
     */
    fun showAt(x: Int, y: Int) {
        showAt(x, y, defaultSizePx)
    }

    /**
     * Same as showAt, but allows the service to set the ring diameter in px
     * (e.g., based on OCR bounding box size).
     */
    fun showAt(x: Int, y: Int, diameterPx: Int) {
        visibility = VISIBLE
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        val sizePx = diameterPx.coerceAtLeast(dp(44))
        lp.width = sizePx
        lp.height = sizePx
        lp.x = (x - sizePx / 2).coerceAtLeast(0)
        lp.y = (y - sizePx / 2).coerceAtLeast(0)
        layoutParams = lp
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(this, lp)
        } catch (_: Throwable) {}
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
