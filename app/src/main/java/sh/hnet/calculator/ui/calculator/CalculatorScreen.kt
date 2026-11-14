@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.calculator.ui.calculator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.calculator.CalcSettings
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.R
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.ui.components.TooltipIconButton

/**
 * The Calculator mode (SPEC §5, §6; stage 1): top bar with the mode's name and Settings (History
 * joins with stage 3, the mode menu with stage 4), the display, the scientific panel's first row
 * and the basic keypad.
 */
@Composable
fun CalculatorScreen(onOpenSettings: () -> Unit) {
    val vm: CalculatorViewModel = viewModel()
    val app = LocalContext.current.applicationContext as CalculatorApplication
    val settings by app.preferences.calcSettings.collectAsState(CalcSettings())
    // Numbers follow the app's language, not the region (SPEC §2.9)
    val decimalComma = LocalConfiguration.current.locales[0].language == "pl"
    val style = remember(decimalComma, settings) {
        NumberStyle(decimalComma = decimalComma, grouping = settings.grouping, decimals = settings.decimals, notation = settings.notation)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveNow() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_mode_calculator), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.tooltip_settings), onOpenSettings) },
        )
        Display(vm, style, Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        ScientificRow(settings.vibrate, vm::press, Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(8.dp))
        BasicKeypad(
            decimalSeparator = style.decimalSeparator,
            showsResult = vm.showsResult,
            vibrate = settings.vibrate,
            onKey = vm::press,
            onEquals = vm::equals,
            onClear = vm::clear,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}
