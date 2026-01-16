package com.example.devicelinkassistant

import kotlin.math.max
import kotlin.math.min

/**
 * A tiny stabilizer to prevent "random circles".
 *
 * Usage (inside ScreenCoachService after OCR):
 *  - call tracker.update(candidate) each frame
 *  - if update(...) returns Locked => show overlay at Locked.center
 *  - otherwise => hide overlay
 *
 * Candidate coordinates should be in SCREEN coordinates (px).
 */
class StableTargetTracker(
    private val stableRequired: Int = 3,
    private val iouRequired: Float = 0.50f,
    private val minScore: Float = 0.55f
) {
    data class Candidate(
        val label: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float
    ) {
        fun centerX(): Int = ((left + right) / 2f).toInt()
        fun centerY(): Int = ((top + bottom) / 2f).toInt()
        fun diameterPx(): Int = (max(right - left, bottom - top) * 1.3f).toInt().coerceAtLeast(88)
    }

    sealed class Output {
        object None : Output()
        data class CandidateSeen(val label: String) : Output()
        data class Locked(val label: String, val cx: Int, val cy: Int, val diameterPx: Int) : Output()
        object Lost : Output()
    }

    private var last: Candidate? = null
    private var stableCount: Int = 0

    fun reset(): Output {
        last = null
        stableCount = 0
        return Output.Lost
    }

    fun update(c: Candidate?): Output {
        if (c == null || c.score < minScore) {
            if (last != null) return reset()
            return Output.None
        }

        val prev = last
        if (prev == null) {
            last = c
            stableCount = 1
            return Output.CandidateSeen(c.label)
        }

        val iou = iou(prev, c)
        if (iou >= iouRequired) {
            stableCount++
            last = c
        } else {
            // changed target; restart stability
            last = c
            stableCount = 1
            return Output.CandidateSeen(c.label)
        }

        return if (stableCount >= stableRequired) {
            Output.Locked(c.label, c.centerX(), c.centerY(), c.diameterPx())
        } else {
            Output.CandidateSeen(c.label)
        }
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val iw = max(0f, right - left)
        val ih = max(0f, bottom - top)
        val inter = iw * ih
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}
