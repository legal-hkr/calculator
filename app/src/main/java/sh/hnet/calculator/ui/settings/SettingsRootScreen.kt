package sh.hnet.calculator.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.calculator.CalcSettings
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.format.Notation
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.Rational
import sh.hnet.calculator.engine.number.Real
import sh.hnet.calculator.ui.components.RowIcon
import sh.hnet.calculator.ui.components.SettingsSwitchRow
import sh.hnet.calculator.ui.components.settingsRowColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.calculator.BuildConfig
import sh.hnet.calculator.R
import sh.hnet.calculator.ui.components.SettingsGroup
import sh.hnet.calculator.ui.components.SettingsNavigationRow
import sh.hnet.calculator.ui.components.SettingsPage

/**
 * The Settings root (SPEC §13), grouped as Music's, Clock's and News's: Calculation, Keypad, then
 * App (Language), Data (Backup and restore, History, Reset), About on its own.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsRootScreen(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenReset: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as CalculatorApplication
    val settings by app.preferences.calcSettings.collectAsState(CalcSettings())
    val scope = rememberCoroutineScope()
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    // Read on every composition: changing it recreates the activity anyway
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    val decimalComma = LocalConfiguration.current.locales[0].language == "pl"
    val automatic = stringResource(R.string.label_automatic)
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup(stringResource(R.string.title_group_calculation)) {
            row { shapes ->
                val options: List<Int?> = listOf(null) + (0..10).toList()
                MenuRow(
                    shapes, R.drawable.ic_decimal_increase, stringResource(R.string.label_decimal_places),
                    current = settings.decimals?.toString() ?: automatic,
                    options = options.map { it?.toString() ?: automatic },
                    selected = options.indexOf(settings.decimals),
                ) { index -> scope.launch { app.preferences.setDecimals(options[index]) } }
            }
            row { shapes ->
                val notations = Notation.entries
                MenuRow(
                    shapes, R.drawable.ic_superscript, stringResource(R.string.label_scientific_notation),
                    current = stringResource(settings.notation.label),
                    options = notations.map { stringResource(it.label) },
                    descriptions = notations.map { stringResource(it.description) },
                    selected = notations.indexOf(settings.notation),
                ) { index -> scope.launch { app.preferences.setNotation(notations[index]) } }
            }
            row { shapes ->
                // The effect in the app's language (SPEC §13.1)
                val sample = Formatter.format(Real.of(Rational.parse("1234567.89")), NumberStyle(decimalComma, grouping = settings.grouping), 20).text
                SettingsSwitchRow(shapes, R.drawable.ic_numbers, stringResource(R.string.label_thousands_separator), settings.grouping, { on -> scope.launch { app.preferences.setGrouping(on) } }, supporting = sample)
            }
        }
        SettingsGroup(stringResource(R.string.title_group_keypad)) {
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_vibration, stringResource(R.string.label_vibrate_on_key), settings.vibrate, { on -> scope.launch { app.preferences.setVibrate(on) } })
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup), stringResource(R.string.desc_backup_row), onClick = onOpenBackup)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_history, stringResource(R.string.title_settings_history), stringResource(R.string.desc_settings_history_row), onClick = onOpenHistory)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}

private val Notation.label: Int
    get() = when (this) {
        Notation.AUTOMATIC -> R.string.label_automatic
        Notation.ALWAYS -> R.string.label_always
        Notation.NEVER -> R.string.label_never
    }

private val Notation.description: Int
    get() = when (this) {
        Notation.AUTOMATIC -> R.string.desc_notation_automatic
        Notation.ALWAYS -> R.string.desc_notation_always
        Notation.NEVER -> R.string.desc_notation_never
    }

/**
 * A row whose value is chosen from a menu (SPEC §13.1): the current value as its supporting text,
 * a drop-down arrow; the menu as the family's (DropdownMenuPopup with one group, the current item
 * checked).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MenuRow(
    shapes: ListItemShapes,
    @DrawableRes icon: Int,
    title: String,
    current: String,
    options: List<String>,
    selected: Int,
    descriptions: List<String>? = null,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SegmentedListItem(
            onClick = { open = true },
            shapes = shapes,
            colors = settingsRowColors(),
            leadingContent = { RowIcon(icon) },
            trailingContent = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            supportingContent = { Text(current) },
        ) {
            Text(title)
        }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                options.forEachIndexed { index, option ->
                    val isSelected = index == selected
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option, maxLines = 1)
                                descriptions?.getOrNull(index)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        },
                        trailingContent = {
                            if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        },
                        shape = MenuDefaults.itemShape(index, options.size).shape,
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                        },
                        onClick = {
                            open = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
    }
}
