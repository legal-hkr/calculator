@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calculator.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.calculator.CalcSettings
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.R
import sh.hnet.calculator.data.AppMode
import sh.hnet.calculator.data.CalculatorStore
import sh.hnet.calculator.data.HistoryMode
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.ui.calculator.CalculatorContent
import sh.hnet.calculator.ui.components.TooltipIconButton
import sh.hnet.calculator.ui.programmer.ProgrammerContent
import sh.hnet.calculator.ui.converter.ConverterContent

/**
 * The app's root (SPEC §4): the top bar – the mode's name opening the mode menu, History, Settings –
 * over the current mode. A mode switch crossfades and is not a back step; the app opens on the mode
 * used last.
 */
@Composable
fun MainScreen(onOpenSettings: () -> Unit, onOpenHistory: (HistoryMode) -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val app = LocalContext.current.applicationContext as CalculatorApplication
    val store = remember { CalculatorStore(app) }
    val mode by store.lastMode.collectAsState(null)
    val settings by app.preferences.calcSettings.collectAsState(CalcSettings())
    // Numbers follow the app's language, not the region (SPEC §2.9)
    val decimalComma = LocalConfiguration.current.locales[0].language == "pl"
    val style = remember(decimalComma, settings) {
        NumberStyle(decimalComma = decimalComma, grouping = settings.grouping, decimals = settings.decimals, notation = settings.notation)
    }
    val scope = rememberCoroutineScope()
    val current = mode ?: return
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { ModeButton(current) { scope.launch { store.saveMode(it) } } },
            actions = {
                // The Converter has no History: its results never go there (SPEC §7.2)
                if (current != AppMode.CONVERTER) {
                    TooltipIconButton(R.drawable.ic_history, stringResource(R.string.tooltip_history), {
                        onOpenHistory(if (current == AppMode.PROGRAMMER) HistoryMode.PROGRAMMER else HistoryMode.CALCULATOR)
                    })
                }
                TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.tooltip_settings), onOpenSettings)
            },
        )
        val motion = MaterialTheme.motionScheme
        AnimatedContent(current, transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) }, label = "mode") { m ->
            when (m) {
                AppMode.CALCULATOR -> CalculatorContent(style, settings.vibrate, snackbars, messageScope)
                AppMode.PROGRAMMER -> ProgrammerContent(style, settings.vibrate)
                AppMode.CONVERTER -> ConverterContent(style, settings.vibrate)
            }
        }
    }
}

private val AppMode.title: Int
    get() = when (this) {
        AppMode.CALCULATOR -> R.string.title_mode_calculator
        AppMode.PROGRAMMER -> R.string.title_mode_programmer
        AppMode.CONVERTER -> R.string.title_mode_converter
    }

private val AppMode.icon: Int
    @DrawableRes get() = when (this) {
        AppMode.CALCULATOR -> R.drawable.ic_calculate
        AppMode.PROGRAMMER -> R.drawable.ic_code
        AppMode.CONVERTER -> R.drawable.ic_swap_horiz
    }

/** The mode's name with a drop-down arrow; the menu lists the modes, the current one checked (SPEC §4.2). */
@Composable
private fun ModeButton(current: AppMode, onSelect: (AppMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = stringResource(current.title)
    val description = stringResource(R.string.content_description_mode, name)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                AppMode.entries.forEachIndexed { index, m ->
                    val isSelected = m == current
                    DropdownMenuItem(
                        text = { Text(stringResource(m.title), maxLines = 1) },
                        leadingIcon = { Icon(painterResource(m.icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        trailingContent = {
                            if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        },
                        shape = MenuDefaults.itemShape(index, AppMode.entries.size).shape,
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        },
                        onClick = {
                            open = false
                            if (!isSelected) onSelect(m)
                        },
                    )
                }
            }
        }
    }
}
