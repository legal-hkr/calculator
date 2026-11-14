@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.calculator.ui.calculator

import android.content.ClipData
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key as KeyCode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.calculator.CalcSettings
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.R
import sh.hnet.calculator.engine.expr.Key
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.PostOp
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.ui.components.TooltipIconButton

/**
 * The Calculator mode (SPEC §5, §6): top bar with the mode's name and Settings (History joins with
 * stage 3, the mode menu with stage 4), the display, the scientific panel and the basic keypad. A
 * physical keyboard types too (§5.5).
 */
@Composable
fun CalculatorScreen(onOpenSettings: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val vm: CalculatorViewModel = viewModel()
    val app = LocalContext.current.applicationContext as CalculatorApplication
    val settings by app.preferences.calcSettings.collectAsState(CalcSettings())
    // Numbers follow the app's language, not the region (SPEC §2.9)
    val decimalComma = LocalConfiguration.current.locales[0].language == "pl"
    val style = remember(decimalComma, settings) {
        NumberStyle(decimalComma = decimalComma, grouping = settings.grouping, decimals = settings.decimals, notation = settings.notation)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveNow() }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pasteFailed = stringResource(R.string.error_paste)
    fun copy(text: String?) {
        if (text.isNullOrEmpty()) return
        // No snackbar: Android shows its own clipboard confirmation (SPEC §6.6)
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
    }
    fun paste() {
        scope.launch {
            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text == null || !vm.paste(text)) {
                messageScope.launch {
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(pasteFailed, duration = SnackbarDuration.Short)
                }
            }
        }
    }
    val displayClipboard = remember(vm) { DisplayClipboard(copyResult = { copy(vm.resultForCopy()) }, copyExpression = { copy(vm.expressionForCopy()) }, paste = { paste() }) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event -> typed(event, vm, onCopy = { copy(if (vm.showsResult) vm.resultForCopy() else vm.expressionForCopy()) }, onPaste = { paste() }) },
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_mode_calculator), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.tooltip_settings), onOpenSettings) },
        )
        Display(vm, style, displayClipboard, Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        ScientificPanel(
            open = vm.panelOpen,
            second = vm.second,
            hasAns = vm.hasAns,
            vibrate = settings.vibrate,
            actions = remember(vm) { ScientificActions(vm::press, vm::insertExp, vm::insertPowerOfTen, vm::insertAns, vm::toggleSecond, vm::setPanel) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
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

/**
 * A physical keyboard (SPEC §5.5): digits, `.` and `,`, `+ - * /`, `^ % ! ( )`, Enter and `=`,
 * Backspace, Delete, Escape, the arrows, Ctrl+C and Ctrl+V. Letters are not mapped. True when used.
 */
private fun typed(event: KeyEvent, vm: CalculatorViewModel, onCopy: () -> Unit, onPaste: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed) {
        when (event.key) {
            KeyCode.C -> onCopy()
            KeyCode.V -> onPaste()
            else -> return false
        }
        return true
    }
    when (event.key) {
        KeyCode.Enter, KeyCode.NumPadEnter -> vm.equals()
        KeyCode.Backspace -> vm.press(Key.Backspace)
        KeyCode.Delete -> vm.press(Key.DeleteForward)
        KeyCode.Escape -> vm.clear()
        KeyCode.DirectionLeft -> vm.moveCursor(vm.expression.cursor - 1)
        KeyCode.DirectionRight -> vm.moveCursor(vm.expression.cursor + 1)
        else -> {
            val key = when (val c = event.utf16CodePoint.toChar()) {
                in '0'..'9' -> Key.Insert(Token.Digit(c - '0'))
                '.', ',' -> Key.Insert(Token.Point)
                '+' -> Key.Insert(Token.Operator(Op.PLUS))
                '-' -> Key.Insert(Token.Operator(Op.MINUS))
                '*' -> Key.Insert(Token.Operator(Op.TIMES))
                '/' -> Key.Insert(Token.Operator(Op.DIVIDE))
                '^' -> Key.Insert(Token.Operator(Op.POWER))
                '%' -> Key.Insert(Token.Postfix(PostOp.PERCENT))
                '!' -> Key.Insert(Token.Postfix(PostOp.FACTORIAL))
                '(' -> Key.OpenBracket
                ')' -> Key.Insert(Token.Close)
                '=' -> {
                    vm.equals()
                    return true
                }
                else -> return false
            }
            vm.press(key)
        }
    }
    return true
}
