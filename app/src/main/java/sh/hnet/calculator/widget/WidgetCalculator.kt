package sh.hnet.calculator.widget

import sh.hnet.calculator.engine.expr.EvalContext
import sh.hnet.calculator.engine.expr.Evaluation
import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.Key
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.Parser
import sh.hnet.calculator.engine.expr.PostOp
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.expr.TokenCodec
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.Real

/** The widget's keys (SPEC-widget §4.2). */
enum class WidgetKey {
    D0, D1, D2, D3, D4, D5, D6, D7, D8, D9,
    POINT, PLUS, MINUS, TIMES, DIVIDE, PERCENT, SIGN, BACKSPACE, CLEAR, EQUALS,
}

/**
 * One widget's calculation (SPEC-widget §7): the expression typed at its end (no cursor), the
 * result after "=", or the error after "=". Its own, apart from the app's; kept until C.
 */
data class WidgetState(
    val tokens: List<Token> = emptyList(),
    /** The evaluated expression, while its [result] is shown. */
    val evaluated: List<Token>? = null,
    val result: Real? = null,
    /** "=" failed; the expression stays, the next key goes on from it. */
    val error: CalcError? = null,
) {
    val showsResult: Boolean get() = result != null

    /** As text for the widget's own preferences (TokenCodec, the app's storage format). */
    fun encode(): String = listOf(
        TokenCodec.encode(tokens),
        evaluated?.let { TokenCodec.encode(it) } ?: "-",
        result?.let { TokenCodec.encodeReal(it) } ?: "-",
        error?.name ?: "-",
    ).joinToString("\n")

    companion object {
        fun decode(text: String?): WidgetState {
            val parts = text?.split('\n') ?: return WidgetState()
            if (parts.size != 4) return WidgetState()
            val evaluated = parts[1].takeIf { it != "-" }?.let { TokenCodec.decode(it) }
            val result = parts[2].takeIf { it != "-" }?.let { TokenCodec.decodeReal(it) }
            return WidgetState(
                tokens = TokenCodec.decode(parts[0]) ?: emptyList(),
                evaluated = if (result != null) evaluated else null,
                result = if (evaluated != null) result else null,
                error = CalcError.entries.firstOrNull { it.name == parts[3] },
            )
        }
    }
}

/**
 * The widget's keys on its state (SPEC-widget §7.1), through the app's editor and engine: the same
 * rules as the app's keypad, typed always at the end, with the widget's own few: percent once per
 * number, "=" with nothing to evaluate does nothing, ⌫ after a result clears, at most
 * [MAX_CHARACTERS] in the expression.
 */
object WidgetCalculator {
    const val MAX_CHARACTERS = 100

    fun press(state: WidgetState, key: WidgetKey, length: (List<Token>) -> Int): WidgetState {
        if (key == WidgetKey.CLEAR) return WidgetState()
        if (key == WidgetKey.EQUALS) return equals(state)
        val result = state.result
        if (result != null) {
            if (key == WidgetKey.BACKSPACE) return WidgetState()
            val editorKey = editorKey(key)
            // An operator, percent or ± goes on from the result; a digit or the separator starts anew
            return WidgetState(tokens = Expression.afterResult(editorKey, result).tokens)
        }
        val expression = Expression(state.tokens)
        if (key == WidgetKey.PERCENT && state.tokens.lastOrNull() == Token.Postfix(PostOp.PERCENT)) return state.copy(error = null)
        val next = expression.apply(editorKey(key)).tokens
        // A key that would make the expression too long does nothing
        if (next.size > state.tokens.size && length(next) > MAX_CHARACTERS) return state.copy(error = null)
        return WidgetState(tokens = next)
    }

    private fun equals(state: WidgetState): WidgetState {
        if (state.showsResult || state.tokens.isEmpty()) return state
        return when (val e = Parser.evaluate(state.tokens, EvalContext())) {
            is Evaluation.Value -> WidgetState(tokens = state.tokens, evaluated = state.tokens, result = e.value)
            // A trailing operator, nothing to evaluate: "=" does nothing
            Evaluation.Incomplete -> state.copy(error = null)
            is Evaluation.Failure -> state.copy(error = e.error)
        }
    }

    private fun editorKey(key: WidgetKey): Key = when (key) {
        WidgetKey.POINT -> Key.Insert(Token.Point)
        WidgetKey.PLUS -> Key.Insert(Token.Operator(Op.PLUS))
        WidgetKey.MINUS -> Key.Insert(Token.Operator(Op.MINUS))
        WidgetKey.TIMES -> Key.Insert(Token.Operator(Op.TIMES))
        WidgetKey.DIVIDE -> Key.Insert(Token.Operator(Op.DIVIDE))
        WidgetKey.PERCENT -> Key.Insert(Token.Postfix(PostOp.PERCENT))
        WidgetKey.SIGN -> Key.ToggleSign
        WidgetKey.BACKSPACE -> Key.Backspace
        WidgetKey.CLEAR, WidgetKey.EQUALS -> error("handled before")
        else -> Key.Insert(Token.Digit(key.ordinal))
    }
}
