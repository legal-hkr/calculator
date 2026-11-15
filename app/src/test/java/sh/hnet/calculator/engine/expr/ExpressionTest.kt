package sh.hnet.calculator.engine.expr

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.calculator.engine.number.Real

class ExpressionTest {
    private fun render(e: Expression): String {
        val sb = StringBuilder()
        e.tokens.forEachIndexed { k, t ->
            if (k == e.cursor) sb.append('|')
            sb.append(
                when (t) {
                    is Token.Digit -> t.value.toString()
                    Token.Point -> "."
                    Token.Exponent -> "E"
                    is Token.Operator -> when (t.op) {
                        Op.PLUS -> "+"; Op.MINUS -> "−"; Op.TIMES -> "×"; Op.DIVIDE -> "÷"
                        Op.POWER -> "^"; Op.ROOT -> "ʸ√"; Op.MOD -> " mod "
                        else -> " ${t.op} "
                    }
                    Token.Open -> "("
                    Token.Close -> ")"
                    is Token.Function -> t.fn.name.lowercase() + "("
                    is Token.Constant -> when (t.c) { Const.PI -> "π"; Const.E -> "e" }
                    is Token.Value -> "[${t.value}]"
                    is Token.Postfix -> when (t.op) { PostOp.FACTORIAL -> "!"; PostOp.PERCENT -> "%"; PostOp.SQUARE -> "²" }
                    Token.Sqrt -> "√"
                    Token.Not -> "NOT "
                },
            )
        }
        if (e.cursor == e.tokens.size) sb.append('|')
        return sb.toString()
    }

    /** Keys by character: digits . + - * / ^ E ! % ² √ π; ( is the bracket key, [ always opens, ] closes; ± and r (1/x); < backspace. */
    private fun key(c: Char): Key = when (c) {
        in '0'..'9' -> Key.Insert(Token.Digit(c - '0'))
        '.' -> Key.Insert(Token.Point)
        '+' -> Key.Insert(Token.Operator(Op.PLUS))
        '-' -> Key.Insert(Token.Operator(Op.MINUS))
        '*' -> Key.Insert(Token.Operator(Op.TIMES))
        '/' -> Key.Insert(Token.Operator(Op.DIVIDE))
        '^' -> Key.Insert(Token.Operator(Op.POWER))
        'E' -> Key.Insert(Token.Exponent)
        '!' -> Key.Insert(Token.Postfix(PostOp.FACTORIAL))
        '%' -> Key.Insert(Token.Postfix(PostOp.PERCENT))
        '²' -> Key.Insert(Token.Postfix(PostOp.SQUARE))
        '√' -> Key.Insert(Token.Sqrt)
        'π' -> Key.Insert(Token.Constant(Const.PI))
        's' -> Key.Insert(Token.Function(Fn.SIN))
        '(' -> Key.Bracket
        '[' -> Key.OpenBracket
        ']' -> Key.Insert(Token.Close)
        '±' -> Key.ToggleSign
        'r' -> Key.Reciprocal
        '<' -> Key.Backspace
        else -> error("no key $c")
    }

    private fun type(keys: String, from: Expression = Expression()): Expression = keys.fold(from) { e, c -> e.apply(key(c)) }

    private fun check(expected: String, keys: String) = assertEquals(keys, expected, render(type(keys)))

    @Test fun operatorReplacesOperator() {
        check("5÷|", "5*/")
        check("5×−|", "5*-")
        check("5+|", "5*-+")
        check("2^−|", "2^-")
        check("−|", "-")
        check("|", "*")
        check("(|", "(*")
        check("(−|", "(-")
    }

    @Test fun numbers() {
        check("7|", "07")
        check("0|", "00")
        check("10|", "10")
        check("0.|", ".")
        check("1.5|", "1.5.")
        check("2+0.5|", "2+.5")
        check("1.5E−3|", "1.5E-3")
        check("1E3|", "1E3E")
        check("1E|", "1E*")
        check("0.07|", "0.07")
    }

    @Test fun bracketKey() {
        check("(|", "(")
        check("(2+3)|", "(2+3(")
        check("2(|", "2(")
        check("2×(|", "2*(")
        check("((|", "((")
        check("(2(|", "(2[")
        check("2|", "2]")
    }

    @Test fun postfixOnlyAfterAnOperand() {
        check("5!|", "5!")
        check("|", "!")
        check("2+|", "2+%")
        check("(2)²|", "(2(²")
    }

    @Test fun backspaceRemovesAWholeFunction() {
        check("2×|", "2*s<")
        check("2×sin(|", "2*s")
    }

    @Test fun toggleSign() {
        check("−5|", "5±")
        check("5|", "5±±")
        check("5×(−3)|", "5*3±")
        check("5×3|", "5*3±±")
        assertEquals("5×3|", render(type("5*3±").moveCursor(4).apply(Key.ToggleSign).let { it.moveCursor(it.tokens.size) }))
        check("5×3|", "5*-3±")
        check("5−(−3)|", "5-3±")
        check("−|", "±")
        check("2×(−|", "2*±")
        check("−π|", "π±")
    }

    @Test fun reciprocal() {
        check("1÷(7)|", "7r")
        check("1÷(|", "r")
        check("3+1÷(7)|", "3+7r")
        check("2÷(1÷(7))|", "2/7r")
        check("2^(1÷(7))|", "2^7r")
        check("1÷((2+3))|", "(2+3(r")
        check("1÷(5!)|", "5!r")
        check("1÷(√2)|", "√2r")
    }

    @Test fun cursorInTheMiddle() {
        val e = type("12+3").moveCursor(2)
        assertEquals("12|+3", render(e))
        assertEquals("125|+3", render(e.apply(key('5'))))
        assertEquals("1|+3", render(e.apply(Key.Backspace)))
        assertEquals("12|3", render(e.apply(Key.DeleteForward)))
    }

    @Test fun afterResult() {
        val ans = Real.of(21)
        assertEquals("[21]×|", render(Expression.afterResult(key('*'), ans)))
        assertEquals("7|", render(Expression.afterResult(key('7'), ans)))
        assertEquals("[21]!|", render(Expression.afterResult(key('!'), ans)))
        assertEquals("(|", render(Expression.afterResult(key('('), ans)))
        assertEquals("1÷([21])|", render(Expression.afterResult(Key.Reciprocal, ans)))
        assertEquals("−[21]|", render(Expression.afterResult(Key.ToggleSign, ans)))
    }

    @Test fun aWholeValueGoesWithOneBackspace() {
        val e = Expression.afterResult(key('*'), Real.of(21)).apply(key('2'))
        assertEquals("[21]×2|", render(e))
        assertEquals("|", render(e.apply(Key.Backspace).apply(Key.Backspace).apply(Key.Backspace)))
        assertEquals("[21]|", render(e.apply(Key.Backspace).apply(Key.Backspace)))
    }

    @Test fun aWholeNumberNextToDigitsGetsATimes() {
        // Review 15 Nov 2026: "2" and a history value of 3 must not read as "23"
        val three = Key.Insert(Token.Value(Real.of(3)))
        assertEquals("2×[3]|", render(type("2").apply(three)))
        assertEquals("[3]×5|", render(Expression().apply(three).apply(key('5'))))
        assertEquals("[3]×0.|", render(Expression().apply(three).apply(key('.'))))
        assertEquals("2+[3]|", render(type("2+").apply(three)))
    }

    @Test fun anOperatorThatCantStandThereLeavesTheSign() {
        check("(−|", "(-*")
        check("−|", "-/")
        check("√−|", "√-+")
    }

    @Test fun ghostBrackets() {
        assertEquals(2, type("2*(3+s4").unclosedAtEnd())
        assertEquals(0, type("(2(").unclosedAtEnd())
    }
}
