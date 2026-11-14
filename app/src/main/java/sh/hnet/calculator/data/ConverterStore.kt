package sh.hnet.calculator.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import sh.hnet.calculator.engine.convert.Category
import sh.hnet.calculator.engine.convert.Units
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.expr.TokenCodec

private val Context.converterState by preferencesDataStore("converter")

/**
 * The Converter's state (SPEC §7.2): the category used last and, for each category, its unit pair,
 * the value typed and which card it is typed in. The pairs are settings of the mode (Reset
 * restores them, backups hold them); the values are a memory.
 */
class ConverterStore(private val context: Context) {

    data class Memory(val from: String, val to: String, val tokens: List<Token> = emptyList(), val typedInFrom: Boolean = true)

    data class State(val category: Category, val memories: Map<Category, Memory>)

    private val category = stringPreferencesKey("category")
    private fun pair(c: Category) = stringPreferencesKey("pair_${c.name}")
    private fun text(c: Category) = stringPreferencesKey("text_${c.name}")
    private fun typedInFrom(c: Category) = booleanPreferencesKey("from_${c.name}")

    suspend fun load(): State {
        val p = context.converterState.data.first()
        val memories = Category.entries.associateWith { c -> memory(p, c) }
        val current = p[category]?.let { name -> Category.entries.firstOrNull { it.name == name } } ?: Category.LENGTH
        return State(current, memories)
    }

    private fun memory(p: Preferences, c: Category): Memory {
        val (defaultFrom, defaultTo) = Units.defaultPairs.getValue(c)
        val stored = p[pair(c)]?.split(',')?.takeIf { it.size == 2 && it.all { id -> Units.find(c, id) != null } }
        return Memory(
            from = stored?.get(0) ?: defaultFrom,
            to = stored?.get(1) ?: defaultTo,
            tokens = p[text(c)]?.let { TokenCodec.decode(it) } ?: emptyList(),
            typedInFrom = p[typedInFrom(c)] ?: true,
        )
    }

    suspend fun save(current: Category, memories: Map<Category, Memory>) {
        val encoded = memories.mapValues { TokenCodec.encode(it.value.tokens) }
        context.converterState.edit { p ->
            p[category] = current.name
            memories.forEach { (c, m) ->
                p[pair(c)] = "${m.from},${m.to}"
                p[text(c)] = encoded.getValue(c)
                p[typedInFrom(c)] = m.typedInFrom
            }
        }
    }

    /** Settings › Reset: every category back to its default pair; the values stay. */
    suspend fun restoreDefaultPairs() {
        context.converterState.edit { p -> Category.entries.forEach { p.remove(pair(it)) } }
    }
}
