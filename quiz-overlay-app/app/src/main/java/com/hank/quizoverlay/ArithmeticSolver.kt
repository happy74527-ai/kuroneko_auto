package com.hank.quizoverlay

/**
 * 「77+96=?」這類算數題直接算，不用問模型：比模型快，也不會算錯。
 * 只處理「整數 運算子 整數 = ?」，其他題目回傳 null 交給 [Decider]。
 */
object ArithmeticSolver {
    private val EXPR = Regex("""^\s*(\d+)\s*([+\-－−×xX*÷/])\s*(\d+)\s*[=＝]\s*[?？]?\s*$""")

    fun solve(question: String, options: List<String>): Decision? {
        val m = EXPR.matchEntire(question) ?: return null
        val a = m.groupValues[1].toLong()
        val b = m.groupValues[3].toLong()
        val result = when (m.groupValues[2]) {
            "+" -> a + b
            "-", "－", "−" -> a - b
            "×", "x", "X", "*" -> a * b
            else -> if (b != 0L && a % b == 0L) a / b else return null
        }
        val idx = options.indexOfFirst { it.trim().replace(",", "") == result.toString() }
        if (idx < 0) return null
        return Decision(idx, 1f, List(options.size) { if (it == idx) 1f else 0f }, source = "math")
    }
}
