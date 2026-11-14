package sh.hnet.calculator.engine.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import sh.hnet.calculator.engine.number.Calc
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real

class TokenCodecTest {
    @Test fun expressionsGoThereAndBack() {
        val tokens = Paste.read("sin(30)+√2π*1.5E-3-17 mod 5^2!%", false)!! +
            Token.Value(Real.of(Rational.of(1, 3))) + Token.Not + Token.Operator(Op.ROL) + Token.Digit(15)
        val back = TokenCodec.decode(TokenCodec.encode(tokens))!!
        assertEquals(tokens.size, back.size)
        tokens.zip(back).forEach { (a, b) ->
            if (a is Token.Value) assertEquals(a.value.rational, (b as Token.Value).value.rational) else assertEquals(a, b)
        }
        assertEquals(emptyList<Token>(), TokenCodec.decode(""))
        assertNull(TokenCodec.decode("d1 x9"))
    }

    @Test fun anInexactNumberKeepsItsDigits() {
        val sqrt2 = Calc.sqrt(Real.of(2))
        val back = TokenCodec.decodeReal(TokenCodec.encodeReal(sqrt2))!!
        assertFalse(back.isExact)
        assertEquals(sqrt2.decimal(1000), back.decimal(1000))
        val huge = Calc.exp(Real.of(20000))
        val hugeBack = TokenCodec.decodeReal(TokenCodec.encodeReal(huge))!!
        assertEquals(huge.approx(-8000), hugeBack.approx(-8000))
        assertEquals(Real.ZERO.rational, TokenCodec.decodeReal(TokenCodec.encodeReal(Calc.sin(Calc.pi, sh.hnet.calculator.engine.number.AngleUnit.RADIANS)))!!.rational)
    }
}
