package sh.hnet.calculator.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.R
import sh.hnet.calculator.ui.components.DestructiveButton
import sh.hnet.calculator.ui.components.SettingsDescription
import sh.hnet.calculator.ui.components.SettingsPage

/**
 * Settings › History (SPEC §13.4, as a page of the family – Clock's Reset, News's Storage): how
 * much is kept, and Clear history for both lists, at once with Undo.
 */
@Composable
fun HistorySettingsScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val app = LocalContext.current.applicationContext as CalculatorApplication
    val cleared = stringResource(R.string.msg_history_cleared)
    val undo = stringResource(R.string.button_undo)
    SettingsPage(title = stringResource(R.string.title_settings_history), onBack = onBack) {
        SettingsDescription(stringResource(R.string.desc_history_limit))
        DestructiveButton(stringResource(R.string.button_clear_history), R.drawable.ic_delete_sweep, {
            messageScope.launch {
                val stamp = app.history.clearAll()
                snackbars.currentSnackbarData?.dismiss()
                val result = snackbars.showSnackbar(cleared, actionLabel = undo, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) app.history.restore(stamp) else app.history.purge(stamp)
            }
        }, Modifier.padding(top = 8.dp))
    }
}
