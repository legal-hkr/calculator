package sh.hnet.calculator.engine.number

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Units of an angle (SILNIK §3). */
enum class AngleUnit { DEGREES, RADIANS, GRADIANS }

/**
 * The operations of the calculator on [Real]s (SILNIK §1). Rational in, rational out wherever the
 * result is rational (also √(9/4), sin 30°, log 1000); everything else becomes a recipe of
 * [Real]. Results beyond 10^10000 are an [CalcError.OVERFLOW]; rationals below 10^−10000 become
 * zero.
 */
object Calc {
    /** The app's range: 10^±10000. */
    const val RANGE_DIGITS = 10000

    private val TEN = BigInteger.TEN
    private val LIMIT: BigInteger by lazy { TEN.pow(RANGE_DIGITS) }

    // Constants

    // Computed recipes may be asked for negative places (a few digits of a huge number): each keeps
    // its working precision at a sensible minimum

    val pi: Real = Real.computed { p -> Series.pi(max(p + 2, 2)).let { scaledOf(it, p) } }

    val e: Real = Real.computed { p -> scaledOf(Series.exp(BigDecimal.ONE, max(p + 5, 5)), p) }

    private val ln10: Real by lazy { ln(Real.of(10)) }

    private fun scaledOf(d: BigDecimal, p: Int): BigInteger = d.setScale(p, RoundingMode.HALF_UP).unscaledValue()

    // Range

    /** The value itself, or an overflow beyond 10^10000; a rational below 10^−10000 becomes zero. */
    fun checked(x: Real): Real {
        val r = x.rational
        if (r != null) {
            if (r.isZero) return x
            val lg = log10Estimate(r)
            if (lg > RANGE_DIGITS - 1) {
                if (r.num.abs() > r.den * LIMIT) throw CalcException(CalcError.OVERFLOW)
            } else if (lg < -RANGE_DIGITS + 1) {
                if (r.num.abs() * LIMIT < r.den) return Real.ZERO
            }
            return x
        }
        // Only a number that may be near the edge costs more than the cheap upper bound
        if (x.upperExponent() > RANGE_DIGITS) {
            val m = x.approx(-(RANGE_DIGITS - 20)).abs()
            if (m >= TEN.pow(20)) throw CalcException(CalcError.OVERFLOW)
        }
        return x
    }

    /** log10 |r| in double precision, for any size. */
    private fun log10Estimate(r: Rational): Double = log10Estimate(r.num.abs()) - log10Estimate(r.den)

    private fun log10Estimate(n: BigInteger): Double {
        val shift = max(0, n.bitLength() - 60)
        return kotlin.math.log10(n.shiftRight(shift).toDouble()) + shift * kotlin.math.log10(2.0)
    }

    // The four operations

    fun add(a: Real, b: Real): Real {
        val ra = a.rational
        val rb = b.rational
        if (ra != null && rb != null) return checked(Real.of(ra + rb))
        if (ra?.isZero == true) return b
        if (rb?.isZero == true) return a
        return checked(SumReal(a, b))
    }

    fun negate(a: Real): Real = a.rational?.let { Real.of(-it) } ?: NegReal(a)

    fun subtract(a: Real, b: Real): Real = add(a, negate(b))

    fun multiply(a: Real, b: Real): Real {
        val ra = a.rational
        val rb = b.rational
        if (ra != null && rb != null) return checked(Real.of(ra * rb))
        if (ra?.isZero == true || rb?.isZero == true) return Real.ZERO
        if (ra == Rational.ONE) return b
        if (rb == Rational.ONE) return a
        return checked(ProductReal(a, b))
    }

    fun inverse(a: Real): Real {
        a.rational?.let { return checked(Real.of(Rational.ONE / it)) }
        val e = a.magnitude() ?: throw CalcException(CalcError.DIVISION_BY_ZERO)
        return checked(InverseReal(a, e))
    }

    fun divide(a: Real, b: Real): Real {
        val ra = a.rational
        val rb = b.rational
        if (ra != null && rb != null) return checked(Real.of(ra / rb))
        return multiply(a, inverse(b))
    }

    /** b / 100. */
    fun percent(a: Real): Real = divide(a, Real.of(100))

    fun abs(a: Real): Real {
        a.rational?.let { return Real.of(it.abs()) }
        return if (a.signum() < 0) negate(a) else a
    }

    /**
     * a mod b. Decimal mode (SILNIK D2): the sign of the divisor, a − b · floor(a/b), so −7 mod 3 is 2.
     */
    fun mod(a: Real, b: Real): Real {
        val ra = a.rational
        val rb = b.rational
        if (ra != null && rb != null) {
            if (rb.isZero) throw CalcException(CalcError.DIVISION_BY_ZERO)
            return checked(Real.of(ra - rb * Rational.of((ra / rb).floor())))
        }
        val q = divide(a, b)
        return subtract(a, multiply(b, Real.of(Rational.of(floor(q)))))
    }

    /** The largest integer not greater than x; a number equal to an integer is that integer. */
    fun floor(x: Real): BigInteger {
        x.rational?.let { return it.floor() }
        val k = Real.divRound(x.approx(5), TEN.pow(5))
        val diff = subtract(x, Real.of(Rational.of(k)))
        return if (diff.signum() >= 0) k else k - BigInteger.ONE
    }

    // Powers and roots

    fun sqrt(a: Real): Real {
        a.rational?.let { r ->
            if (r.signum < 0) throw CalcException(CalcError.DOMAIN)
            r.exactRoot(2)?.let { return Real.of(it) }
            return SqrtReal(a)
        }
        return when (a.signum()) {
            -1 -> throw CalcException(CalcError.DOMAIN)
            0 -> Real.ZERO
            else -> SqrtReal(a)
        }
    }

    /** The [n]-th root of a (ʸ√x with y = n); for a negative a only odd roots. */
    fun root(n: Real, a: Real): Real {
        val rn = n.rational
        if (rn != null && rn.isInteger && rn.num == BigInteger.TWO) return sqrt(a)
        if (rn != null && rn.isZero) throw CalcException(CalcError.DOMAIN)
        return power(a, inverse(n))
    }

    /**
     * a^b. Exact for a rational a and a rational b whose root is exact (8^(1/3) = 2); a negative
     * base takes only a rational exponent with an odd denominator (the real odd root). 0^0 and a
     * negative power of zero are errors.
     */
    fun power(a: Real, b: Real): Real {
        val ra = a.rational
        val rb = b.rational
        if (rb != null && rb.isZero) {
            if (ra != null && ra.isZero) throw CalcException(CalcError.DOMAIN)
            if (ra == null && a.signum() == 0) throw CalcException(CalcError.DOMAIN)
            return Real.ONE
        }
        val signA = ra?.signum ?: a.signum()
        if (signA == 0) {
            val signB = rb?.signum ?: b.signum()
            if (signB < 0) throw CalcException(CalcError.DIVISION_BY_ZERO)
            return Real.ZERO
        }
        if (ra != null && rb != null) exactPower(ra, rb)?.let { return it }
        if (signA < 0) {
            // Only p/q with an odd q has a real value: (−x)^(p/q) = ±x^(p/q)
            if (rb == null || rb.den.testBit(0).not()) throw CalcException(CalcError.DOMAIN)
            val magnitude = power(negate(a), b)
            return if (rb.num.testBit(0)) negate(magnitude) else magnitude
        }
        if (ra == Rational.ONE) return Real.ONE
        // A positive base: e^(b · ln a)
        return exp(multiply(b, ln(a)))
    }

    /** a^b for rationals when the result is rational, else null. Checks the range before working. */
    private fun exactPower(a: Rational, b: Rational): Real? {
        if (b.isInteger) {
            val lg = log10Estimate(a.abs())
            val exponent = b.num
            if (exponent.bitLength() > 31) {
                if (a.abs() == Rational.ONE) return Real.of(if (a.signum < 0 && exponent.testBit(0)) -Rational.ONE else Rational.ONE)
                // A base near 1 stays in range even so (1.000001^3000000000 ≈ 10^1303): the general way decides
                return null
            }
            val n = exponent.toInt()
            // An exact power of a long fraction grows too long to be worth it (1.0000001^1000000): the general way then
            if ((a.num.bitLength().toLong() + a.den.bitLength()) * abs(n.toLong()) > 4_000_000L) return null
            if (lg * n > RANGE_DIGITS + 1) throw CalcException(CalcError.OVERFLOW)
            if (lg * n < -RANGE_DIGITS - 1) return Real.ZERO
            return checked(Real.of(a.pow(n)))
        }
        if (b.den.bitLength() > 31) return null
        val q = b.den.toInt()
        val root = a.exactRoot(q) ?: return null
        return exactPower(root, Rational.of(b.num))
    }

    fun square(a: Real): Real = multiply(a, a)

    fun exp(x: Real): Real {
        x.rational?.let { if (it.isZero) return Real.ONE }
        val bound = x.approx(0)
        // e^23026 is about 10^10000
        if (bound > BigInteger.valueOf(23_030)) throw CalcException(CalcError.OVERFLOW)
        if (bound < BigInteger.valueOf(-23_030)) return Real.ZERO
        if (bound > BigInteger.valueOf(23_020)) {
            // Near the edge: decide by the value itself
            if (x.decimal(10) > BigDecimal("23025.850929940")) throw CalcException(CalcError.OVERFLOW)
        }
        // |e^x| < 10^digits
        val digits = max(0, ceil((bound.toDouble() + 1) / 2.302585).toInt()) + 1
        return Real.computed { p ->
            // The result moves by e^x · dx: x to p + digits + 2 places
            val xd = x.decimal(p + digits + 2)
            scaledOf(Series.exp(xd, max(p + digits + 5, 10)), p)
        }
    }

    /** 10^x (exact for a rational whose power of ten is rational). */
    fun exp10(x: Real): Real = power(Real.of(10), x)

    fun ln(x: Real): Real {
        val r = x.rational
        if (r != null) {
            if (r.signum <= 0) throw CalcException(CalcError.DOMAIN)
            if (r == Rational.ONE) return Real.ZERO
        } else if (x.signum() <= 0) throw CalcException(CalcError.DOMAIN)
        val e = x.magnitude()!!
        return Real.computed { p ->
            // d ln x = dx / x and x ≥ 10^(e−2)
            // Never fewer places than keep x within half of itself (ln 10^−50 asked for few digits)
            val xd = x.decimal(max(p + 5 - e, 3 - e))
            scaledOf(Series.ln(xd, max(p + 3, 5)), p)
        }
    }

    /** log10 x: exact for a rational power of ten. */
    fun log10(x: Real): Real {
        x.rational?.let { r ->
            if (r.signum <= 0) throw CalcException(CalcError.DOMAIN)
            powerOfTen(r)?.let { return Real.of(Rational.of(it.toLong())) }
        }
        return divide(ln(x), ln10)
    }

    /** k when r = 10^k exactly. */
    private fun powerOfTen(r: Rational): Int? {
        fun tens(n: BigInteger): Int? {
            var k = 0
            var m = n
            while (m > BigInteger.ONE) {
                val (q, rem) = m.divideAndRemainder(TEN)
                if (rem.signum() != 0) return null
                m = q
                k++
            }
            return if (m == BigInteger.ONE) k else null
        }
        if (r.den == BigInteger.ONE) return tens(r.num)
        if (r.num == BigInteger.ONE) return tens(r.den)?.let { -it }
        return null
    }

    // Factorial

    /** n! for an integer 0 ≤ n ≤ 3248 (3249! is beyond 10^10000); a fraction is outside the domain (D3). */
    fun factorial(x: Real): Real {
        val r = x.rational ?: run {
            // An irrational number is never an integer
            throw CalcException(CalcError.DOMAIN)
        }
        if (!r.isInteger || r.signum < 0) throw CalcException(CalcError.DOMAIN)
        if (r.num > BigInteger.valueOf(3248)) throw CalcException(CalcError.OVERFLOW)
        return Real.of(Rational.of(product(1, r.num.toInt())))
    }

    private fun product(from: Int, to: Int): BigInteger {
        Cancel.check()
        if (to < from) return BigInteger.ONE
        if (to - from < 16) {
            var p = BigInteger.ONE
            for (i in from..to) p = p.multiply(BigInteger.valueOf(i.toLong()))
            return p
        }
        val mid = (from + to) / 2
        return product(from, mid) * product(mid + 1, to)
    }

    // Trigonometry

    private fun turn(unit: AngleUnit): Rational = when (unit) {
        AngleUnit.DEGREES -> Rational.of(360)
        AngleUnit.GRADIANS -> Rational.of(400)
        AngleUnit.RADIANS -> error("not rational")
    }

    /**
     * The angle as a fraction of a full turn in [0, 1), when it is rational (degrees and gradians
     * of a rational number), else null.
     */
    private fun turns(x: Real, unit: AngleUnit): Rational? {
        if (unit == AngleUnit.RADIANS) return null
        val r = x.rational ?: return null
        val t = r / turn(unit)
        return t - Rational.of(t.floor())
    }

    /** x in radians, reduced first by whole turns when it is rational in degrees or gradians. */
    private fun radians(x: Real, unit: AngleUnit): Real {
        val t = turns(x, unit)
        if (t != null) return multiply(Real.of(t * Rational.of(2)), pi)
        return when (unit) {
            AngleUnit.RADIANS -> x
            else -> multiply(x, divide(pi, Real.of(turn(unit) / Rational.of(2))))
        }
    }

    // sin at multiples of 30°, by twelfths of a turn; null where irrational (√3/2)
    private val SIN_TWELFTHS: Array<Rational?> = arrayOf(
        Rational.ZERO, Rational.of(1, 2), null, Rational.ONE, null, Rational.of(1, 2),
        Rational.ZERO, Rational.of(-1, 2), null, Rational.of(-1), null, Rational.of(-1, 2),
    )

    private fun exactSin(t: Rational, shiftTwelfths: Int): Rational? {
        val twelfths = t * Rational.of(12)
        if (!twelfths.isInteger) return null
        return SIN_TWELFTHS[(twelfths.num.toInt() + shiftTwelfths).mod(12)]
    }

    fun sin(x: Real, unit: AngleUnit): Real {
        turns(x, unit)?.let { t -> exactSin(t, 0)?.let { return Real.of(it) } }
        if (unit == AngleUnit.RADIANS && x.rational?.isZero == true) return Real.ZERO
        return sinRadians(radians(x, unit), quarter = 0)
    }

    fun cos(x: Real, unit: AngleUnit): Real {
        turns(x, unit)?.let { t -> exactSin(t, 3)?.let { return Real.of(it) } }
        if (unit == AngleUnit.RADIANS && x.rational?.isZero == true) return Real.ONE
        return sinRadians(radians(x, unit), quarter = 1)
    }

    fun tan(x: Real, unit: AngleUnit): Real {
        turns(x, unit)?.let { t ->
            val eighths = t * Rational.of(8)
            if (eighths.isInteger) {
                return when (eighths.num.toInt()) {
                    0, 4 -> Real.ZERO
                    1, 5 -> Real.ONE
                    3, 7 -> Real.of(-1)
                    else -> throw CalcException(CalcError.DOMAIN)
                }
            }
        }
        if (unit == AngleUnit.RADIANS && x.rational?.isZero == true) return Real.ZERO
        val rad = radians(x, unit)
        val c = sinRadians(rad, quarter = 1)
        if (c.signum() == 0) throw CalcException(CalcError.DOMAIN)
        return divide(sinRadians(rad, quarter = 0), c)
    }

    /**
     * sin(x + quarter · π/2) for x in radians: x reduced by the nearest multiple of π/2 to |r| ≤ π/4,
     * then the series for sin or cos of r by the quadrant.
     */
    private fun sinRadians(x: Real, quarter: Int): Real = Real.computed { p ->
        val work = max(p + 8, 8)
        val xd = x.decimal(work)
        // π/2 to enough places that k · π/2 stays accurate
        val intDigits = Real.digitCount(xd.abs().toBigInteger() + BigInteger.ONE)
        val halfPi = Series.pi(work + intDigits + 2).divide(BigDecimal.valueOf(2))
        val k = xd.divide(halfPi, 0, RoundingMode.HALF_EVEN).toBigInteger()
        val r = (xd - BigDecimal(k).multiply(halfPi)).setScale(work, RoundingMode.HALF_EVEN)
        val value = when ((k + BigInteger.valueOf(quarter.toLong())).mod(BigInteger.valueOf(4)).toInt()) {
            0 -> Series.sinCos(r, work, cosine = false)
            1 -> Series.sinCos(r, work, cosine = true)
            2 -> Series.sinCos(r, work, cosine = false).negate()
            else -> Series.sinCos(r, work, cosine = true).negate()
        }
        scaledOf(value, p)
    }

    /** An angle given in degrees, as a number in [unit]. */
    private fun fromDegrees(degrees: Rational, unit: AngleUnit): Real = when (unit) {
        AngleUnit.DEGREES -> Real.of(degrees)
        AngleUnit.GRADIANS -> Real.of(degrees * Rational.of(10, 9))
        AngleUnit.RADIANS -> if (degrees.isZero) Real.ZERO else multiply(Real.of(degrees / Rational.of(180)), pi)
    }

    /** An angle given in radians, as a number in [unit]. */
    private fun fromRadians(radians: Real, unit: AngleUnit): Real = when (unit) {
        AngleUnit.RADIANS -> radians
        AngleUnit.DEGREES -> divide(multiply(radians, Real.of(180)), pi)
        AngleUnit.GRADIANS -> divide(multiply(radians, Real.of(200)), pi)
    }

    private val ASIN_EXACT = mapOf(
        Rational.ZERO to Rational.ZERO,
        Rational.of(1, 2) to Rational.of(30), Rational.of(-1, 2) to Rational.of(-30),
        Rational.ONE to Rational.of(90), Rational.of(-1) to Rational.of(-90),
    )

    fun asin(x: Real, unit: AngleUnit): Real {
        x.rational?.let { r ->
            ASIN_EXACT[r]?.let { return fromDegrees(it, unit) }
            if (r.abs() > Rational.ONE) throw CalcException(CalcError.DOMAIN)
        }
        val rest = subtract(Real.ONE, square(x))
        when (rest.signum()) {
            -1 -> throw CalcException(CalcError.DOMAIN)
            // |x| = 1
            0 -> return fromDegrees(Rational.of(90L * x.signum()), unit)
        }
        return fromRadians(atanRadians(divide(x, sqrt(rest))), unit)
    }

    fun acos(x: Real, unit: AngleUnit): Real {
        x.rational?.let { r -> ASIN_EXACT[r]?.let { return fromDegrees(Rational.of(90) - it, unit) } }
        val asinDegrees = asin(x, AngleUnit.RADIANS)
        return fromRadians(subtract(divide(pi, Real.of(2)), asinDegrees), unit)
    }

    fun atan(x: Real, unit: AngleUnit): Real {
        x.rational?.let { r ->
            when (r) {
                Rational.ZERO -> return Real.ZERO
                Rational.ONE -> return fromDegrees(Rational.of(45), unit)
                Rational.of(-1) -> return fromDegrees(Rational.of(-45), unit)
            }
        }
        return fromRadians(atanRadians(x), unit)
    }

    private fun atanRadians(x: Real): Real = Real.computed { p ->
        // The derivative is at most 1
        val work = max(p, 2)
        scaledOf(Series.atan(x.decimal(work + 5), work + 3), p)
    }
}
