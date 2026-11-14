package sh.hnet.calculator.engine.expr

import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Expressions and numbers as text for storage (the current expression, Ans, history, backups):
 * language-independent, one code a token, separated by spaces. A number is exact (`q` + fraction)
 * or its first [Formatter.MAX_DIGITS] significant digits and a few more (`a` + decimal).
 */
object TokenCodec {

    /** Significant digits kept of an inexact number: what the display can ever show, and a margin. */
    private const val KEPT_DIGITS = Formatter.MAX_DIGITS + 10

    fun encode(tokens: List<Token>): String = tokens.joinToString(" ") { encode(it) }

    fun decode(text: String): List<Token>? {
        if (text.isBlank()) return emptyList()
        return text.split(' ').map { decodeToken(it) ?: return null }
    }

    private fun encode(t: Token): String = when (t) {
        is Token.Digit -> "d${t.value}"
        Token.Point -> "."
        Token.Exponent -> "E"
        is Token.Operator -> "o${t.op.name}"
        Token.Open -> "("
        Token.Close -> ")"
        is Token.Function -> "f${t.fn.name}"
        is Token.Constant -> "c${t.c.name}"
        is Token.Postfix -> "p${t.op.name}"
        Token.Sqrt -> "√"
        Token.Not -> "~"
        is Token.Value -> "v${encodeReal(t.value)}"
    }

    private fun decodeToken(s: String): Token? = when {
        s == "." -> Token.Point
        s == "E" -> Token.Exponent
        s == "(" -> Token.Open
        s == ")" -> Token.Close
        s == "√" -> Token.Sqrt
        s == "~" -> Token.Not
        s.startsWith("d") -> s.drop(1).toIntOrNull()?.takeIf { it in 0..15 }?.let { Token.Digit(it) }
        s.startsWith("o") -> Op.entries.firstOrNull { it.name == s.drop(1) }?.let { Token.Operator(it) }
        s.startsWith("f") -> Fn.entries.firstOrNull { it.name == s.drop(1) }?.let { Token.Function(it) }
        s.startsWith("c") -> Const.entries.firstOrNull { it.name == s.drop(1) }?.let { Token.Constant(it) }
        s.startsWith("p") -> PostOp.entries.firstOrNull { it.name == s.drop(1) }?.let { Token.Postfix(it) }
        s.startsWith("v") -> decodeReal(s.drop(1))?.let { Token.Value(it) }
        else -> null
    }

    fun encodeReal(x: Real): String {
        x.rational?.let { return "q${it.num}/${it.den}" }
        val e = x.magnitude() ?: return "q0/1"
        return "a" + x.decimal(KEPT_DIGITS - e).toString()
    }

    fun decodeReal(s: String): Real? = try {
        when {
            s.startsWith("q") -> {
                val (n, d) = s.drop(1).split('/')
                Real.of(Rational.of(BigInteger(n), BigInteger(d)))
            }
            s.startsWith("a") -> Real.approximation(BigDecimal(s.drop(1)))
            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }
}
