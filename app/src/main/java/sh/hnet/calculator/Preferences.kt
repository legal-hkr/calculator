package sh.hnet.calculator

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.hnet.calculator.engine.format.Notation

private val Context.settings by preferencesDataStore("settings")

/** How results are written and keys feel (SPEC §13.1, §13.2); the page to set them comes with stage 6. */
data class CalcSettings(
    /** null: Automatic; 0–10 fixed. */
    val decimals: Int? = null,
    val notation: Notation = Notation.AUTOMATIC,
    val grouping: Boolean = true,
    val vibrate: Boolean = true,
)

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). Every stage adds its settings here. */
class Preferences(private val context: Context) {
    private val decimals = intPreferencesKey("decimals")
    private val notation = stringPreferencesKey("notation")
    private val grouping = booleanPreferencesKey("grouping")
    private val vibrate = booleanPreferencesKey("vibrate")

    val calcSettings: Flow<CalcSettings> = context.settings.data.map { p ->
        CalcSettings(
            decimals = p[decimals]?.takeIf { it in 0..10 },
            notation = p[notation]?.let { name -> Notation.entries.firstOrNull { it.name == name } } ?: Notation.AUTOMATIC,
            grouping = p[grouping] ?: true,
            vibrate = p[vibrate] ?: true,
        )
    }

    /** Restore defaults (Settings › Reset): every setting of this store back to its default. */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }
}
