package sh.hnet.calculator.engine.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.hnet.calculator.engine.expr.Parser
import sh.hnet.calculator.engine.expr.Paste
import sh.hnet.calculator.engine.expr.Evaluation
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.CalcException
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Conversions against published values (NIST SP 811 appendix B, the definitions themselves), not
 * against the factors in Units.
 */
class UnitsTest {
    private fun u(c: Category, id: String): UnitDef {
        val unit = Units.find(c, id)
        assertNotNull("no unit $id", unit)
        return unit!!
    }
    private fun q(text: String): Real = Real.of(Rational.parse(text))

    private fun exact(expected: String, value: String, c: Category, from: String, to: String) {
        val v = Units.convert(q(value), u(c, from), u(c, to))
        assertEquals("$value $from in $to", Rational.parse(expected), v.rational)
    }

    /** Within one unit of the last place of [expected]. */
    private fun close(expected: String, value: String, c: Category, from: String, to: String) {
        val v = Units.convert(q(value), u(c, from), u(c, to))
        val places = expected.substringAfter('.', "").length
        val diff = (v.approx(places) - BigDecimal(expected).unscaledValue()).abs()
        assertTrue("$value $from in $to: $expected vs ${v.decimal(places)}", diff <= BigInteger.ONE)
    }

    @Test fun specAcceptance() {
        // SPEC §23 stage 5: 5 × 12 in → 152.4 cm; −40 °C = −40 °F
        val input = (Parser.evaluate(Paste.read("5*12", false)!!) as Evaluation.Value).value
        assertEquals(Rational.parse("152.4"), Units.convert(input, u(Category.LENGTH, "inch"), u(Category.LENGTH, "centimeter")).rational)
        exact("-40", "-40", Category.TEMPERATURE, "celsius", "fahrenheit")
        try {
            Units.convert(Real.ZERO, u(Category.FUEL, "liter_per_100km"), u(Category.FUEL, "mpg_us")).approx(5)
            fail("zero l/100 km converted")
        } catch (e: CalcException) {
            assertEquals(CalcError.DIVISION_BY_ZERO, e.error)
        }
    }

    @Test fun exactDefinitions() {
        exact("1.609344", "1", Category.LENGTH, "mile", "kilometer")
        exact("1.852", "1", Category.LENGTH, "nautical_mile", "kilometer")
        exact("453.59237", "1", Category.MASS, "pound", "gram")
        exact("6.35029318", "1", Category.MASS, "stone", "kilogram")
        exact("1016.0469088", "1", Category.MASS, "long_ton", "kilogram")
        exact("907.18474", "1", Category.MASS, "short_ton", "kilogram")
        exact("212", "100", Category.TEMPERATURE, "celsius", "fahrenheit")
        exact("-273.15", "0", Category.TEMPERATURE, "kelvin", "celsius")
        exact("3.785411784", "1", Category.VOLUME, "gallon_us", "liter")
        exact("4.54609", "1", Category.VOLUME, "gallon_imperial", "liter")
        exact("14.78676478125", "1", Category.VOLUME, "tablespoon_us", "milliliter")
        exact("4.92892159375", "1", Category.VOLUME, "teaspoon_us", "milliliter")
        exact("236.5882365", "1", Category.VOLUME, "cup_us", "milliliter")
        exact("568.26125", "1", Category.VOLUME, "pint_imperial", "milliliter")
        exact("28.4130625", "1", Category.VOLUME, "fluid_ounce_imperial", "milliliter")
        exact("16.387064", "1", Category.VOLUME, "cubic_inch", "milliliter")
        exact("4046.8564224", "1", Category.AREA, "acre", "square_meter")
        exact("2.589988110336", "1", Category.AREA, "square_mile", "square_kilometer")
        exact("96.56064", "60", Category.SPEED, "mile_per_hour", "kilometer_per_hour")
        exact("1.852", "1", Category.SPEED, "knot", "kilometer_per_hour")
        exact("365.2425", "1", Category.TIME, "year", "day")
        exact("1.073741824", "1", Category.DATA, "gibibyte", "gigabyte")
        exact("0.95367431640625", "1", Category.DATA, "megabyte", "mebibyte")
        exact("8", "1", Category.DATA, "megabyte", "megabit")
        exact("3386.388640341", "1", Category.PRESSURE, "inhg", "pascal")
        exact("101325", "1", Category.PRESSURE, "atmosphere", "pascal")
        exact("3600", "1", Category.ENERGY, "kilowatt_hour", "kilojoule")
        exact("4.184", "1", Category.ENERGY, "kilocalorie", "kilojoule")
        exact("1.05505585262", "1", Category.ENERGY, "btu", "kilojoule")
        exact("735.49875", "1", Category.POWER, "metric_horsepower", "watt")
        exact("745.69987158227022", "1", Category.POWER, "mechanical_horsepower", "watt")
        exact("60", "1", Category.ANGLE, "degree", "arcminute")
        exact("400", "1", Category.ANGLE, "turn", "gradian")
        exact("1", "60", Category.FREQUENCY, "rpm", "hertz")
    }

    @Test fun publishedRoundedValues() {
        // NIST SP 811 B.8: psi 6.894757 E+03 Pa; rad 57.295 779 513 082 3°
        close("6894.757293168361", "1", Category.PRESSURE, "psi", "pascal")
        close("57.29577951308232", "1", Category.ANGLE, "radian", "degree")
        close("0.017453292519943", "1", Category.ANGLE, "degree", "radian")
        // 235.2146 / (l/100 km) = mpg (US); 282.4809 for imperial
        close("23.521458333", "10", Category.FUEL, "liter_per_100km", "mpg_us")
        close("28.248093633", "10", Category.FUEL, "liter_per_100km", "mpg_imperial")
        close("4.251437075", "10", Category.FUEL, "mpg_us", "kilometer_per_liter")
        close("10", "23.5214583333333", Category.FUEL, "mpg_us", "liter_per_100km")
        close("759.999891726", "1", Category.PRESSURE, "atmosphere", "mmhg")
    }

    @Test fun everyUnitGoesThereAndBack() {
        val x = q("12.345")
        for (c in Category.entries) {
            val units = Units.of(c)
            for (a in units) for (b in units) {
                val back = Units.convert(Units.convert(x, a, b), b, a)
                val diff = (back.approx(30) - x.approx(30)).abs()
                assertTrue("${a.id} → ${b.id} → back", diff <= BigInteger.TWO)
                val sa = a.scale
                val sb = b.scale
                if ((sa !is Scale.Linear || sa.factor.isExact) && sb is Scale.Linear && sb.factor.isExact) {
                    assertTrue("${a.id} → ${b.id} exact", back.isExact)
                }
            }
        }
    }

    @Test fun defaultPairsExist() {
        for (c in Category.entries) {
            val (a, b) = Units.defaultPairs.getValue(c)
            assertNotNull(Units.find(c, a))
            assertNotNull(Units.find(c, b))
        }
    }
}
