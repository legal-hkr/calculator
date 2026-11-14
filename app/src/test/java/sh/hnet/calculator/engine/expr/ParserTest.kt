package sh.hnet.calculator.engine.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.calculator.engine.number.AngleUnit
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real

class ParserTest {
    private fun tokens(text: String, comma: Boolean = false): List<Token> {
        val t = Paste.read(text, comma)
        assertNotNull("unreadable: $text", t)
        return t!!
    }

    private fun eval(text: String, angle: AngleUnit = AngleUnit.DEGREES, ans: Real? = null): Evaluation =
        Parser.evaluate(tokens(text), EvalContext(angle, ans))

    private fun exact(expected: String, text: String, angle: AngleUnit = AngleUnit.DEGREES) {
        val e = eval(text, angle)
        assertTrue("$text gave $e", e is Evaluation.Value)
        val r = (e as Evaluation.Value).value.rational
        assertEquals("$text exact", Rational.parse(expected), r)
    }

    private fun approx(expected: String, text: String, angle: AngleUnit = AngleUnit.DEGREES) {
        val e = eval(text, angle)
        assertTrue("$text gave $e", e is Evaluation.Value)
        val places = expected.substringAfter('.').length
        val v = (e as Evaluation.Value).value.approx(places)
        val want = java.math.BigDecimal(expected).unscaledValue()
        assertTrue("$text: $expected vs ${e.value.decimal(places)}", (v - want).abs() <= java.math.BigInteger.ONE)
    }

    private fun failure(kind: CalcError, text: String) {
        val e = eval(text)
        assertTrue("$text gave $e", e is Evaluation.Failure && e.error == kind)
    }

    @Test fun precedence() {
        exact("14", "2+3*4")
        exact("20", "(2+3)*4")
        exact("512", "2^3^2")
        exact("-4", "-2^2")
        exact("0.5", "2^-1")
        exact("-4", "-2²")
        exact("64", "2^3!")
        exact("36", "3!²")
        exact("1", "7-3-2-1")
        exact("2", "16/4/2")
    }

    @Test fun impliedMultiplicationBindsTighter() {
        // D1: a monomial is a monomial
        exact("1", "6÷2(1+2)")
        exact("9", "6÷2*(1+2)")
        approx("0.15915494309189533577", "1÷2π", AngleUnit.RADIANS)
        approx("1.57079632679489661923", "1÷2*π", AngleUnit.RADIANS)
        exact("6", "(2)(3)")
        exact("27", "3(4+5)")
        exact("1", "2sin(30)")
    }

    @Test fun percent() {
        exact("220", "200+10%")
        exact("180", "200-10%")
        exact("20", "200*10%")
        exact("0.5", "50%")
        exact("200.1", "200+2*5%")
        exact("2000", "200/10%")
    }

    @Test fun rootsAndFunctions() {
        exact("3", "√9")
        exact("4", "√4²")
        exact("2", "3ʸ√8")
        exact("2", "√(3+1)")
        exact("3", "abs(-3)")
        exact("3", "log(1000)")
        exact("1", "17 mod 4")
        exact("2", "-7 mod 3")
        exact("1500", "1.5E3")
        exact("0.002", "2E-3")
        exact("120", "5!")
    }

    @Test fun unclosedParenthesesCloseAtTheEnd() {
        exact("14", "2*(3+4")
        exact("0.5", "sin(30")
    }

    @Test fun incomplete() {
        assertEquals(Evaluation.Incomplete, Parser.evaluate(emptyList()))
        assertEquals(Evaluation.Incomplete, eval("5*"))
        assertEquals(Evaluation.Incomplete, eval("sin("))
        assertEquals(Evaluation.Incomplete, eval("2*()"))
        assertEquals(Evaluation.Incomplete, eval("12*(3+"))
    }

    @Test fun preview() {
        val e = Parser.preview(tokens("12*(3+"))
        assertEquals(Rational.of(36), (e as Evaluation.Value).value.rational)
        assertEquals(Evaluation.Incomplete, Parser.preview(tokens("sin(")))
        assertEquals(Rational.of(2), (Parser.preview(tokens("2+sin(")) as Evaluation.Value).value.rational)
    }

    @Test fun failures() {
        failure(CalcError.SYNTAX, "5*/3")
        failure(CalcError.SYNTAX, "2+3)")
        failure(CalcError.DIVISION_BY_ZERO, "1/0")
        failure(CalcError.DOMAIN, "√-1")
        failure(CalcError.DOMAIN, "tan(90)")
        failure(CalcError.OVERFLOW, "10^10^10")
        failure(CalcError.OVERFLOW, "1E20000")
        val e = eval("5*/3") as Evaluation.Failure
        assertEquals(2, e.position)
    }

    @Test fun ans() {
        val e = eval("Ans*2", ans = Real.of(21))
        assertEquals(Rational.of(42), (e as Evaluation.Value).value.rational)
    }

    @Test fun angleUnits() {
        exact("1", "sin(90)", AngleUnit.DEGREES)
        exact("1", "sin(100)", AngleUnit.GRADIANS)
        approx("0.84147098480789650665", "sin(1)", AngleUnit.RADIANS)
        exact("30", "asin(0.5)", AngleUnit.DEGREES)
    }

    @Test fun loneNumber() {
        assertTrue(Parser.isLoneNumber(tokens("1234.5")))
        assertTrue(Parser.isLoneNumber(tokens("-12")))
        assertTrue(Parser.isLoneNumber(tokens("6.022E23")))
        assertTrue(Parser.isLoneNumber(tokens("1E-5")))
        assertFalse(Parser.isLoneNumber(tokens("1-5")))
        assertFalse(Parser.isLoneNumber(tokens("π")))
        assertFalse(Parser.isLoneNumber(emptyList()))
    }

    @Test fun pasteSeparators() {
        assertEquals(Paste.read("1234.5", false), Paste.read("1 234,5", true))
        assertEquals(Paste.read("1234.5", false), Paste.read("1,234.5", false))
        assertEquals(Paste.read("1234.5", false), Paste.read("1.234,5", true))
        assertEquals(Paste.read("1234", false), Paste.read("1,234", false))
        assertEquals(Paste.read("3.5", false), Paste.read("3,5", false))
        assertEquals(Paste.read("3.5", false), Paste.read("3.5", true))
        assertEquals(Paste.read("1234567", false), Paste.read("1.234.567", true))
        assertEquals(Paste.read("2×3−1÷4", false), Paste.read("2*3-1/4", false))
        assertEquals(Paste.read("√(2)π", false), Paste.read("sqrt(2)pi", false))
        assertEquals(Paste.read("2+3", false), Paste.read(" 2 + 3 = ", false))
        assertNull(Paste.read("hello", false))
        assertNull(Paste.read("1\n2", false))
        assertNull(Paste.read("sin 30", false))
        assertNull(Paste.read("", false))
    }
}
