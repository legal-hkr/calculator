package sh.hnet.calculator.engine.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.calculator.engine.expr.Paste
import sh.hnet.calculator.engine.number.AngleUnit
import sh.hnet.calculator.engine.number.Calc
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real

class FormatterTest {
    private val en = NumberStyle(decimalComma = false)
    private val pl = NumberStyle(decimalComma = true)

    private fun q(text: String): Real = Real.of(Rational.parse(text))
    private fun frac(a: Long, b: Long): Real = Real.of(Rational.of(a, b))

    private fun check(text: String, exact: Boolean, f: Formatted, exponent: Int? = null) {
        assertEquals(text, f.text)
        assertEquals("exact of ${f.text}", exact, f.exact)
        assertEquals("exponent of ${f.text}", exponent, f.exponent)
    }

    @Test fun languages() {
        check("1,234,567.89", true, Formatter.format(q("1234567.89"), en, 20))
        check("1 234 567,89", true, Formatter.format(q("1234567.89"), pl, 20))
        check("1234567.89", true, Formatter.format(q("1234567.89"), en.copy(grouping = false), 20))
        // D4: four digits grouped too
        check("1 234", true, Formatter.format(q("1234"), pl, 20))
        check("−1,234.5", true, Formatter.format(q("-1234.5"), en, 20))
        assertEquals("-1234.5", Formatter.format(q("-1234.5"), en, 20).copy)
        assertEquals("-1234,5", Formatter.format(q("-1234.5"), pl, 20).copy)
    }

    @Test fun automaticDecimalsCutNotRounded() {
        check("0.33333333", false, Formatter.format(frac(1, 3), en, 10))
        check("0.66666666", false, Formatter.format(frac(2, 3), en, 10))
        check("0.5", true, Formatter.format(q("0.5"), en, 10))
        check("0.3", true, Formatter.format(Calc.add(q("0.1"), q("0.2")), en, 10))
        check("1.41421356", false, Formatter.format(Calc.sqrt(q("2")), en, 10))
    }

    @Test fun realsEqualToADecimalAreExact() {
        val two = Calc.multiply(Calc.sqrt(q("2")), Calc.sqrt(q("2")))
        check("2", true, Formatter.format(two, en, 12))
        val half = Calc.sin(Calc.divide(Calc.pi, q("6")), AngleUnit.RADIANS)
        check("0.5", true, Formatter.format(half, en, 12))
        check("0", true, Formatter.format(Calc.sin(Calc.pi, AngleUnit.RADIANS), en, 12))
    }

    @Test fun fixedDecimalsRoundHalfUp() {
        val two = en.copy(decimals = 2)
        check("0.67", false, Formatter.format(frac(2, 3), two, 20))
        check("0.50", true, Formatter.format(q("0.5"), two, 20))
        check("1.01", false, Formatter.format(q("1.005"), two, 20))
        check("3", false, Formatter.format(q("2.5"), en.copy(decimals = 0), 20))
        check("−3", false, Formatter.format(q("-2.5"), en.copy(decimals = 0), 20))
        check("0.00", true, Formatter.format(Real.ZERO, two, 20))
    }

    @Test fun scientific() {
        check("1", true, Formatter.format(q("1E20"), en, 12), exponent = 20)
        assertEquals("1E20", Formatter.format(q("1E20"), en, 12).copy)
        check("1.23", true, Formatter.format(q("0.00000000000123"), en, 20), exponent = -12)
        check("1.23", true, Formatter.format(q("1230"), en.copy(notation = Notation.ALWAYS), 20), exponent = 3)
        check("1.00", false, Formatter.format(q("9.999"), en.copy(notation = Notation.ALWAYS, decimals = 2), 20), exponent = 1)
        val f = Formatter.format(Calc.power(q("10"), q("100")), pl.copy(notation = Notation.ALWAYS), 20)
        assertEquals("1E100", f.copy)
        val pi100 = Formatter.format(Calc.multiply(Calc.pi, Calc.power(q("10"), q("100"))), pl, 20)
        assertEquals(100, pi100.exponent)
        assertEquals("3,14159265358", pi100.text)
        assertEquals("3,14159265358E100", pi100.copy)
        assertFalse(pi100.exact)
    }

    @Test fun neverNotation() {
        val never = en.copy(notation = Notation.NEVER)
        check("0." + "0".repeat(29) + "1", true, Formatter.format(q("1E-30"), never, 10))
        check("10,000,000,000,000,000,000,000,000,000,000,000,000,000", true, Formatter.format(q("1E40"), never, 10))
        assertNull(Formatter.format(q("1E40"), never, 10).exponent)
    }

    @Test fun moreDigitsWithMoreWidth() {
        val narrow = Formatter.format(Calc.sqrt(q("2")), en, 10)
        val wide = Formatter.format(Calc.sqrt(q("2")), en, 60)
        assertTrue(wide.text.startsWith(narrow.text))
        assertEquals(60, wide.text.length)
    }

    @Test fun hugeNumbersAreQuick() {
        val started = System.nanoTime()
        val f = Formatter.format(Calc.exp(q("22970")), en, 20)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(9975, f.exponent)
        assertTrue("took $ms ms", ms < 500)
    }

    @Test fun superscript() {
        assertEquals("⁻⁷", Formatter.superscript(-7))
        assertEquals("¹⁰⁰", Formatter.superscript(100))
    }

    @Test fun expressionText() {
        fun render(s: String, style: NumberStyle = en) = ExpressionText.render(Paste.read(s, false)!!, style).text
        assertEquals("1,234.5 + 3", render("1234.5+3"))
        assertEquals("1 234,5 × −3", render("1234.5*-3", pl))
        assertEquals("2^10 − 1E12345", render("2^10-1E12345"))
        assertEquals("sin(30) + √2π", render("sin(30)+√2π"))
        assertEquals("17 mod 5", render("17 mod 5"))
        assertEquals("−5 + (−3)", render("-5+(-3)"))
        val t = ExpressionText.render(Paste.read("1234+5", false)!!, en)
        // 1,234 + 5: the cursor after the 4th digit sits after "1,234"
        assertEquals(5, t.offsets[4])
        assertEquals(9, t.offsets[6])
        assertEquals(4, t.cursorAt(5))
        val third = Real.of(Rational.of(1, 3))
        val ans = ExpressionText.render(listOf(sh.hnet.calculator.engine.expr.Token.Value(third), sh.hnet.calculator.engine.expr.Token.Operator(sh.hnet.calculator.engine.expr.Op.TIMES)), pl).text
        assertEquals("0,333333333333… × ", ans)
        val big = ExpressionText.render(listOf(sh.hnet.calculator.engine.expr.Token.Value(Calc.multiply(Calc.pi, Calc.power(q("10"), q("15"))))), en).text
        assertEquals("3.141592… × 10¹⁵", big)
    }
}
