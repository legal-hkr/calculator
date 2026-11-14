@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calculator.ui.converter

import android.content.ClipData
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sh.hnet.calculator.R
import sh.hnet.calculator.engine.convert.Category
import sh.hnet.calculator.engine.convert.UnitDef
import sh.hnet.calculator.engine.convert.UnitGroup
import sh.hnet.calculator.engine.convert.Units
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.ui.calculator.BasicKeypad
import sh.hnet.calculator.ui.calculator.ExpressionLine
import sh.hnet.calculator.ui.calculator.FittedLine
import sh.hnet.calculator.ui.calculator.message
import sh.hnet.calculator.ui.calculator.resultText
import java.util.Locale

private val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** A card's value: display-small, down to 28 sp, then sideways (SPEC §7.2). */
private val CARD_SIZES = listOf(36.sp, 28.sp)

private val Category.title: Int
    get() = when (this) {
        Category.LENGTH -> R.string.title_category_length
        Category.MASS -> R.string.title_category_mass
        Category.TEMPERATURE -> R.string.title_category_temperature
        Category.VOLUME -> R.string.title_category_volume
        Category.AREA -> R.string.title_category_area
        Category.SPEED -> R.string.title_category_speed
        Category.TIME -> R.string.title_category_time
        Category.DATA -> R.string.title_category_data
        Category.FUEL -> R.string.title_category_fuel
        Category.PRESSURE -> R.string.title_category_pressure
        Category.ENERGY -> R.string.title_category_energy
        Category.POWER -> R.string.title_category_power
        Category.ANGLE -> R.string.title_category_angle
        Category.FREQUENCY -> R.string.title_category_frequency
    }

private val UnitGroup.title: Int
    get() = when (this) {
        UnitGroup.METRIC -> R.string.title_unit_group_metric
        UnitGroup.IMPERIAL_US -> R.string.title_unit_group_imperial_us
        UnitGroup.OTHER -> R.string.title_unit_group_other
        UnitGroup.DECIMAL -> R.string.title_unit_group_decimal
        UnitGroup.BINARY -> R.string.title_unit_group_binary
        UnitGroup.BITS -> R.string.title_unit_group_bits
    }

private fun UnitDef.nameRes(): Int = unitName(category.name.lowercase(), id)

/** The unit's symbol in the app's language. */
@Composable
private fun UnitDef.symbolText(): String = unitSymbol(category.name.lowercase(), id)?.let { stringResource(it) } ?: symbol

/** "inch (in)" (SPEC §7.5). */
@Composable
private fun UnitDef.label(): String = stringResource(nameRes()) + " (" + symbolText() + ")"

/**
 * The Converter mode (SPEC §7): the category chips, the "from" and "to" cards with swap and All
 * units between them, and the basic keypad typing into the active card.
 */
@Composable
fun ConverterContent(style: NumberStyle, vibrate: Boolean) {
    val vm: ConverterViewModel = viewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveNow() }
    var unitSheet by remember { mutableStateOf<Boolean?>(null) }
    var allUnits by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) = scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }

    Column(Modifier.fillMaxSize()) {
        CategoryChips(vm.category, vm::selectCategory)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val measurer = rememberTextMeasurer()
            val cardStyle = MaterialTheme.typography.displaySmall.tabular
            val inner = constraints.maxWidth - with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.roundToPx() }
            val chars = remember(inner, cardStyle) {
                (inner / measurer.measure("0", cardStyle.copy(fontSize = CARD_SIZES.last())).size.width.coerceAtLeast(1) - 1).coerceAtLeast(4)
            }
            LaunchedEffect(style, chars) { vm.layout(style, chars) }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                UnitCard(vm, fromCard = true, style, inner, onUnit = { unitSheet = true })
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    val swap = stringResource(R.string.content_description_swap_units)
                    FilledTonalIconButton(onClick = vm::swap, modifier = Modifier.focusProperties { canFocus = false }.semantics { contentDescription = swap }) {
                        Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = null)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { allUnits = true }, modifier = Modifier.focusProperties { canFocus = false }) {
                        Icon(painterResource(R.drawable.ic_list), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.button_all_units))
                    }
                }
                UnitCard(vm, fromCard = false, style, inner, onUnit = { unitSheet = false })
            }
        }
        Spacer(Modifier.weight(1f))
        BasicKeypad(
            decimalSeparator = style.decimalSeparator,
            showsResult = false,
            vibrate = vibrate,
            onKey = vm::press,
            onEquals = vm::equals,
            onClear = vm::clear,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
    unitSheet?.let { fromCard ->
        UnitSheet(
            category = vm.category,
            current = if (fromCard) vm.from else vm.to,
            fromCard = fromCard,
            onPick = { vm.selectUnit(fromCard, it) },
            onDismiss = { unitSheet = null },
        )
    }
    if (allUnits) AllUnitsSheet(vm, onPick = { vm.selectUnit(fromCard = !vm.typedInFrom, it) }, onCopy = ::copy, onDismiss = { allUnits = false })
}

/** The categories as one scrolling row of single-choice chips, the current one in view (SPEC §7.1). */
@Composable
private fun CategoryChips(current: Category, onSelect: (Category) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { state.scrollToItem(Category.entries.indexOf(current)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(Category.entries) { _, c ->
            val isSelected = c == current
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(c) },
                label = { Text(stringResource(c.title)) },
                leadingIcon = if (isSelected) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
                modifier = Modifier.focusProperties { canFocus = false },
            )
        }
    }
}

/**
 * A card (SPEC §7.2): its unit at the top start, its value at the bottom end. The active card holds
 * the typed expression with its cursor; the other follows, and a tap makes it the active one.
 */
@Composable
private fun UnitCard(vm: ConverterViewModel, fromCard: Boolean, style: NumberStyle, width: Int, onUnit: () -> Unit) {
    val active = fromCard == vm.typedInFrom
    val unit = if (fromCard) vm.from else vm.to
    val scheme = MaterialTheme.colorScheme
    val cardStyle = MaterialTheme.typography.displaySmall.tabular
    val shape = RoundedCornerShape(20.dp)
    val unitName = stringResource(unit.nameRes())
    Column(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(shape)
            .background(if (active) scheme.surfaceContainerHighest else scheme.surfaceContainer)
            .then(if (active) Modifier else Modifier.clickable { vm.typeIn(fromCard) })
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        TextButton(onClick = onUnit, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.focusProperties { canFocus = false }) {
            Text(unit.label(), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            if (active) {
                ExpressionLine(
                    ExpressionText.render(vm.expression.tokens, style),
                    vm.expression.cursor,
                    vm.expression.unclosedAtEnd(),
                    cardStyle,
                    width,
                    onPlace = vm::moveCursor,
                    onCopy = {},
                    onPaste = null,
                    sizes = CARD_SIZES,
                )
            } else {
                val error = vm.error
                val output = vm.output
                val description = stringResource(if (fromCard) R.string.content_description_from else R.string.content_description_to, (output?.text ?: "") + " " + unitName)
                when {
                    error != null -> Text(stringResource(error.message), style = MaterialTheme.typography.bodyLarge, color = scheme.error, maxLines = 1)
                    vm.inverseZero -> Text(stringResource(R.string.msg_fuel_zero), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2)
                    else -> FittedLine(
                        if (output != null) resultText(output) else AnnotatedString(""),
                        cardStyle,
                        CARD_SIZES,
                        width,
                        Modifier.semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

/** Names and symbols in both languages, for the search (SPEC §7.3: "in" and "cal" both find inch). */
@Composable
private fun searchTexts(category: Category): Map<String, String> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(category, configuration) {
        val localized = listOf(Locale.ENGLISH, Locale.forLanguageTag("pl")).map { locale ->
            context.createConfigurationContext(Configuration(configuration).apply { setLocale(locale) }).resources
        }
        Units.of(category).associate { u ->
            val names = localized.joinToString(" ") { r ->
                r.getString(u.nameRes()) + " " + (unitSymbol(category.name.lowercase(), u.id)?.let { r.getString(it) } ?: "")
            }
            u.id to (names + " " + u.symbol).lowercase()
        }
    }
}

/** The unit sheet (SPEC §7.3): searchable, grouped; the current unit marked; a tap picks and closes. */
@Composable
private fun UnitSheet(category: Category, current: UnitDef, fromCard: Boolean, onPick: (UnitDef) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val texts = searchTexts(category)
    val units = Units.of(category).filter { query.isBlank() || texts[it.id]?.contains(query.trim().lowercase()) == true }
    val scheme = MaterialTheme.colorScheme
    // Opened in full at once: the search field sits at the top, clear of the keyboard (the sheet's
    // own option for a sheet with a text field)
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(category.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(if (fromCard) R.string.label_unit_source else R.string.label_unit_target), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.hint_search_units)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            units.groupBy { it.group }.forEach { (group, list) ->
                item(key = "group ${group.name}") {
                    Text(
                        stringResource(group.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    )
                }
                itemsIndexed(list, key = { _, u -> u.id }) { index, u ->
                    val isSelected = u.id == current.id
                    SegmentedListItem(
                        onClick = {
                            onPick(u)
                            onDismiss()
                        },
                        shapes = ListItemDefaults.segmentedShapes(index, list.size),
                        colors = ListItemDefaults.segmentedColors(containerColor = if (isSelected) scheme.secondaryContainer else scheme.surfaceContainer),
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.symbolText(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                                if (isSelected) {
                                    Spacer(Modifier.size(8.dp))
                                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        modifier = Modifier.padding(bottom = 2.dp).semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        },
                    ) {
                        Text(stringResource(u.nameRes()), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** All units (SPEC §7.4): the value in every unit of the category; a tap makes one the "to" unit, a long press copies. */
@Composable
private fun AllUnitsSheet(vm: ConverterViewModel, onPick: (UnitDef) -> Unit, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val units = Units.of(vm.category)
    val source = vm.source
    val sourceName = stringResource(source.nameRes())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.button_all_units), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val typed = vm.input?.let { vm.valueIn(source, 16)?.text }
            if (typed != null) Text("$typed $sourceName", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            itemsIndexed(units, key = { _, u -> u.id }) { index, u ->
                val value = vm.valueIn(u, 18)
                val isSelected = u.id == vm.target.id
                SegmentedListItem(
                    onClick = {
                        onPick(u)
                        onDismiss()
                    },
                    onLongClick = {
                        value?.let {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onCopy(it.copy)
                        }
                    },
                    shapes = ListItemDefaults.segmentedShapes(index, units.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = if (isSelected) scheme.secondaryContainer else scheme.surfaceContainer),
                    supportingContent = { Text(u.symbolText()) },
                    trailingContent = {
                        if (value != null) Text(resultText(value), style = MaterialTheme.typography.bodyLarge.tabular, maxLines = 1)
                    },
                    modifier = Modifier.padding(bottom = 2.dp).semantics { selected = isSelected },
                ) {
                    Text(stringResource(u.nameRes()), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
