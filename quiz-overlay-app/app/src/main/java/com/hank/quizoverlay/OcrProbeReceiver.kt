package com.hank.quizoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 開發用：對一張圖片用不同前處理各跑一次 OCR，把結果寫進 logcat（tag OcrProbe），用來調整前處理。
 * 只有 adb shell 能觸發（receiver 需要 DUMP 權限）：
 *   adb shell am broadcast -n com.hank.quizoverlay/.OcrProbeReceiver --es path /sdcard/Download/f.png \
 *       --ei qtop 700 --ei qbottom 950
 * qtop/qbottom：題目所在的 y 範圍（原圖座標）
 */
class OcrProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val qTop = intent.getIntExtra("qtop", 0)
        val qBottom = intent.getIntExtra("qbottom", 0)
        val lang = intent.getStringExtra("lang") ?: "zh"
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            val ocr = OcrEngine()
            try {
                val full = BitmapFactory.decodeFile(path) ?: error("讀不到 $path")
                val region = Rect(0, qTop, full.width, qBottom)
                val variants = listOf(
                    "q 720 raw" to Preprocess.scaleToWidth(
                        android.graphics.Bitmap.createBitmap(full, 0, qTop, full.width, qBottom - qTop), 720),
                    "q 1080 raw" to android.graphics.Bitmap.createBitmap(full, 0, qTop, full.width, qBottom - qTop),
                    "q 1080 bin200" to Preprocess.binarizeBrightText(full, region, 200),
                    "q 1080 bin170" to Preprocess.binarizeBrightText(full, region, 170),
                    "q 720 bin200" to Preprocess.scaleToWidth(Preprocess.binarizeBrightText(full, region, 200), 720),
                )
                for ((name, bmp) in variants) {
                    ocr.recognize(bmp, lang) // 暖機，避免第一次載入模型的時間算進去
                    val t = SystemClock.elapsedRealtime()
                    val lines = ocr.recognize(bmp, lang)
                    Log.i(TAG, "$name ${SystemClock.elapsedRealtime() - t}ms → ${lines.joinToString(" / ") { it.text }}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "probe failed", e)
            } finally {
                ocr.close()
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "OcrProbe"
    }
}
