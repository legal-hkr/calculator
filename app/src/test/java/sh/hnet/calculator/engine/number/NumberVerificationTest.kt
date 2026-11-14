package sh.hnet.calculator.engine.number

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * The engine against reference values computed independently with mpmath at 1100 digits or more
 * (SILNIK §10). The case file is generated outside the repository (it is large); the test runs only
 * when the environment variable CALC_VERIFY_NUMBERS names it, and is skipped otherwise.
 *
 * A line: op|x|y|p|truth, truth = round(value · 10^(p+5)). The engine passes when its approximation
 * at p places is within one unit of the last place.
 */
class NumberVerificationTest {
    private fun q(text: String): Real = Real.of(Rational.parse(text))

    private fun compute(op: String, x: Real, y: Real?): Real = when (op) {
        "sqrt" -> Calc.sqrt(x)
        "exp" -> Calc.exp(x)
        "ln" -> Calc.ln(x)
        "log10" -> Calc.log10(x)
        "exp10" -> Calc.exp10(x)
        "sin_r" -> Calc.sin(x, AngleUnit.RADIANS)
        "cos_r" -> Calc.cos(x, AngleUnit.RADIANS)
        "tan_r" -> Calc.tan(x, AngleUnit.RADIANS)
        "sin_d" -> Calc.sin(x, AngleUnit.DEGREES)
        "cos_d" -> Calc.cos(x, AngleUnit.DEGREES)
        "tan_d" -> Calc.tan(x, AngleUnit.DEGREES)
        "sin_g" -> Calc.sin(x, AngleUnit.GRADIANS)
        "asin_r" -> Calc.asin(x, AngleUnit.RADIANS)
        "acos_r" -> Calc.acos(x, AngleUnit.RADIANS)
        "atan_r" -> Calc.atan(x, AngleUnit.RADIANS)
        "asin_d" -> Calc.asin(x, AngleUnit.DEGREES)
        "acos_g" -> Calc.acos(x, AngleUnit.GRADIANS)
        "atan_d" -> Calc.atan(x, AngleUnit.DEGREES)
        "pow" -> Calc.power(x, y!!)
        "root" -> Calc.root(y!!, x)
        "pi_times" -> Calc.multiply(Calc.pi, x)
        "sqrt_plus_ln" -> Calc.add(Calc.sqrt(x), Calc.ln(y!!))
        "inv_sqrt" -> Calc.inverse(Calc.sqrt(x))
        "cancel" -> Calc.subtract(Calc.add(Calc.sqrt(x), Real.ONE), Calc.sqrt(x))
        "exp_ln" -> Calc.exp(Calc.ln(x))
        "div_pi" -> Calc.divide(x, Calc.pi)
        "mod_pi" -> Calc.mod(x, Calc.pi)
        else -> error("unknown op $op")
    }

    @Test
    fun againstMpmath() {
        val path = System.getenv("CALC_VERIFY_NUMBERS")
        assumeTrue("CALC_VERIFY_NUMBERS not set", path != null)
        val shift = BigInteger.TEN.pow(5)
        val tolerance = shift + BigInteger.ONE
        val failures = mutableListOf<String>()
        var count = 0
        val started = System.nanoTime()
        val slow = mutableListOf<Pair<Long, String>>()
        File(path!!).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val (op, xs, ys, ps, truthText) = line.split('|')
            val p = ps.toInt()
            count++
            val t0 = System.nanoTime()
            try {
                val value = compute(op, q(xs), ys.takeIf { it.isNotEmpty() }?.let { q(it) })
                val diff = (value.approx(p) * shift - BigInteger(truthText)).abs()
                if (diff > tolerance) failures += "$op($xs, $ys) at $p: off by ${diff.divide(shift)} units"
            } catch (e: Exception) {
                failures += "$op($xs, $ys) at $p: $e"
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            slow += ms to "$op($xs, $ys) at $p"
        }
        val total = (System.nanoTime() - started) / 1_000_000
        println("Verified $count cases in $total ms, ${failures.size} failed")
        slow.sortedByDescending { it.first }.take(10).forEach { println("slow: ${it.first} ms ${it.second}") }
        failures.take(40).forEach { println("FAIL $it") }
        assertTrue("${failures.size} of $count cases failed; first: ${failures.take(5)}", failures.isEmpty())
    }
}
