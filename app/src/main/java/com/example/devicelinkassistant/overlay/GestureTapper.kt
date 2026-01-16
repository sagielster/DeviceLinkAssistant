package com.example.devicelinkassistant.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build

/**
 * Fallback tap using dispatchGesture (API 24+).
 */
object GestureTapper {
    fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }
}
