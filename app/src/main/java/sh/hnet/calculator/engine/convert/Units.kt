package sh.hnet.calculator.engine.convert

import sh.hnet.calculator.engine.number.Calc
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real

/** The converter's categories in the order of SPEC §7.1. */
enum class Category { LENGTH, MASS, TEMPERATURE, VOLUME, AREA, SPEED, TIME, DATA, FUEL, PRESSURE, ENERGY, POWER, ANGLE, FREQUENCY }

/** The unit sheet's groups (SPEC §7.3); Data has its own. */
enum class UnitGroup { METRIC, IMPERIAL_US, OTHER, DECIMAL, BINARY, BITS }

/**
 * How a unit relates to its category's base unit. Linear: base = value × factor. Temperatures
 * (base kelvin): base = (value + offset) × factor. Fuel consumption (base km/l): l/100 km is
 * inverse, base = factor / value.
 */
sealed interface Scale {
    data class Linear(val factor: Real) : Scale
    data class Affine(val offset: Rational, val factor: Rational) : Scale
    data class Inverse(val factor: Rational) : Scale
}

/** A unit: [id] names its strings (`unit_<category>_<id>`), [symbol] is the same in both languages. */
data class UnitDef(val category: Category, val id: String, val symbol: String, val group: UnitGroup, val scale: Scale)

/**
 * The units of SPEC §7.5 with factors that are exact where the definitions are (international
 * yard and pound 1959, US gallon 231 in³, imperial gallon 4.54609 l, thermochemical calorie,
 * International Table BTU, conventional mmHg 133.322387415 Pa; NIST SP 811, BIPM SI Brochure).
 * Only the radian is irrational (a turn is 2π).
 */
object Units {
    private fun r(text: String): Rational = Rational.parse(text)
    private fun frac(a: String, b: String): Rational = r(a) / r(b)

    private fun linear(c: Category, id: String, symbol: String, group: UnitGroup, factor: Rational) =
        UnitDef(c, id, symbol, group, Scale.Linear(Real.of(factor)))

    // Shared definitions
    private val INCH = r("0.0254")
    private val FOOT = r("0.3048")
    private val YARD = r("0.9144")
    private val MILE = r("1609.344")
    private val NAUTICAL_MILE = r("1852")
    private val POUND = r("0.45359237")
    private val US_GALLON = r("0.003785411784")
    private val IMPERIAL_GALLON = r("0.00454609")
    private val US_FLUID_OUNCE = US_GALLON / Rational.of(128)
    private val IMPERIAL_FLUID_OUNCE = IMPERIAL_GALLON / Rational.of(160)
    private val STANDARD_GRAVITY = r("9.80665")
    private val POUND_FORCE = POUND * STANDARD_GRAVITY
    private val MM_HG = r("133.322387415")
    private val BTU = r("1055.05585262")
    private val DAY = r("86400")

    val all: List<UnitDef> = buildList {
        val m = UnitGroup.METRIC
        val i = UnitGroup.IMPERIAL_US
        val o = UnitGroup.OTHER

        Category.LENGTH.let { c ->
            add(linear(c, "millimeter", "mm", m, r("0.001")))
            add(linear(c, "centimeter", "cm", m, r("0.01")))
            add(linear(c, "meter", "m", m, Rational.ONE))
            add(linear(c, "kilometer", "km", m, r("1000")))
            add(linear(c, "inch", "in", i, INCH))
            add(linear(c, "foot", "ft", i, FOOT))
            add(linear(c, "yard", "yd", i, YARD))
            add(linear(c, "mile", "mi", i, MILE))
            add(linear(c, "nautical_mile", "NM", o, NAUTICAL_MILE))
        }
        Category.MASS.let { c ->
            add(linear(c, "milligram", "mg", m, r("0.000001")))
            add(linear(c, "gram", "g", m, r("0.001")))
            add(linear(c, "kilogram", "kg", m, Rational.ONE))
            add(linear(c, "metric_ton", "t", m, r("1000")))
            add(linear(c, "ounce", "oz", i, POUND / Rational.of(16)))
            add(linear(c, "pound", "lb", i, POUND))
            add(linear(c, "stone", "st", i, POUND * Rational.of(14)))
            add(linear(c, "short_ton", "short ton", i, POUND * Rational.of(2000)))
            add(linear(c, "long_ton", "long ton", i, POUND * Rational.of(2240)))
        }
        Category.TEMPERATURE.let { c ->
            add(UnitDef(c, "celsius", "°C", m, Scale.Affine(r("273.15"), Rational.ONE)))
            add(UnitDef(c, "fahrenheit", "°F", i, Scale.Affine(r("459.67"), Rational.of(5, 9))))
            add(UnitDef(c, "kelvin", "K", m, Scale.Affine(Rational.ZERO, Rational.ONE)))
        }
        Category.VOLUME.let { c ->
            add(linear(c, "milliliter", "ml", m, r("0.000001")))
            add(linear(c, "liter", "l", m, r("0.001")))
            add(linear(c, "cubic_meter", "m³", m, Rational.ONE))
            add(linear(c, "teaspoon_us", "tsp", i, US_FLUID_OUNCE / Rational.of(6)))
            add(linear(c, "tablespoon_us", "tbsp", i, US_FLUID_OUNCE / Rational.of(2)))
            add(linear(c, "fluid_ounce_us", "US fl oz", i, US_FLUID_OUNCE))
            add(linear(c, "fluid_ounce_imperial", "imp fl oz", i, IMPERIAL_FLUID_OUNCE))
            add(linear(c, "cup_us", "cup", i, US_FLUID_OUNCE * Rational.of(8)))
            add(linear(c, "pint_us", "US pt", i, US_GALLON / Rational.of(8)))
            add(linear(c, "pint_imperial", "imp pt", i, IMPERIAL_GALLON / Rational.of(8)))
            add(linear(c, "gallon_us", "US gal", i, US_GALLON))
            add(linear(c, "gallon_imperial", "imp gal", i, IMPERIAL_GALLON))
            add(linear(c, "cubic_inch", "in³", i, INCH.pow(3)))
            add(linear(c, "cubic_foot", "ft³", i, FOOT.pow(3)))
        }
        Category.AREA.let { c ->
            add(linear(c, "square_millimeter", "mm²", m, r("0.000001")))
            add(linear(c, "square_centimeter", "cm²", m, r("0.0001")))
            add(linear(c, "square_meter", "m²", m, Rational.ONE))
            add(linear(c, "hectare", "ha", m, r("10000")))
            add(linear(c, "square_kilometer", "km²", m, r("1000000")))
            add(linear(c, "square_inch", "in²", i, INCH.pow(2)))
            add(linear(c, "square_foot", "ft²", i, FOOT.pow(2)))
            add(linear(c, "square_yard", "yd²", i, YARD.pow(2)))
            add(linear(c, "acre", "ac", i, FOOT.pow(2) * Rational.of(43560)))
            add(linear(c, "square_mile", "mi²", i, MILE.pow(2)))
        }
        Category.SPEED.let { c ->
            add(linear(c, "meter_per_second", "m/s", m, Rational.ONE))
            add(linear(c, "kilometer_per_hour", "km/h", m, frac("1000", "3600")))
            add(linear(c, "mile_per_hour", "mph", i, MILE / Rational.of(3600)))
            add(linear(c, "knot", "kn", o, NAUTICAL_MILE / Rational.of(3600)))
            add(linear(c, "foot_per_second", "ft/s", i, FOOT))
        }
        Category.TIME.let { c ->
            add(linear(c, "millisecond", "ms", m, r("0.001")))
            add(linear(c, "second", "s", m, Rational.ONE))
            add(linear(c, "minute", "min", o, r("60")))
            add(linear(c, "hour", "h", o, r("3600")))
            add(linear(c, "day", "d", o, DAY))
            add(linear(c, "week", "wk", o, DAY * Rational.of(7)))
            // D5: the Gregorian year, 365.2425 days
            add(linear(c, "year", "yr", o, DAY * r("365.2425")))
        }
        Category.DATA.let { c ->
            val d = UnitGroup.DECIMAL
            val b = UnitGroup.BINARY
            val bits = UnitGroup.BITS
            add(linear(c, "bit", "bit", bits, Rational.ONE))
            add(linear(c, "byte", "B", d, Rational.of(8)))
            add(linear(c, "kilobyte", "kB", d, Rational.of(8_000)))
            add(linear(c, "megabyte", "MB", d, Rational.of(8_000_000)))
            add(linear(c, "gigabyte", "GB", d, Rational.of(8_000_000_000)))
            add(linear(c, "terabyte", "TB", d, Rational.of(8_000_000_000_000)))
            add(linear(c, "kibibyte", "KiB", b, Rational.of(8L shl 10)))
            add(linear(c, "mebibyte", "MiB", b, Rational.of(8L shl 20)))
            add(linear(c, "gibibyte", "GiB", b, Rational.of(8L shl 30)))
            add(linear(c, "tebibyte", "TiB", b, Rational.of(8L shl 40)))
            add(linear(c, "kilobit", "kbit", bits, Rational.of(1_000)))
            add(linear(c, "megabit", "Mbit", bits, Rational.of(1_000_000)))
            add(linear(c, "gigabit", "Gbit", bits, Rational.of(1_000_000_000)))
        }
        Category.FUEL.let { c ->
            // Base: kilometers per liter
            add(UnitDef(c, "liter_per_100km", "l/100 km", m, Scale.Inverse(Rational.of(100))))
            add(UnitDef(c, "kilometer_per_liter", "km/l", m, Scale.Linear(Real.ONE)))
            add(linear(c, "mpg_us", "mpg (US)", i, MILE / Rational.of(1000) / (US_GALLON * Rational.of(1000))))
            add(linear(c, "mpg_imperial", "mpg (imp)", i, MILE / Rational.of(1000) / (IMPERIAL_GALLON * Rational.of(1000))))
        }
        Category.PRESSURE.let { c ->
            add(linear(c, "pascal", "Pa", m, Rational.ONE))
            add(linear(c, "hectopascal", "hPa", m, r("100")))
            add(linear(c, "kilopascal", "kPa", m, r("1000")))
            add(linear(c, "bar", "bar", m, r("100000")))
            add(linear(c, "millibar", "mbar", m, r("100")))
            add(linear(c, "atmosphere", "atm", o, r("101325")))
            add(linear(c, "psi", "psi", i, POUND_FORCE / INCH.pow(2)))
            add(linear(c, "mmhg", "mmHg", o, MM_HG))
            add(linear(c, "inhg", "inHg", i, MM_HG * r("25.4")))
        }
        Category.ENERGY.let { c ->
            add(linear(c, "joule", "J", m, Rational.ONE))
            add(linear(c, "kilojoule", "kJ", m, r("1000")))
            add(linear(c, "calorie", "cal", o, r("4.184")))
            add(linear(c, "kilocalorie", "kcal", o, r("4184")))
            add(linear(c, "watt_hour", "Wh", m, r("3600")))
            add(linear(c, "kilowatt_hour", "kWh", m, r("3600000")))
            add(linear(c, "electronvolt", "eV", o, r("1.602176634E-19")))
            add(linear(c, "btu", "BTU", i, BTU))
        }
        Category.POWER.let { c ->
            add(linear(c, "watt", "W", m, Rational.ONE))
            add(linear(c, "kilowatt", "kW", m, r("1000")))
            add(linear(c, "megawatt", "MW", m, r("1000000")))
            // 75 kgf·m/s
            add(linear(c, "metric_horsepower", "PS", o, STANDARD_GRAVITY * Rational.of(75)))
            // 550 ft·lbf/s
            add(linear(c, "mechanical_horsepower", "hp", i, FOOT * POUND_FORCE * Rational.of(550)))
            add(linear(c, "btu_per_hour", "BTU/h", i, BTU / Rational.of(3600)))
        }
        Category.ANGLE.let { c ->
            // Base: the turn, so that only the radian is irrational
            add(linear(c, "degree", "°", o, Rational.of(1, 360)))
            add(UnitDef(c, "radian", "rad", m, Scale.Linear(Calc.inverse(Calc.multiply(Real.of(2), Calc.pi)))))
            add(linear(c, "gradian", "gon", o, Rational.of(1, 400)))
            add(linear(c, "arcminute", "′", o, Rational.of(1, 21_600)))
            add(linear(c, "arcsecond", "″", o, Rational.of(1, 1_296_000)))
            add(linear(c, "turn", "tr", o, Rational.ONE))
        }
        Category.FREQUENCY.let { c ->
            add(linear(c, "hertz", "Hz", m, Rational.ONE))
            add(linear(c, "kilohertz", "kHz", m, r("1000")))
            add(linear(c, "megahertz", "MHz", m, r("1000000")))
            add(linear(c, "gigahertz", "GHz", m, r("1000000000")))
            add(linear(c, "rpm", "rpm", o, Rational.of(1, 60)))
        }
    }

    fun of(category: Category): List<UnitDef> = all.filter { it.category == category }

    fun find(category: Category, id: String): UnitDef? = all.firstOrNull { it.category == category && it.id == id }

    /** The pair a category starts with (SPEC §7.5). */
    val defaultPairs: Map<Category, Pair<String, String>> = mapOf(
        Category.LENGTH to ("centimeter" to "inch"),
        Category.MASS to ("kilogram" to "pound"),
        Category.TEMPERATURE to ("celsius" to "fahrenheit"),
        Category.VOLUME to ("liter" to "gallon_us"),
        Category.AREA to ("square_meter" to "square_foot"),
        Category.SPEED to ("kilometer_per_hour" to "mile_per_hour"),
        Category.TIME to ("minute" to "hour"),
        Category.DATA to ("megabyte" to "mebibyte"),
        Category.FUEL to ("liter_per_100km" to "mpg_us"),
        Category.PRESSURE to ("bar" to "psi"),
        Category.ENERGY to ("kilocalorie" to "kilojoule"),
        Category.POWER to ("kilowatt" to "metric_horsepower"),
        Category.ANGLE to ("degree" to "radian"),
        Category.FREQUENCY to ("hertz" to "rpm"),
    )

    /**
     * [value] in [from] as a number in [to] (same category). Exact where both factors are. Zero in
     * an inverse unit (0 l/100 km) is a [sh.hnet.calculator.engine.number.CalcError.DIVISION_BY_ZERO]: the UI
     * says that zero can't be converted (SPEC §7.5).
     */
    fun convert(value: Real, from: UnitDef, to: UnitDef): Real {
        require(from.category == to.category)
        if (from == to) return value
        return fromBase(toBase(value, from.scale), to.scale)
    }

    private fun toBase(v: Real, s: Scale): Real = when (s) {
        is Scale.Linear -> Calc.multiply(v, s.factor)
        is Scale.Affine -> Calc.multiply(Calc.add(v, Real.of(s.offset)), Real.of(s.factor))
        is Scale.Inverse -> Calc.divide(Real.of(s.factor), v)
    }

    private fun fromBase(b: Real, s: Scale): Real = when (s) {
        is Scale.Linear -> Calc.divide(b, s.factor)
        is Scale.Affine -> Calc.subtract(Calc.divide(b, Real.of(s.factor)), Real.of(s.offset))
        is Scale.Inverse -> Calc.divide(Real.of(s.factor), b)
    }
}
