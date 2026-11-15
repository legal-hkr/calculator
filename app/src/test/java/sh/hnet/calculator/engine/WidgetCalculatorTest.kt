package sh.hnet.calculator.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.widget.WidgetCalculator
import sh.hnet.calculator.widget.WidgetKey
import sh.hnet.calculator.widget.WidgetKey.*
import sh.hnet.calculator.widget.WidgetState

/** The widget's keys (SPEC-widget §7.1, stage 1 acceptance). */
class WidgetCalculatorTest {
    private val pl = NumberStyle(decimalComma = true)

    private fun type(vararg keys: WidgetKey, from: WidgetState = WidgetState()): WidgetState =
        keys.fold(from) { s, k -> WidgetCalculator.press(s, k) { ExpressionText.render(it, pl).text.length } }

    private fun shown(s: WidgetState) = ExpressionText.render(s.tokens, pl).text

    @Test fun exactSum() {
        val s = type(D0, POINT, D1, PLUS, D0, POINT, D2, EQUALS)
        assertEquals(Rational.parse("0.3"), s.result?.rational)
    }

    @Test fun percentAsInTheApp() {
        val s = type(D2, D0, D0, PLUS, D1, D0, PERCENT, PERCENT)
        assertEquals("200 + 10%", shown(s))
        assertEquals(Rational.of(220), type(EQUALS, from = s).result?.rational)
    }

    @Test fun errorOnlyOnEqualsAndTheNextKeyGoesOn() {
        val typed = type(D8, DIVIDE, D0)
        assertNull(typed.error)
        val failed = type(EQUALS, from = typed)
        assertEquals(CalcError.DIVISION_BY_ZERO, failed.error)
        val next = type(PLUS, D1, from = failed)
        assertNull(next.error)
        assertEquals("8 ÷ 0 + 1", shown(next))
    }

    @Test fun afterEquals() {
        val result = type(D2, PLUS, D3, EQUALS)
        assertTrue(result.showsResult)
        // A digit starts anew, an operator goes on, ⌫ clears, a second = does nothing
        assertEquals("7", shown(type(D7, from = result)))
        assertTrue(shown(type(TIMES, from = result)).trimEnd().endsWith("×"))
        assertEquals(WidgetState(), type(BACKSPACE, from = result))
        assertEquals(result, type(EQUALS, from = result))
    }

    @Test fun equalsWithNothingToEvaluateDoesNothing() {
        val s = type(D5, TIMES)
        assertEquals(s, type(EQUALS, from = s))
        assertEquals(WidgetState(), type(EQUALS))
    }

    @Test fun keysAsTheAppsEditor() {
        assertEquals("−", shown(type(MINUS)))
        assertEquals("", shown(type(TIMES)))
        assertEquals("5 ÷ ", shown(type(D5, TIMES, DIVIDE)))
        assertEquals("5 × (−3)", shown(type(D5, TIMES, D3, SIGN)))
        assertEquals("7", shown(type(D0, D7)))
        assertEquals("0,", shown(type(POINT)))
        assertEquals(WidgetState(), type(D1, D2, CLEAR))
    }

    @Test fun limitedLength() {
        val keys = Array(150) { D9 }
        // Grouping adds two characters at a time: the line stops just short of the limit
        assertTrue(shown(type(*keys)).length >= WidgetCalculator.MAX_CHARACTERS - 2)
        assertTrue(shown(type(*keys)).length <= WidgetCalculator.MAX_CHARACTERS)
    }

    @Test fun stateGoesThereAndBack() {
        for (s in listOf(WidgetState(), type(D1, PLUS), type(D2, PLUS, D3, EQUALS), type(D8, DIVIDE, D0, EQUALS))) {
            val back = WidgetState.decode(s.encode())
            assertEquals(s.tokens, back.tokens)
            assertEquals(s.result?.rational, back.result?.rational)
            assertEquals(s.error, back.error)
        }
        assertEquals(WidgetState(), WidgetState.decode(null))
        assertEquals(WidgetState(), WidgetState.decode("garbage"))
    }
}
