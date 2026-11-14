package sh.hnet.calculator.engine.format

import sh.hnet.calculator.engine.number.Calc
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real
import java.math.BigInteger

/** When results switch to scientific notation (SPEC §6.6, §13.1). */
enum class Notation { AUTOMATIC, ALWAYS, NEVER }

/** How numbers are written: by the app's language and the settings. */
data class NumberStyle(
    /** Polish: decimal comma and a narrow no-break space between thousands; English: point and comma. */
    val decimalComma: Boolean,
    val grouping: Boolean = true,
    /** null: as many decimals as fit, trailing zeros dropped; 0–10: that many, rounded half-up (D6). */
    val decimals: Int? = null,
    val notation: Notation = Notation.AUTOMATIC,
) {
    val decimalSeparator: Char get() = if (decimalComma) ',' else '.'
    val groupSeparator: Char get() = if (decimalComma) ' ' else ','
}

/**
 * A result written out (SPEC §6.6). The UI shows [text], then "…" when not [exact], then for
 * scientific notation " × 10" with [exponent] raised ([superscript]).
 */
data class Formatted(
    /** The digits as shown, with sign (U+2212), grouping and the decimal separator; the mantissa in scientific notation. */
    val text: String,
    /** The power of ten in scientific notation, else null. */
    val exponent: Int?,
    /** The value is exactly what is shown: no "…". */
    val exact: Boolean,
    /** For the clipboard: no grouping, no mark, the language's decimal separator, `1.197857166E100` in scientific notation. */
    val copy: String,
)

/**
 * Writes results (SILNIK §4, SPEC §6.6). Automatic decimals show the leading digits of the exact
 * value, cut, not rounded, so scrolling for more digits continues them; fixed decimals round half
 * away from zero. [width] is the room in characters (sign, separators and digits; the "…" and the
 * "× 10ⁿ" are the UI's), so the UI asks again with a larger width as the user scrolls.
 */
object Formatter {

    /** Results below this switch to scientific notation in Automatic (SPEC §6.6). */
    private const val SMALL_EXPONENT = -9

    /** The most significant digits ever shown (SPEC §6.6). */
    const val MAX_DIGITS = 1000

    fun format(x: Real, style: NumberStyle, width: Int): Formatted {
        val sign = x.signum()
        if (sign == 0) return zero(style)
        val negative = sign < 0
        val abs = Calc.abs(x)
        val e = leadingExponent(abs)
        val signChars = if (negative) 1 else 0
        val intDigits = if (e >= 0) e + 1 else 1
        val intChars = signChars + intDigits + if (style.grouping) (intDigits - 1) / 3 else 0
        val scientific = when (style.notation) {
            Notation.ALWAYS -> true
            Notation.NEVER -> false
            Notation.AUTOMATIC -> {
                val decimalsChars = style.decimals?.let { if (it > 0) it + 1 else 0 } ?: 0
                intChars + decimalsChars > width || e < SMALL_EXPONENT
            }
        }
        return if (scientific) scientific(abs, negative, e, style, width) else plain(abs, negative, e, style, width, intChars)
    }

    private fun zero(style: NumberStyle): Formatted {
        val text = style.decimals?.takeIf { it > 0 }?.let { "0" + style.decimalSeparator + "0".repeat(it) } ?: "0"
        return Formatted(text, null, true, text)
    }

    /** E with 10^E ≤ x < 10^(E+1), for x > 0. */
    fun leadingExponent(x: Real): Int {
        val r = x.rational
        if (r != null) {
            // num/den lies between 10^(d−1) and 10^(d+1), d the difference of their digit counts
            val d = Real.digitCount(r.num.abs()) - Real.digitCount(r.den)
            return if (r >= tenPow(d)) d else d - 1
        }
        val m = x.magnitude() ?: return Int.MIN_VALUE
        // 10^(m−2) ≤ x < 10^m: either m − 1 or m − 2
        return if (truncated(x, 1 - m).first.signum() > 0) m - 1 else m - 2
    }

    /**
     * trunc(x · 10^[places]) for x ≥ 0, exactly, and whether that is the whole value (nothing
     * cut off). A real whose approximation sits on a boundary (√2 · √2 next to 2) is settled by
     * testing it against the boundary itself: equal means exact.
     */
    fun truncated(x: Real, places: Int): Pair<BigInteger, Boolean> {
        val r = x.rational
        if (r != null) {
            val scaled = r * tenPow(places)
            val t = scaled.floor()
            return t to scaled.isInteger
        }
        for (guard in intArrayOf(5, 20, 60)) {
            val m = x.approx(places + guard)
            val unit = BigInteger.TEN.pow(guard)
            val low = floorDiv(m - BigInteger.ONE, unit)
            val high = floorDiv(m + BigInteger.ONE, unit)
            if (low == high) return low to false
        }
        // On a boundary b · 10^−places: compare x with it
        val b = floorDiv(x.approx(places + 60) + BigInteger.ONE, BigInteger.TEN.pow(60))
        val boundary = Real.of(Rational.of(b) * tenPow(-places))
        return when (Calc.subtract(x, boundary).signum()) {
            0 -> b to true
            1 -> b to false
            else -> (b - BigInteger.ONE) to false
        }
    }

    /** 10^k as a rational, for any k. */
    private fun tenPow(k: Int): Rational =
        if (k >= 0) Rational.of(BigInteger.TEN.pow(k)) else Rational.of(BigInteger.ONE, BigInteger.TEN.pow(-k))

    private fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
        val (q, r) = a.divideAndRemainder(b)
        return if (r.signum() < 0) q - BigInteger.ONE else q
    }

    /** round(x · 10^places), halves away from zero, for x ≥ 0; and whether it is the whole value. */
    private fun rounded(x: Real, places: Int): Pair<BigInteger, Boolean> {
        val half = Rational.of(1, 2) * tenPow(-places)
        val (t, _) = truncated(Calc.add(x, Real.of(half)), places)
        val (_, whole) = truncated(x, places)
        return t to whole
    }

    private fun plain(x: Real, negative: Boolean, e: Int, style: NumberStyle, width: Int, intChars: Int): Formatted {
        val fixed = style.decimals
        val places: Int
        val n: BigInteger
        val whole: Boolean
        if (fixed != null) {
            places = fixed
            rounded(x, fixed).let { (v, w) -> n = v; whole = w }
        } else {
            // As many decimals as fit; a small number at least to its first digits (Never notation)
            val room = width - intChars - 1
            val minimum = if (e < 0) -e + 2 else 0
            places = maxOf(room, minimum).coerceIn(0, maxOf(0, MAX_DIGITS - e - 1))
            truncated(x, places).let { (v, w) -> n = v; whole = w }
        }
        val all = n.toString().padStart(places + 1, '0')
        var fraction = all.takeLast(places)
        // Exact values drop trailing zeros in Automatic; fixed decimals keep them (SPEC §13.1)
        if (whole && fixed == null) fraction = fraction.trimEnd('0')
        return compose(all.dropLast(places), fraction, negative, null, whole, style)
    }

    private fun scientific(x: Real, negative: Boolean, e0: Int, style: NumberStyle, width: Int): Formatted {
        var e = e0
        val fixed = style.decimals
        var n: BigInteger
        var whole: Boolean
        val decimals: Int
        if (fixed != null) {
            decimals = fixed
            rounded(x, fixed - e).let { (v, w) -> n = v; whole = w }
            // 9.99 rounded up to 10.0: one more power of ten
            if (n.toString().length > fixed + 1) {
                e++
                rounded(x, fixed - e).let { (v, w) -> n = v; whole = w }
            }
        } else {
            // Room left by the sign, the first digit and the separator, and " × 10" with the exponent
            val exponentChars = 4 + e.toString().length
            decimals = (width - (if (negative) 1 else 0) - 2 - exponentChars).coerceIn(1, MAX_DIGITS - 1)
            truncated(x, decimals - e).let { (v, w) -> n = v; whole = w }
        }
        val all = n.toString()
        var fraction = all.drop(1)
        if (whole && fixed == null) fraction = fraction.trimEnd('0')
        return compose(all.take(1), fraction, negative, e, whole, style)
    }

    private fun compose(integer: String, fraction: String, negative: Boolean, exponent: Int?, exact: Boolean, style: NumberStyle): Formatted {
        val shown = StringBuilder()
        if (negative) shown.append(MINUS)
        shown.append(if (style.grouping) group(integer, style.groupSeparator) else integer)
        val copy = StringBuilder()
        if (negative) copy.append('-')
        copy.append(integer)
        if (fraction.isNotEmpty()) {
            shown.append(style.decimalSeparator).append(fraction)
            copy.append(style.decimalSeparator).append(fraction)
        }
        if (exponent != null) copy.append('E').append(exponent)
        return Formatted(shown.toString(), exponent, exact, copy.toString())
    }

    /** Digits grouped by three from the right. */
    fun group(digits: String, separator: Char): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder(digits.length + digits.length / 3)
        val head = digits.length % 3
        if (head > 0) sb.append(digits, 0, head)
        var k = head
        while (k < digits.length) {
            if (sb.isNotEmpty()) sb.append(separator)
            sb.append(digits, k, k + 3)
            k += 3
        }
        return sb.toString()
    }

    const val MINUS = '\u2212'

    private val SUPERSCRIPTS = charArrayOf('⁰', '¹', '²', '³', '⁴', '⁵', '⁶', '⁷', '⁸', '⁹')

    /** An exponent raised: −7 → ⁻⁷. */
    fun superscript(n: Int): String = n.toString().map { if (it == '-') '⁻' else SUPERSCRIPTS[it - '0'] }.joinToString("")
}
