package sh.hnet.calculator

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.hnet.calculator.data.CalculatorDatabase
import sh.hnet.calculator.data.HistoryRepository

class CalculatorApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val history: HistoryRepository by lazy { HistoryRepository(CalculatorDatabase.get(this).history()) }

    override fun onCreate() {
        super.onCreate()
        // Entries removed and waiting for an Undo when the process ended are gone for good
        appScope.launch(Dispatchers.IO) { history.purgeRemoved() }
    }
}
