@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calculator.ui.history

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.calculator.CalcSettings
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.R
import sh.hnet.calculator.data.HistoryEntry
import sh.hnet.calculator.data.HistoryMode
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.expr.TokenCodec
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.Real
import sh.hnet.calculator.engine.programmer.Base
import sh.hnet.calculator.engine.programmer.Programmer
import sh.hnet.calculator.engine.programmer.Word
import sh.hnet.calculator.ui.programmer.groupsOf
import sh.hnet.calculator.ui.programmer.programmerValueCopy
import sh.hnet.calculator.ui.programmer.programmerValueText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.pluralStringResource
import sh.hnet.calculator.ui.components.EmptyState
import sh.hnet.calculator.ui.components.MenuItem
import sh.hnet.calculator.ui.components.SwipeToRemove
import sh.hnet.calculator.ui.components.TooltipIconButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WORD_SIZES = setOf(8, 16, 32, 64)

/** An entry decoded for the list: its parts as the display writes them. */
private class Shown(val entry: HistoryEntry, val tokens: List<Token>, val value: Real, val day: LocalDate) {
    /** A Programmer entry's value, base and word, else null. */
    val programmer: Triple<java.math.BigInteger, Base, Word>? =
        entry.base?.let { name ->
            val base = Base.entries.firstOrNull { it.name == name } ?: return@let null
            val n = value.rational?.takeIf { it.isInteger }?.num ?: return@let null
            // A word size that isn't one of the four (a damaged backup) reads as 64 bits rather than failing
            Triple(n, base, Word(entry.bits?.takeIf { it in WORD_SIZES } ?: 64, entry.signed ?: true))
        }
}

/**
 * A mode's History (SPEC §10), a page of the family opened by its top-bar icon: oldest to newest, opened at the end, in day groups as News's list.
 * A tap puts the result at the cursor and closes the page; a long press offers Insert expression,
 * the copies and Delete; a swipe removes, Clear history empties the list, both at once with Undo.
 */
@Composable
fun HistoryScreen(
    mode: HistoryMode,
    onBack: () -> Unit,
    onInsertResult: (Real) -> Unit,
    onInsertExpression: (List<Token>, String?) -> Unit,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
) {
    val app = LocalContext.current.applicationContext as CalculatorApplication
    val settings by app.preferences.calcSettings.collectAsState(CalcSettings())
    val decimalComma = LocalConfiguration.current.locales[0].language == "pl"
    val style = remember(decimalComma, settings) {
        NumberStyle(decimalComma = decimalComma, grouping = settings.grouping, decimals = settings.decimals, notation = settings.notation)
    }
    val entries by app.history.observe(mode).collectAsState(null)
    val zone = remember { ZoneId.systemDefault() }
    val shown = remember(entries) {
        entries?.mapNotNull { e ->
            val tokens = TokenCodec.decode(e.expression) ?: return@mapNotNull null
            val value = TokenCodec.decodeReal(e.result) ?: return@mapNotNull null
            Shown(e, tokens, value, Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalDate())
        }
    }
    val today = LocalDate.now(zone)

    val deleted = stringResource(R.string.msg_entry_deleted)
    val cleared = stringResource(R.string.msg_history_cleared)
    val undo = stringResource(R.string.button_undo)
    // Removed at once; Undo puts it back, the end of the snackbar makes it final (as News)
    fun offerUndo(message: String, stamp: Long) {
        messageScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) app.history.restore(stamp) else app.history.purge(stamp)
        }
    }
    fun remove(entry: HistoryEntry) = messageScope.launch { offerUndo(deleted, app.history.remove(entry.id)) }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) = scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            val navigation: @Composable () -> Unit = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) }
            val title: @Composable () -> Unit = { Text(stringResource(R.string.title_history), modifier = Modifier.semantics { heading() }) }
            val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                if (!shown.isNullOrEmpty()) {
                    TooltipIconButton(R.drawable.ic_delete_sweep, stringResource(R.string.tooltip_clear_history), {
                        messageScope.launch { offerUndo(cleared, app.history.clear(mode)) }
                    })
                }
            }
            // The Programmer's list says whose it is (SPEC §10.3)
            if (mode == HistoryMode.PROGRAMMER) {
                TopAppBar(title = title, subtitle = { Text(stringResource(R.string.title_mode_programmer)) }, navigationIcon = navigation, actions = actions)
            } else {
                TopAppBar(title = title, navigationIcon = navigation, actions = actions)
            }
            val list = shown ?: return@Column
            if (list.isEmpty()) {
                // The app's mark is drawn on the launcher icon's canvas, hence the larger size
                EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_history_empty), iconSize = 108.dp)
                return@Column
            }
            val state = rememberLazyListState()
            var opened by remember { mutableStateOf(false) }
            LaunchedEffect(list.size) {
                // Opened at the newest entry, at the bottom
                if (!opened) {
                    // Rows and one header a day
                    state.scrollToItem(list.size + list.map { it.day }.distinct().size - 1)
                    opened = true
                }
            }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                list.forEachIndexed { index, item ->
                    val first = index == 0 || list[index - 1].day != item.day
                    val last = index == list.lastIndex || list[index + 1].day != item.day
                    if (first) {
                        item(key = "day ${item.day}", contentType = "day") {
                            Text(
                                dayHeader(item.day, today),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                            )
                        }
                    }
                    item(key = item.entry.id, contentType = "entry") {
                        val top = if (first) 20.dp else 6.dp
                        val bottom = if (last) 20.dp else 6.dp
                        val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
                        Box(Modifier.padding(bottom = if (last) 0.dp else 2.dp)) {
                            SwipeToRemove(shape, onRemove = { remove(item.entry) }) {
                                EntryRow(
                                    item, style, shape,
                                    onInsert = {
                                        onInsertResult(item.value)
                                        onBack()
                                    },
                                    onInsertExpression = {
                                        onInsertExpression(item.tokens, item.entry.base)
                                        onBack()
                                    },
                                    onCopyResult = {
                                        val p = item.programmer
                                        copy(if (p != null) Programmer.copy(p.first, p.second, p.third) else Formatter.format(item.value, style, COPY_WIDTH).copy)
                                    },
                                    onCopyExpression = {
                                        val p = item.programmer
                                        copy(ExpressionText.ascii(item.tokens, style, p?.let { programmerValueCopy(it.second, it.third) }))
                                    },
                                    onDelete = { remove(item.entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Digits a copied result carries: well beyond a line, as the display's copy after scrolling. */
private const val COPY_WIDTH = 60

@Composable
private fun EntryRow(
    item: Shown,
    style: NumberStyle,
    shape: androidx.compose.ui.graphics.Shape,
    onInsert: () -> Unit,
    onInsertExpression: () -> Unit,
    onCopyResult: () -> Unit,
    onCopyExpression: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val p = item.programmer
    val expression = remember(item, style) {
        if (p != null) ExpressionText.render(item.tokens, style, programmerValueText(p.second, p.third, style), groupsOf(p.second)).text
        else ExpressionText.render(item.tokens, style).text
    }
    val result: AnnotatedString = if (p != null) {
        AnnotatedString(Programmer.text(p.first, p.second, p.third, style))
    } else {
        val formatted = remember(item, style) { Formatter.format(item.value, style, ROW_WIDTH) }
        buildAnnotatedString {
            append(formatted.text)
            if (!formatted.exact) withStyle(SpanStyle(color = muted)) { append('…') }
            formatted.exponent?.let { append(" × 10" + Formatter.superscript(it)) }
        }
    }
    // "HEX, 8 bits, unsigned" (SPEC §10.3)
    val tag = p?.let {
        stringResource(
            R.string.label_history_tag,
            it.second.name,
            pluralStringResource(R.plurals.label_word_size, it.third.bits, it.third.bits),
            stringResource(if (it.third.signed) R.string.label_signed else R.string.label_unsigned).lowercase(),
        )
    }
    val pressed = MaterialTheme.colorScheme.surfaceContainerHighest
    Box {
        SegmentedListItem(
            onClick = onInsert,
            onLongClick = { menu = true },
            shapes = ListItemDefaults.segmentedShapes(0, 1).copy(shape = shape),
            colors = if (menu) ListItemDefaults.segmentedColors(containerColor = pressed) else ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { contentDescription = "$expression = ${result.text}" },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (tag != null) Text(tag, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
                // Long lines are cut at their start, so their end stays in view (SPEC §10.3)
                Text(expression, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                Text(result, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
        DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                MenuItem(stringResource(R.string.menu_insert_expression), R.drawable.ic_input, 0, 3) { menu = false; onInsertExpression() }
                MenuItem(stringResource(R.string.menu_copy_result), R.drawable.ic_content_copy, 1, 3) { menu = false; onCopyResult() }
                MenuItem(stringResource(R.string.menu_copy_expression), R.drawable.ic_content_copy, 2, 3) { menu = false; onCopyExpression() }
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                MenuItem(stringResource(R.string.menu_delete), R.drawable.ic_delete, 0, 1, error = true) { menu = false; onDelete() }
            }
        }
    }
}

/** A result's characters in a row. */
private const val ROW_WIDTH = 24

/** "Today", "Yesterday", then "Thursday, 12 November", with the year when it is not this one (as News). */
@Composable
private fun dayHeader(day: LocalDate, today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (day) {
        today -> stringResource(R.string.label_today)
        today.minusDays(1) -> stringResource(R.string.label_yesterday)
        else -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (day.year == today.year) "EEEEdMMMM" else "EEEEdMMMMy"), locale).format(day)
            .replaceFirstChar { it.titlecase(locale) }
    }
}
