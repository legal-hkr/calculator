package sh.hnet.calculator.ui.converter

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
import sh.hnet.calculator.data.ConverterStore
import sh.hnet.calculator.engine.convert.Category
import sh.hnet.calculator.engine.convert.Scale
import sh.hnet.calculator.engine.convert.UnitDef
import sh.hnet.calculator.engine.convert.Units
import sh.hnet.calculator.engine.expr.EvalContext
import sh.hnet.calculator.engine.expr.Evaluation
import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.Key
import sh.hnet.calculator.engine.expr.Parser
import sh.hnet.calculator.engine.expr.Paste
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.format.Formatted
import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.CalcException
import sh.hnet.calculator.engine.number.Real
import sh.hnet.calculator.ui.calculator.DisplayError

/**
 * The Converter (SPEC §7): a category, its two units, a value typed into one card (an expression)
 * and the other card following it live. Each category keeps its pair and its value.
 */
class ConverterViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConverterStore(application)

    var loaded by mutableStateOf(false)
        private set
    var category by mutableStateOf(Category.LENGTH)
        private set
    private var memories: Map<Category, ConverterStore.Memory> = emptyMap()

    var from by mutableStateOf(Units.of(Category.LENGTH).first())
        private set
    var to by mutableStateOf(Units.of(Category.LENGTH).last())
        private set

    /** The value is typed into the "from" card (else into the "to" card). */
    var typedInFrom by mutableStateOf(true)
        private set
    var expression by mutableStateOf(Expression())
        private set

    /** The other card's value. */
    var output by mutableStateOf<Formatted?>(null)
        private set

    /** The typed value itself, for All units; null while there is none. */
    var input by mutableStateOf<Real?>(null)
        private set

    /** Zero in an inverse unit (fuel consumption): the other card shows the note instead (SPEC §7.5). */
    var inverseZero by mutableStateOf(false)
        private set
    var error by mutableStateOf<DisplayError?>(null)
        private set

    private var style = NumberStyle(decimalComma = false)
    private var width = 12
    private var job: Job? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val s = store.load()
            memories = s.memories
            show(s.category)
            loaded = true
            refresh()
        }
    }

    /** The unit typed in and the one that follows. */
    val source: UnitDef get() = if (typedInFrom) from else to
    val target: UnitDef get() = if (typedInFrom) to else from

    fun layout(style: NumberStyle, width: Int) {
        if (style == this.style && width == this.width) return
        this.style = style
        this.width = width
        refresh()
    }

    private fun show(c: Category) {
        val m = memories.getValue(c)
        category = c
        from = Units.find(c, m.from) ?: Units.of(c).first()
        to = Units.find(c, m.to) ?: Units.of(c).last()
        typedInFrom = m.typedInFrom
        expression = Expression(m.tokens)
    }

    private fun remember() {
        memories = memories + (category to ConverterStore.Memory(from.id, to.id, expression.tokens, typedInFrom))
    }

    fun selectCategory(c: Category) {
        if (!loaded || c == category) return
        remember()
        show(c)
        changed()
    }

    fun press(key: Key) {
        if (!loaded) return
        error = null
        expression = expression.apply(key)
        changed()
    }

    fun clear() {
        if (!loaded) return
        error = null
        expression = Expression()
        changed()
    }

    fun moveCursor(to: Int) {
        if (!loaded) return
        expression = expression.moveCursor(to)
        save()
    }

    /** "=": the expression becomes its value; an error shows in the other card (SPEC §7.2). */
    fun equals() {
        if (!loaded || expression.isEmpty) return
        when (val e = Parser.evaluate(expression.tokens, EvalContext())) {
            is Evaluation.Value -> {
                expression = Expression(listOf(Token.Value(e.value)))
                changed()
            }
            Evaluation.Incomplete -> error = DisplayError.INCOMPLETE
            is Evaluation.Failure -> error = when (e.error) {
                CalcError.DIVISION_BY_ZERO -> DisplayError.DIVISION_BY_ZERO
                CalcError.DOMAIN -> DisplayError.DOMAIN
                CalcError.OVERFLOW -> DisplayError.OVERFLOW
                CalcError.SYNTAX -> DisplayError.MALFORMED
            }
        }
    }

    /** Swap: the units change places, the typed text stays in its card (SPEC §7.2). */
    fun swap() {
        if (!loaded) return
        val a = from
        from = to
        to = a
        changed()
    }

    /** The other card becomes the one typed in, its value the text to edit. */
    fun typeIn(fromCard: Boolean) {
        if (!loaded || fromCard == typedInFrom) return
        val shown = output
        typedInFrom = fromCard
        expression = Expression(shown?.copy?.let { Paste.read(it, style.decimalComma) } ?: emptyList())
        error = null
        changed()
    }

    fun selectUnit(fromCard: Boolean, unit: UnitDef) {
        if (!loaded) return
        if (fromCard) from = unit else to = unit
        changed()
    }

    /** All units: the typed value in every unit of the category (SPEC §7.4). */
    fun valueIn(unit: UnitDef, width: Int): Formatted? {
        val v = input ?: return null
        return try {
            Formatter.format(Units.convert(v, source, unit), style, width)
        } catch (_: CalcException) {
            null
        }
    }

    private fun changed() {
        refresh()
        save()
    }

    private fun refresh() {
        job?.cancel()
        val tokens = expression.tokens
        val src = source
        val dst = target
        val style = style
        val width = width
        if (tokens.isEmpty()) {
            output = null
            input = null
            inverseZero = false
            return
        }
        job = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runInterruptible {
                    val v = (Parser.preview(tokens, EvalContext()) as? Evaluation.Value)?.value ?: return@runInterruptible Outcome(null, null, false)
                    try {
                        Outcome(v, Formatter.format(Units.convert(v, src, dst), style, width), false)
                    } catch (e: CalcException) {
                        val inverse = src.scale is Scale.Inverse || dst.scale is Scale.Inverse
                        Outcome(v, null, inverse && e.error == CalcError.DIVISION_BY_ZERO)
                    }
                }
            }
            input = outcome.value
            output = outcome.formatted
            inverseZero = outcome.inverseZero
        }
    }

    private class Outcome(val value: Real?, val formatted: Formatted?, val inverseZero: Boolean)

    private fun save() {
        remember()
        saveJob?.cancel()
        val c = category
        val m = memories
        saveJob = viewModelScope.launch(Dispatchers.Default) {
            delay(300)
            store.save(c, m)
        }
    }

    fun saveNow() {
        if (!loaded) return
        remember()
        saveJob?.cancel()
        val c = category
        val m = memories
        viewModelScope.launch(Dispatchers.Default + NonCancellable) { store.save(c, m) }
    }
}
