package com.example.autotapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** 접근성 서비스: 지정 좌표를 탭한다 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * 여러 지점을 한 번의 제스처로 탭.
     * simultaneous=true 면 멀티터치로 동시에, false 면 짧은 간격으로 연속.
     */
    fun tap(xs: FloatArray, ys: FloatArray, count: Int, tapMs: Long, simultaneous: Boolean): Boolean {
        if (count <= 0) return false
        val n = minOf(count, GestureDescription.getMaxStrokeCount())
        val b = GestureDescription.Builder()
        for (i in 0 until n) {
            val path = Path().apply { moveTo(xs[i], ys[i]) }
            val start = if (simultaneous) 0L else i * (tapMs + 2L)
            b.addStroke(GestureDescription.StrokeDescription(path, start, tapMs))
        }
        return dispatchGesture(b.build(), null, null)
    }
}
