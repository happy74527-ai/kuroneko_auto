package com.hank.quizoverlay

import android.graphics.Bitmap
import android.graphics.Point

/**
 * 黑貓維茲的選題目類型畫面（中文版 tw.sonet.wiz、日文版 jp.colopl.quizwiz）：
 * 四個面板各有一個屬性圓形圖示＋類型文字。只能選我方隊伍有的屬性顏色，否則攻擊無效。
 *
 * 面板靠「位置＋圖示顏色」找，不靠文字：日文版的「アニメゲーム」是藝術字，OCR 會讀成「アJム」。
 * 兩版的上方黑邊高度不同（日文版整體往下移），所以我方顏色條和怪物的位置都從面板的高度往上推算。
 * 水平位置與距離是依 1080x2400 模擬器量的相對值（遊戲以寬度縮放）。
 */
object WizCategoryPicker {
    private const val TAG = "QuizOverlay"

    /** 面板文字的水平中心（左欄、右欄），相對畫面寬 */
    private val LABEL_COLUMNS = floatArrayOf(0.37f, 0.815f)
    private const val LABEL_COLUMN_TOLERANCE = 0.07f

    private val PARTY_BAR_X = floatArrayOf(0.12f, 0.31f, 0.5f, 0.69f, 0.88f)
    /** 我方顏色條在第一排面板文字上方多遠，相對畫面高 */
    private const val PARTY_BAR_ABOVE_LABEL = 0.12f

    /** 圓形圖示在類型文字左邊多遠（相對畫面寬） */
    private const val ORB_OFFSET_X = 0.215f

    /** 怪物卡片所在的範圍：第一排面板文字上方 0.33–0.43 倍畫面高 */
    private const val ENEMY_ABOVE_LABEL_FROM = 0.43f
    private const val ENEMY_ABOVE_LABEL_TO = 0.33f

    /** 屬性相剋：key 剋 value（水剋火、火剋雷、雷剋水，光闇互剋） */
    private val BEATS = mapOf(
        "blue" to setOf("red"), "red" to setOf("yellow"), "yellow" to setOf("blue"),
        "white" to setOf("purple"), "purple" to setOf("white"),
    )

    data class Pick(
        val tap: Point,
        val label: String,
        val color: String,
        val partyColors: List<String>,
        val enemyColors: Set<String>,
    )

    /** 不是選類型畫面就回傳 null */
    fun pick(lines: List<OcrLine>, bmp: Bitmap): Pick? {
        val w = bmp.width
        val h = bmp.height
        // 面板文字：畫面下半部、落在左欄或右欄、字夠大；左邊同高度的圓形圖示要是屬性顏色
        val panels = lines
            .filter { l ->
                l.box.centerY() in (h * 0.55f).toInt()..(h * 0.93f).toInt() &&
                    l.box.height() >= h * 0.02f &&
                    LABEL_COLUMNS.any { c -> kotlin.math.abs(l.box.centerX() - c * w) < LABEL_COLUMN_TOLERANCE * w }
            }
            .map { l -> l to colorAt(bmp, (l.box.centerX() - ORB_OFFSET_X * w).toInt(), l.box.centerY(), 18) }
            .filter { it.second in ELEMENTS }
        if (!looksLikePanelGrid(panels.map { it.first }, w, h)) {
            // 有面板文字但排列不對：記下來，方便判斷是選單畫面還是真的選類型畫面沒認出來
            if (panels.isNotEmpty()) {
                android.util.Log.i(TAG, "category: not a 2x2 grid ${panels.map { "${it.first.text}(${it.second})@${it.first.box.centerY()}" }}")
            }
            return null
        }

        // 第一排面板的基準高度：取第一排裡最低的文字中心（「動漫遊戲」這種兩行字的第一行偏高）
        val firstRowCy = panels.minOf { it.first.box.centerY() }
        val labelTop = panels.map { it.first.box.centerY() }.filter { it < firstRowCy + h * 0.05f }.max()
        val partyY = findPartyBarY(bmp, (labelTop - PARTY_BAR_ABOVE_LABEL * h).toInt())
        val party = PARTY_BAR_X.map { colorAt(bmp, (it * w).toInt(), partyY, 6) }.filter { it in ELEMENTS }
        if (party.isEmpty()) {
            android.util.Log.i(TAG, "category: party colors not found near y=$partyY")
            return null
        }

        val enemies = enemyColors(
            bmp,
            (labelTop - ENEMY_ABOVE_LABEL_FROM * h).toInt(),
            (labelTop - ENEMY_ABOVE_LABEL_TO * h).toInt(),
        )
        // 分數 = 我方這個顏色的人數 × 相剋倍率；我方沒有的顏色是 0 分（攻擊無效），
        // 但所有面板都是 0 分時還是要選一個作答
        val (label, color) = panels.maxBy { (_, c) -> score(c, party, enemies) }
        return Pick(Point(label.box.centerX(), label.box.centerY()), label.text, color, party, enemies)
    }

    /**
     * 選類型畫面是 2x2 的面板：至少 3 個面板文字、左右兩欄都有、上下兩排相隔約 0.13 倍畫面高。
     * 選單畫面（前回順位、正答率、精靈名稱）偶爾也有大字＋彩色圖案，用排列把它們排除，避免亂點選單。
     */
    private fun looksLikePanelGrid(labels: List<OcrLine>, w: Int, h: Int): Boolean {
        if (labels.size < 3) return false
        val columns = labels.map { l -> LABEL_COLUMNS.indices.minBy { kotlin.math.abs(l.box.centerX() - LABEL_COLUMNS[it] * w) } }
        if (columns.toSet().size < 2) return false
        // 「動漫遊戲」這種兩行字會在同一排出現兩個中心，所以以 0.05 倍畫面高為一排
        val rows = labels.map { it.box.centerY() }.sorted()
            .fold(mutableListOf<MutableList<Int>>()) { acc, y ->
                if (acc.isEmpty() || y - acc.last().last() > h * 0.05f) acc.add(mutableListOf(y)) else acc.last().add(y)
                acc
            }
        if (rows.size != 2) return false
        val gap = rows[1].average() - rows[0].average()
        return gap in h * 0.09..h * 0.17
    }

    /**
     * 顏色條只有約 18px 高，推算的位置差幾個像素就會取到旁邊的金色邊框，
     * 所以在推算位置上下 40px 內找五個取樣點平均飽和度最高的那一列。
     */
    private fun findPartyBarY(bmp: Bitmap, guess: Int): Int {
        val hsv = FloatArray(3)
        return (guess - 40..guess + 40 step 4).maxBy { y ->
            PARTY_BAR_X.sumOf { fx ->
                val x = (fx * bmp.width).toInt()
                val p = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
                android.graphics.Color.RGBToHSV(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF, hsv)
                hsv[1].toDouble()
            }
        }
    }

    private fun score(color: String, party: List<String>, enemies: Set<String>): Float {
        val attackers = party.count { it == color }
        if (attackers == 0) return 0f
        val multiplier = when {
            enemies.any { it in BEATS[color].orEmpty() } -> 1.5f   // 剋制敵人
            enemies.any { color in BEATS[it].orEmpty() } -> 0.5f   // 被敵人剋制
            else -> 1f
        }
        return attackers * multiplier
    }

    /**
     * 怪物卡片的顏色：在卡片那一段取樣，只算飽和度 >= 0.6 的像素（天空約 0.5、草地是綠色都不算），
     * 占 20% 以上的顏色都當作敵人的屬性。
     */
    private fun enemyColors(bmp: Bitmap, yFrom: Int, yTo: Int): Set<String> {
        val counts = mutableMapOf<String, Int>()
        val hsv = FloatArray(3)
        for (y in yFrom.coerceAtLeast(0) until yTo.coerceAtMost(bmp.height) step 8) {
            for (x in 0 until bmp.width step 8) {
                val p = bmp.getPixel(x, y)
                android.graphics.Color.RGBToHSV(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF, hsv)
                if (hsv[1] < 0.6f || hsv[2] < 0.45f) continue
                val c = hueToElement(hsv[0]) ?: continue
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        val total = counts.values.sum().takeIf { it > 0 } ?: return emptySet()
        return counts.filterValues { it * 5 >= total }.keys
    }

    // 火屬性圖示中間是橘色火焰，平均起來色相約 20–30 度，也要算紅
    private fun hueToElement(h: Float): String? = when {
        h < 32f || h >= 340f -> "red"
        h in 32f..70f -> "yellow"
        h in 180f..250f -> "blue"
        h in 250f..330f -> "purple"
        else -> null
    }

    private val ELEMENTS = setOf("red", "blue", "yellow", "purple", "white")

    /** 取 (cx, cy) 周圍方塊的平均顏色並分類成屬性 */
    private fun colorAt(bmp: Bitmap, cx: Int, cy: Int, r: Int): String {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        for (y in (cy - r).coerceAtLeast(0) until (cy + r).coerceAtMost(bmp.height) step 3) {
            for (x in (cx - r).coerceAtLeast(0) until (cx + r).coerceAtMost(bmp.width) step 3) {
                val p = bmp.getPixel(x, y)
                rs += p shr 16 and 0xFF; gs += p shr 8 and 0xFF; bs += p and 0xFF; n++
            }
        }
        if (n == 0) return "?"
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt(), hsv)
        val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
        return when {
            s < 0.25f && v > 0.6f -> "white"
            s < 0.25f -> "?"
            else -> hueToElement(h) ?: "?"
        }
    }
}
