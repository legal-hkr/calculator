@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package sh.hnet.calculator.ui.programmer

import android.content.ClipData
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sh.hnet.calculator.R
import sh.hnet.calculator.engine.expr.Key
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.format.DigitGroups
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.programmer.Base
import sh.hnet.calculator.engine.programmer.Programmer
import sh.hnet.calculator.engine.programmer.Word
import sh.hnet.calculator.ui.calculator.CalcKey
import sh.hnet.calculator.ui.calculator.ExpressionLine
import sh.hnet.calculator.ui.calculator.FittedLine
import sh.hnet.calculator.ui.calculator.KeyGroup
import sh.hnet.calculator.ui.calculator.KeyIcon
import sh.hnet.calculator.ui.calculator.KeyText
import sh.hnet.calculator.ui.calculator.message

private val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** The expression line's sizes in the Programmer: display-small, then smaller, then sideways (SPEC §8.1). */
private val PROGRAMMER_SIZES = listOf(36.sp, 28.sp, 24.sp)

/** Digits grouped by the base (SPEC §8.3): HEX and BIN by 4, OCT by 3, DEC by the language. */
fun groupsOf(base: Base): DigitGroups? = when (base) {
    Base.HEX, Base.BIN -> DigitGroups(4, ' ')
    Base.OCT -> DigitGroups(3, ' ')
    Base.DEC -> null
}

/** A whole number (Ans, a history value) in an expression of the Programmer: in the input base. */
fun programmerValueText(base: Base, word: Word, style: NumberStyle): (sh.hnet.calculator.engine.number.Real) -> String =
    { v -> v.rational?.takeIf { it.isInteger }?.let { Programmer.text(it.num, base, word, style) } ?: "?" }

/**
 * The Programmer mode (SPEC §8): word size and sign chips, the expression (and the result after
 * "="), the overflow note, the bases panel and the 6 × 6 keypad; the bit sheet from the BIN row.
 */
@Composable
fun ProgrammerContent(style: NumberStyle, vibrate: Boolean) {
    val vm: ProgrammerViewModel = viewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveNow() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) = scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
    var bitsOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { typed(it, vm) { copy(Programmer.copy(vm.value, vm.base, vm.word)) } },
    ) {
        WordChips(vm.word, onBits = vm::selectBits, onSigned = vm::toggleSigned, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        ProgrammerDisplay(vm, style, onCopyExpression = { copy(ExpressionText.ascii(vm.expression.tokens, style)) }, modifier = Modifier.weight(1f))
        BasesPanel(
            value = vm.value,
            selected = vm.base,
            word = vm.word,
            style = style,
            onSelect = vm::selectBase,
            onBits = { bitsOpen = true },
            onCopy = { b -> copy(Programmer.copy(vm.value, b, vm.word)) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(10.dp))
        ProgrammerKeypad(vm, vibrate, Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
    if (bitsOpen) BitSheet(vm, vibrate, style, onDismiss = { bitsOpen = false })
}

/** The word size chip with its menu and the sign chip (SPEC §8.2). */
@Composable
private fun WordChips(word: Word, onBits: (Int) -> Unit, onSigned: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(pluralStringResource(R.plurals.label_word_size, word.bits, word.bits)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.focusProperties { canFocus = false },
            )
            DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    val sizes = listOf(8, 16, 32, 64)
                    sizes.forEachIndexed { index, bits ->
                        val isSelected = bits == word.bits
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.label_word_size, bits, bits), maxLines = 1) },
                            trailingContent = {
                                if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            },
                            shape = MenuDefaults.itemShape(index, sizes.size).shape,
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                selected = isSelected
                            },
                            onClick = {
                                open = false
                                onBits(bits)
                            },
                        )
                    }
                }
            }
        }
        FilterChip(
            selected = word.signed,
            onClick = onSigned,
            label = { Text(stringResource(if (word.signed) R.string.label_signed else R.string.label_unsigned)) },
            leadingIcon = if (word.signed) {
                { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
            modifier = Modifier.focusProperties { canFocus = false },
        )
    }
}

/** The expression while typing, the evaluated one and the result after "=", the error, the overflow note. */
@Composable
private fun ProgrammerDisplay(vm: ProgrammerViewModel, style: NumberStyle, onCopyExpression: () -> Unit, modifier: Modifier) {
    val mainStyle = MaterialTheme.typography.displaySmall.tabular
    val smallStyle = MaterialTheme.typography.titleMedium.tabular.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueText = programmerValueText(vm.base, vm.word, style)
    val groups = groupsOf(vm.base)
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val width = constraints.maxWidth
        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val evaluated = vm.evaluated
            val result = vm.result
            if (evaluated != null && result != null) {
                FittedLine(AnnotatedString(ExpressionText.render(evaluated, style, valueText, groups).text + " ="), smallStyle, listOf(16.sp), width)
                FittedLine(
                    AnnotatedString(Programmer.text(result, vm.base, vm.word, style)),
                    mainStyle,
                    PROGRAMMER_SIZES,
                    width,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                ExpressionLine(
                    ExpressionText.render(vm.expression.tokens, style, valueText, groups),
                    vm.expression.cursor,
                    vm.expression.unclosedAtEnd(),
                    mainStyle,
                    width,
                    onPlace = vm::moveCursor,
                    onCopy = onCopyExpression,
                    onPaste = null,
                    sizes = PROGRAMMER_SIZES,
                )
            }
            val error = vm.error
            when {
                error != null -> Text(
                    stringResource(error.message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                vm.wrapped -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 24.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.label_overflow_wrapped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The bases panel (SPEC §8.3): HEX, DEC, OCT, BIN, the input base selected; a tap makes a row the
 * input base, a long press copies its value, on BIN it opens the bit sheet. BIN in fixed rows of 16
 * bits with each row's top bit index.
 */
@Composable
private fun BasesPanel(
    value: java.math.BigInteger,
    selected: Base,
    word: Word,
    style: NumberStyle,
    onSelect: (Base) -> Unit,
    onBits: () -> Unit,
    onCopy: (Base) -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    val bases = listOf(Base.HEX, Base.DEC, Base.OCT, Base.BIN)
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        bases.forEachIndexed { index, b ->
            val isSelected = b == selected
            val top = if (index == 0) 20.dp else 6.dp
            val bottom = if (index == bases.lastIndex) 20.dp else 6.dp
            val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clip(shape)
                    .background(if (isSelected) scheme.secondaryContainer else scheme.surfaceContainer)
                    .combinedClickable(
                        onClick = { onSelect(b) },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            if (b == Base.BIN) onBits() else onCopy(b)
                        },
                    )
                    .semantics {
                        role = Role.RadioButton
                        this.selected = isSelected
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(b.name, style = MaterialTheme.typography.labelMedium, color = if (isSelected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
                val digits = MaterialTheme.typography.titleMedium.tabular.copy(fontSize = 18.sp)
                if (b == Base.BIN) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Programmer.binaryRows(value, word).forEach { (topBit, bits) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(topBit.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(bits, style = digits.copy(fontSize = 14.sp), maxLines = 1)
                            }
                        }
                    }
                } else {
                    Text(Programmer.text(value, b, word, style), style = digits, maxLines = 1, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

/**
 * The keypad, 6 × 6 (SPEC §8.4). Digits not of the input base are disabled, with a state saying why.
 */
@Composable
private fun ProgrammerKeypad(vm: ProgrammerViewModel, vibrate: Boolean, modifier: Modifier) {
    val digitStyle = MaterialTheme.typography.headlineSmall
    val logicStyle = MaterialTheme.typography.labelLarge
    val notDigit = stringResource(
        when (vm.base) {
            Base.DEC -> R.string.desc_not_decimal_digit
            Base.OCT -> R.string.desc_not_octal_digit
            else -> R.string.desc_not_binary_digit
        },
    )
    val h = 56.dp

    @Composable
    fun RowScope.digit(d: Int, wide: Boolean = false) {
        val enabled = vm.isDigitOf(d)
        val label = d.toString(16).uppercase()
        CalcKey(
            KeyGroup.DIGIT, label, vibrate, { vm.press(Key.Insert(Token.Digit(d))) }, Modifier.weight(if (wide) 2f else 1f), height = h,
            enabled = enabled, state = if (enabled) null else notDigit,
        ) { KeyText(label, digitStyle) }
    }

    @Composable
    fun RowScope.op(op: Op, label: String, description: Int?, group: KeyGroup = KeyGroup.FUNCTION) {
        val text = description?.let { stringResource(it) } ?: label
        CalcKey(group, text, vibrate, { vm.press(Key.Insert(Token.Operator(op))) }, Modifier.weight(1f), height = h) {
            KeyText(label, if (group == KeyGroup.FUNCTION) logicStyle else digitStyle)
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            op(Op.AND, "AND", null)
            op(Op.OR, "OR", null)
            op(Op.XOR, "XOR", null)
            CalcKey(KeyGroup.FUNCTION, "NOT", vibrate, { vm.press(Key.Insert(Token.Not)) }, Modifier.weight(1f), height = h) { KeyText("NOT", logicStyle) }
            op(Op.NAND, "NAND", null)
            op(Op.NOR, "NOR", null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            op(Op.SHL, "<<", R.string.content_description_shift_left)
            op(Op.SHR, ">>", R.string.content_description_shift_right)
            op(Op.ROL, "RoL", R.string.content_description_rotate_left)
            op(Op.ROR, "RoR", R.string.content_description_rotate_right)
            if (vm.showsResult) {
                CalcKey(KeyGroup.CLEAR, stringResource(R.string.content_description_clear), vibrate, vm::clear, Modifier.weight(1f), height = h) {
                    KeyText(stringResource(R.string.button_clear), digitStyle)
                }
            } else {
                CalcKey(
                    KeyGroup.CLEAR, stringResource(R.string.content_description_delete), vibrate, { vm.press(Key.Backspace) }, Modifier.weight(1f), height = h,
                    onLongClick = vm::clear, longClickLabel = stringResource(R.string.content_description_clear),
                    state = stringResource(R.string.desc_delete_long_press),
                ) { KeyIcon(R.drawable.ic_backspace) }
            }
            op(Op.DIVIDE, "÷", R.string.content_description_divide, KeyGroup.OPERATOR)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            digit(0xA); digit(0xB); digit(7); digit(8); digit(9)
            op(Op.TIMES, "×", R.string.content_description_multiply, KeyGroup.OPERATOR)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            digit(0xC); digit(0xD); digit(4); digit(5); digit(6)
            op(Op.MINUS, "−", R.string.content_description_minus, KeyGroup.OPERATOR)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            digit(0xE); digit(0xF); digit(1); digit(2); digit(3)
            op(Op.PLUS, "+", R.string.content_description_plus, KeyGroup.OPERATOR)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            op(Op.MOD, "mod", R.string.content_description_mod, KeyGroup.OPERATOR)
            CalcKey(
                KeyGroup.OPERATOR, stringResource(R.string.content_description_bracket), vibrate, { vm.press(Key.Bracket) }, Modifier.weight(1f), height = h,
                onLongClick = { vm.press(Key.OpenBracket) }, longClickLabel = stringResource(R.string.content_description_open_bracket),
            ) { KeyText("( )", digitStyle) }
            CalcKey(KeyGroup.DIGIT, stringResource(R.string.content_description_sign), vibrate, { vm.press(Key.ToggleSign) }, Modifier.weight(1f), height = h) { KeyText("±", digitStyle) }
            digit(0, wide = true)
            CalcKey(KeyGroup.EQUALS, stringResource(R.string.content_description_equals), vibrate, vm::equals, Modifier.weight(1f), height = h) { KeyText("=", digitStyle) }
        }
    }
}

/**
 * The bit sheet (SPEC §8.6): one row per byte, highest first, nibbles apart; a tap flips a bit and
 * the expression becomes the new value. Cells of 34 × 40 dp, the owner's accepted exception to
 * 48 dp targets.
 */
@Composable
private fun BitSheet(vm: ProgrammerViewModel, vibrate: Boolean, style: NumberStyle, onDismiss: () -> Unit) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    val word = vm.word
    val pattern = word.pattern(vm.value)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_bits), style = MaterialTheme.typography.titleLarge)
                    Text(
                        pluralStringResource(R.plurals.label_word_size, word.bits, word.bits) + ", " +
                            stringResource(if (word.signed) R.string.label_signed else R.string.label_unsigned).lowercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.label_value_in_base, vm.base.name, Programmer.text(vm.value, vm.base, word, style)),
                    style = MaterialTheme.typography.titleMedium.tabular,
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                for (row in 0 until word.bits / 8) {
                    val topBit = word.bits - 1 - row * 8
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(topBit.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                        for (k in 0 until 8) {
                            val bit = topBit - k
                            val set = pattern.testBit(bit)
                            if (k == 4) Spacer(Modifier.width(8.dp)) else if (k > 0) Spacer(Modifier.width(2.dp))
                            val label = stringResource(R.string.content_description_bit, bit)
                            Box(
                                Modifier
                                    .size(width = 34.dp, height = 40.dp)
                                    .clip(if (set) CircleShape else RoundedCornerShape(8.dp))
                                    .background(if (set) scheme.primary else scheme.surfaceContainerHighest)
                                    .toggleable(value = set, role = Role.Switch) {
                                        if (vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        vm.toggleBit(bit)
                                    }
                                    .semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (set) "1" else "0", style = MaterialTheme.typography.labelLarge, color = if (set) scheme.onPrimary else scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A physical keyboard in the Programmer (SPEC §5.5): digits and a–f, `+ - * /`, `%` for mod, `&`
 * AND, `|` OR, `^` XOR, `~` NOT, `<` `>` shifts, brackets, Enter and `=`, Backspace, Delete,
 * Escape, the arrows, Ctrl+C.
 */
private fun typed(event: KeyEvent, vm: ProgrammerViewModel, onCopy: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed) {
        if (event.key == KeyCode.C) {
            onCopy()
            return true
        }
        return false
    }
    when (event.key) {
        KeyCode.Enter, KeyCode.NumPadEnter -> vm.equals()
        KeyCode.Backspace -> vm.press(Key.Backspace)
        KeyCode.Delete -> vm.press(Key.DeleteForward)
        KeyCode.Escape -> vm.clear()
        KeyCode.DirectionLeft -> vm.moveCursor(vm.expression.cursor - 1)
        KeyCode.DirectionRight -> vm.moveCursor(vm.expression.cursor + 1)
        else -> {
            val c = event.utf16CodePoint.toChar()
            val digit = Character.digit(c, 16)
            val key = when {
                digit >= 0 -> Key.Insert(Token.Digit(digit))
                c == '+' -> Key.Insert(Token.Operator(Op.PLUS))
                c == '-' -> Key.Insert(Token.Operator(Op.MINUS))
                c == '*' -> Key.Insert(Token.Operator(Op.TIMES))
                c == '/' -> Key.Insert(Token.Operator(Op.DIVIDE))
                c == '%' -> Key.Insert(Token.Operator(Op.MOD))
                c == '&' -> Key.Insert(Token.Operator(Op.AND))
                c == '|' -> Key.Insert(Token.Operator(Op.OR))
                c == '^' -> Key.Insert(Token.Operator(Op.XOR))
                c == '~' -> Key.Insert(Token.Not)
                c == '<' -> Key.Insert(Token.Operator(Op.SHL))
                c == '>' -> Key.Insert(Token.Operator(Op.SHR))
                c == '(' -> Key.OpenBracket
                c == ')' -> Key.Insert(Token.Close)
                c == '=' -> {
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
