package com.hank.quizoverlay

import android.graphics.Bitmap
import android.graphics.Rect

/** OCR 前處理 */
object Preprocess {

    /**
     * 把「深色/花俏背景上的白字」轉成白底黑字：亮度 >= [threshold] 的像素變黑，其餘變白。
     * 知識王的題目是白字疊在照片上，背景的燈光、建築紋理會讓 OCR 把筆畫讀錯（蛾→峨、撲→摸）。
     * 只處理 [region]（題目那一段），選項按鈕是淺底深字，不能用同一套。
     */
    fun binarizeBrightText(src: Bitmap, region: Rect, threshold: Int = 200): Bitmap {
        val r = Rect(region).apply { intersect(0, 0, src.width, src.height) }
        val w = r.width()
        val h = r.height()
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, r.left, r.top, w, h)
        for (i in px.indices) {
            val p = px[i]
            // 白字：三個通道都要夠亮（排除紅色、橘色燈光）
            val min = minOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            px[i] = if (min >= threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun scaleToWidth(src: Bitmap, width: Int): Bitmap =
        if (src.width == width) src
        else Bitmap.createScaledBitmap(src, width, src.height * width / src.width, true)
}
