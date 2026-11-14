package sh.hnet.calculator.ui.calculator

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import sh.hnet.calculator.data.CalculatorStore
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.data.HistoryEntry
import sh.hnet.calculator.data.HistoryMode
import sh.hnet.calculator.engine.expr.TokenCodec
import sh.hnet.calculator.engine.expr.EvalContext
import sh.hnet.calculator.engine.expr.Evaluation
import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.Key
import sh.hnet.calculator.engine.expr.Parser
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.Const
import sh.hnet.calculator.engine.expr.Fn
import sh.hnet.calculator.engine.expr.Paste
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.Formatted
import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.AngleUnit
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.Real

/** What "=" can end in besides a result (SPEC §6.8). */
enum class DisplayError { DIVISION_BY_ZERO, DOMAIN, OVERFLOW, MALFORMED, INCOMPLETE }

/**
 * The calculator mode's state (SPEC §4.1, §6): the expression, the live preview, the result after
 * "=" and its errors, Ans. Evaluation runs off the main thread, the latest request wins (a key
 * cancels the one before). Saved 300 ms after a change and when the app stops.
 */
class CalculatorViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CalculatorStore(application)
    private val history = (application as CalculatorApplication).history

    var loaded by mutableStateOf(false)
        private set
    var expression by mutableStateOf(Expression())
        private set

    /** The expression whose result is on the main line (after "="), else null. */
    var evaluated by mutableStateOf<List<Token>?>(null)
        private set
    var result by mutableStateOf<Formatted?>(null)
        private set
    var preview by mutableStateOf<Formatted?>(null)
        private set
    var error by mutableStateOf<DisplayError?>(null)
        private set
    var angle by mutableStateOf(AngleUnit.DEGREES)
        private set
    var panelOpen by mutableStateOf(false)
        private set
    var second by mutableStateOf(false)
        private set

    /** The last result: what an operator after "=" goes on from. */
    private var ans: Real? = null

    /** There is a last result for the Ans key. */
    var hasAns by mutableStateOf(false)
        private set

    /** The result's width in characters: grows by [MORE_DIGITS] as the user scrolls for more (SPEC §6.6). */
    private var resultWidth = 12

    private var style = NumberStyle(decimalComma = false)
    private var mainChars = 12
    private var previewChars = 16

    private var previewJob: Job? = null
    private var equalsJob: Job? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val s = store.load()
            expression = s.expression
            ans = s.ans
            hasAns = s.ans != null
            angle = s.angle
            panelOpen = s.panelOpen
            second = s.second
            if (s.showsResult) evaluated = s.expression.tokens
            loaded = true
            refresh()
        }
    }

    val showsResult: Boolean get() = evaluated != null

    /** The style and the room on the lines (characters at their smallest size), from the UI. */
    fun layout(style: NumberStyle, mainChars: Int, previewChars: Int) {
        if (style == this.style && mainChars == this.mainChars && previewChars == this.previewChars) return
        this.style = style
        this.mainChars = mainChars
        this.previewChars = previewChars
        resultWidth = mainChars
        refresh()
    }

    fun press(key: Key) {
        if (!loaded) return
        equalsJob?.cancel()
        error = null
        expression = if (showsResult) {
            evaluated = null
            result = null
            val last = ans
            if (key == Key.Backspace || last == null) Expression().let { if (key == Key.Backspace) it else it.apply(key) }
            else Expression.afterResult(key, last)
        } else {
            expression.apply(key)
        }
        changed()
    }

    /** Long press on ⌫ (or C): everything goes. */
    fun clear() {
        if (!loaded) return
        equalsJob?.cancel()
        error = null
        evaluated = null
        result = null
        expression = Expression()
        changed()
    }

    fun moveCursor(to: Int) {
        if (!loaded || showsResult) return
        error = null
        expression = expression.moveCursor(to)
        save()
    }

    fun equals() {
        if (!loaded || showsResult) return
        val tokens = expression.tokens
        if (tokens.isEmpty()) return
        val context = EvalContext(angle)
        val style = style
        val width = mainChars
        equalsJob?.cancel()
        equalsJob = viewModelScope.launch {
            val (evaluation, formatted) = withContext(Dispatchers.Default) {
                runInterruptible {
                    val e = Parser.evaluate(tokens, context)
                    e to (e as? Evaluation.Value)?.let { Formatter.format(it.value, style, width) }
                }
            }
            when (evaluation) {
                is Evaluation.Value -> {
                    ans = evaluation.value
                    hasAns = true
                    record(tokens, evaluation.value)
                    resultWidth = width
                    evaluated = tokens
                    result = formatted
                    preview = null
                }
                Evaluation.Incomplete -> error = DisplayError.INCOMPLETE
                is Evaluation.Failure -> {
                    error = when (evaluation.error) {
                        CalcError.DIVISION_BY_ZERO -> DisplayError.DIVISION_BY_ZERO
                        CalcError.DOMAIN -> DisplayError.DOMAIN
                        CalcError.OVERFLOW -> DisplayError.OVERFLOW
                        CalcError.SYNTAX -> DisplayError.MALFORMED
                    }
                    // The cursor goes where reading stopped (SPEC §6.8)
                    evaluation.position?.let { expression = expression.moveCursor(it) }
                }
            }
            save()
        }
    }

    /** The result's next digits, as the user scrolls it (SPEC §6.6): in chunks, up to 1000 significant digits. */
    fun moreDigits() {
        val shown = result ?: return
        if (shown.exact || style.decimals != null || resultWidth >= Formatter.MAX_DIGITS + 20) return
        resultWidth = minOf(resultWidth + MORE_DIGITS, Formatter.MAX_DIGITS + 20)
        refresh()
    }

    /** Every successful "=" goes to History (SPEC §10.1); written off the main thread. */
    private fun record(tokens: List<Token>, value: Real) {
        val at = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.Default) {
            history.add(HistoryEntry(mode = HistoryMode.CALCULATOR.name, expression = TokenCodec.encode(tokens), result = TokenCodec.encodeReal(value), createdAt = at))
        }
    }

    /** A history entry's result at the cursor, as a whole number (after "=": a new expression). */
    fun insertValue(value: Real) = press(Key.Insert(Token.Value(value)))

    /** A history entry's expression in place of the current one (History › Insert expression). */
    fun replaceExpression(tokens: List<Token>) {
        if (!loaded) return
        equalsJob?.cancel()
        error = null
        evaluated = null
        result = null
        expression = Expression(tokens)
        changed()
    }

    /** The last result as a whole number at the cursor (the Ans key). */
    fun insertAns() {
        val last = ans ?: return
        press(Key.Insert(Token.Value(last)))
    }

    /** 10ˣ: `10^(`, after a number with an explicit × (digits can't stand next to digits). */
    fun insertPowerOfTen() {
        if (!showsResult && expression.tokens.getOrNull(expression.cursor - 1).let { it is Token.Digit || it == Token.Point }) {
            press(Key.Insert(Token.Operator(Op.TIMES)))
        }
        listOf(Key.Insert(Token.Digit(1)), Key.Insert(Token.Digit(0)), Key.Insert(Token.Operator(Op.POWER)), Key.Insert(Token.Open)).forEach { press(it) }
    }

    /** eˣ: `e^(`. */
    fun insertExp() {
        listOf(Key.Insert(Token.Constant(Const.E)), Key.Insert(Token.Operator(Op.POWER)), Key.Insert(Token.Open)).forEach { press(it) }
    }

    fun toggleAngle() {
        angle = if (angle == AngleUnit.DEGREES) AngleUnit.RADIANS else AngleUnit.DEGREES
        if (!showsResult) refresh()
        save()
    }

    fun setPanel(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        save()
    }

    fun toggleSecond() {
        second = !second
        save()
    }

    /** The angle chip shows when the panel is open or the expression has a trigonometric function (SPEC §6.7). */
    val showsAngle: Boolean
        get() = panelOpen || expression.tokens.any { it is Token.Function && it.fn in TRIGONOMETRIC }

    /**
     * Text from the clipboard at the cursor (after "=": a new expression). False when it can't be
     * read: nothing changes and the UI says so.
     */
    fun paste(text: String): Boolean {
        if (!loaded) return false
        val tokens = Paste.read(text, style.decimalComma, ans) ?: return false
        equalsJob?.cancel()
        error = null
        val base = if (showsResult) Expression() else expression
        evaluated = null
        result = null
        expression = base.insertAll(tokens)
        changed()
        return true
    }

    /** The expression for the clipboard, ASCII only. */
    fun expressionForCopy(): String = ExpressionText.ascii(if (showsResult) evaluated.orEmpty() else expression.tokens, style)

    /** The result for the clipboard: as shown plus every digit computed so far. */
    fun resultForCopy(): String? = result?.copy

    private fun changed() {
        refresh()
        save()
    }

    /** Recomputes what the display shows from the expression or the result. */
    private fun refresh() {
        previewJob?.cancel()
        val shown = evaluated
        val style = style
        if (shown != null) {
            val value = ans ?: return
            val width = resultWidth
            previewJob = viewModelScope.launch {
                result = withContext(Dispatchers.Default) { runInterruptible { Formatter.format(value, style, width) } }
            }
            return
        }
        val tokens = expression.tokens
        // A lone number (or a lone Ans) would only repeat the line (SPEC §6.3)
        if (tokens.isEmpty() || Parser.isLoneNumber(tokens) || (tokens.size == 1 && tokens[0] is Token.Value)) {
            preview = null
            return
        }
        val context = EvalContext(angle)
        val width = previewChars
        previewJob = viewModelScope.launch {
            preview = withContext(Dispatchers.Default) {
                runInterruptible {
                    (Parser.preview(tokens, context) as? Evaluation.Value)?.let { Formatter.format(it.value, style, width) }
                }
            }
        }
    }

    private fun state() = CalculatorStore.State(expression, showsResult, ans, angle, panelOpen, second)

    private companion object {
        const val MORE_DIGITS = 50
        val TRIGONOMETRIC = setOf(Fn.SIN, Fn.COS, Fn.TAN, Fn.ASIN, Fn.ACOS, Fn.ATAN)
    }

    private fun save() {
        saveJob?.cancel()
        val state = state()
        saveJob = viewModelScope.launch(Dispatchers.Default) {
            delay(300)
            store.save(state)
        }
    }

    /** At once, when the app stops (SPEC §4.1). */
    fun saveNow() {
        if (!loaded) return
        saveJob?.cancel()
        val state = state()
        viewModelScope.launch(Dispatchers.Default + NonCancellable) { store.save(state) }
    }
}
