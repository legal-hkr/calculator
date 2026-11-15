package sh.hnet.calculator.engine.expr

import sh.hnet.calculator.engine.number.Real

/** What a key does to the expression (SPEC §5.3, §5.4). */
sealed interface Key {
    data class Insert(val token: Token) : Key

    /** The one bracket key: opens or closes by context. */
    data object Bracket : Key

    /** Its long press: always opens. */
    data object OpenBracket : Key

    /** ±: the sign of the number at the cursor. */
    data object ToggleSign : Key

    /** 1/x: wraps the operand before the cursor, `7` → `1÷(7)`. */
    data object Reciprocal : Key

    data object Backspace : Key

    /** Delete on a physical keyboard: the element after the cursor. */
    data object DeleteForward : Key
}

/**
 * The expression being typed: its elements and the cursor between them (0 … size). Keys go
 * through [apply], which keeps the input rules of SILNIK §2.3 (an operator replaces an operator, one
 * point per number, `07` becomes `7`, …); a key that makes no sense where the cursor is does
 * nothing. Immutable: every key gives a new expression.
 */
data class Expression(val tokens: List<Token> = emptyList(), val cursor: Int = tokens.size) {

    init {
        require(cursor in 0..tokens.size)
    }

    val isEmpty: Boolean get() = tokens.isEmpty()

    fun apply(key: Key): Expression = when (key) {
        is Key.Insert -> insert(key.token)
        Key.Bracket -> bracket()
        Key.OpenBracket -> if (prev == Token.Exponent) this else put(Token.Open)
        Key.ToggleSign -> toggleSign()
        Key.Reciprocal -> reciprocal()
        Key.Backspace -> if (cursor == 0) this else Expression(tokens.without(cursor - 1), cursor - 1)
        Key.DeleteForward -> if (cursor == tokens.size) this else Expression(tokens.without(cursor), cursor)
    }

    fun moveCursor(to: Int): Expression = copy(cursor = to.coerceIn(0, tokens.size))

    /** Elements inserted as they are (a paste, a history entry); the parser judges them. */
    fun insertAll(more: List<Token>): Expression =
        Expression(tokens.subList(0, cursor) + more + tokens.subList(cursor, tokens.size), cursor + more.size)

    /** Parentheses opened before the cursor and not yet closed. */
    fun unclosedBeforeCursor(): Int = unclosed(cursor)

    /** Parentheses left open at the end: drawn as ghost brackets (SPEC §6.2). */
    fun unclosedAtEnd(): Int = unclosed(tokens.size)

    private fun unclosed(end: Int): Int {
        var depth = 0
        for (k in 0 until end) {
            val t = tokens[k]
            if (t.opens) depth++ else if (t == Token.Close && depth > 0) depth--
        }
        return depth
    }

    private val prev: Token? get() = tokens.getOrNull(cursor - 1)

    private fun put(vararg more: Token): Expression = insertAll(more.toList())

    private fun insert(t: Token): Expression = when (t) {
        is Token.Digit -> digit(t)
        Token.Point -> point()
        Token.Exponent -> exponent()
        is Token.Operator -> operator(t.op)
        is Token.Postfix -> if (prev?.endsOperand == true) put(t) else this
        Token.Close -> if (unclosedBeforeCursor() > 0 && prev?.endsOperand == true) put(t) else this
        // A whole number drawn next to an operand would read as one number ("2" and 3 as "23"): an explicit × between them
        is Token.Value -> if (prev == Token.Exponent) this else if (prev?.endsOperand == true) put(Token.Operator(Op.TIMES), t) else put(t)
        is Token.Function, is Token.Constant, Token.Sqrt, Token.Not, Token.Open -> if (prev == Token.Exponent) this else put(t)
    }

    // Number literals

    private fun isLiteralPart(k: Int): Boolean {
        val t = tokens.getOrNull(k) ?: return false
        if (t.isNumberPart) return true
        // The sign of an exponent, 1E−5
        return t is Token.Operator && (t.op == Op.MINUS || t.op == Op.PLUS) && tokens.getOrNull(k - 1) == Token.Exponent
    }

    /** The literal around [position]: [start, end). Empty when there is none. */
    private fun literalAround(position: Int): IntRange {
        var start = position
        while (start > 0 && isLiteralPart(start - 1)) start--
        var end = position
        while (end < tokens.size && isLiteralPart(end)) end++
        return start until end
    }

    private fun digit(t: Token.Digit): Expression {
        // After a whole number a digit starts a number of its own, with an explicit ×
        if (prev is Token.Value) return put(Token.Operator(Op.TIMES), t)
        val literal = literalAround(cursor)
        val left = tokens.subList(literal.first, cursor)
        // A lone leading zero gives way: 0 then 7 is 7, not 07
        if (left == listOf(Token.Digit(0)) && cursor == literal.last + 1) {
            if (t.value == 0) return this
            return Expression(tokens.subList(0, cursor - 1) + t + tokens.subList(cursor, tokens.size), cursor)
        }
        return put(t)
    }

    private fun point(): Expression {
        if (prev is Token.Value) return put(Token.Operator(Op.TIMES), Token.Digit(0), Token.Point)
        val literal = literalAround(cursor)
        val inside = tokens.subList(literal.first, literal.last + 1)
        if (Token.Point in inside || Token.Exponent in inside) return this
        return if (literal.first == cursor) put(Token.Digit(0), Token.Point) else put(Token.Point)
    }

    private fun exponent(): Expression {
        val literal = literalAround(cursor)
        if (cursor != literal.last + 1 || literal.isEmpty()) return this
        val inside = tokens.subList(literal.first, literal.last + 1)
        if (Token.Exponent in inside || inside.none { it is Token.Digit }) return this
        return put(Token.Exponent)
    }

    // Operators

    private fun operator(op: Op): Expression {
        val p = prev
        return when {
            p == Token.Exponent -> if (op == Op.MINUS || op == Op.PLUS) put(Token.Operator(op)) else this
            // At the start or after an opening: only a sign
            p == null || p.opens || p == Token.Sqrt || p == Token.Not -> if (op == Op.MINUS) put(Token.Operator(op)) else this
            p is Token.Operator -> {
                if (op == Op.MINUS && p.op in AFTER_WHICH_MINUS_IS_A_SIGN) return put(Token.Operator(op))
                // An exponent's sign is part of a number, not an operator to replace
                if (tokens.getOrNull(cursor - 2) == Token.Exponent) return this
                // Replaced only where the new operator may stand; else the key does nothing ("(−" then × keeps the sign)
                val shortened = Expression(tokens.without(cursor - 1), cursor - 1)
                val replaced = shortened.operator(op)
                if (replaced === shortened) this else replaced
            }
            else -> put(Token.Operator(op))
        }
    }

    private fun bracket(): Expression {
        val p = prev
        if (p == Token.Exponent) return this
        val closes = p != null && p.endsOperand && unclosedBeforeCursor() > 0
        return put(if (closes) Token.Close else Token.Open)
    }

    // Operands

    /** Where the operand ending at [end] starts (with its postfixes and √), or null when there is none. */
    private fun operandStart(end: Int): Int? {
        var k = end
        while (k > 0 && tokens[k - 1] is Token.Postfix) k--
        if (k == 0) return null
        val t = tokens[k - 1]
        k = when {
            t == Token.Close -> matchingOpen(k - 1) ?: return null
            isLiteralPart(k - 1) -> literalAround(k).first
            t is Token.Constant || t is Token.Value -> k - 1
            else -> return null
        }
        while (k > 0 && tokens[k - 1] == Token.Sqrt) k--
        return k
    }

    private fun matchingOpen(close: Int): Int? {
        var depth = 0
        for (j in close downTo 0) {
            val t = tokens[j]
            if (t == Token.Close) depth++ else if (t.opens) {
                depth--
                if (depth == 0) return j
            }
        }
        return null
    }

    /** The end of the operand at the cursor: the end of a number the cursor stands in. */
    private fun operandEnd(): Int {
        val literal = literalAround(cursor)
        return if (literal.isEmpty()) cursor else literal.last + 1
    }

    private fun toggleSign(): Expression {
        val end = operandEnd()
        val start = operandStart(end)
        val minus = Token.Operator(Op.MINUS)
        if (start == null) {
            // Nothing to change yet: begin a negative number
            return if (prev is Token.Operator) put(Token.Open, minus) else put(minus)
        }
        // The operand is itself (−3), the cursor after its parenthesis: back to 3
        if (tokens[start] == Token.Open && tokens.getOrNull(start + 1) == minus && tokens[end - 1] == Token.Close && matchingOpen(end - 1) == start) {
            return Expression(tokens.subList(0, start) + tokens.subList(start + 2, end - 1) + tokens.subList(end, tokens.size), end - 3)
        }
        val before = tokens.getOrNull(start - 1)
        val beforeThat = tokens.getOrNull(start - 2)
        // (−3) back to 3, the cursor in the number
        if (before == minus && beforeThat == Token.Open && tokens.getOrNull(end) == Token.Close && matchingOpen(end) == start - 2) {
            return Expression(tokens.subList(0, start - 2) + tokens.subList(start, end) + tokens.subList(end + 1, tokens.size), end - 2)
        }
        // A sign at the start, after an opening or after an operator: removed
        if (before == minus && (beforeThat == null || beforeThat.opens || beforeThat is Token.Operator || beforeThat == Token.Sqrt)) {
            return Expression(tokens.without(start - 1), end - 1)
        }
        // After an operator the negative number goes in parentheses: 5 × 3 → 5 × (−3)
        if (before is Token.Operator) {
            return Expression(tokens.subList(0, start) + Token.Open + minus + tokens.subList(start, end) + Token.Close + tokens.subList(end, tokens.size), end + 3)
        }
        return Expression(tokens.subList(0, start) + minus + tokens.subList(start, tokens.size), end + 1)
    }

    private fun reciprocal(): Expression {
        val end = operandEnd()
        val start = operandStart(end)
        val one = listOf(Token.Digit(1), Token.Operator(Op.DIVIDE), Token.Open)
        if (start == null) return insertAll(if (prev?.endsOperand == true) listOf(Token.Operator(Op.TIMES)) + one else one)
        val before = tokens.getOrNull(start - 1)
        // After an operator that binds tighter than ÷ (or ÷ itself) the whole goes in parentheses: 2 ÷ 7 → 2 ÷ (1÷(7))
        val wrap = before is Token.Operator && before.op in BINDS_LIKE_DIVIDE_OR_TIGHTER
        val operand = tokens.subList(start, end)
        val replacement = (if (wrap) listOf(Token.Open) else emptyList()) + one + operand + Token.Close + (if (wrap) listOf(Token.Close) else emptyList())
        return Expression(tokens.subList(0, start) + replacement + tokens.subList(end, tokens.size), start + replacement.size)
    }

    companion object {
        private val AFTER_WHICH_MINUS_IS_A_SIGN = setOf(
            Op.TIMES, Op.DIVIDE, Op.POWER, Op.ROOT, Op.MOD,
            Op.AND, Op.OR, Op.XOR, Op.NAND, Op.NOR, Op.SHL, Op.SHR, Op.ROL, Op.ROR,
        )
        private val BINDS_LIKE_DIVIDE_OR_TIGHTER = setOf(Op.DIVIDE, Op.MOD, Op.POWER, Op.ROOT)

        private val CONTINUING = setOf(Key.ToggleSign, Key.Reciprocal)

        /**
         * A key pressed while a result is shown (SPEC §6.5): an operator, a postfix, ± or 1/x go on
         * from the result ([ans], as a whole number); anything else starts a new expression.
         */
        fun afterResult(key: Key, ans: Real): Expression {
            val continues = key in CONTINUING || (key is Key.Insert && (key.token is Token.Operator || key.token is Token.Postfix))
            val start = if (continues) Expression(listOf(Token.Value(ans))) else Expression()
            return start.apply(key)
        }

        private fun <T> List<T>.without(index: Int): List<T> = subList(0, index) + subList(index + 1, size)
    }
}
