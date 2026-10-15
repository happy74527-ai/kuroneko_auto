package com.hank.quizoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 浮窗：一個可拖曳的控制面板，加上一個標示建議答案的圓點。
 * 兩者的位置會從 OCR 範圍排除，避免模型讀到自己的輸出。
 */
class OverlayController(
    private val context: Context,
    private val onToggle: (running: Boolean) -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    var running = false
        private set

    private val toggleButton = Button(context).apply {
        text = "▶ 開始"
        setOnClickListener { setRunning(!running) }
    }

    private val status = TextView(context).apply {
        text = "按「開始」後會持續讀取畫面"
        setTextColor(Color.WHITE)
        textSize = 11f
        maxWidth = dp(240)
        maxLines = 4
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.argb(220, 30, 30, 30))
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(6), dp(4), dp(6), dp(4))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toggleButton)
            addView(Button(context).apply {
                text = "✕"
                setOnClickListener { onClose() }
            })
        }
        addView(row)
        addView(status)
    }

    private val marker = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(0, 160, 90))
        }
    }
    private var markerShown = false
    private val markerSize get() = dp(36)

    // 預設放在畫面最上方（遊戲通常是玩家資訊列），題目和選項區留給 OCR
    private val panelParams = overlayParams(touchable = true).apply { x = dp(8); y = dp(28) }
    private val markerParams = overlayParams(touchable = false).apply {
        width = markerSize
        height = markerSize
        // Android 12+：不可觸控的浮窗透明度要 <= 0.8，點擊才能穿透到遊戲
        alpha = 0.7f
    }

    private fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        panel.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = panelParams.x; startY = panelParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = startX + (e.rawX - downX).toInt()
                    panelParams.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(panel, panelParams)
                    true
                }
                else -> false
            }
        }
        wm.addView(panel, panelParams)
    }

    fun remove() {
        hideMarker()
        if (panel.isAttachedToWindow) wm.removeView(panel)
    }

    fun setRunning(value: Boolean) {
        running = value
        toggleButton.text = if (value) "⏸ 暫停" else "▶ 開始"
        if (!value) hideMarker()
        onToggle(value)
    }

    fun setStatus(text: String) {
        status.text = text
    }

    /** x, y 為螢幕座標（選項左緣、垂直中心） */
    fun showMarker(label: String, x: Int, y: Int) {
        marker.text = label
        // 放在選項文字左側並留一點間距，避免蓋住選項讓下一幀 OCR 讀不到
        markerParams.x = (x - markerSize - dp(8)).coerceAtLeast(0)
        markerParams.y = y - markerSize / 2
        if (markerShown) {
            wm.updateViewLayout(marker, markerParams)
        } else {
            wm.addView(marker, markerParams)
            markerShown = true
        }
    }

    fun hideMarker() {
        if (markerShown) {
            wm.removeView(marker)
            markerShown = false
        }
    }

    /** 浮窗目前佔用的螢幕範圍 */
    fun excludedRects(): List<Rect> = buildList {
        add(screenRect(panel))
        if (markerShown) add(screenRect(marker))
    }

    private fun screenRect(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }
}
