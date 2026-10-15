package com.hank.quizoverlay

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ParsedQuestion(val question: String, val questionBox: Rect, val options: List<OcrLine>) {
    /** 用來判斷是不是同一題，避免重複作答 */
    val key: String get() = question + "|" + options.joinToString("|") { it.text }

    /** 題目＋選項涵蓋的範圍 */
    val bounds: Rect get() = Rect(questionBox).apply { options.forEach { union(it.box) } }
}

/**
 * 從 OCR 結果切出「題目」和「選項」。
 * 假設：選項是畫面下方最後 N 個文字區塊（直排或 2x2 都可以），題目在選項上方。
 */
object QuestionParser {
    private val NOISE = Regex("^[\\d\\s:：./%+×xX-]+$")
    private val QUESTION_END = Regex("([?？]|か)\\s*$")

    /** 看起來像一個問句（問號結尾，或日文「〜か」結尾）。選單、結算畫面不會通過，自動點擊前用來把關。 */
    fun isQuestion(text: String) = QUESTION_END.containsMatchIn(text)

    /**
     * 比 [isQuestion] 寬鬆：問號被擠到下一行、甚至沒被 OCR 讀到時，靠疑問詞判斷
     * （「下列何者為著名的「美國高爾夫選手」」＋單獨一行的「?」）。選單文字（入場費、攻擊回合）不含這些詞。
     */
    fun looksLikeQuestion(text: String) =
        !DIALOG_WORDS.containsMatchIn(text) &&
            // 只剩幾個字的通常是換行的題目只讀到最後一行（「選手」 ?」），等下一幀讀完整再答
            text.replace(PUNCT_OR_SPACE, "").length >= 4 &&
            (isQuestion(text) || QUESTION_WORDS.containsMatchIn(text))

    private val PUNCT_OR_SPACE = Regex("[\\s\\p{P}?？]")

    /** 遊戲自己的確認對話框（「本当によろしいですか？」），不是題目，絕對不能點 */
    private val DIALOG_WORDS = Regex("よろしいですか|本当に|確認|リタイア|中断|しますか|挑戦|是否確定|確定要|要挑戰")

    private val QUESTION_WORDS = Regex(
        "下列|何者|為何|哪|什麼|甚麼|誰|幾|多少|是否|請問|どれ|どこ|だれ|いつ|なに|何|" +
            "(?i)\\b(which|what|who|whom|where|when|how|why)\\b",
    )

    /** 畫面上已經有問句（例如題目先出現、選項還沒出來），這時不用重試，等下一幀就好 */
    fun hasQuestionLine(lines: List<OcrLine>) = lines.any { isQuestion(it.text) }

    fun parse(lines: List<OcrLine>, screenW: Int, screenH: Int, optionCount: Int, excluded: List<Rect>): ParsedQuestion? {
        val usable = lines
            .filter { l ->
                l.text.isNotBlank() &&
                    // 純數字只在畫面兩側時當雜訊（分數、計時）；畫面中間的可能是算數題的選項
                    !(NOISE.matches(l.text) && !isCentered(l.box, screenW)) &&
                    l.box.top > screenH * 0.06 &&      // 狀態列
                    l.box.bottom < screenH * 0.97 &&   // 導覽列
                    // 只排除中心點落在浮窗裡的文字；只是邊緣重疊的（例如題目被面板蓋到一角）要保留
                    excluded.none { it.contains(l.box.centerX(), l.box.centerY()) }
            }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))

        val groups = mergeWrappedLines(usable).sortedWith(compareBy({ it.box.top }, { it.box.left }))
        if (groups.size < optionCount + 1) return null

        // 選項：題目（最下面的問句）下方第一組排列整齊的 N 個區塊（直排置中，或 2x2）。
        // 黑貓維茲的題目和選項之間夾著「文科／Pass／答題成績」，選項下方還有「選單／領域」，
        // 所以不能直接取最下面 N 個。找不到整齊的一組就不是題目畫面（例如超時後只剩正確答案的畫面）
        val qIdx = groups.indexOfLast { isQuestion(it.text) }
            .takeIf { it >= 0 } ?: groups.indexOfLast { looksLikeQuestion(it.text) }
        val below = if (qIdx >= 0) groups.drop(qIdx + 1) else groups
        val picked = findAlignedOptions(below, optionCount, screenW) ?: return null
        // 真正的題目選項不會重複；重複的通常是確認對話框後面露出來的類型面板（芸能、雑学、芸能…）
        if (picked.map { it.text.trim() }.distinct().size < picked.size) return null
        val options = picked.sortedWith { a, b ->
            val sameRow = abs(a.box.centerY() - b.box.centerY()) < min(a.box.height(), b.box.height()) / 2
            if (sameRow) a.box.left - b.box.left else a.box.centerY() - b.box.centerY()
        }
        val firstOptionTop = options.minOf { it.box.top }
        val above = groups.filter { it !in options && it.box.bottom <= firstOptionTop }
        if (above.isEmpty()) return null

        // 題目：優先選以問號結尾、最靠近選項的那段；沒有問號就選最長的（玩家名稱、分數、彈出提示都比較短）
        val main = above.filter { isQuestion(it.text) }.maxByOrNull { it.box.bottom }
            ?: above.filter { looksLikeQuestion(it.text) }.maxByOrNull { it.box.bottom }
            ?: above.maxBy { it.text.length }
        return ParsedQuestion(main.text, main.box, options)
    }

    /** 依序找第一組「直排置中、間距一致」或「2x2」的 [n] 個區塊 */
    private fun findAlignedOptions(cands: List<OcrLine>, n: Int, screenW: Int): List<OcrLine>? {
        if (cands.size < n || screenW <= 0) return null
        for (start in 0..cands.size - n) {
            val win = cands.subList(start, start + n)
            if (isVerticalList(win, screenW) || isGrid(win, screenW)) return win
        }
        return null
    }

    private fun isVerticalList(win: List<OcrLine>, screenW: Int): Boolean {
        val cx = win.map { it.box.centerX() }
        if (cx.max() - cx.min() > screenW * 12 / 100) return false
        val cy = win.map { it.box.centerY() }.sorted()
        val gaps = cy.zipWithNext { a, b -> b - a }
        if (gaps.any { it <= 0 }) return false
        return gaps.max() * 100 <= gaps.min() * 135
    }

    private fun isGrid(win: List<OcrLine>, screenW: Int): Boolean {
        if (win.size != 4) return false
        val byY = win.sortedBy { it.box.centerY() }
        val rows = listOf(byY.take(2).sortedBy { it.box.centerX() }, byY.drop(2).sortedBy { it.box.centerX() })
        val h = win.map { it.box.height() }.average()
        val sameRows = rows.all { abs(it[0].box.centerY() - it[1].box.centerY()) < h }
        val colTol = screenW / 10
        val sameCols = (0..1).all { c -> abs(rows[0][c].box.centerX() - rows[1][c].box.centerX()) < colTol }
        return sameRows && sameCols && rows[1][0].box.top > rows[0][0].box.bottom
    }

    /** 水平中心落在畫面中間 60% */
    private fun isCentered(box: Rect, screenW: Int) =
        screenW > 0 && box.centerX() in (screenW * 20 / 100)..(screenW * 80 / 100)

    /** 把同一個按鈕/段落裡換行的文字合併成一塊 */
    private fun mergeWrappedLines(lines: List<OcrLine>): List<OcrLine> {
        val groups = mutableListOf<OcrLine>()
        val lastLineH = mutableListOf<Int>()  // 每組最後一行的字高，用來比對字體大小
        for (line in lines) {
            val h = line.box.height()
            val idx = groups.indices.reversed().take(4).firstOrNull { i ->
                val g = groups[i].box
                val gap = line.box.top - g.bottom
                val overlap = min(g.right, line.box.right) - max(g.left, line.box.left)
                // 段落內換行的間距約 0.3–0.6 倍字高；選項按鈕之間通常 > 2 倍，取 1 倍當分界
                gap in (-h / 2)..h && overlap > 0 &&
                    // 問句已經結束的段落不再往下接（黑貓維茲題目下方緊接著「文科／答題成績」）
                    !isQuestion(groups[i].text) &&
                    // 字體大小差太多就不是同一段；但只有一兩個字的行（被擠到下一行的「?」「」?」）
                    // 外框高度本來就比較小，不比字高
                    (line.text.trim().length <= 2 || h * 100 in lastLineH[i] * 75..lastLineH[i] * 133)
            }
            if (idx == null) {
                groups += line
                lastLineH += h
            } else {
                val g = groups[idx]
                groups[idx] = OcrLine(joinText(g.text, line.text), Rect(g.box).apply { union(line.box) })
                lastLineH[idx] = h
            }
        }
        return groups
    }

    private fun joinText(a: String, b: String): String {
        val cjk = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")
        return if (cjk.matches(a.takeLast(1)) && cjk.matches(b.take(1))) a + b else "$a $b"
    }
}
