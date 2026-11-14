package sh.hnet.calculator

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    /**
     * History and everything typed are kept between runs (owner, 15 Nov 2026). Off: no History
     * entry is written, and the expressions, the last result and the converter's values live only
     * while the app runs.
     */
    val keepHistory: Boolean = true,
)

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). Every stage adds its settings here. */
class Preferences(private val context: Context) {
    private val decimals = intPreferencesKey("decimals")
    private val notation = stringPreferencesKey("notation")
    private val grouping = booleanPreferencesKey("grouping")
    private val vibrate = booleanPreferencesKey("vibrate")
    private val keepHistory = booleanPreferencesKey("keep_history")

    val calcSettings: Flow<CalcSettings> = context.settings.data.map { p ->
        CalcSettings(
            decimals = p[decimals]?.takeIf { it in 0..10 },
            notation = p[notation]?.let { name -> Notation.entries.firstOrNull { it.name == name } } ?: Notation.AUTOMATIC,
            grouping = p[grouping] ?: true,
            vibrate = p[vibrate] ?: true,
            keepHistory = p[keepHistory] ?: true,
        )
    }

    suspend fun setDecimals(value: Int?) = context.settings.edit { if (value == null) it.remove(decimals) else it[decimals] = value }
    suspend fun setNotation(value: Notation) = context.settings.edit { it[notation] = value.name }
    suspend fun setGrouping(value: Boolean) = context.settings.edit { it[grouping] = value }
    suspend fun setVibrate(value: Boolean) = context.settings.edit { it[vibrate] = value }
    suspend fun setKeepHistory(value: Boolean) = context.settings.edit { it[keepHistory] = value }

    /** For a backup. */
    suspend fun current(): CalcSettings = calcSettings.first()

    /** From a backup: every setting replaced. */
    suspend fun replace(s: CalcSettings) = context.settings.edit { p ->
        if (s.decimals == null) p.remove(decimals) else p[decimals] = s.decimals
        p[notation] = s.notation.name
        p[grouping] = s.grouping
        p[vibrate] = s.vibrate
        p[keepHistory] = s.keepHistory
    }

    /** Restore defaults (Settings › Reset): every setting of this store back to its default. */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }
}
