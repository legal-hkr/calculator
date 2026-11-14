package sh.hnet.calculator.engine.expr

import sh.hnet.calculator.engine.number.Real

/**
 * Text from the clipboard as expression elements (SILNIK §2.3, SPEC §6.2), or null when it can't
 * be read: nothing is inserted then. Accepts the calculator's own symbols and the usual ASCII ones
 * (`*` `/` `:` `-` `^` `sqrt` `pi`), function names with a parenthesis, `2e5` as an exponent, a
 * trailing `=`, `Ans` as [ans] (unreadable without one). Whitespace is ignored; text over several lines is not read.
 *
 * Decimal separators: [decimalComma] (Polish) reads both `,` and `.` as decimal and spaces as
 * grouping; English reads `.` as decimal and `,` as grouping, unless a single comma is not followed
 * by exactly three digits (`3,5`). In a number with both, the last one is the decimal separator.
 */
object Paste {

    private val FUNCTIONS = listOf(
        "arcsin" to Fn.ASIN, "arccos" to Fn.ACOS, "arctan" to Fn.ATAN,
        "asin" to Fn.ASIN, "acos" to Fn.ACOS, "atan" to Fn.ATAN,
        "sin⁻¹" to Fn.ASIN, "cos⁻¹" to Fn.ACOS, "tan⁻¹" to Fn.ATAN,
        "sin" to Fn.SIN, "cos" to Fn.COS, "tan" to Fn.TAN,
        "ln" to Fn.LN, "log" to Fn.LOG, "abs" to Fn.ABS,
    ).sortedByDescending { it.first.length }

    private const val GROUPING_SPACES = "     \t"

    fun read(text: String, decimalComma: Boolean, ans: Real? = null): List<Token>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n') || trimmed.contains('\r')) return null
        val s = trimmed.removeSuffix("=").filterNot { it in GROUPING_SPACES }
        val out = mutableListOf<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit() || ((c == '.' || c == ',') && s.getOrNull(i + 1)?.isDigit() == true)) {
                i = number(s, i, decimalComma, out) ?: return null
                continue
            }
            val rest = s.substring(i)
            val lower = rest.lowercase()
            val word = FUNCTIONS.firstOrNull { lower.startsWith(it.first) }
            when {
                word != null -> {
                    i += word.first.length
                    // A function needs its parenthesis
                    if (s.getOrNull(i) != '(') return null
                    out += Token.Function(word.second)
                    i++
                }
                lower.startsWith("sqrt") -> {
                    out += Token.Sqrt
                    i += 4
                }
                lower.startsWith("root") -> {
                    out += Token.Operator(Op.ROOT)
                    i += 4
                }
                lower.startsWith("mod") -> {
                    out += Token.Operator(Op.MOD)
                    i += 3
                }
                lower.startsWith("ans") -> {
                    out += Token.Value(ans ?: return null)
                    i += 3
                }
                lower.startsWith("pi") -> {
                    out += Token.Constant(Const.PI)
                    i += 2
                }
                rest.startsWith("ʸ√") -> {
                    out += Token.Operator(Op.ROOT)
                    i += 2
                }
                else -> {
                    out += symbol(c) ?: return null
                    i++
                }
            }
        }
        return out.ifEmpty { null }
    }

    private fun symbol(c: Char): List<Token>? = when (c) {
        '+' -> listOf(Token.Operator(Op.PLUS))
        '-', '−', '–' -> listOf(Token.Operator(Op.MINUS))
        '*', '×', '·', 'x', 'X', '⋅' -> listOf(Token.Operator(Op.TIMES))
        '/', '÷', ':' -> listOf(Token.Operator(Op.DIVIDE))
        '^' -> listOf(Token.Operator(Op.POWER))
        '%' -> listOf(Token.Postfix(PostOp.PERCENT))
        '!' -> listOf(Token.Postfix(PostOp.FACTORIAL))
        '²' -> listOf(Token.Postfix(PostOp.SQUARE))
        '³' -> listOf(Token.Operator(Op.POWER), Token.Digit(3))
        '(' -> listOf(Token.Open)
        ')' -> listOf(Token.Close)
        '√' -> listOf(Token.Sqrt)
        'π' -> listOf(Token.Constant(Const.PI))
        'e' -> listOf(Token.Constant(Const.E))
        else -> null
    }

    /** Reads a number from [start] into [out]; returns where it ends, or null when it can't be read. */
    private fun number(s: String, start: Int, decimalComma: Boolean, out: MutableList<Token>): Int? {
        var end = start
        while (end < s.length && (s[end].isDigit() || s[end] == '.' || s[end] == ',')) end++
        // A separator at the end belongs to the text around ("1, 2") – not read as part of the number
        while (end > start && !s[end - 1].isDigit()) end--
        val run = s.substring(start, end)
        val decimalAt = decimalIndex(run, decimalComma) ?: return null
        for ((k, ch) in run.withIndex()) {
            when {
                ch.isDigit() -> out += Token.Digit(ch - '0')
                k == decimalAt -> out += Token.Point
            }
        }
        var i = end
        // An exponent: 2e5, 1.5E-3
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            var j = i + 1
            val sign = s.getOrNull(j)?.let { if (it == '-' || it == '−' || it == '+') it else null }
            if (sign != null) j++
            if (s.getOrNull(j)?.isDigit() == true) {
                out += Token.Exponent
                if (sign != null) out += Token.Operator(if (sign == '+') Op.PLUS else Op.MINUS)
                while (j < s.length && s[j].isDigit()) {
                    out += Token.Digit(s[j] - '0')
                    j++
                }
                i = j
            }
        }
        return i
    }

    /** The index of the decimal separator in a run of digits and separators, −1 for none, null when unreadable. */
    private fun decimalIndex(run: String, decimalComma: Boolean): Int? {
        val commas = run.count { it == ',' }
        val points = run.count { it == '.' }
        if (commas > 0 && points > 0) {
            val last = maxOf(run.lastIndexOf(','), run.lastIndexOf('.'))
            // Only one decimal separator, after every grouping one
            return if (run.count { it == run[last] } == 1) last else null
        }
        val sep = if (commas > 0) ',' else if (points > 0) '.' else return -1
        val count = commas + points
        if (count > 1) return -1
        val at = run.indexOf(sep)
        if (sep == ',' && !decimalComma) {
            // 1,234 is a thousand; 3,5 is three and a half
            val after = run.length - at - 1
            return if (after == 3) -1 else at
        }
        return at
    }
}
