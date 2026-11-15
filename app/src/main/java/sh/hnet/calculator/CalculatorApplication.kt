package sh.hnet.calculator

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import android.content.res.Configuration
import android.os.LocaleList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import sh.hnet.calculator.widget.CalculatorWidget
import sh.hnet.calculator.data.CalculatorDatabase
import sh.hnet.calculator.data.HistoryRepository
import sh.hnet.calculator.backup.BackupManager

class CalculatorApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val history: HistoryRepository by lazy { HistoryRepository(CalculatorDatabase.get(this).history()) }

    /** Memories that are not settings and survive Restore defaults: the last backup. */
    private val stamps by lazy { getSharedPreferences("stamps", MODE_PRIVATE) }

    val backup: BackupManager by lazy { BackupManager(this, stamps) }

    override fun onCreate() {
        super.onCreate()
        locales = resources.configuration.locales
        // Entries removed and waiting for an Undo when the process ended are gone for good
        appScope.launch(Dispatchers.IO) { history.purgeRemoved() }
        // The widget writes numbers by the Calculation settings: drawn again when they change (SPEC-widget §7.5)
        appScope.launch {
            preferences.calcSettings.map { Triple(it.decimals, it.notation, it.grouping) }.distinctUntilChanged().drop(1)
                .catch { /* Settings that can't be read: the widget keeps what it shows */ }
                .collect { CalculatorWidget.updateAll(this@CalculatorApplication) }
        }
    }

    /** The language the widget was last drawn in; any other change of configuration leaves it alone. */
    private var locales: LocaleList? = null

    /** The system's language changed (the app's own one recreates the activity, which redraws too). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val previous = locales
        locales = newConfig.locales
        if (previous != null && previous != newConfig.locales) CalculatorWidget.updateAll(this)
    }
}
