package sh.hnet.calculator.engine.number

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * The elementary functions on BigDecimal, in fixed point: each takes and returns numbers with
 * [scale] places after the point and is accurate to a few units of the last place. Callers ask for
 * a handful of guard places on top of what they need ([Calc] does).
 */
internal object Series {

    private fun mul(a: BigDecimal, b: BigDecimal, scale: Int): BigDecimal = a.multiply(b).setScale(scale, RoundingMode.HALF_EVEN)

    private fun div(a: BigDecimal, b: BigDecimal, scale: Int): BigDecimal = a.divide(b, scale, RoundingMode.HALF_EVEN)

    private fun epsilon(scale: Int): BigDecimal = BigDecimal.ONE.movePointLeft(scale)

    // π by the Chudnovsky series with binary splitting, kept for the most places computed so far
    @Volatile private var piCache: BigDecimal = BigDecimal.ZERO
    @Volatile private var piScale = -1

    fun pi(scale: Int): BigDecimal {
        if (piScale >= scale) return piCache.setScale(scale, RoundingMode.HALF_EVEN)
        val work = scale + 10
        val terms = work / 14 + 2
        val (_, q, t) = chudnovsky(0, terms)
        val mc = MathContext(work + 5)
        val sqrt = BigDecimal.valueOf(10005).sqrt(mc)
        val value = BigDecimal(q).multiply(BigDecimal.valueOf(426880)).multiply(sqrt).divide(BigDecimal(t), mc)
            .setScale(work, RoundingMode.HALF_EVEN)
        synchronized(this) {
            if (work > piScale) {
                piCache = value
                piScale = work
            }
        }
        return value.setScale(scale, RoundingMode.HALF_EVEN)
    }

    private val C3_24: BigInteger = BigInteger.valueOf(640320).pow(3).divide(BigInteger.valueOf(24))

    private fun chudnovsky(a: Int, b: Int): Triple<BigInteger, BigInteger, BigInteger> {
        Cancel.check()
        if (b - a == 1) {
            val k = a.toLong()
            val p = if (a == 0) BigInteger.ONE else BigInteger.valueOf(6 * k - 5) * BigInteger.valueOf(2 * k - 1) * BigInteger.valueOf(6 * k - 1)
            val q = if (a == 0) BigInteger.ONE else BigInteger.valueOf(k).pow(3) * C3_24
            var t = p * (BigInteger.valueOf(13591409) + BigInteger.valueOf(545140134) * BigInteger.valueOf(k))
            if (a % 2 == 1) t = t.negate()
            return Triple(p, q, t)
        }
        val m = (a + b) / 2
        val (p1, q1, t1) = chudnovsky(a, m)
        val (p2, q2, t2) = chudnovsky(m, b)
        return Triple(p1 * p2, q1 * q2, t1 * q2 + p1 * t2)
    }

    /**
     * e^x to [digits] significant digits (relative precision). The argument is halved k times to
     * below 2^−10, the series summed, the result squared k times; the squarings cost k · log10 2
     * digits, added as guard digits.
     */
    fun exp(x: BigDecimal, digits: Int): BigDecimal {
        if (x.signum() == 0) return BigDecimal.ONE
        val intBits = x.abs().toBigInteger().bitLength()
        val k = intBits + 10
        val guard = k * 3 / 10 + 10
        val scale = digits + guard
        val r = x.divide(BigDecimal(BigInteger.ONE.shiftLeft(k)), scale, RoundingMode.HALF_EVEN)
        var sum = BigDecimal.ONE.setScale(scale)
        var term = BigDecimal.ONE.setScale(scale)
        var n = 1
        val eps = epsilon(scale)
        while (true) {
            Cancel.check()
            term = div(mul(term, r, scale), BigDecimal.valueOf(n.toLong()), scale)
            if (term.abs() < eps) break
            sum += term
            n++
        }
        val mc = MathContext(scale)
        repeat(k) {
            Cancel.check()
            sum = sum.multiply(sum, mc)
        }
        return sum.round(MathContext(digits + 2))
    }

    /**
     * ln a for a > 0, to [scale] places. Square roots bring a near 1 (s of them, multiplying the
     * result's error by 2^s, hence guard places), then ln a = 2 atanh((a−1)/(a+1)).
     */
    fun ln(a: BigDecimal, scale: Int): BigDecimal {
        require(a.signum() > 0)
        if (a.compareTo(BigDecimal.ONE) == 0) return BigDecimal.ZERO.setScale(scale)
        // Estimate the square roots needed: |ln a| / 2^s < 0.01
        val lnEstimate = kotlin.math.abs(estimateLn(a))
        var s = 0
        while (lnEstimate / Math.pow(2.0, s.toDouble()) >= 0.01) s++
        val guard = s * 3 / 10 + 10
        val work = scale + guard
        // Relative precision for the roots: a may be huge or tiny, its roots end near 1
        val mc = MathContext(work + 5 + kotlin.math.max(0, a.precision() - a.scale()))
        var x = a
        repeat(s) {
            Cancel.check()
            x = x.sqrt(mc)
        }
        x = x.setScale(work, RoundingMode.HALF_EVEN)
        val z = div(x - BigDecimal.ONE, x + BigDecimal.ONE, work)
        val z2 = mul(z, z, work)
        var power = z
        var sum = z
        var n = 3L
        val eps = epsilon(work)
        while (true) {
            Cancel.check()
            power = mul(power, z2, work)
            val term = div(power, BigDecimal.valueOf(n), work)
            if (term.abs() < eps) break
            sum += term
            n += 2
        }
        return sum.multiply(BigDecimal(BigInteger.ONE.shiftLeft(s + 1))).setScale(scale, RoundingMode.HALF_EVEN)
    }

    /** ln a in double precision, also for a beyond the range of double. */
    fun estimateLn(a: BigDecimal): Double {
        val unscaled = a.unscaledValue()
        val shift = kotlin.math.max(0, unscaled.bitLength() - 60)
        val head = unscaled.shiftRight(shift).toDouble()
        return kotlin.math.ln(head) + shift * kotlin.math.ln(2.0) - a.scale() * kotlin.math.ln(10.0)
    }

    /**
     * sin r (or cos r when [cosine]) for |r| ≤ 1, to [scale] places: the Taylor series, whose terms
     * fall fast at this size.
     */
    fun sinCos(r: BigDecimal, scale: Int, cosine: Boolean): BigDecimal {
        val work = scale + 5
        val r2 = mul(r, r, work)
        var term = if (cosine) BigDecimal.ONE.setScale(work) else r.setScale(work, RoundingMode.HALF_EVEN)
        var sum = term
        var n = if (cosine) 1L else 2L
        val eps = epsilon(work)
        while (true) {
            Cancel.check()
            term = div(mul(term, r2, work), BigDecimal.valueOf(n * (n + 1)), work).negate()
            if (term.abs() < eps) break
            sum += term
            n += 2
        }
        return sum.setScale(scale, RoundingMode.HALF_EVEN)
    }

    /**
     * atan x to [scale] places. Beyond 1 by π/2 − atan(1/x); then halved by
     * atan x = 2 atan(x / (1 + √(1 + x²))) to below 0.1, and the series.
     */
    fun atan(x: BigDecimal, scale: Int): BigDecimal {
        val work = scale + 10
        if (x.abs() > BigDecimal.ONE) {
            val half = pi(work).divide(BigDecimal.valueOf(2))
            val inner = atan(div(BigDecimal.ONE, x, work + 5), work)
            val result = if (x.signum() > 0) half - inner else half.negate() - inner
            return result.setScale(scale, RoundingMode.HALF_EVEN)
        }
        var y = x.setScale(work, RoundingMode.HALF_EVEN)
        var halvings = 0
        val tenth = BigDecimal("0.1")
        val mc = MathContext(work + 5)
        while (y.abs() > tenth) {
            Cancel.check()
            val root = (BigDecimal.ONE + mul(y, y, work)).sqrt(mc)
            y = div(y, BigDecimal.ONE + root, work)
            halvings++
        }
        val y2 = mul(y, y, work)
        var power = y
        var sum = y
        var n = 3L
        var negative = true
        val eps = epsilon(work)
        while (true) {
            Cancel.check()
            power = mul(power, y2, work)
            val term = div(power, BigDecimal.valueOf(n), work)
            if (term.abs() < eps) break
            sum = if (negative) sum - term else sum + term
            negative = !negative
            n += 2
        }
        return sum.multiply(BigDecimal(BigInteger.ONE.shiftLeft(halvings))).setScale(scale, RoundingMode.HALF_EVEN)
    }
}
