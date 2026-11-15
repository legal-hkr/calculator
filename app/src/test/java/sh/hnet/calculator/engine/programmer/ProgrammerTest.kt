package sh.hnet.calculator.engine.programmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.CalcError
import java.math.BigInteger

class ProgrammerTest {
    private val s8 = Word(8, true)
    private val u8 = Word(8, false)
    private val s64 = Word(64, true)

    /** Tokens from a compact text: digits 0–9 A–F, + - * / %(mod) & | ^ < > (shifts) L R (rotations) N(NAND) O(NOR) ~(NOT) ( ). */
    private fun t(text: String): List<Token> = text.filterNot { it == ' ' }.map { c ->
        when (c) {
            in '0'..'9' -> Token.Digit(c - '0')
            in 'A'..'F' -> Token.Digit(c - 'A' + 10)
            '+' -> Token.Operator(Op.PLUS)
            '-' -> Token.Operator(Op.MINUS)
            '*' -> Token.Operator(Op.TIMES)
            '/' -> Token.Operator(Op.DIVIDE)
            '%' -> Token.Operator(Op.MOD)
            '&' -> Token.Operator(Op.AND)
            '|' -> Token.Operator(Op.OR)
            '^' -> Token.Operator(Op.XOR)
            'N' -> Token.Operator(Op.NAND)
            'O' -> Token.Operator(Op.NOR)
            '<' -> Token.Operator(Op.SHL)
            '>' -> Token.Operator(Op.SHR)
            'L' -> Token.Operator(Op.ROL)
            'R' -> Token.Operator(Op.ROR)
            '~' -> Token.Not
            '(' -> Token.Open
            ')' -> Token.Close
            else -> error("no token $c")
        }
    }

    private fun value(expected: Long, text: String, word: Word, base: Base = Base.DEC, wrapped: Boolean = false) {
        val e = Programmer.evaluate(t(text), base, word)
        assertTrue("$text gave $e", e is ProgrammerEvaluation.Value)
        e as ProgrammerEvaluation.Value
        assertEquals(text, BigInteger.valueOf(expected), e.value)
        assertEquals("wrapped: $text", wrapped, e.wrapped)
    }

    @Test fun wrapsIntoTheWord() {
        // SPEC §23 stage 4: 8-bit unsigned 200 + 100 = 44 with the note
        value(44, "200 + 100", u8, wrapped = true)
        value(-128, "127 + 1", s8, wrapped = true)
        value(-128, "-128 / -1", s8, wrapped = true)
        value(-128, "-128", s8)
        value(56, "0 - 200", u8, wrapped = true)
        value(-1, "FF", s8, Base.HEX)
        value(-1, "1FF", s8, Base.HEX, wrapped = true)
    }

    @Test fun precedenceAsInC() {
        value(24, "1 + 2 < 3", s64)
        value(10, "6 & 3 | 8", s64)
        value(1, "1 | 2 ^ 3", s64)
        value(14, "2 + 3 * 4", s64)
        value(-3, "~2", s64)
        value(5, "~2 + 8", s64)
    }

    @Test fun divisionAndRemainder() {
        value(-3, "-7 / 2", s64)
        value(-1, "-7 % 3", s64)
        value(1, "7 % -3", s64)
        assertEquals(ProgrammerEvaluation.Failure(CalcError.DIVISION_BY_ZERO), Programmer.evaluate(t("1/0"), Base.DEC, s64))
    }

    @Test fun shiftsAndRotations() {
        value(-4, "-8 > 1", s8)
        value(0x0F, "F0 > 4", u8, Base.HEX)
        value(-8, "F0 > 1", s8, Base.HEX)
        value(0x03, "81 L 1", u8, Base.HEX)
        value(0xC0, "81 R 1", u8, Base.HEX)
        value(0x81, "81 L 8", u8, Base.HEX)
        value(0, "1 < 8", u8)
        // Review 15 Nov 2026: a rotation by the word size or more goes round again
        value(0x02, "1 L 9", u8, Base.HEX)
        value(0xC0, "81 R 9", u8, Base.HEX)
        value(0x81, "81 R 8", u8, Base.HEX)
    }

    @Test fun logic() {
        value(-1, "~0", s8)
        value(0xFF, "~0", u8)
        value(0xFE, "3 N 1", u8)
        value(0xF8, "3 O 4", u8)
    }

    @Test fun incompleteAndPreview() {
        assertEquals(ProgrammerEvaluation.Incomplete, Programmer.evaluate(t("5 +"), Base.DEC, s64))
        val p = Programmer.preview(t("12 * (3 +"), Base.DEC, s64)
        assertEquals(BigInteger.valueOf(36), (p as ProgrammerEvaluation.Value).value)
        assertTrue(Programmer.evaluate(t("A"), Base.DEC, s64) is ProgrammerEvaluation.Failure)
    }

    @Test fun basesAsShown() {
        val pl = NumberStyle(decimalComma = true)
        assertEquals("FFFF FFFF FFFF FFFF", Programmer.text(BigInteger.valueOf(-1), Base.HEX, s64, pl))
        assertEquals("1F40", Programmer.text(BigInteger.valueOf(8000), Base.HEX, s64, pl))
        assertEquals("−8 000", Programmer.text(BigInteger.valueOf(-8000), Base.DEC, s64, pl))
        assertEquals("17 500", Programmer.text(BigInteger.valueOf(8000), Base.OCT, s64, pl))
        assertEquals("1111 1111", Programmer.text(BigInteger.valueOf(-1), Base.BIN, s8, pl))
        assertEquals("FFFFFFFFFFFFE0C0", Programmer.copy(BigInteger.valueOf(-8000), Base.HEX, s64))
        assertEquals("-8000", Programmer.copy(BigInteger.valueOf(-8000), Base.DEC, s64))
        val rows = Programmer.binaryRows(BigInteger.valueOf(8000), s64)
        assertEquals(listOf(63, 47, 31, 15), rows.map { it.first })
        assertEquals("0001 1111 0100 0000", rows.last().second)
        assertEquals(listOf(7 to "0000 0101"), Programmer.binaryRows(BigInteger.valueOf(5), u8))
    }

    @Test fun digitsThatFit() {
        fun e(text: String) = Expression(t(text))
        assertTrue(Programmer.accepts(e("25"), 5, Base.DEC, u8))
        assertFalse(Programmer.accepts(e("25"), 6, Base.DEC, u8))
        assertTrue(Programmer.accepts(e("12"), 8, Base.DEC, s8))
        assertFalse(Programmer.accepts(e("12"), 9, Base.DEC, s8))
        assertFalse(Programmer.accepts(e("FF"), 0xF, Base.HEX, s8))
        assertTrue(Programmer.accepts(e("F"), 0xF, Base.HEX, s8))
        assertFalse(Programmer.accepts(e(""), 0xA, Base.DEC, s64))
        assertFalse(Programmer.accepts(e("1"), 2, Base.BIN, s64))
    }

    @Test fun rebase() {
        val style = NumberStyle(decimalComma = false, grouping = false)
        fun text(e: Expression) = ExpressionText.render(e.tokens, style).text
        assertEquals("−1 + 1", text(Programmer.rebase(Expression(t("FF+1")), Base.HEX, Base.DEC, s8)))
        assertEquals("1 + (−1)", text(Programmer.rebase(Expression(t("1+FF")), Base.HEX, Base.DEC, s8)))
        assertEquals("FF + 10", text(Programmer.rebase(Expression(t("255+16")), Base.DEC, Base.HEX, u8)))
        assertEquals("11111111 AND 1010", text(Programmer.rebase(Expression(t("FF&A")), Base.HEX, Base.BIN, u8)))
        val moved = Programmer.rebase(Expression(t("255+16"), cursor = 3), Base.DEC, Base.HEX, u8)
        assertEquals(2, moved.cursor)
    }

    @Test fun bits() {
        assertEquals(BigInteger.valueOf(-128), Programmer.toggleBit(BigInteger.ZERO, 7, s8))
        assertEquals(BigInteger.valueOf(128), Programmer.toggleBit(BigInteger.ZERO, 7, u8))
        assertEquals(listOf(Token.Operator(Op.MINUS), Token.Digit(1), Token.Digit(2), Token.Digit(8)), Programmer.literal(BigInteger.valueOf(-128), Base.DEC, s8))
        assertEquals(listOf(Token.Digit(8), Token.Digit(0)), Programmer.literal(BigInteger.valueOf(-128), Base.HEX, s8))
    }

    @Test fun wordChange() {
        // 300 in a 16-bit word, then the word shrinks to 8 bits: the literal wraps, with the note
        value(44, "300", u8, wrapped = true)
    }
}
