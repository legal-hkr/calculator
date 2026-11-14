package sh.hnet.calculator.engine.number

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.hnet.calculator.engine.number.AngleUnit.DEGREES
import sh.hnet.calculator.engine.number.AngleUnit.GRADIANS
import sh.hnet.calculator.engine.number.AngleUnit.RADIANS

class CalcTest {
    private fun q(text: String): Real = Real.of(Rational.parse(text))
    private fun n(v: Long): Real = Real.of(v)

    private fun exact(expected: String, x: Real) {
        val r = x.rational
        assertTrue("expected exact $expected, got $x", r != null)
        assertEquals(Rational.parse(expected), r)
    }

    /** Within one unit of the last place of [expected] (the contract of [Real.approx]). */
    private fun digits(expected: String, x: Real) {
        val places = expected.substringAfter('.', "").length
        val want = java.math.BigDecimal(expected).unscaledValue()
        val diff = (x.approx(places) - want).abs()
        assertTrue("$expected vs ${x.decimal(places)}", diff <= java.math.BigInteger.ONE)
    }

    private fun error(kind: CalcError, block: () -> Real) {
        try {
            val x = block()
            // Lazy recipes fail when first asked for digits
            x.approx(10)
            fail("expected $kind")
        } catch (e: CalcException) {
            assertEquals(kind, e.error)
        }
    }

    @Test fun decimalSumIsExact() = exact("0.3", Calc.add(q("0.1"), q("0.2")))

    @Test fun thirdTimesThreeIsOne() = exact("1", Calc.multiply(Calc.divide(n(1), n(3)), n(3)))

    @Test fun pi100() = digits(
        "3.1415926535897932384626433832795028841971693993751058209749445923078164062862089986280348253421170680",
        Calc.pi,
    )

    @Test fun e50() = digits("2.71828182845904523536028747135266249775724709369996", Calc.e)

    @Test fun sqrt2() = digits("1.41421356237309504880168872420969807856967187537694", Calc.sqrt(n(2)))

    @Test fun ln2() = digits("0.69314718055994530941723212145817656807550013436026", Calc.ln(n(2)))

    @Test fun sin1() = digits("0.84147098480789650665250232163029899962256306079837", Calc.sin(n(1), RADIANS))

    @Test fun atan1Radians() = digits("0.78539816339744830961566084581987572104929234984378", Calc.atan(q("1"), RADIANS))

    @Test fun exactSpecialValues() {
        exact("0.5", Calc.sin(n(30), DEGREES))
        exact("0.5", Calc.cos(n(60), DEGREES))
        exact("1", Calc.tan(n(45), DEGREES))
        exact("0", Calc.sin(n(180), DEGREES))
        exact("-1", Calc.sin(n(-90), DEGREES))
        exact("0.5", Calc.sin(n(750), DEGREES))
        exact("1", Calc.sin(n(100), GRADIANS))
        exact("1.5", Calc.sqrt(q("2.25")))
        exact("2", Calc.root(n(3), n(8)))
        exact("-2", Calc.root(n(3), n(-8)))
        exact("2", Calc.power(n(8), Real.of(Rational.of(1, 3))))
        exact("4", Calc.power(n(-8), Real.of(Rational.of(2, 3))))
        exact("0", Calc.ln(n(1)))
        exact("3", Calc.log10(n(1000)))
        exact("-2", Calc.log10(q("0.01")))
        exact("30", Calc.asin(q("0.5"), DEGREES))
        exact("120", Calc.acos(q("-0.5"), DEGREES))
        exact("1024", Calc.power(n(2), n(10)))
        exact("0.125", Calc.power(n(2), n(-3)))
        exact("120", Calc.factorial(n(5)))
        exact("2", Calc.mod(n(-7), n(3)))
    }

    @Test fun sinPiIsZero() {
        val x = Calc.sin(Calc.pi, RADIANS)
        assertEquals(0, x.signum())
        assertNull(x.magnitude())
    }

    @Test fun sqrtTwoSquaredMinusTwoIsZero() {
        val x = Calc.subtract(Calc.square(Calc.sqrt(n(2))), n(2))
        assertEquals(0, x.signum())
    }

    @Test fun errors() {
        error(CalcError.DIVISION_BY_ZERO) { Calc.divide(n(1), n(0)) }
        error(CalcError.DOMAIN) { Calc.sqrt(n(-1)) }
        error(CalcError.DOMAIN) { Calc.ln(n(0)) }
        error(CalcError.DOMAIN) { Calc.tan(n(90), DEGREES) }
        error(CalcError.DOMAIN) { Calc.tan(Calc.divide(Calc.pi, n(2)), RADIANS) }
        error(CalcError.DOMAIN) { Calc.power(n(0), n(0)) }
        error(CalcError.DOMAIN) { Calc.power(n(-4), q("0.5")) }
        error(CalcError.DOMAIN) { Calc.factorial(q("0.5")) }
        error(CalcError.DOMAIN) { Calc.asin(n(2), DEGREES) }
        error(CalcError.OVERFLOW) { Calc.power(n(10), Calc.power(n(10), n(10))) }
        error(CalcError.OVERFLOW) { Calc.factorial(n(3249)) }
        error(CalcError.OVERFLOW) { Calc.exp(n(30000)) }
        error(CalcError.DIVISION_BY_ZERO) { Calc.divide(n(1), Calc.sin(Calc.pi, RADIANS)) }
    }

    @Test fun rangeEdges() {
        exact("1E+10000", Calc.power(n(10), n(10000)))
        error(CalcError.OVERFLOW) { Calc.multiply(Calc.power(n(10), n(10000)), n(10)) }
        exact("0", Calc.power(n(10), n(-10001)))
        assertEquals(9998, Calc.factorial(n(3248)).upperExponent())
    }

    @Test fun identities() {
        for (v in listOf("0.3", "1.7", "12.5", "-3.25", "1000.01")) {
            val x = q(v)
            val s = Calc.sin(x, RADIANS)
            val c = Calc.cos(x, RADIANS)
            val one = Calc.add(Calc.square(s), Calc.square(c))
            assertEquals("sin²+cos² for $v", 0, Calc.subtract(one, Real.ONE).signum(limit = 200))
            if (x.signum() > 0) {
                assertEquals("exp ln $v", 0, Calc.subtract(Calc.exp(Calc.ln(x)), x).signum(limit = 200))
            }
        }
    }

    @Test fun moreDigitsOnDemand() {
        val x = Calc.sqrt(n(2))
        val d1000 = x.decimal(1000).toPlainString()
        assertTrue(d1000.startsWith(x.decimal(50).toPlainString().dropLast(1)))
        assertEquals(1002, d1000.length)
    }
}
