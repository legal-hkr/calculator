package sh.hnet.calculator.engine.number

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.min

/**
 * A real number as a recipe rather than an approximation: [approx] gives it to any number of
 * decimal places asked for, with an error of at most one unit in the last place. Recipes combine
 * into a tree (π × √2 asks π and √2 for a few more places than it was asked for itself), so a
 * result can be shown to as many digits as the user scrolls to.
 *
 * A number known exactly carries its [rational]; the operations keep it exact as long as they can
 * (see [Calc]).
 */
abstract class Real {
    /** The exact value, when this number is rational. */
    open val rational: Rational? get() = null

    val isExact: Boolean get() = rational != null

    /**
     * An integer m with |x · 10^p − m| ≤ 1 for this number x, for p ≥ 0. Implementations may assume
     * p ≥ 0 and must check [Cancel] in long loops.
     */
    protected abstract fun approximate(p: Int): BigInteger

    @Volatile private var cache: Pair<Int, BigInteger>? = null

    /** This number times 10^[p], to the nearest integer within one unit. */
    fun approx(p: Int): BigInteger {
        if (p < 0) return divRound(approx(0), TEN.pow(-p))
        val cached = cache
        if (cached != null && cached.first >= p) {
            // Rounding a finer approximation adds at most half a unit to its tenth of a unit
            return if (cached.first == p) cached.second else divRound(cached.second, TEN.pow(cached.first - p))
        }
        Cancel.check()
        val value = approximate(p)
        cache = p to value
        return value
    }

    /** This number to [p] places after the point, within one unit of the last place. */
    fun decimal(p: Int): BigDecimal = BigDecimal(approx(p), p)

    /** An e ≥ 1 with |x| < 10^e (cheap: one approximation to the units). */
    fun upperExponent(): Int = digitCount(approx(0).abs() + BigInteger.ONE)

    /**
     * An e with 10^(e−2) ≤ |x| < 10^e, or null when |x| ≤ 2 · 10^−[limit]: that small, the number is
     * taken for zero (below the app's range of 10^−10000, SILNIK §1). The search asks for more and
     * more places, so a number that is truly zero (sin π) costs the most.
     */
    fun magnitude(limit: Int = ZERO_LIMIT): Int? {
        rational?.let { r ->
            if (r.isZero) return null
        }
        var p = FIRST_PRECISION
        while (true) {
            val m = approx(p).abs()
            if (m > BigInteger.ONE) return digitCount(m + BigInteger.ONE) - p
            if (p >= limit) return null
            p = min(p * 5, limit)
        }
    }

    /** −1, 0 or 1; zero as [magnitude] counts it. */
    fun signum(limit: Int = ZERO_LIMIT): Int {
        rational?.let { return it.signum }
        val e = magnitude(limit) ?: return 0
        // |x| ≥ 10^(e−2), so at 22 − e places the approximation is far from zero
        return approx(max(0, 22 - e)).signum()
    }

    override fun toString(): String = rational?.toString() ?: decimal(20).toPlainString() + "…"

    companion object {
        val TEN: BigInteger = BigInteger.TEN

        /** Places at which a number still not told from zero is zero (the range ends at 10^−10000). */
        const val ZERO_LIMIT = 10010

        private const val FIRST_PRECISION = 20

        fun of(r: Rational): Real = RationalReal(r)
        fun of(n: Long): Real = RationalReal(Rational.of(n))

        val ZERO: Real = of(0)
        val ONE: Real = of(1)

        /** a / b to the nearest integer, halves away from zero; b > 0. */
        fun divRound(a: BigInteger, b: BigInteger): BigInteger {
            val (q, r) = a.divideAndRemainder(b)
            val twice = r.abs().shiftLeft(1)
            return if (twice >= b) (if (a.signum() < 0) q - BigInteger.ONE else q + BigInteger.ONE) else q
        }

        /** Decimal digits of a positive integer. */
        fun digitCount(n: BigInteger): Int {
            // From bitLength, a lower bound off by one at most; corrected by comparison
            var estimate = Rational.floorLog10Pow2((n.bitLength() - 1).toLong()).toInt() + 1
            while (n >= TEN.pow(estimate)) estimate++
            return estimate
        }

        /** A recipe from a function of the places asked for (p ≥ 0). */
        fun computed(f: (Int) -> BigInteger): Real = object : Real() {
            override fun approximate(p: Int): BigInteger = f(p)
        }
    }
}

private class RationalReal(val value: Rational) : Real() {
    override val rational: Rational get() = value
    override fun approximate(p: Int): BigInteger = value.scaled(p)
}

internal class SumReal(private val a: Real, private val b: Real) : Real() {
    // Two errors of a tenth of a unit, then half a unit for the rounding
    override fun approximate(p: Int): BigInteger = divRound(a.approx(p + 1) + b.approx(p + 1), TEN)
}

internal class NegReal(private val a: Real) : Real() {
    override fun approximate(p: Int): BigInteger = a.approx(p).negate()
}

internal class ProductReal(private val a: Real, private val b: Real) : Real() {
    override fun approximate(p: Int): BigInteger {
        // |a| < 10^ea and |b| < 10^eb: each factor's error, scaled by the other factor's size, stays
        // under a tenth of a unit of the result
        val ea = a.upperExponent()
        val eb = b.upperExponent()
        val pa = p + eb + 1
        val pb = p + ea + 1
        return divRound(a.approx(pa) * b.approx(pb), TEN.pow(pa + pb - p))
    }
}

/** 1/a; a must not be zero (checked by the caller with [Real.magnitude], whose result is [e]). */
internal class InverseReal(private val a: Real, private val e: Int) : Real() {
    override fun approximate(p: Int): BigInteger {
        // |a| ≥ 10^(e−2); an error ε in a moves 1/a by at most 2ε/a² while ε ≤ |a|/2
        val q = max(max(p + 5 - 2 * e, 3 - e), 0)
        val m = a.approx(q)
        // |m| ≥ 9, as q ≥ 3 − e
        return divRound(TEN.pow(p + q), m.abs()) * m.signum().toBigInteger()
    }
}

/** √a for a ≥ 0 (checked by the caller). */
internal class SqrtReal(private val a: Real) : Real() {
    override fun approximate(p: Int): BigInteger {
        // |√x − √y| ≤ √|x − y|: one unit at 2p + 2 places is at most one unit at p + 1 places
        val m = a.approx(2 * p + 2)
        if (m.signum() <= 0) return BigInteger.ZERO
        return divRound(m.sqrt(), TEN)
    }
}
