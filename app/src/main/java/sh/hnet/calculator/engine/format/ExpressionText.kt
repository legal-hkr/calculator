package sh.hnet.calculator.engine.format

import sh.hnet.calculator.engine.expr.Const
import sh.hnet.calculator.engine.expr.Fn
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.PostOp
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.expr.endsOperand
import sh.hnet.calculator.engine.expr.opens
import sh.hnet.calculator.engine.expr.startsOperand
import sh.hnet.calculator.engine.number.Real

/**
 * An expression as text for the display (SPEC §6.2): binary operators with spaces around (`5 × 3`),
 * a sign without (`5 × −3`), powers as `^`, function names with their parenthesis, number literals
 * grouped by the style. [offsets] maps the cursor: offsets[k] is where element k starts in [text],
 * offsets[size] is the end.
 */
/** Digits grouped by [size] with [separator]. */
data class DigitGroups(val size: Int, val separator: Char)

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
        /** [value]: how a whole number (Ans) is written; by default as a result in [VALUE_WIDTH] characters. */
        /** [groups]: the Programmer's grouping of its base (HEX by 4, OCT by 3), in place of the style's. */
        fun render(
            tokens: List<Token>,
            style: NumberStyle,
            value: (Real) -> String = { valueText(it, style) },
            groups: DigitGroups? = null,
        ): ExpressionText {
            val groupSize = groups?.size ?: 3
            val groupSeparator = groups?.separator ?: style.groupSeparator
            val grouping = groups != null || style.grouping
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
                            if (grouping && !inExponent && j < point && remaining > 0 && remaining % groupSize == 0 && intCount > groupSize) {
                                sb.append(groupSeparator)
                            }
                        } else {
                            sb.append(style.decimalSeparator)
                        }
                    }
                    k = end
                    continue
                }
                offsets[k] = sb.length
                sb.append(if (t is Token.Value) value(t.value) else text(t, tokens.getOrNull(k - 1)))
                k++
            }
            offsets[tokens.size] = sb.length
            return ExpressionText(sb.toString(), offsets)
        }

        private fun digitChar(v: Int): Char = if (v < 10) '0' + v else 'A' + (v - 10)

        /** A minus that is a sign: at the start, after an opening, an operator, √ or an exponent. */
        private fun isSign(prev: Token?): Boolean =
            prev == null || prev == Token.Open || prev is Token.Function || prev is Token.Operator || prev == Token.Sqrt || prev == Token.Not || prev == Token.Exponent

        /**
         * The expression for the clipboard: ASCII only (owner's rule), so it pastes anywhere and back
         * into the calculator – `*` `/` `-` `^`, `sqrt`, `pi`, `asin(`, no grouping; the language's
         * decimal separator; a whole number as its digits (`E` for the exponent).
         */
        fun ascii(tokens: List<Token>, style: NumberStyle, value: ((Real) -> String)? = null): String {
            val sb = StringBuilder()
            tokens.forEachIndexed { k, t ->
                sb.append(
                    when (t) {
                        is Token.Digit -> digitChar(t.value).toString()
                        Token.Point -> style.decimalSeparator.toString()
                        Token.Exponent -> "E"
                        is Token.Operator -> when (t.op) {
                            Op.PLUS -> "+"
                            Op.MINUS -> "-"
                            Op.TIMES -> "*"
                            Op.DIVIDE -> "/"
                            Op.POWER -> "^"
                            Op.ROOT -> " root "
                            Op.MOD -> " mod "
                            Op.AND -> " AND "
                            Op.OR -> " OR "
                            Op.XOR -> " XOR "
                            Op.NAND -> " NAND "
                            Op.NOR -> " NOR "
                            Op.SHL -> " << "
                            Op.SHR -> " >> "
                            Op.ROL -> " RoL "
                            Op.ROR -> " RoR "
                        }
                        Token.Open -> "("
                        Token.Close -> ")"
                        is Token.Function -> when (t.fn) {
                            Fn.ASIN -> "asin("
                            Fn.ACOS -> "acos("
                            Fn.ATAN -> "atan("
                            else -> t.fn.name.lowercase() + "("
                        }
                        is Token.Constant -> if (t.c == Const.PI) "pi" else "e"
                        is Token.Postfix -> when (t.op) {
                            PostOp.FACTORIAL -> "!"
                            PostOp.PERCENT -> "%"
                            PostOp.SQUARE -> "^2"
                        }
                        Token.Sqrt -> "sqrt"
                        Token.Not -> "NOT "
                        is Token.Value -> {
                            val copy = value?.invoke(t.value) ?: Formatter.format(t.value, style.copy(grouping = false, decimals = null), 40).copy
                            val before = tokens.getOrNull(k - 1)
                            val after = tokens.getOrNull(k + 1)
                            // Written so it pastes back as the same calculation: a negative value in parentheses
                            // unless it opens the expression or a group, and an explicit * next to an operand
                            val own = if (copy.startsWith("-") && before != null && !before.opens) "($copy)" else copy
                            (if (before?.endsOperand == true) "*" else "") + own + (if (after?.startsOperand == true) "*" else "")
                        }
                    },
                )
            }
            return sb.toString()
        }

        /** Characters for a whole number in an expression: room for a usual result, not the whole line. */
        const val VALUE_WIDTH = 14

        /** A whole number as the result line would write it: `15 548,4…`, `1,23… × 10¹⁵`. */
        fun valueText(v: Real, style: NumberStyle): String {
            val f = Formatter.format(v, style.copy(decimals = null), VALUE_WIDTH)
            val sb = StringBuilder(f.text)
            if (!f.exact) sb.append('…')
            f.exponent?.let { sb.append(" × 10").append(Formatter.superscript(it)) }
            return sb.toString()
        }

        private fun text(t: Token, prev: Token?): String = when (t) {
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
                Op.AND -> " AND "
                Op.OR -> " OR "
                Op.XOR -> " XOR "
                Op.NAND -> " NAND "
                Op.NOR -> " NOR "
                Op.SHL -> " << "
                Op.SHR -> " >> "
                Op.ROL -> " RoL "
                Op.ROR -> " RoR "
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
            }
            is Token.Postfix -> when (t.op) {
                PostOp.FACTORIAL -> "!"
                PostOp.PERCENT -> "%"
                PostOp.SQUARE -> "²"
            }
            Token.Sqrt -> "√"
            Token.Not -> "NOT "
            is Token.Value -> error("written by render")
        }
    }
}
