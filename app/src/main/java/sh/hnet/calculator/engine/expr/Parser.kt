package sh.hnet.calculator.engine.expr

import sh.hnet.calculator.engine.number.AngleUnit
import sh.hnet.calculator.engine.number.Calc
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.CalcException
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real
import java.math.BigInteger

/** What an expression evaluates to (SILNIK §2.2). */
sealed interface Evaluation {
    data class Value(val value: Real) : Evaluation

    /** Still being typed (`12 × (3 +`, `sin(`, empty): never shown as an error while typing. */
    data object Incomplete : Evaluation

    /** [position]: the element where reading stopped, for a [CalcError.SYNTAX]. */
    data class Failure(val error: CalcError, val position: Int? = null) : Evaluation
}

/** What an evaluation needs besides the expression. */
data class EvalContext(val angle: AngleUnit = AngleUnit.DEGREES, val ans: Real? = null)

/**
 * Reads an expression and evaluates it (SILNIK §2.1). A Pratt parser; from the tightest binding:
 * postfix `!` `%` `²`; `^` and `ʸ√` (right-associative); unary minus and `√`; implied
 * multiplication (D1: `1 ÷ 2π` = 1/(2π)); `×` `÷` `mod`; `+` `−`. Unclosed parentheses are closed at
 * the end. Percent after `+` or `−` takes the left operand's share: `200 + 10%` = 220.
 */
object Parser {

    fun evaluate(tokens: List<Token>, context: EvalContext = EvalContext()): Evaluation {
        val tree = try {
            Reader(tokens).readAll()
        } catch (_: IncompleteSignal) {
            return Evaluation.Incomplete
        } catch (e: SyntaxSignal) {
            return Evaluation.Failure(CalcError.SYNTAX, e.position)
        } catch (e: CalcException) {
            // A literal beyond the range
            return Evaluation.Failure(e.error)
        }
        return try {
            Evaluation.Value(tree.value(context))
        } catch (e: CalcException) {
            Evaluation.Failure(e.error)
        }
    }

    /**
     * The preview while typing (SILNIK §2.2, SPEC §6.3): trailing operators, open parentheses,
     * functions and a bare exponent are dropped, the rest is closed and evaluated.
     */
    fun preview(tokens: List<Token>, context: EvalContext = EvalContext()): Evaluation {
        var end = tokens.size
        while (end > 0) {
            val last = tokens[end - 1]
            if (last is Token.Operator || last.opens || last is Token.Exponent || last is Token.Sqrt) end-- else break
        }
        return evaluate(tokens.subList(0, end), context)
    }

    /** One number and nothing else: the preview would only repeat it (SPEC §6.3). */
    fun isLoneNumber(tokens: List<Token>): Boolean {
        val body = if (tokens.firstOrNull() == Token.Operator(Op.MINUS)) tokens.drop(1) else tokens
        if (body.isEmpty()) return false
        // Digits, a point, an exponent and its sign
        return body.withIndex().all { (k, t) ->
            t.isNumberPart || (t is Token.Operator && (t.op == Op.MINUS || t.op == Op.PLUS) && k > 0 && body[k - 1] == Token.Exponent)
        }
    }

    private class IncompleteSignal : RuntimeException()
    private class SyntaxSignal(val position: Int) : RuntimeException()

    // The tree

    private sealed interface Node {
        fun value(c: EvalContext): Real
    }

    private class Num(val v: Real) : Node {
        override fun value(c: EvalContext) = v
    }

    private class Constant(val k: Const) : Node {
        override fun value(c: EvalContext) = when (k) {
            Const.PI -> Calc.pi
            Const.E -> Calc.e
            Const.ANS -> c.ans ?: Real.ZERO
        }
    }

    private class Negate(val x: Node) : Node {
        override fun value(c: EvalContext) = Calc.negate(x.value(c))
    }

    private class SquareRoot(val x: Node) : Node {
        override fun value(c: EvalContext) = Calc.sqrt(x.value(c))
    }

    private class Post(val op: PostOp, val x: Node) : Node {
        override fun value(c: EvalContext): Real {
            val v = x.value(c)
            return when (op) {
                PostOp.FACTORIAL -> Calc.factorial(v)
                PostOp.PERCENT -> Calc.percent(v)
                PostOp.SQUARE -> Calc.square(v)
            }
        }
    }

    private class Call(val fn: Fn, val x: Node) : Node {
        override fun value(c: EvalContext): Real {
            val v = x.value(c)
            return when (fn) {
                Fn.SIN -> Calc.sin(v, c.angle)
                Fn.COS -> Calc.cos(v, c.angle)
                Fn.TAN -> Calc.tan(v, c.angle)
                Fn.ASIN -> Calc.asin(v, c.angle)
                Fn.ACOS -> Calc.acos(v, c.angle)
                Fn.ATAN -> Calc.atan(v, c.angle)
                Fn.LN -> Calc.ln(v)
                Fn.LOG -> Calc.log10(v)
                Fn.ABS -> Calc.abs(v)
            }
        }
    }

    private class Binary(val op: Op, val a: Node, val b: Node) : Node {
        override fun value(c: EvalContext): Real {
            val x = a.value(c)
            // a ± b%: b percent of a (SILNIK §2.1)
            if ((op == Op.PLUS || op == Op.MINUS) && b is Post && b.op == PostOp.PERCENT) {
                val share = Calc.multiply(x, Calc.percent(b.x.value(c)))
                return if (op == Op.PLUS) Calc.add(x, share) else Calc.subtract(x, share)
            }
            val y = b.value(c)
            return when (op) {
                Op.PLUS -> Calc.add(x, y)
                Op.MINUS -> Calc.subtract(x, y)
                Op.TIMES -> Calc.multiply(x, y)
                Op.DIVIDE -> Calc.divide(x, y)
                Op.POWER -> Calc.power(x, y)
                Op.ROOT -> Calc.root(x, y)
                Op.MOD -> Calc.mod(x, y)
            }
        }
    }

    // Binding powers
    private const val ADD = 10
    private const val MUL = 20
    private const val IMPLIED = 30
    private const val UNARY = 40
    private const val POW = 50

    private fun leftPower(op: Op): Int = when (op) {
        Op.PLUS, Op.MINUS -> ADD
        Op.TIMES, Op.DIVIDE, Op.MOD -> MUL
        Op.POWER, Op.ROOT -> POW
    }

    /** Right-associative powers read their right side at their own level; the rest one above. */
    private fun rightPower(op: Op): Int = if (op == Op.POWER || op == Op.ROOT) POW else leftPower(op) + 1

    private class Reader(val tokens: List<Token>) {
        var i = 0

        fun readAll(): Node {
            if (tokens.isEmpty()) throw IncompleteSignal()
            val tree = expression(0)
            // Whatever is left can only be a parenthesis closed that was never opened
            if (i < tokens.size) throw SyntaxSignal(i)
            return tree
        }

        fun expression(minPower: Int): Node {
            var left = prefix()
            while (i < tokens.size) {
                val t = tokens[i]
                left = when {
                    t is Token.Postfix -> {
                        i++
                        Post(t.op, left)
                    }
                    t is Token.Operator -> {
                        if (leftPower(t.op) < minPower) break
                        i++
                        Binary(t.op, left, expression(rightPower(t.op)))
                    }
                    t.startsOperand -> {
                        if (IMPLIED < minPower) break
                        Binary(Op.TIMES, left, expression(IMPLIED + 1))
                    }
                    else -> break
                }
            }
            return left
        }

        fun prefix(): Node {
            if (i >= tokens.size) throw IncompleteSignal()
            val t = tokens[i]
            return when (t) {
                is Token.Digit, Token.Point -> number()
                is Token.Constant -> {
                    i++
                    Constant(t.c)
                }
                Token.Open -> {
                    i++
                    group().also { closeGroup() }
                }
                is Token.Function -> {
                    i++
                    Call(t.fn, group()).also { closeGroup() }
                }
                Token.Sqrt -> {
                    i++
                    SquareRoot(expression(UNARY))
                }
                is Token.Operator -> when (t.op) {
                    Op.MINUS -> {
                        i++
                        Negate(expression(UNARY))
                    }
                    Op.PLUS -> {
                        i++
                        expression(UNARY)
                    }
                    else -> throw SyntaxSignal(i)
                }
                // An empty group, `()` or `sin()`, is still to be filled in
                Token.Close -> throw IncompleteSignal()
                Token.Exponent, is Token.Postfix -> throw SyntaxSignal(i)
            }
        }

        /** The inside of a group; empty is incomplete. */
        fun group(): Node {
            if (i < tokens.size && tokens[i] == Token.Close) throw IncompleteSignal()
            return expression(0)
        }

        /** A group closes with `)` or, unclosed, at the end. */
        fun closeGroup() {
            if (i < tokens.size && tokens[i] == Token.Close) i++
        }

        fun number(): Node {
            val start = i
            val mantissa = StringBuilder()
            var points = 0
            var digits = 0
            while (i < tokens.size) {
                when (val t = tokens[i]) {
                    is Token.Digit -> {
                        if (t.value > 9) throw SyntaxSignal(i)
                        mantissa.append(t.value)
                        digits++
                    }
                    Token.Point -> {
                        if (++points > 1) throw SyntaxSignal(i)
                        mantissa.append('.')
                    }
                    else -> break
                }
                i++
            }
            if (digits == 0) throw SyntaxSignal(start)
            var value = Rational.parse(mantissa.toString().let { if (it.startsWith('.')) "0$it" else it }.trimEnd('.'))
            if (i < tokens.size && tokens[i] == Token.Exponent) {
                i++
                var negative = false
                if (i < tokens.size && tokens[i] is Token.Operator) {
                    when ((tokens[i] as Token.Operator).op) {
                        Op.MINUS -> negative = true
                        Op.PLUS -> {}
                        else -> throw SyntaxSignal(i)
                    }
                    i++
                }
                val exponent = StringBuilder()
                while (i < tokens.size && tokens[i] is Token.Digit) {
                    val d = (tokens[i] as Token.Digit).value
                    if (d > 9) throw SyntaxSignal(i)
                    exponent.append(d)
                    i++
                }
                if (exponent.isEmpty()) {
                    if (i >= tokens.size) throw IncompleteSignal()
                    throw SyntaxSignal(i)
                }
                value = scaleByPowerOfTen(value, BigInteger(exponent.toString()).let { if (negative) it.negate() else it })
            }
            return Num(Calc.checked(Real.of(value)))
        }

        private fun scaleByPowerOfTen(v: Rational, exponent: BigInteger): Rational {
            if (v.isZero) return v
            if (exponent.abs() > BigInteger.valueOf(Calc.RANGE_DIGITS + 100L)) {
                if (exponent.signum() > 0) throw CalcException(CalcError.OVERFLOW)
                return Rational.ZERO
            }
            return v * Rational.of(10).pow(exponent.toInt())
        }
    }
}
