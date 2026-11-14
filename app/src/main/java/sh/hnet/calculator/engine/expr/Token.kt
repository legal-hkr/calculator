package sh.hnet.calculator.engine.expr

import sh.hnet.calculator.engine.number.Real

/**
 * One element of an expression as the editor holds it (SILNIK §2): the cursor stands between
 * elements, and backspace removes a whole element, so `sin(` goes with one press. The UI draws
 * them (with the language's decimal separator and grouping); the parser reads them.
 */
sealed interface Token {
    /** A digit, 0–9 (10–15 for the programmer's A–F). */
    data class Digit(val value: Int) : Token

    /** The decimal separator. */
    data object Point : Token

    /** EE: the exponent of a number, `6.022E23`. */
    data object Exponent : Token

    data class Operator(val op: Op) : Token

    data object Open : Token

    data object Close : Token

    /** A function with its opening parenthesis, `sin(`. */
    data class Function(val fn: Fn) : Token

    data class Constant(val c: Const) : Token

    /** A postfix operator, `5!`, `10%`, `7²`. */
    data class Postfix(val op: PostOp) : Token

    /** √ as a prefix, without a parenthesis: `√2`, `√(8 + 1)`. */
    data object Sqrt : Token

    /** NOT as a prefix (Programmer). */
    data object Not : Token

    /**
     * A number held whole: Ans (the last result), or a history entry's value. Its value is frozen
     * when it is inserted, so an expression reused later gives the same result; it shows as the
     * number (`15 548,4…`), is computed exactly, and goes as one element (owner, 14 Nov 2026).
     */
    class Value(val value: Real) : Token {
        override fun toString(): String = "Value($value)"
    }
}

enum class Op {
    PLUS, MINUS, TIMES, DIVIDE, POWER,
    /** ʸ√ as an infix, the index first: `3ʸ√8` = 2. */
    ROOT,
    MOD,
    // Programmer only
    AND, OR, XOR, NAND, NOR,
    /** Shifts and rotations: `<<` `>>` RoL RoR. */
    SHL, SHR, ROL, ROR,
}

enum class Fn { SIN, COS, TAN, ASIN, ACOS, ATAN, LN, LOG, ABS }

enum class Const { PI, E }

enum class PostOp { FACTORIAL, PERCENT, SQUARE }

/** Ends an operand: a number, a constant, a closed group, a postfix operator. */
internal val Token.endsOperand: Boolean
    get() = this is Token.Digit || this is Token.Point || this is Token.Close || this is Token.Constant || this is Token.Value || this is Token.Postfix

/** Begins an operand: what may follow an operand as an implied multiplication. */
internal val Token.startsOperand: Boolean
    get() = this is Token.Digit || this is Token.Point || this is Token.Open || this is Token.Function || this is Token.Constant || this is Token.Value || this is Token.Sqrt || this is Token.Not

/** Part of a number literal. */
internal val Token.isNumberPart: Boolean
    get() = this is Token.Digit || this is Token.Point || this is Token.Exponent

/** Opens a group (a parenthesis or a function's). */
internal val Token.opens: Boolean
    get() = this is Token.Open || this is Token.Function
