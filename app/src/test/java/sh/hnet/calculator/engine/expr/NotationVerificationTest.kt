package sh.hnet.calculator.engine.expr

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.calculator.engine.number.AngleUnit
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * How the calculator reads its notation, against Wolfram Alpha (SILNIK §10). The case file comes
 * from a script outside the repository that queries Wolfram Alpha; the test runs only when
 * CALC_VERIFY_NOTATION names it. A line: ours|angle|query|wa_input|value|digits, where digits is
 * how many significant digits Wolfram Alpha gave (60 for an exact value).
 */
class NotationVerificationTest {

    @Test
    fun againstWolframAlpha() {
        val path = System.getenv("CALC_VERIFY_NOTATION")
        assumeTrue("CALC_VERIFY_NOTATION not set", path != null)
        val failures = mutableListOf<String>()
        var count = 0
        File(path!!).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split('|')
            val ours = parts[0]
            val angle = AngleUnit.valueOf(parts[1])
            val wa = BigDecimal(parts[parts.size - 2])
            val digits = parts.last().toInt()
            count++
            val tokens = Paste.read(ours, decimalComma = false)
            if (tokens == null) {
                failures += "$ours: unreadable"
                return@forEachLine
            }
            val e = Parser.evaluate(tokens, EvalContext(angle))
            if (e !is Evaluation.Value) {
                failures += "$ours: $e, Wolfram Alpha ${parts[2]} = $wa"
                return@forEachLine
            }
            // Places after the point to compare at: all but the last digit Wolfram Alpha gave
            val integerDigits = if (wa.signum() == 0) 1 else wa.precision() - wa.scale()
            val places = if (digits >= 60) 50 else maxOf(0, digits - 1 - integerDigits)
            val want = wa.setScale(places, RoundingMode.HALF_EVEN).unscaledValue()
            val got = e.value.approx(places)
            if ((got - want).abs() > BigInteger.TWO) {
                failures += "$ours = ${e.value.decimal(places)}, Wolfram Alpha ${parts[2]} = $wa"
            }
        }
        println("Compared $count expressions with Wolfram Alpha, ${failures.size} differ")
        failures.forEach { println("DIFF $it") }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
