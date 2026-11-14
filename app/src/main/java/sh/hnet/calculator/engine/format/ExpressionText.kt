package sh.hnet.calculator.engine.format

import sh.hnet.calculator.engine.expr.Const
import sh.hnet.calculator.engine.expr.Fn
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.PostOp
import sh.hnet.calculator.engine.expr.Token

/**
 * An expression as text for the display (SPEC §6.2): binary operators with spaces around (`5 × 3`),
 * a sign without (`5 × −3`), powers as `^`, function names with their parenthesis, number literals
 * grouped by the style. [offsets] maps the cursor: offsets[k] is where element k starts in [text],
 * offsets[size] is the end.
 */
class ExpressionText(val text: String, val offsets: IntArray) {

    /** The cursor position (between elements) nearest to a place in the text, for a tap. */
    fun cursorAt(textOffset: Int): Int {
        var best = 0
        for (k in offsets.indices) {
            if (kotlin.math.abs(offsets[k] - textOffset) < kotlin.math.abs(offsets[best] - textOffset)) best = k
        }
        return best
    }

    companion object {
        fun render(tokens: List<Token>, style: NumberStyle, ans: String = "Ans"): ExpressionText {
            val sb = StringBuilder()
            val offsets = IntArray(tokens.size + 1)
            var k = 0
            while (k < tokens.size) {
                val t = tokens[k]
                if (t is Token.Digit || t == Token.Point) {
                    // A number's mantissa: its integer digits grouped
                    var end = k
                    while (end < tokens.size && (tokens[end] is Token.Digit || tokens[end] == Token.Point)) end++
                    val point = (k until end).firstOrNull { tokens[it] == Token.Point } ?: end
                    val intCount = point - k
                    // An exponent's digits are not grouped (1E12345)
                    val before = tokens.getOrNull(k - 1)
                    val inExponent = before == Token.Exponent || (before is Token.Operator && tokens.getOrNull(k - 2) == Token.Exponent)
                    for (j in k until end) {
                        offsets[j] = sb.length
                        val tj = tokens[j]
                        if (tj is Token.Digit) {
                            sb.append(digitChar(tj.value))
                            // A separator after this digit when a group of three follows before the point
                            val remaining = point - j - 1
                            if (style.grouping && !inExponent && j < point && remaining > 0 && remaining % 3 == 0 && intCount > 3) {
                                sb.append(style.groupSeparator)
                            }
                        } else {
                            sb.append(style.decimalSeparator)
                        }
                    }
                    k = end
                    continue
                }
                offsets[k] = sb.length
                sb.append(text(t, tokens.getOrNull(k - 1), ans))
                k++
            }
            offsets[tokens.size] = sb.length
            return ExpressionText(sb.toString(), offsets)
        }

        private fun digitChar(v: Int): Char = if (v < 10) '0' + v else 'A' + (v - 10)

        /** A minus that is a sign: at the start, after an opening, an operator, √ or an exponent. */
        private fun isSign(prev: Token?): Boolean =
            prev == null || prev == Token.Open || prev is Token.Function || prev is Token.Operator || prev == Token.Sqrt || prev == Token.Exponent

        private fun text(t: Token, prev: Token?, ans: String): String = when (t) {
            is Token.Digit -> digitChar(t.value).toString()
            Token.Point -> "."
            Token.Exponent -> "E"
            is Token.Operator -> when (t.op) {
                Op.PLUS -> if (prev == Token.Exponent) "+" else " + "
                Op.MINUS -> if (isSign(prev)) "${Formatter.MINUS}" else " ${Formatter.MINUS} "
                Op.TIMES -> " × "
                Op.DIVIDE -> " ÷ "
                Op.POWER -> "^"
                Op.ROOT -> "ʸ√"
                Op.MOD -> " mod "
            }
            Token.Open -> "("
            Token.Close -> ")"
            is Token.Function -> when (t.fn) {
                Fn.SIN -> "sin("
                Fn.COS -> "cos("
                Fn.TAN -> "tan("
                Fn.ASIN -> "sin⁻¹("
                Fn.ACOS -> "cos⁻¹("
                Fn.ATAN -> "tan⁻¹("
                Fn.LN -> "ln("
                Fn.LOG -> "log("
                Fn.ABS -> "abs("
            }
            is Token.Constant -> when (t.c) {
                Const.PI -> "π"
                Const.E -> "e"
                Const.ANS -> ans
            }
            is Token.Postfix -> when (t.op) {
                PostOp.FACTORIAL -> "!"
                PostOp.PERCENT -> "%"
                PostOp.SQUARE -> "²"
            }
            Token.Sqrt -> "√"
        }
    }
}
