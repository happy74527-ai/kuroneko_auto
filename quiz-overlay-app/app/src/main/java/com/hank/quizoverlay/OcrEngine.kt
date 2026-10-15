package com.hank.quizoverlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

data class OcrLine(val text: String, val box: Rect)

/**
 * OCR，兩種引擎：
 * - mode = "pp"：送到 PC 上的 PP-OCRv6 small（ocr-eval/ocr_server.py），一個模型同時讀中／日／英，準確度比 ML Kit 高很多
 * - mode = auto / zh / ja：ML Kit 裝置端。中文辨識器也能讀英文；日文題目靠假名比例判斷。
 *   「自動」模式第一次會兩個辨識器都跑來判斷語言，之後只跑判斷出來的那一個（[stickyLang]），
 *   呼叫端在信心偏低時呼叫 [resetLanguage] 重新判斷。
 */
class OcrEngine(private val ppUrl: String = "", private val apiKey: String = "") {
    private val zh = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val ja = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    var stickyLang: String? = null
        private set

    fun resetLanguage() {
        stickyLang = null
    }

    /**
     * bitmap 是從原畫面裁切、縮放過的圖時，用 [scaleBack]（縮放倍率的倒數）與 [offsetX]/[offsetY]（裁切左上角）
     * 把回傳的座標換算回原畫面。
     */
    suspend fun recognize(
        bitmap: Bitmap,
        mode: String,
        offsetX: Int = 0,
        offsetY: Int = 0,
        scaleBack: Float = 1f,
    ): List<OcrLine> {
        if (mode == MODE_PP) return recognizeRemote(bitmap, offsetX, offsetY, scaleBack)
        val start = SystemClock.elapsedRealtime()
        val lang = if (mode == "zh" || mode == "ja") mode else stickyLang
        val text = if (lang != null) {
            run(lang, bitmap)
        } else {
            coroutineScope {
                // 兩個辨識器同時跑，延遲約等於較慢的那一個
                val zhTask = async { run("zh", bitmap) }
                val jaTask = async { run("ja", bitmap) }
                val zhText = zhTask.await()
                val jaText = jaTask.await()
                val detected = if (looksJapanese(jaText.text)) "ja" else "zh"
                stickyLang = detected
                if (detected == "ja") jaText else zhText
            }
        }
        Log.i(TAG, "ocr ${bitmap.width}x${bitmap.height} lang=${lang ?: "detect→$stickyLang"} " +
            "${SystemClock.elapsedRealtime() - start}ms")
        return text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                line.boundingBox?.let { b ->
                    val box = Rect(
                        (b.left * scaleBack).toInt(), (b.top * scaleBack).toInt(),
                        (b.right * scaleBack).toInt(), (b.bottom * scaleBack).toInt(),
                    ).apply { offset(offsetX, offsetY) }
                    OcrLine(line.text, box)
                }
            }
        }
    }

    fun close() {
        zh.close()
        ja.close()
    }

    private suspend fun recognizeRemote(bitmap: Bitmap, offsetX: Int, offsetY: Int, scaleBack: Float): List<OcrLine> =
        withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            val req = Request.Builder().url(ppUrl).post(jpeg.toRequestBody(JPEG_TYPE))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val body = http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("OCR 伺服器 HTTP ${resp.code}: ${text.take(200)}")
                JSONObject(text)
            }
            val arr = body.getJSONArray("lines")
            Log.i(TAG, "ocr ${bitmap.width}x${bitmap.height} pp ${SystemClock.elapsedRealtime() - start}ms " +
                "(server ${body.optDouble("ms")}ms, ${jpeg.size / 1024}KB)")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("box")
                OcrLine(
                    o.getString("text"),
                    Rect(
                        (b.getInt(0) * scaleBack).toInt(), (b.getInt(1) * scaleBack).toInt(),
                        (b.getInt(2) * scaleBack).toInt(), (b.getInt(3) * scaleBack).toInt(),
                    ).apply { offset(offsetX, offsetY) },
                )
            }
        }

    private suspend fun run(lang: String, bitmap: Bitmap): Text =
        (if (lang == "ja") ja else zh).process(InputImage.fromBitmap(bitmap, 0)).await()

    /**
     * 日文辨識器讀中文時偶爾會把漢字誤讀成一兩個假名，所以看比例：
     * 日文句子裡假名（は、の、が…）通常佔漢字＋假名的兩成以上。
     */
    private fun looksJapanese(text: String): Boolean {
        val kana = KANA.findAll(text).count()
        val cjk = CJK.findAll(text).count()
        return kana >= 3 && kana * 100 >= (kana + cjk) * 15
    }

    companion object {
        const val MODE_PP = "pp"
        private const val TAG = "QuizOverlay"
        private val JPEG_TYPE = "image/jpeg".toMediaType()
        private val KANA = Regex("[\\u3040-\\u309F\\u30A0-\\u30FA]")
        private val CJK = Regex("[\\u4E00-\\u9FFF]")
    }
}
