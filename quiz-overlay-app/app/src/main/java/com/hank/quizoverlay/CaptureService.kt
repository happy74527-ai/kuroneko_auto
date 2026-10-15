package com.hank.quizoverlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * 前景服務：持有螢幕擷取權限，持續擷取畫面 → 偵測新題目 → OCR → 決策模型 → 標示/點擊。
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private lateinit var overlay: OverlayController
    private lateinit var ocr: OcrEngine

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var loopJob: Job? = null

    /** 擷取解析度相對於實際螢幕的縮放比例 */
    private var scale = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        settings = Settings(this)
        ocr = OcrEngine(settings.ppUrl, settings.apiKey)
        startProjection(resultCode, data)

        overlay = OverlayController(
            this,
            onToggle = { running -> if (running) startLoop() else stopLoop() },
            onClose = { stopSelf() },
        )
        overlay.show()
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, Handler(Looper.getMainLooper()))
        projection = mp

        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        // 以原始解析度擷取；縮放留到 OCR 前自己做（見 OCR_WIDTH）
        scale = 1f
        val w = (bounds.width() * scale).toInt()
        val h = (bounds.height() * scale).toInt()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "quiz-capture", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
    }

    private fun startLoop() {
        stopLoop()
        val decider = Decider.create(settings)
        overlay.setStatus("等待題目…")
        loopJob = scope.launch {
            var frame: Bitmap? = null
            var prevSig: IntArray? = null
            var handledSig: IntArray? = null
            var lastKey: String? = null
            // 成功讀到題目後記下題目＋選項所在的範圍，之後只 OCR 這一段，背景圖片不用一起辨識
            var roi: Rect? = null
            var retriedKey: String? = null
            /** 同一個畫面已經放寬到第幾步（見下方讀不到題目時的處理） */
            var escalation = 0
            /** 讀到問句、等選項出現的開始時間；0 = 沒在等，TIMED_OUT = 這個畫面已經等過、逾時了 */
            var waitingSince = 0L
            var lastAttempt = 0L
            while (isActive) {
                delay(POLL_MS)
                val excluded = overlay.excludedRects().map { toCapture(it) }
                val fresh = grabFrame()
                val stable: Boolean
                if (fresh != null) {
                    frame?.recycle()
                    frame = fresh
                    val sig = withContext(Dispatchers.Default) { signature(fresh, excluded) }
                    // 還沒有題目範圍時看整個畫面，容許計時器這類小範圍動畫（約 1.5% 的格子）；
                    // 有題目範圍時容許其中 4% 的格子在動（黑貓維茲的計時條就夾在題目和選項之間，一直在跑）
                    val tolerance = roi?.let { r ->
                        maxOf(STABLE_DIFF, (r.height().toFloat() / fresh.height * SIG_H * SIG_W * 0.04f).toInt())
                    } ?: STABLE_DIFF_FULL
                    stable = prevSig?.let { diff(it, sig, roi, fresh.width, fresh.height) <= tolerance } ?: false
                    prevSig = sig
                } else {
                    // 畫面靜止時系統不會送新的一幀，沒有新幀就代表畫面已經穩定
                    stable = frame != null
                }
                val bmp = frame ?: continue
                val sig = prevSig ?: continue

                // 畫面要「穩定」而且跟上次處理的「不一樣」才處理。
                // 例外：讀到問句在等選項，畫面卻靜止太久 → 選項多半其實在、只是沒讀出來，重讀同一幀往下放寬
                val changed = handledSig?.let { diff(it, sig, roi, bmp.width, bmp.height) > CHANGED_DIFF } ?: true
                val waitTimedOut = waitingSince > 0L &&
                    SystemClock.elapsedRealtime() - waitingSince > WAIT_OPTIONS_MS
                // 背景一直有動畫（燭火、水泡）時畫面永遠不「穩定」：有變化但超過 FORCE_PROCESS_MS 沒處理過就直接處理
                val now = SystemClock.elapsedRealtime()
                val overdue = changed && now - lastAttempt > FORCE_PROCESS_MS
                if (!(stable || overdue) || !(changed || waitTimedOut)) continue
                lastAttempt = now
                if (waitTimedOut) {
                    waitingSince = TIMED_OUT
                } else if (handledSig != null) {
                    // 真的換了畫面（不是放寬條件後重讀同一幀）
                    escalation = 0
                    waitingSince = 0L
                }
                handledSig = sig

                try {
                    withContext(Dispatchers.Default) { maskRects(bmp, excluded) }
                    val t0 = SystemClock.elapsedRealtime()
                    val crop = roi
                    val detecting = settings.ocrLang == "auto" && ocr.stickyLang == null
                    // 以原始解析度擷取，OCR 前自己用平滑縮放縮到 OCR_WIDTH：
                    // 讓系統直接輸出低解析度畫面會有鋸齒，OCR 錯字明顯變多（囓→喝、處→虎）
                    val region = crop ?: Rect(0, 0, bmp.width, bmp.height)
                    val factor = min(1f, OCR_WIDTH / bmp.width)
                    val part = withContext(Dispatchers.Default) {
                        val cut = Bitmap.createBitmap(bmp, region.left, region.top, region.width(), region.height())
                        if (factor == 1f) cut
                        else Bitmap.createScaledBitmap(
                            cut, (cut.width * factor).toInt(), (cut.height * factor).toInt(), true,
                        ).also { cut.recycle() }
                    }
                    val lines = ocr.recognize(part, settings.ocrLang, region.left, region.top, 1f / factor)
                    part.recycle()
                    val t1 = SystemClock.elapsedRealtime()
                    // 「題目」不是問句的（選單、選類型畫面、結算畫面）不問模型，當作沒讀到題目
                    val parsed = QuestionParser.parse(lines, bmp.width, bmp.height, settings.optionCount, excluded)
                        ?.takeIf { QuestionParser.looksLikeQuestion(it.question) }
                    if (parsed == null) {
                        // 畫面已經不是上一題了，把上一題的答案圓點收起來
                        overlay.hideMarker()
                        if (settings.autoTap && settings.wizAutoCategory) {
                            val pick = WizCategoryPicker.pick(lines, bmp)
                            if (pick != null) {
                                val now = SystemClock.elapsedRealtime()
                                // 點下去之後面板有動畫，短時間內不重複點
                                if (now - lastCategoryTap > CATEGORY_TAP_GAP_MS) {
                                    lastCategoryTap = now
                                    val tapped = TapService.tap(pick.tap.x / scale, pick.tap.y / scale)
                                    Log.i(TAG, "category ${pick.label}(${pick.color}) party=${pick.partyColors} enemy=${pick.enemyColors}: $tapped")
                                    overlay.setStatus(
                                        "選類型：${pick.label}（${pick.color}）\n" +
                                            "我方：${pick.partyColors.joinToString()}｜敵：${pick.enemyColors.joinToString()}",
                                    )
                                }
                                handledSig = null  // 點完後的畫面就算只有小變化也要再讀
                                continue
                            }
                        }
                        if (QuestionParser.hasQuestionLine(lines) && waitingSince == 0L) {
                            // 題目先出現、選項還沒出來（知識王就是這樣）：先不重試，等畫面變化（選項出現）。
                            // 畫面靜止超過 WAIT_OPTIONS_MS 還是一樣，就當作選項其實在、只是沒讀出來，往下重試
                            overlay.setStatus("讀到題目，等選項…（OCR ${t1 - t0}ms）")
                            waitingSince = SystemClock.elapsedRealtime()
                            continue
                        }
                        overlay.setStatus("等待題目…（OCR ${t1 - t0}ms）")
                        // 切不出題目可能是語言或裁切範圍不對（辨識器遇到不認得的文字會整行丟掉），
                        // 同一幀逐步放寬再讀：裁切＋目前語言 → 裁切＋重新判斷語言 → 整張畫面＋重新判斷語言
                        val auto = settings.ocrLang == "auto"
                        // 文字很少的是過場畫面（「第 3 題」、倒數），不是讀錯：保留題目範圍，不重試
                        if (lines.size <= settings.optionCount) {
                            saveDebugFrame(bmp, "noquiz")
                            continue
                        }
                        when {
                            escalation == 0 && auto && crop != null && !detecting -> {
                                ocr.resetLanguage()
                                escalation = 1
                            }
                            escalation <= 1 && (crop != null || (auto && !detecting)) -> {
                                if (auto) ocr.resetLanguage()
                                roi = null
                                escalation = 2
                            }
                            else -> escalation = 3
                        }
                        if (escalation < 3) handledSig = null else saveDebugFrame(bmp, "noquiz")
                        continue
                    }
                    // OCR 每次可能差一兩個字（合歡山／台歡山），相似度夠高就當作同一題
                    if (lastKey?.let { similarity(it, parsed.key) >= SAME_QUESTION } == true) continue
                    lastKey = parsed.key
                    overlay.hideMarker()
                    overlay.setStatus("思考中…\n${parsed.question.take(60)}")

                    val optionTexts = parsed.options.map { it.text }
                    val decision = ArithmeticSolver.solve(parsed.question, optionTexts)
                        ?: decider.decide(parsed.question, optionTexts)
                    val t2 = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Q=${parsed.question} | options=${parsed.options.map { it.text }} " +
                        "-> ${optionLetter(decision.index)} conf=${decision.confidence} " +
                        "probs=${decision.probabilities.map { "%.2f".format(it) }} source=${decision.source} ocr=${t1 - t0}ms model=${t2 - t1}ms")

                    // 題目只剩幾個字，通常是用錯語言的辨識器把主要那行整行丟掉了（例如日文題只讀到「か?」）：
                    // 先不作答，重新判斷語言、讀同一幀一次
                    val alreadyRetried = retriedKey?.let { similarity(it, parsed.key) >= SAME_QUESTION } == true
                    if (settings.ocrLang == "auto" && parsed.question.length < SHORT_QUESTION && !alreadyRetried &&
                        decision.confidence < settings.threshold
                    ) {
                        Log.i(TAG, "question too short, retry with language detection")
                        retriedKey = parsed.key
                        ocr.resetLanguage()
                        roi = null
                        lastKey = null
                        handledSig = null
                        continue
                    }

                    answer(parsed, decision, ocrMs = t1 - t0, modelMs = t2 - t1)
                    saveDebugFrame(bmp, "quiz")
                    if (QuestionParser.looksLikeQuestion(parsed.question)) roi = questionBand(parsed.bounds, bmp)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastKey = null
                    overlay.setStatus("錯誤：${e.message}")
                }
            }
        }
    }

    /** 題目＋選項所在的水平帶狀範圍（整個寬度，上下各留一些空間給較長的題目） */
    private fun questionBand(bounds: Rect, bmp: Bitmap) = Rect(
        0,
        (bounds.top - bmp.height * 0.10f).toInt().coerceAtLeast(0),
        bmp.width,
        (bounds.bottom + bmp.height * 0.04f).toInt().coerceAtMost(bmp.height),
    )

    private var debugFrameCount = 0
    private var lastCategoryTap = 0L
    private var pixelBuffer: IntArray? = null

    /**
     * 保留最近幾張有作答的畫面，用 adb pull 拿出來分析版面（/sdcard/Android/data/<pkg>/files/frames）。
     * 沒讀到題目的畫面不存：強制處理時每 1.2 秒就一張，整張壓 JPEG 會拖慢模擬器。存之前先縮到一半。
     */
    private fun saveDebugFrame(bmp: Bitmap, tag: String) {
        if (tag != "quiz") return
        val dir = getExternalFilesDir("frames") ?: return
        val copy = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
        val file = java.io.File(dir, "${tag}_${debugFrameCount++ % DEBUG_FRAMES}.jpg")
        scope.launch(Dispatchers.IO) {
            file.outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            copy.recycle()
        }
    }

    /** 把浮窗佔用的區域塗成旁邊的背景色，避免 OCR 讀到面板或答案圓點上的字 */
    private fun maskRects(bmp: Bitmap, rects: List<Rect>) {
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint()
        for (r in rects) {
            val sx = (r.right + 2).coerceAtMost(bmp.width - 1)
            val sy = r.centerY().coerceIn(0, bmp.height - 1)
            paint.color = bmp.getPixel(sx, sy)
            canvas.drawRect(r, paint)
        }
    }

    private fun answer(parsed: ParsedQuestion, decision: Decision, ocrMs: Long, modelMs: Long) {
        val opt = parsed.options[decision.index]
        val label = optionLetter(decision.index)
        val centerX = opt.box.centerX() / scale
        val centerY = opt.box.centerY() / scale
        overlay.showMarker(label, (opt.box.left / scale).toInt(), centerY.toInt())

        val sure = decision.confidence >= settings.threshold
        // 題庫和算數的答案是確定的，直接標來源；模型判斷的才顯示信心值
        val origin = when (decision.source) {
            "qbank" -> "【題庫】"
            "qbank-web" -> "【題庫・線上】"
            "math" -> "【計算】"
            // d3 判斷，但參考了題庫裡意思相近的題目
            "d3+hint" -> "（信心 ${(decision.confidence * 100).toInt()}%${if (sure) "" else "，猜的"}・參考題庫）"
            else -> "（信心 ${(decision.confidence * 100).toInt()}%${if (sure) "" else "，猜的"}）"
        }
        var status = "答案 $label：${opt.text}$origin"
        if (decision.probabilities.isNotEmpty()) {
            // 每個選項的機率，例如「A 2%  B 91%  C 5%  D 2%」
            status += "\n" + decision.probabilities.withIndex()
                .joinToString("  ") { (i, p) -> "${optionLetter(i)} ${(p * 100).toInt()}%" }
        }
        // 「判斷」包含查題庫（本機或線上）和模型的時間
        status += "\nOCR ${ocrMs}ms｜判斷 ${modelMs}ms"

        // 只在「像問句」的畫面點擊，避免在選單、結算畫面誤點
        val isQuestion = QuestionParser.looksLikeQuestion(parsed.question)
        if (settings.autoTap && isQuestion && (sure || settings.guessWhenUnsure)) {
            val tapped = TapService.tap(centerX, centerY)
            Log.i(TAG, "tap $label at ($centerX, $centerY): $tapped")
            status += if (tapped) "｜已點擊" else "\n⚠ 無障礙服務未啟用，無法點擊"
        } else if (settings.autoTap && !isQuestion) {
            status += "｜不像題目，未點擊"
        }
        overlay.setStatus(status)
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun grabFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return image.use { it.toBitmap() }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        // 要可寫入（maskRects 會在上面畫），所以用 copy 而不是 createBitmap 子圖
        val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(cropped).drawBitmap(padded, 0f, 0f, null)
        padded.recycle()
        return cropped
    }

    private fun toCapture(r: Rect) = Rect(
        (r.left * scale).toInt(), (r.top * scale).toInt(),
        (r.right * scale).toInt(), (r.bottom * scale).toInt(),
    )

    /**
     * 畫面指紋：切成 SIG_W x SIG_H 格，每格取「區域平均」亮度（浮窗範圍不算）。
     * 只取單點會漏掉換題時的細小文字變化，所以要用區域平均。
     */
    private fun signature(bmp: Bitmap, excluded: List<Rect>): IntArray {
        val w = bmp.width
        val h = bmp.height
        // 每 150ms 跑一次：只讀取樣到的那幾列、每 SIG_STEP 個像素取一點，
        // 不要每次把整張 1080x2400（約 10MB）複製出來，模擬器會越跑越卡
        val row = pixelBuffer?.takeIf { it.size == w } ?: IntArray(w).also { pixelBuffer = it }
        val sum = LongArray(SIG_W * SIG_H)
        val count = IntArray(SIG_W * SIG_H)
        for (y in 0 until h step SIG_STEP) {
            val cy = y * SIG_H / h
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step SIG_STEP) {
                if (excluded.any { it.contains(x, y) }) continue
                val p = row[x]
                val cell = cy * SIG_W + x * SIG_W / w
                sum[cell] += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                count[cell]++
            }
        }
        return IntArray(sum.size) { if (count[it] == 0) 0 else (sum[it] / count[it]).toInt() }
    }

    /** 1 - 編輯距離 / 較長字串長度 */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /**
     * 亮度差超過門檻的格數。有 [focus]（題目＋選項的範圍）時只看中心點在範圍內的格子：
     * 知識王上方的倒數計時器一直在轉，看整個畫面的話幾乎永遠不「穩定」，選項出現後要等好幾秒才會處理。
     */
    private fun diff(a: IntArray, b: IntArray, focus: Rect? = null, w: Int = 0, h: Int = 0): Int =
        a.indices.count { i ->
            abs(a[i] - b[i]) > CELL_DIFF && (focus == null || focus.contains(
                ((i % SIG_W + 0.5f) * w / SIG_W).toInt(),
                ((i / SIG_W + 0.5f) * h / SIG_H).toInt(),
            ))
        }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "螢幕讀取", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("答題浮窗運行中")
            .setContentText("正在讀取螢幕畫面")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        if (::overlay.isInitialized) overlay.remove()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        projection = null
        if (::ocr.isInitialized) ocr.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuizOverlay"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 150L
        /** OCR 用的寬度：720 寬讀題目大小的字最準也夠快（1080 原圖反而偶爾讀錯，像素也多一倍） */
        private const val OCR_WIDTH = 720f
        private const val SAME_QUESTION = 0.85
        private const val CATEGORY_TAP_GAP_MS = 1500L
        /** 畫面一直不穩定時，最多隔這麼久還是要處理一次 */
        private const val FORCE_PROCESS_MS = 1200L
        /** 讀到問句後等選項出現的最長時間（畫面靜止時） */
        private const val WAIT_OPTIONS_MS = 800L
        private const val TIMED_OUT = -1L
        /** 題目少於這個字數就懷疑是語言辨識錯 */
        private const val SHORT_QUESTION = 6
        private const val DEBUG_FRAMES = 6
        /** 畫面指紋每隔幾個像素取一點（每格約 30x30 像素，取 7x7 點平均就夠穩定） */
        private const val SIG_STEP = 4
        private const val SIG_W = 36
        private const val SIG_H = 80
        /** 單格平均亮度差超過這個值才算「這格變了」 */
        private const val CELL_DIFF = 3
        /** 跟上一幀相比，變動格數 <= 這個值就算畫面穩定 */
        private const val STABLE_DIFF = 2
        /** 沒有題目範圍、看整個畫面時的穩定門檻 */
        private const val STABLE_DIFF_FULL = 40
        /** 跟上次處理的畫面相比，變動格數 > 這個值才算換題 */
        private const val CHANGED_DIFF = 6
    }
}
