package com.hank.quizoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var endpoint: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var optionCount: EditText
    private lateinit var ppUrl: EditText
    private lateinit var threshold: EditText
    private lateinit var autoTap: CheckBox
    private lateinit var guess: CheckBox
    private lateinit var wizCategory: CheckBox
    private lateinit var backendGroup: RadioGroup
    private lateinit var langGroup: RadioGroup
    private lateinit var result: TextView

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, r.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, r.data)
            )
            moveTaskToBack(true)
        } else {
            toast("沒有取得螢幕擷取權限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setPadding(0, 0, 0, 16)
        })
        // 設定好之後最常用的就是啟動，放在最上面不用每次捲到底
        root.addView(button("▶ 啟動浮窗") { launchOverlay() })

        root.addView(header("決策後端"))
        backendGroup = RadioGroup(this).apply {
            addView(RadioButton(context).apply { id = 1; text = "System One API（PC 上的 d3 / Clef / Jev 相容）" })
            addView(RadioButton(context).apply { id = 2; text = "Gemini API（免費額度）" })
            check(if (settings.backend == Settings.BACKEND_GEMINI) 2 else 1)
        }
        root.addView(backendGroup)

        endpoint = field(root, "System One 端點 URL", settings.endpoint)
        apiKey = field(root, "API Key（PC 伺服器 .env 的 API_TOKEN，d3 和 OCR 共用；沒設就留空）", settings.apiKey)
        model = field(root, "模型名稱（可留空；Gemini 預設 ${Settings.DEFAULT_GEMINI_MODEL}）", settings.model)
        optionCount = field(root, "選項數量", settings.optionCount.toString(), InputType.TYPE_CLASS_NUMBER)

        root.addView(header("OCR"))
        langGroup = RadioGroup(this).apply {
            addView(RadioButton(context).apply { id = 14; text = "PC 上的 PP-OCRv6（中日英一起讀，最準）" })
            addView(RadioButton(context).apply { id = 11; text = "ML Kit 手機端：自動判斷語言" })
            addView(RadioButton(context).apply { id = 12; text = "ML Kit 手機端：中/英" })
            addView(RadioButton(context).apply { id = 13; text = "ML Kit 手機端：日/英" })
            check(when (settings.ocrLang) { OcrEngine.MODE_PP -> 14; "zh" -> 12; "ja" -> 13; else -> 11 })
        }
        root.addView(langGroup)
        ppUrl = field(root, "PP-OCRv6 服務 URL", settings.ppUrl)

        root.addView(header("自動點擊"))
        autoTap = CheckBox(this).apply { text = "信心值超過門檻時自動點擊（需啟用無障礙服務）"; isChecked = settings.autoTap }
        root.addView(autoTap)
        guess = CheckBox(this).apply { text = "信心低於門檻時仍點機率最高的選項"; isChecked = settings.guessWhenUnsure }
        root.addView(guess)
        wizCategory = CheckBox(this).apply { text = "黑貓維茲：自動選我方有的屬性類型"; isChecked = settings.wizAutoCategory }
        root.addView(wizCategory)
        threshold = field(root, "信心門檻（0–1）", settings.threshold.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        root.addView(header("步驟"))
        root.addView(button("1. 授予浮窗權限") {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. 開啟無障礙服務（自動點擊才需要）") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("3. 測試決策後端") { testBackend() })
        root.addView(button("4. 啟動浮窗") { launchOverlay() })

        result = TextView(this).apply { setPadding(0, 24, 0, 0) }
        root.addView(result)

        return ScrollView(this).apply {
            addView(root)
            // Android 15 起內容一律延伸到狀態列／導覽列底下，要自己留出空間
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun saveSettings() {
        settings.backend = if (backendGroup.checkedRadioButtonId == 2) Settings.BACKEND_GEMINI else Settings.BACKEND_SYSTEM_ONE
        settings.endpoint = endpoint.text.toString()
        settings.apiKey = apiKey.text.toString()
        settings.model = model.text.toString()
        settings.optionCount = optionCount.text.toString().toIntOrNull() ?: 4
        settings.ocrLang = when (langGroup.checkedRadioButtonId) {
            14 -> OcrEngine.MODE_PP; 12 -> "zh"; 13 -> "ja"; else -> "auto"
        }
        settings.ppUrl = ppUrl.text.toString()
        settings.autoTap = autoTap.isChecked
        settings.guessWhenUnsure = guess.isChecked
        settings.wizAutoCategory = wizCategory.isChecked
        settings.threshold = threshold.text.toString().toFloatOrNull() ?: 0.6f
    }

    private fun testBackend() {
        saveSettings()
        result.text = "測試中…"
        val options = listOf("大阪", "東京", "京都", "名古屋")
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            result.text = try {
                val d = Decider.create(settings).decide("日本的首都是哪裡？", options)
                "回答：${optionLetter(d.index)} ${options[d.index]}，信心 ${(d.confidence * 100).toInt()}%，" +
                    "耗時 ${System.currentTimeMillis() - start}ms"
            } catch (e: Exception) {
                "失敗：${e.message}"
            }
        }
    }

    private fun launchOverlay() {
        saveSettings()
        if (!AndroidSettings.canDrawOverlays(this)) {
            toast("請先完成步驟 1：授予浮窗權限")
            return
        }
        if (settings.autoTap && !TapService.isEnabled) {
            toast("已開啟自動點擊，但無障礙服務尚未啟用（步驟 2）")
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 32, 0, 8)
    }

    private fun field(root: LinearLayout, label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        root.addView(TextView(this).apply { text = label })
        return EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
        }.also { root.addView(it) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
