package sh.hnet.calculator.engine.programmer

import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.expr.opens
import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real
import java.math.BigInteger

/** The input base of the Programmer (SPEC §8.3). */
enum class Base(val radix: Int) { HEX(16), DEC(10), OCT(8), BIN(2) }

/**
 * A word of [bits] bits, read [signed] (two's complement) or not (SILNIK §5, SPEC §8.2). Values are
 * kept normalized: signed in [−2^(bits−1), 2^(bits−1)), unsigned in [0, 2^bits).
 */
data class Word(val bits: Int = 64, val signed: Boolean = true) {
    init {
        require(bits in setOf(8, 16, 32, 64))
    }

    val mask: BigInteger get() = BigInteger.ONE.shiftLeft(bits) - BigInteger.ONE
    val min: BigInteger get() = if (signed) BigInteger.ONE.shiftLeft(bits - 1).negate() else BigInteger.ZERO
    val max: BigInteger get() = if (signed) BigInteger.ONE.shiftLeft(bits - 1) - BigInteger.ONE else mask

    /** The bit pattern of a normalized value, as an unsigned number. */
    fun pattern(v: BigInteger): BigInteger = v.and(mask)

    /** A bit pattern read as this word's value. */
    fun fromPattern(p: BigInteger): BigInteger {
        val bitsOnly = p.and(mask)
        return if (signed && bitsOnly.testBit(bits - 1)) bitsOnly - BigInteger.ONE.shiftLeft(bits) else bitsOnly
    }

    /** Any integer wrapped into the word; [Wrapped.wrapped] when that changed it. */
    fun wrap(v: BigInteger): Wrapped {
        val w = fromPattern(v)
        return Wrapped(w, w != v)
    }
}

data class Wrapped(val value: BigInteger, val wrapped: Boolean)

/** What a Programmer expression evaluates to. */
sealed interface ProgrammerEvaluation {
    /** [wrapped]: some step overflowed the word and wrapped (the note of SPEC §8.5). */
    data class Value(val value: BigInteger, val wrapped: Boolean) : ProgrammerEvaluation

    data object Incomplete : ProgrammerEvaluation

    data class Failure(val error: CalcError, val position: Int? = null) : ProgrammerEvaluation
}

/**
 * The Programmer's integer arithmetic (SILNIK §5, SPEC §8.4). Precedence as in C, from the tightest:
 * NOT and unary minus; × ÷ mod (and implied multiplication); + −; << >> RoL RoR; AND NAND; XOR;
 * OR NOR. Every step is wrapped into the word. ÷ cuts toward zero, mod takes the dividend's sign
 * (D2). >> is arithmetic for signed words, logical for unsigned; rotations stay within the word.
 * Literals are digits in the input base; one that is too long for the word wraps.
 */
object Programmer {

    fun evaluate(tokens: List<Token>, base: Base, word: Word): ProgrammerEvaluation {
        val reader = Reader(tokens, base, word)
        return try {
            if (tokens.isEmpty()) throw IncompleteSignal()
            val v = reader.expression(0)
            if (reader.i < tokens.size) throw SyntaxSignal(reader.i)
            ProgrammerEvaluation.Value(v, reader.wrapped)
        } catch (_: IncompleteSignal) {
            ProgrammerEvaluation.Incomplete
        } catch (e: SyntaxSignal) {
            ProgrammerEvaluation.Failure(CalcError.SYNTAX, e.position)
        } catch (_: DivisionByZero) {
            ProgrammerEvaluation.Failure(CalcError.DIVISION_BY_ZERO)
        }
    }

    /** As the decimal preview: trailing operators and openings dropped, the rest closed. */
    fun preview(tokens: List<Token>, base: Base, word: Word): ProgrammerEvaluation {
        var end = tokens.size
        while (end > 0) {
            val last = tokens[end - 1]
            if (last is Token.Operator || last.opens || last == Token.Not) end-- else break
        }
        return evaluate(tokens.subList(0, end), base, word)
    }

    private class IncompleteSignal : RuntimeException()
    private class SyntaxSignal(val position: Int) : RuntimeException()
    private class DivisionByZero : RuntimeException()

    private const val OR = 10
    private const val XOR = 20
    private const val AND = 30
    private const val SHIFT = 40
    private const val ADD = 50
    private const val MUL = 60
    private const val UNARY = 70

    private fun power(op: Op): Int? = when (op) {
        Op.OR, Op.NOR -> OR
        Op.XOR -> XOR
        Op.AND, Op.NAND -> AND
        Op.SHL, Op.SHR, Op.ROL, Op.ROR -> SHIFT
        Op.PLUS, Op.MINUS -> ADD
        Op.TIMES, Op.DIVIDE, Op.MOD -> MUL
        Op.POWER, Op.ROOT -> null
    }

    private class Reader(val tokens: List<Token>, val base: Base, val word: Word) {
        var i = 0
        var wrapped = false

        fun wrap(v: BigInteger): BigInteger {
            val w = word.wrap(v)
            if (w.wrapped) wrapped = true
            return w.value
        }

        fun expression(minPower: Int): BigInteger {
            var left = prefix()
            while (i < tokens.size) {
                val t = tokens[i]
                left = when {
                    t is Token.Operator -> {
                        val p = power(t.op) ?: throw SyntaxSignal(i)
                        if (p < minPower) break
                        i++
                        apply(t.op, left, expression(p + 1))
                    }
                    t is Token.Digit || t == Token.Open || t is Token.Value || t == Token.Not -> {
                        // Implied multiplication, at the level of ×
                        if (MUL < minPower) break
                        apply(Op.TIMES, left, expression(MUL + 1))
                    }
                    else -> break
                }
            }
            return left
        }

        fun prefix(): BigInteger {
            if (i >= tokens.size) throw IncompleteSignal()
            return when (val t = tokens[i]) {
                is Token.Digit -> literal()
                is Token.Value -> {
                    i++
                    val r = t.value.rational
                    if (r == null || !r.isInteger) throw SyntaxSignal(i - 1)
                    wrap(r.num)
                }
                Token.Open -> {
                    i++
                    if (i < tokens.size && tokens[i] == Token.Close) throw IncompleteSignal()
                    val v = expression(0)
                    if (i < tokens.size && tokens[i] == Token.Close) i++
                    v
                }
                Token.Not -> {
                    i++
                    word.fromPattern(word.pattern(expression(UNARY)).not())
                }
                is Token.Operator -> when (t.op) {
                    Op.MINUS -> {
                        i++
                        // A minus right before a DEC number belongs to it: −128 fits a signed byte
                        if (base == Base.DEC && i < tokens.size && tokens[i] is Token.Digit) wrap(digits().negate())
                        else wrap(expression(UNARY).negate())
                    }
                    Op.PLUS -> {
                        i++
                        expression(UNARY)
                    }
                    else -> throw SyntaxSignal(i)
                }
                Token.Close -> throw IncompleteSignal()
                else -> throw SyntaxSignal(i)
            }
        }

        fun literal(): BigInteger {
            val v = digits()
            // A literal is a bit pattern in HEX, OCT, BIN; a number in DEC
            return if (base == Base.DEC) wrap(v) else {
                if (v > word.mask) wrapped = true
                word.fromPattern(v)
            }
        }

        /** The digits at the cursor as a number, unwrapped. */
        fun digits(): BigInteger {
            var v = BigInteger.ZERO
            val radix = BigInteger.valueOf(base.radix.toLong())
            while (i < tokens.size) {
                val t = tokens[i] as? Token.Digit ?: break
                if (t.value >= base.radix) throw SyntaxSignal(i)
                v = v * radix + BigInteger.valueOf(t.value.toLong())
                i++
            }
            return v
        }

        fun apply(op: Op, a: BigInteger, b: BigInteger): BigInteger = when (op) {
            Op.PLUS -> wrap(a + b)
            Op.MINUS -> wrap(a - b)
            Op.TIMES -> wrap(a * b)
            Op.DIVIDE -> {
                if (b.signum() == 0) throw DivisionByZero()
                // BigInteger division cuts toward zero
                wrap(a.divide(b))
            }
            Op.MOD -> {
                if (b.signum() == 0) throw DivisionByZero()
                // The remainder takes the dividend's sign (D2)
                wrap(a.rem(b))
            }
            Op.AND -> word.fromPattern(word.pattern(a).and(word.pattern(b)))
            Op.OR -> word.fromPattern(word.pattern(a).or(word.pattern(b)))
            Op.XOR -> word.fromPattern(word.pattern(a).xor(word.pattern(b)))
            Op.NAND -> word.fromPattern(word.pattern(a).and(word.pattern(b)).not())
            Op.NOR -> word.fromPattern(word.pattern(a).or(word.pattern(b)).not())
            Op.SHL -> word.fromPattern(word.pattern(a).shiftLeft(shiftCount(b)))
            // Arithmetic for signed (a keeps its sign), logical for unsigned (a ≥ 0)
            Op.SHR -> word.fromPattern(a.shiftRight(shiftCount(b)))
            Op.ROL -> rotate(a, shiftCount(b) % word.bits)
            Op.ROR -> rotate(a, (word.bits - shiftCount(b) % word.bits) % word.bits)
            Op.POWER, Op.ROOT -> throw SyntaxSignal(i)
        }

        /** A shift by a negative count is read as a count of zero; beyond the word, the word's size. */
        fun shiftCount(b: BigInteger): Int = if (b.signum() <= 0) 0 else b.min(BigInteger.valueOf(word.bits.toLong())).toInt()

        fun rotate(a: BigInteger, k: Int): BigInteger {
            val p = word.pattern(a)
            return word.fromPattern(p.shiftLeft(k).or(p.shiftRight(word.bits - k)))
        }
    }

    // Writing values

    /**
     * A value in a base as the bases panel shows it (SPEC §8.3): DEC with the sign and the
     * language's grouping, the others as the word's bit pattern, HEX grouped by 4 with a space, OCT
     * by 3, BIN by nibbles. Digits A–F in capitals.
     */
    fun text(v: BigInteger, base: Base, word: Word, style: NumberStyle, grouping: Boolean = true): String = when (base) {
        Base.DEC -> {
            val digits = v.abs().toString()
            val grouped = if (grouping && style.grouping) Formatter.group(digits, style.groupSeparator) else digits
            if (v.signum() < 0) "${Formatter.MINUS}$grouped" else grouped
        }
        else -> {
            val digits = word.pattern(v).toString(base.radix).uppercase()
            if (!grouping) digits else groupBy(digits, if (base == Base.OCT) 3 else 4, ' ')
        }
    }

    /** For the clipboard: no grouping, ASCII minus. */
    fun copy(v: BigInteger, base: Base, word: Word): String =
        if (base == Base.DEC) v.toString() else word.pattern(v).toString(base.radix).uppercase()

    private fun groupBy(digits: String, size: Int, separator: Char): String {
        val sb = StringBuilder()
        val head = digits.length % size
        if (head > 0) sb.append(digits, 0, head)
        var k = head
        while (k < digits.length) {
            if (sb.isNotEmpty()) sb.append(separator)
            sb.append(digits, k, k + size)
            k += size
        }
        return sb.toString()
    }

    /**
     * The bits in rows of 16 (8 for an 8-bit word), highest first: each row's top bit index and its
     * bits as nibbles (SPEC §8.3).
     */
    fun binaryRows(v: BigInteger, word: Word): List<Pair<Int, String>> {
        val all = word.pattern(v).toString(2).padStart(word.bits, '0')
        val rowBits = if (word.bits == 8) 8 else 16
        return all.chunked(rowBits).mapIndexed { k, row -> (word.bits - 1 - k * rowBits) to row.chunked(4).joinToString(" ") }
    }

    /** The value with [bit] (0 = lowest) flipped (the bit sheet, SPEC §8.6). */
    fun toggleBit(v: BigInteger, bit: Int, word: Word): BigInteger = word.fromPattern(word.pattern(v).flipBit(bit))

    // Editing

    /** Digits of a value in a base, as the editor's elements (a negative DEC value with a sign). */
    fun literal(v: BigInteger, base: Base, word: Word): List<Token> {
        val text = if (base == Base.DEC) v.abs().toString() else word.pattern(v).toString(base.radix)
        val digits = text.map { Token.Digit(Character.digit(it, 16)) }
        return if (base == Base.DEC && v.signum() < 0) listOf(Token.Operator(Op.MINUS)) + digits else digits
    }

    /**
     * Whether [digit] may go in at the cursor (D7): valid in the base, and the number it lands in
     * still fits the word (as a bit pattern in HEX, OCT, BIN; in DEC up to the word's largest value,
     * or 2^(bits−1) for a signed word, the size of its most negative value).
     */
    fun accepts(expression: Expression, digit: Int, base: Base, word: Word): Boolean {
        if (digit >= base.radix) return false
        val tokens = expression.tokens
        var start = expression.cursor
        while (start > 0 && tokens[start - 1] is Token.Digit) start--
        var end = expression.cursor
        while (end < tokens.size && tokens[end] is Token.Digit) end++
        val digits = tokens.subList(start, expression.cursor) + Token.Digit(digit) + tokens.subList(expression.cursor, end)
        var v = BigInteger.ZERO
        for (d in digits) v = v * BigInteger.valueOf(base.radix.toLong()) + BigInteger.valueOf((d as Token.Digit).value.toLong())
        val limit = when {
            base != Base.DEC -> word.mask
            word.signed -> BigInteger.ONE.shiftLeft(word.bits - 1)
            else -> word.mask
        }
        return v <= limit
    }

    /**
     * The expression with every number written in another base, operators kept (SPEC §8.3). A HEX,
     * OCT or BIN pattern that is negative in a signed word becomes a negative DEC number, in
     * parentheses after an operator.
     */
    fun rebase(expression: Expression, from: Base, to: Base, word: Word): Expression {
        if (from == to) return expression
        val tokens = expression.tokens
        val out = mutableListOf<Token>()
        var cursor = expression.cursor
        var k = 0
        while (k < tokens.size) {
            if (tokens[k] !is Token.Digit) {
                if (k == expression.cursor) cursor = out.size
                out += tokens[k]
                k++
                continue
            }
            val start = k
            var v = BigInteger.ZERO
            while (k < tokens.size && tokens[k] is Token.Digit) {
                v = v * BigInteger.valueOf(from.radix.toLong()) + BigInteger.valueOf((tokens[k] as Token.Digit).value.toLong())
                k++
            }
            val value = if (from == Base.DEC) v else word.fromPattern(v)
            var digits = literal(value, to, word)
            if (digits.first() is Token.Operator) {
                // −8 after an operator goes in parentheses: 5 + (−8)
                val prev = out.lastOrNull()
                if (prev != null && !prev.opens) digits = listOf(Token.Open) + digits + Token.Close
            }
            if (expression.cursor in start until k) cursor = out.size + digits.size
            out += digits
        }
        if (expression.cursor == tokens.size) cursor = out.size
        return Expression(out, cursor)
    }

    /** A Programmer value as a whole number (Ans, a history entry). */
    fun asValue(v: BigInteger): Token.Value = Token.Value(Real.of(Rational.of(v)))
}
