package sh.hnet.calculator.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.hnet.calculator.engine.expr.Expression
import sh.hnet.calculator.engine.expr.TokenCodec
import sh.hnet.calculator.engine.number.AngleUnit
import sh.hnet.calculator.engine.number.Real

private val Context.calculatorState by preferencesDataStore("calculator")

/** The app's modes (SPEC §4.1). */
enum class AppMode { CALCULATOR, PROGRAMMER, CONVERTER }

/**
 * The calculator's state between runs (SPEC §4.1): the expression with its cursor, whether a
 * result is shown, Ans, the angle unit and the scientific panel's state. A memory, not a setting: Reset leaves it.
 */
class CalculatorStore(private val context: Context) {

    data class State(
        val expression: Expression = Expression(),
        /** The expression's result is on the main line (after "="). */
        val showsResult: Boolean = false,
        val ans: Real? = null,
        val angle: AngleUnit = AngleUnit.DEGREES,
        /** The scientific panel is open (SPEC §5.4). */
        val panelOpen: Boolean = false,
        /** 2nd is on. */
        val second: Boolean = false,
    )

    private val tokens = stringPreferencesKey("tokens")
    private val cursor = intPreferencesKey("cursor")
    private val showsResult = booleanPreferencesKey("shows_result")
    private val ans = stringPreferencesKey("ans")
    private val angle = stringPreferencesKey("angle")
    private val panelOpen = booleanPreferencesKey("panel_open")
    private val second = booleanPreferencesKey("second")

    private val mode = stringPreferencesKey("mode")

    /** The mode used last: the app opens on it (SPEC §3). A memory: not in backups, Reset leaves it. */
    val lastMode: Flow<AppMode> = context.calculatorState.data.map { p ->
        p[mode]?.let { name -> AppMode.entries.firstOrNull { it.name == name } } ?: AppMode.CALCULATOR
    }

    suspend fun saveMode(m: AppMode) {
        context.calculatorState.edit { it[mode] = m.name }
    }

    suspend fun load(): State {
        val p = context.calculatorState.data.first()
        val list = p[tokens]?.let { TokenCodec.decode(it) } ?: emptyList()
        val at = (p[cursor] ?: list.size).coerceIn(0, list.size)
        val answer = p[ans]?.let { TokenCodec.decodeReal(it) }
        return State(
            expression = Expression(list, at),
            showsResult = (p[showsResult] ?: false) && answer != null,
            ans = answer,
            angle = p[angle]?.let { name -> AngleUnit.entries.firstOrNull { it.name == name } } ?: AngleUnit.DEGREES,
            panelOpen = p[panelOpen] ?: false,
            second = p[second] ?: false,
        )
    }

    /** History turned off: what was typed and the last result go; the angle unit and the panel stay. */
    suspend fun forget() {
        context.calculatorState.edit { p ->
            p.remove(tokens)
            p.remove(cursor)
            p.remove(showsResult)
            p.remove(ans)
        }
    }

    /** From a backup or Reset: the angle unit alone. */
    suspend fun saveAngle(a: AngleUnit) {
        context.calculatorState.edit { it[angle] = a.name }
    }

    suspend fun save(state: State) {
        // Encoded before the edit: an inexact Ans is written out to its digits once, off the main thread
        val encodedTokens = TokenCodec.encode(state.expression.tokens)
        val encodedAns = state.ans?.let { TokenCodec.encodeReal(it) }
        context.calculatorState.edit { p ->
            p[tokens] = encodedTokens
            p[cursor] = state.expression.cursor
            p[showsResult] = state.showsResult
            if (encodedAns != null) p[ans] = encodedAns else p.remove(ans)
            p[angle] = state.angle.name
            p[panelOpen] = state.panelOpen
            p[second] = state.second
        }
    }
}
