package com.hank.quizoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** 只用來執行點擊手勢；不讀取任何無障礙事件內容。 */
class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        private var instance: TapService? = null

        val isEnabled: Boolean get() = instance != null

        /** 回傳 false 代表使用者還沒啟用無障礙服務 */
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            service.tapAt(x, y)
            return true
        }
    }
}
