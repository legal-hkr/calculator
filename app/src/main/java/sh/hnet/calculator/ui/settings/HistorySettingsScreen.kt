package sh.hnet.calculator.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import sh.hnet.calculator.CalcSettings
import sh.hnet.calculator.data.CalculatorStore
import sh.hnet.calculator.data.ConverterStore
import sh.hnet.calculator.data.ProgrammerStore
import sh.hnet.calculator.ui.components.ConfirmDestructiveDialog
import sh.hnet.calculator.ui.components.SettingsGroup
import sh.hnet.calculator.ui.components.SettingsSwitchRow
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
 * Settings › History (SPEC §13.4, as a page of the family – Clock's Reset, News's Storage): whether
 * history is kept at all, how much, and Clear history for both lists at once with Undo. Turning it
 * off (owner, 15 Nov 2026) asks first, then removes both lists and what the modes remembered
 * between runs; from then on nothing typed outlives the app.
 */
@Composable
fun HistorySettingsScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val app = LocalContext.current.applicationContext as CalculatorApplication
    val settings by app.preferences.calcSettings.collectAsState(CalcSettings())
    var confirming by rememberSaveable { mutableStateOf(false) }
    val cleared = stringResource(R.string.msg_history_cleared)
    val undo = stringResource(R.string.button_undo)
    SettingsPage(title = stringResource(R.string.title_settings_history), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_history, stringResource(R.string.label_keep_history), settings.keepHistory,
                    { on -> if (on) messageScope.launch { app.preferences.setKeepHistory(true) } else confirming = true },
                    supporting = stringResource(if (settings.keepHistory) R.string.desc_keep_history_on else R.string.desc_keep_history_off),
                )
            }
        }
        if (settings.keepHistory) {
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
    if (confirming) {
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_history,
            title = stringResource(R.string.title_confirm_history_off),
            text = AnnotatedString(stringResource(R.string.msg_confirm_history_off)),
            confirmLabel = stringResource(R.string.button_turn_off),
            onConfirm = {
                confirming = false
                messageScope.launch {
                    app.preferences.setKeepHistory(false)
                    app.history.deleteAll()
                    CalculatorStore(app).forget()
                    ProgrammerStore(app).forget()
                    ConverterStore(app).forget()
                }
            },
            onDismiss = { confirming = false },
        )
    }
}
