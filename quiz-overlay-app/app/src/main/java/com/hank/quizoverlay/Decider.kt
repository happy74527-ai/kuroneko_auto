package com.hank.quizoverlay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [probabilities]：每個選項的機率（依選項順序）；後端沒提供時為空。
 * [source]：答案從哪裡來（PC 伺服器回傳）：qbank＝本機題庫、qbank-web＝剛從網站題庫查到、d3＝模型判斷
 */
data class Decision(
    val index: Int,
    val confidence: Float,
    val probabilities: List<Float> = emptyList(),
    val source: String = "",
) {
    val fromBank: Boolean get() = source.startsWith("qbank")
}

interface Decider {
    suspend fun decide(question: String, options: List<String>): Decision

    companion object {
        fun create(s: Settings): Decider = when (s.backend) {
            Settings.BACKEND_GEMINI ->
                GeminiDecider(s.apiKey, s.model.ifBlank { Settings.DEFAULT_GEMINI_MODEL })
            else -> SystemOneDecider(s.endpoint, s.apiKey, s.model)
        }
    }
}

internal val http = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

fun optionLetter(i: Int): String = ('A' + i).toString()

private fun letterIndex(s: String): Int = s.trim().uppercase().firstOrNull()?.let { it - 'A' } ?: -1

/**
 * System One 格式（Jev / d3 / Clef 共用的 state + questions 合約）。
 * 預設對接 pc-server/server.py；Cloudflare 會把回應包在 result 裡，這裡兩種都接受。
 */
class SystemOneDecider(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
) : Decider {

    override suspend fun decide(question: String, options: List<String>): Decision =
        withContext(Dispatchers.IO) {
            val criteria = JSONObject()
            options.forEachIndexed { i, o -> criteria.put(optionLetter(i), o) }
            val body = JSONObject()
                .put("state", question)
                .put(
                    "questions", JSONObject().put(
                        "answer", JSONObject()
                            .put("type", "choice")
                            .put(
                                "instructions",
                                "The state is quiz text read from a phone screen (Chinese, Japanese or English). " +
                                    "Which option is the correct answer to the quiz question?"
                            )
                            .put("criteria", criteria)
                    )
                )
            if (model.isNotBlank()) body.put("model", model)

            val req = Request.Builder().url(endpoint).post(body.toString().toRequestBody(JSON_TYPE))
            if (apiKey.isNotBlank()) req.header("Authorization", "Bearer $apiKey")
            http.newCall(req.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(200)}")
                parse(text, options.size)
            }
        }

    companion object {
        fun parse(text: String, optionCount: Int): Decision {
            val root = JSONObject(text)
            val answers = root.optJSONObject("answers")
                ?: root.optJSONObject("result")?.optJSONObject("answers")
                ?: throw IOException("回應裡沒有 answers：${text.take(200)}")
            val ans = answers.optJSONObject("answer")
                ?: throw IOException("回應裡沒有 answer：${text.take(200)}")

            var choice = ans.optString("choice", "")
            var conf = ans.optDouble("confidence", Double.NaN)
            val probs = ans.optJSONObject("probabilities")
            if (probs != null) {
                if (choice.isEmpty()) choice = probs.keys().asSequence().maxByOrNull { probs.optDouble(it) } ?: ""
                if (conf.isNaN()) conf = probs.optDouble(choice, Double.NaN)
            }
            val idx = letterIndex(choice)
            if (idx !in 0 until optionCount) throw IOException("無法解析選項：$choice")
            val probList = probs?.let { p -> List(optionCount) { p.optDouble(optionLetter(it), 0.0).toFloat() } }.orEmpty()
            return Decision(idx, if (conf.isNaN()) 0f else conf.toFloat(), probList, root.optString("source", ""))
        }
    }
}

/** Gemini：用 responseSchema 把輸出限制在選項字母，等同一個「生成式」的決策器。 */
class GeminiDecider(private val apiKey: String, private val model: String) : Decider {

    override suspend fun decide(question: String, options: List<String>): Decision =
        withContext(Dispatchers.IO) {
            val letters = JSONArray().apply { options.indices.forEach { put(optionLetter(it)) } }
            val prompt = buildString {
                appendLine("You are answering a multiple-choice quiz. The text was OCR'd from a phone screen and may contain noise such as player names, scores or timers. The question may be in Chinese, Japanese or English.")
                appendLine()
                appendLine("Question: $question")
                options.forEachIndexed { i, o -> appendLine("${optionLetter(i)}. $o") }
                appendLine()
                append("Reply with the letter of the correct option and your confidence between 0 and 1.")
            }
            val schema = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties", JSONObject()
                        .put("answer", JSONObject().put("type", "STRING").put("enum", letters))
                        .put("confidence", JSONObject().put("type", "NUMBER"))
                )
                .put("required", JSONArray().put("answer").put("confidence"))
            val body = JSONObject()
                .put(
                    "contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                )
                .put(
                    "generationConfig", JSONObject()
                        .put("temperature", 0)
                        .put("responseMimeType", "application/json")
                        .put("responseSchema", schema)
                )

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(200)}")
                val out = JSONObject(text)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text")
                val obj = JSONObject(out)
                val idx = letterIndex(obj.getString("answer"))
                if (idx !in options.indices) throw IOException("無法解析選項：$out")
                Decision(idx, obj.optDouble("confidence", 0.0).toFloat(), source = "gemini")
            }
        }
}
