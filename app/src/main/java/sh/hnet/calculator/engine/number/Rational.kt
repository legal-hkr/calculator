package sh.hnet.calculator.engine.number

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * An exact rational number, always reduced, with a positive denominator. Every result the four
 * operations, integer powers, percent and factorial give from rationals stays one of these, so
 * 0.1 + 0.2 is exactly 3/10.
 */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    val signum: Int get() = num.signum()
    val isZero: Boolean get() = num.signum() == 0
    val isInteger: Boolean get() = den == BigInteger.ONE

    operator fun plus(other: Rational): Rational =
        if (den == other.den) of(num + other.num, den) else of(num * other.den + other.num * den, den * other.den)

    operator fun minus(other: Rational): Rational = this + (-other)

    operator fun times(other: Rational): Rational = of(num * other.num, den * other.den)

    /** @throws CalcException DIVISION_BY_ZERO */
    operator fun div(other: Rational): Rational {
        if (other.isZero) throw CalcException(CalcError.DIVISION_BY_ZERO)
        return of(num * other.den, den * other.num)
    }

    operator fun unaryMinus(): Rational = Rational(num.negate(), den)

    fun abs(): Rational = if (signum < 0) -this else this

    /** The largest integer not greater than this. */
    fun floor(): BigInteger {
        val (q, r) = num.divideAndRemainder(den)
        return if (r.signum() < 0) q - BigInteger.ONE else q
    }

    /** To an integer, halves away from zero. */
    fun round(): BigInteger {
        val twice = num.shiftLeft(1) + (if (signum < 0) -den else den)
        return twice.divide(den.shiftLeft(1))
    }

    /** This number times 10^[digits], rounded to an integer, halves away from zero. */
    fun scaled(digits: Int): BigInteger {
        val n = if (digits >= 0) num * BigInteger.TEN.pow(digits) else num
        val d = if (digits >= 0) den else den * BigInteger.TEN.pow(-digits)
        return of(n, d).round()
    }

    /**
     * An integer power. A negative power of zero is a division by zero; 0^0 is 1 here (the caller
     * decides how to treat it).
     */
    fun pow(exponent: Int): Rational {
        if (exponent == 0) return ONE
        if (exponent < 0) {
            if (isZero) throw CalcException(CalcError.DIVISION_BY_ZERO)
            return of(den.pow(-exponent), num.pow(-exponent))
        }
        return Rational(num.pow(exponent), den.pow(exponent))
    }

    /** The exact [n]-th root when there is one (a perfect power over a perfect power), else null. */
    fun exactRoot(n: Int): Rational? {
        require(n >= 1)
        if (n == 1 || isZero) return this
        if (signum < 0) {
            if (n % 2 == 0) return null
            return (-this).exactRoot(n)?.let { -it }
        }
        val a = integerRoot(num, n) ?: return null
        val b = integerRoot(den, n) ?: return null
        return Rational(a, b)
    }

    /** True when the decimal expansion ends (the denominator has no prime factors but 2 and 5). */
    val terminates: Boolean
        get() {
            var d = den
            val twos = d.lowestSetBit
            d = d.shiftRight(twos)
            while (d.mod(FIVE).signum() == 0) d = d.divide(FIVE)
            return d == BigInteger.ONE
        }

    /** The exact value when it [terminates], else null. */
    fun toBigDecimalExact(): BigDecimal? {
        if (!terminates) return null
        return BigDecimal(num).divide(BigDecimal(den))
    }

    /** Rounded to [scale] digits after the point, halves away from zero. */
    fun toBigDecimal(scale: Int): BigDecimal = BigDecimal(num).divide(BigDecimal(den), scale, RoundingMode.HALF_UP)

    /** An upper bound of log10 |x| (for range checks before costly work); for zero a very small number. */
    fun magnitudeUpperBound(): Long {
        if (isZero) return Long.MIN_VALUE / 2
        // log10 2 < 0.30103; bitLength b means 2^(b-1) <= |n| < 2^b
        return ceilLog10Pow2(num.bitLength().toLong()) - floorLog10Pow2((den.bitLength() - 1).toLong())
    }

    override fun compareTo(other: Rational): Int = (num * other.den).compareTo(other.num * den)

    override fun equals(other: Any?): Boolean = other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = num.hashCode() * 31 + den.hashCode()

    override fun toString(): String = if (isInteger) num.toString() else "$num/$den"

    companion object {
        private val FIVE: BigInteger = BigInteger.valueOf(5)

        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(num: BigInteger, den: BigInteger = BigInteger.ONE): Rational {
            if (den.signum() == 0) throw CalcException(CalcError.DIVISION_BY_ZERO)
            if (num.signum() == 0) return ZERO
            val g = num.gcd(den)
            var n = if (g == BigInteger.ONE) num else num.divide(g)
            var d = if (g == BigInteger.ONE) den else den.divide(g)
            if (d.signum() < 0) {
                n = n.negate()
                d = d.negate()
            }
            return Rational(n, d)
        }

        fun of(num: Long, den: Long = 1): Rational = of(BigInteger.valueOf(num), BigInteger.valueOf(den))

        fun of(value: BigDecimal): Rational =
            if (value.scale() <= 0) of(value.toBigIntegerExact())
            else of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))

        /** A decimal literal such as "12.5" or "-0.001" (point as the separator, no grouping). */
        fun parse(text: String): Rational = of(BigDecimal(text))

        /** The exact [n]-th root of a non-negative integer, or null when it isn't a perfect power. */
        fun integerRoot(x: BigInteger, n: Int): BigInteger? {
            if (x.signum() == 0 || x == BigInteger.ONE) return x
            // x ≥ 2 and 2^n > x: the root lies between 1 and 2, never an integer (and 2^n is never built)
            if (n >= x.bitLength()) return null
            if (n == 2) {
                val r = x.sqrt()
                return if (r * r == x) r else null
            }
            // Newton's method from an upper bound
            var r = BigInteger.ONE.shiftLeft(x.bitLength() / n + 1)
            val nn = BigInteger.valueOf(n.toLong())
            val n1 = BigInteger.valueOf((n - 1).toLong())
            while (true) {
                Cancel.check()
                val next = (r * n1 + x.divide(r.pow(n - 1))).divide(nn)
                if (next >= r) break
                r = next
            }
            return if (r.pow(n) == x) r else null
        }

        /** ceil(b · log10 2), for b >= 0. */
        fun ceilLog10Pow2(b: Long): Long = (b * 30103L + 99999L) / 100000L

        /** floor(b · log10 2) or one less, for 0 ≤ b < 10^7 (log10 2 > 0.30102999566). */
        fun floorLog10Pow2(b: Long): Long = b * 30102999566L / 100000000000L
    }
}
