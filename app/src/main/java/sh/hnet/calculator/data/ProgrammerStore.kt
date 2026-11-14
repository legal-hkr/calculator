package sh.hnet.calculator.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.TokenCodec
import sh.hnet.calculator.engine.programmer.Base
import sh.hnet.calculator.engine.programmer.Word
import java.math.BigInteger

private val Context.programmerState by preferencesDataStore("programmer")

/**
 * The Programmer's own state between runs (SPEC §8.7): expression and cursor, whether a result is
 * shown, its Ans, the input base, word size and sign. The word size and sign are settings of the
 * mode (Reset restores them, backups hold them); the rest is a memory.
 */
class ProgrammerStore(private val context: Context) {

    data class State(
        val expression: Expression = Expression(),
        val showsResult: Boolean = false,
        val ans: BigInteger? = null,
        val base: Base = Base.DEC,
        val word: Word = Word(),
    )

    private val tokens = stringPreferencesKey("tokens")
    private val cursor = intPreferencesKey("cursor")
    private val showsResult = booleanPreferencesKey("shows_result")
    private val ans = stringPreferencesKey("ans")
    private val base = stringPreferencesKey("base")
    private val bits = intPreferencesKey("bits")
    private val signed = booleanPreferencesKey("signed")

    suspend fun load(): State {
        val p = context.programmerState.data.first()
        val list = p[tokens]?.let { TokenCodec.decode(it) } ?: emptyList()
        val answer = p[ans]?.toBigIntegerOrNull()
        return State(
            expression = Expression(list, (p[cursor] ?: list.size).coerceIn(0, list.size)),
            showsResult = (p[showsResult] ?: false) && answer != null,
            ans = answer,
            base = p[base]?.let { name -> Base.entries.firstOrNull { it.name == name } } ?: Base.DEC,
            word = Word(p[bits]?.takeIf { it in setOf(8, 16, 32, 64) } ?: 64, p[signed] ?: true),
        )
    }

    /** From a backup or Reset: the word alone. */
    suspend fun saveWord(word: Word) {
        context.programmerState.edit { p ->
            p[bits] = word.bits
            p[signed] = word.signed
        }
    }

    suspend fun save(state: State) {
        val encoded = TokenCodec.encode(state.expression.tokens)
        context.programmerState.edit { p ->
            p[tokens] = encoded
            p[cursor] = state.expression.cursor
            p[showsResult] = state.showsResult
            if (state.ans != null) p[ans] = state.ans.toString() else p.remove(ans)
            p[base] = state.base.name
            p[bits] = state.word.bits
            p[signed] = state.word.signed
        }
    }
}
