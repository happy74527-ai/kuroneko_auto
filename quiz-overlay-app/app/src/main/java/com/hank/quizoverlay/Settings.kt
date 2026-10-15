package com.hank.quizoverlay

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("quiz_overlay", Context.MODE_PRIVATE)

    var backend: String
        get() = prefs.getString("backend", BACKEND_SYSTEM_ONE)!!
        set(v) = prefs.edit().putString("backend", v).apply()

    /** System One 相容端點，例如 PC 上的 d3 伺服器 http://<PC IP>:8765/v1/systemone */
    var endpoint: String
        get() = prefs.getString("endpoint", "http://192.168.1.100:8765/v1/systemone")!!
        set(v) = prefs.edit().putString("endpoint", v.trim()).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "")!!
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()

    /** System One：可留空（d3 伺服器不需要）；Gemini：留空則用 [DEFAULT_GEMINI_MODEL] */
    var model: String
        get() = prefs.getString("model", "")!!
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    var optionCount: Int
        get() = prefs.getInt("optionCount", 4)
        set(v) = prefs.edit().putInt("optionCount", v.coerceIn(2, 6)).apply()

    /** PC 上 PP-OCRv6 OCR 服務（ocr-eval/ocr_server.py）的網址 */
    var ppUrl: String
        get() = prefs.getString("ppUrl", "http://192.168.1.100:8767/v1/ocr")!!
        set(v) = prefs.edit().putString("ppUrl", v.trim()).apply()

    /** pp（PC 上的 PP-OCRv6）/ auto / zh / ja（ML Kit 裝置端） */
    var ocrLang: String
        get() = prefs.getString("ocrLang", "auto")!!
        set(v) = prefs.edit().putString("ocrLang", v).apply()

    var autoTap: Boolean
        get() = prefs.getBoolean("autoTap", false)
        set(v) = prefs.edit().putBoolean("autoTap", v).apply()

    /** 信心低於門檻時仍點機率最高的選項（限時答題猜一個通常比不答好） */
    var guessWhenUnsure: Boolean
        get() = prefs.getBoolean("guessWhenUnsure", true)
        set(v) = prefs.edit().putBoolean("guessWhenUnsure", v).apply()

    /** 黑貓維茲：自動在選類型畫面點我方有的屬性顏色 */
    var wizAutoCategory: Boolean
        get() = prefs.getBoolean("wizAutoCategory", true)
        set(v) = prefs.edit().putBoolean("wizAutoCategory", v).apply()

    var threshold: Float
        get() = prefs.getFloat("threshold", 0.6f)
        set(v) = prefs.edit().putFloat("threshold", v.coerceIn(0f, 1f)).apply()

    companion object {
        const val BACKEND_SYSTEM_ONE = "system_one"
        const val BACKEND_GEMINI = "gemini"
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"
    }
}
