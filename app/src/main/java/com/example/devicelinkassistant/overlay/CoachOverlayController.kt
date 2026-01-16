package com.example.devicelinkassistant.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * Overlay owned by an AccessibilityService.
 * Uses TYPE_ACCESSIBILITY_OVERLAY, so it does not require SYSTEM_ALERT_WINDOW.
 */
class CoachOverlayController(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = CoachOverlayView(service)
    private var isShown = false

    fun show() {
        if (isShown) return

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        wm.addView(view, lp)
        isShown = true
    }

    fun hide() {
        if (!isShown) return
        try {
            wm.removeView(view)
        } catch (_: Throwable) {
        }
        isShown = false
    }

    fun update(target: Rect, label: String) {
        view.setTarget(target, label)
    }
}
