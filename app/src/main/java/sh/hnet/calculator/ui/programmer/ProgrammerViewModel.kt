package sh.hnet.calculator.ui.programmer

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.data.HistoryEntry
import sh.hnet.calculator.data.HistoryMode
import sh.hnet.calculator.data.ProgrammerStore
import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.Key
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.expr.TokenCodec
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.Real
import sh.hnet.calculator.engine.programmer.Base
import sh.hnet.calculator.engine.programmer.Programmer
import sh.hnet.calculator.engine.programmer.ProgrammerEvaluation
import sh.hnet.calculator.engine.programmer.Word
import sh.hnet.calculator.ui.calculator.DisplayError
import java.math.BigInteger

/**
 * The Programmer's state (SPEC §8): its own expression, input base, word and History list. Integer
 * arithmetic is quick, so the live value is computed at once on every change.
 */
class ProgrammerViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProgrammerStore(application)
    private val history = (application as CalculatorApplication).history

    var loaded by mutableStateOf(false)
        private set
    var expression by mutableStateOf(Expression())
        private set
    var base by mutableStateOf(Base.DEC)
        private set
    var word by mutableStateOf(Word())
        private set

    /** The expression whose result is shown (after "="), else null. */
    var evaluated by mutableStateOf<List<Token>?>(null)
        private set
    var result by mutableStateOf<BigInteger?>(null)
        private set

    /** The expression's value while typing (the bases panel follows it), null when it has none yet. */
    var live by mutableStateOf<BigInteger?>(null)
        private set

    /** The value shown wrapped around the word (the note of SPEC §8.5). */
    var wrapped by mutableStateOf(false)
        private set
    var error by mutableStateOf<DisplayError?>(null)
        private set

    private var ans: BigInteger? = null

    /** History is kept (Settings › History): else nothing typed outlives the process and no entry is written. */
    @Volatile private var keep = true

    init {
        viewModelScope.launch {
            (application as CalculatorApplication).preferences.calcSettings.map { it.keepHistory }.distinctUntilChanged().collect {
                keep = it
                // Turned off: what was saved goes now, not at the next change
                if (!it) saveNow()
            }
        }
    }
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val s = store.load()
            expression = s.expression
            ans = s.ans
            base = s.base
            word = s.word
            if (s.showsResult && s.ans != null) {
                evaluated = s.expression.tokens
                result = s.ans
            }
            loaded = true
            refresh()
        }
    }

    val showsResult: Boolean get() = evaluated != null

    /** What the bases panel and the bit sheet show: the result, else the live value, else 0. */
    val value: BigInteger get() = result ?: live ?: BigInteger.ZERO

    /** A digit key is enabled when it is a digit of the input base (SPEC §8.4). */
    fun isDigitOf(d: Int): Boolean = d < base.radix

    fun press(key: Key) {
        if (!loaded) return
        error = null
        val digit = (key as? Key.Insert)?.token as? Token.Digit
        if (digit != null) {
            if (!isDigitOf(digit.value)) return
            // D7: a digit with which the number would not fit the word does not go in
            if (!showsResult && !Programmer.accepts(expression, digit.value, base, word)) return
        }
        expression = if (showsResult) {
            val last = ans
            evaluated = null
            result = null
            if (key == Key.Backspace || last == null) Expression().let { if (key == Key.Backspace) it else it.apply(key) }
            else Expression.afterResult(key, Programmer.asValue(last).value)
        } else {
            expression.apply(key)
        }
        changed()
    }

    fun clear() {
        if (!loaded) return
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
        if (!loaded || showsResult || expression.isEmpty) return
        val tokens = expression.tokens
        when (val e = Programmer.evaluate(tokens, base, word)) {
            is ProgrammerEvaluation.Value -> {
                ans = e.value
                result = e.value
                wrapped = e.wrapped
                evaluated = tokens
                record(tokens, e.value)
            }
            ProgrammerEvaluation.Incomplete -> error = DisplayError.INCOMPLETE
            is ProgrammerEvaluation.Failure -> {
                error = if (e.error == CalcError.DIVISION_BY_ZERO) DisplayError.DIVISION_BY_ZERO else DisplayError.MALFORMED
                e.position?.let { expression = expression.moveCursor(it) }
            }
        }
        save()
    }

    /** Another input base: the expression is written again in it (SPEC §8.3). */
    fun selectBase(b: Base) {
        if (!loaded || b == base) return
        expression = Programmer.rebase(expression, base, b, word)
        if (showsResult) evaluated = expression.tokens
        base = b
        changed()
    }

    /** Another word size: the value is cut to it, wrapped like an overflow (SPEC §8.2). */
    fun selectBits(bits: Int) {
        if (!loaded || bits == word.bits) return
        word = word.copy(bits = bits)
        rewrap()
    }

    /** Signed or unsigned: the same bits, read the other way. */
    fun toggleSigned() {
        if (!loaded) return
        val old = word
        word = word.copy(signed = !word.signed)
        result?.let { result = word.fromPattern(old.pattern(it)) }
        ans?.let { ans = word.fromPattern(old.pattern(it)) }
        refresh()
        save()
    }

    /** From Reset or a restored backup: the word replaced, the shown value cut to it. */
    fun applyWord(w: Word) {
        if (!loaded || w == word) return
        word = w
        rewrap()
    }

    private fun rewrap() {
        result?.let {
            val w = word.wrap(it)
            result = w.value
            ans = w.value
            wrapped = w.wrapped
        }
        refresh()
        save()
    }

    /** A bit flipped in the bit sheet: the expression becomes the new value in the input base (SPEC §8.6). */
    fun toggleBit(bit: Int) {
        if (!loaded) return
        val next = Programmer.toggleBit(value, bit, word)
        error = null
        evaluated = null
        result = null
        expression = Expression(Programmer.literal(next, base, word))
        changed()
    }

    /** A history entry's result at the cursor, as a whole number. */
    fun insertValue(v: Real) {
        v.rational?.takeIf { it.isInteger } ?: return
        press(Key.Insert(Token.Value(v)))
    }

    /** A history entry's expression in place of the current one, written in the current base. */
    fun replaceExpression(tokens: List<Token>, entryBase: String?) {
        if (!loaded) return
        val from = Base.entries.firstOrNull { it.name == entryBase } ?: base
        error = null
        evaluated = null
        result = null
        expression = Programmer.rebase(Expression(tokens), from, base, word)
        changed()
    }

    private fun record(tokens: List<Token>, v: BigInteger) {
        if (!keep) return
        val at = System.currentTimeMillis()
        val entry = HistoryEntry(
            mode = HistoryMode.PROGRAMMER.name,
            expression = TokenCodec.encode(tokens),
            result = TokenCodec.encodeReal(Programmer.asValue(v).value),
            createdAt = at,
            base = base.name,
            bits = word.bits,
            signed = word.signed,
        )
        viewModelScope.launch(Dispatchers.Default) { history.add(entry) }
    }

    private fun changed() {
        refresh()
        save()
    }

    private fun refresh() {
        if (showsResult) {
            live = null
            return
        }
        val tokens = expression.tokens
        val e = if (tokens.isEmpty()) null else Programmer.preview(tokens, base, word) as? ProgrammerEvaluation.Value
        live = e?.value
        wrapped = e?.wrapped ?: false
    }

    private fun state() =
        if (keep) ProgrammerStore.State(expression, showsResult, ans, base, word)
        else ProgrammerStore.State(base = base, word = word)

    private fun save() {
        saveJob?.cancel()
        val state = state()
        saveJob = viewModelScope.launch(Dispatchers.Default) {
            delay(300)
            store.save(state)
        }
    }

    fun saveNow() {
        if (!loaded) return
        saveJob?.cancel()
        val state = state()
        viewModelScope.launch(Dispatchers.Default + NonCancellable) { store.save(state) }
    }
}
