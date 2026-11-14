@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calculator.ui.calculator

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import sh.hnet.calculator.engine.expr.Fn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import sh.hnet.calculator.R
import sh.hnet.calculator.engine.expr.Const
import sh.hnet.calculator.engine.expr.Key
import sh.hnet.calculator.engine.expr.Op
import sh.hnet.calculator.engine.expr.PostOp
import sh.hnet.calculator.engine.expr.Token

/** Key groups (SPEC §5.2): they differ by container role and shape, never by a color of their own. */
enum class KeyGroup { DIGIT, OPERATOR, EQUALS, CLEAR, FUNCTION }

private val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/**
 * A key: an M3 Expressive [Button] whose shape morphs while pressed ([ButtonShapes]). Input is
 * applied in onClick, with the keyboard-tap haptic when [vibrate] (SPEC §2.5, §13.2). A long press,
 * where there is one, is read from the button's own interactions: held past the system's long-press
 * time it fires once, and the click that ends the press is dropped.
 */
@Composable
fun CalcKey(
    group: KeyGroup,
    description: String,
    vibrate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    state: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = when (group) {
        KeyGroup.DIGIT -> scheme.surfaceContainerHigh to scheme.onSurface
        KeyGroup.OPERATOR -> scheme.secondaryContainer to scheme.onSecondaryContainer
        KeyGroup.EQUALS -> scheme.primary to scheme.onPrimary
        KeyGroup.CLEAR -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        KeyGroup.FUNCTION -> scheme.surfaceContainer to scheme.onSurface
    }
    val shapes = when (group) {
        KeyGroup.DIGIT, KeyGroup.CLEAR -> ButtonShapes(CircleShape, RoundedCornerShape(16.dp))
        KeyGroup.OPERATOR, KeyGroup.EQUALS -> ButtonShapes(RoundedCornerShape(20.dp), RoundedCornerShape(12.dp))
        KeyGroup.FUNCTION -> ButtonShapes(CircleShape, RoundedCornerShape(12.dp))
    }
    val interactions = remember { MutableInteractionSource() }
    var heldLong by remember { mutableStateOf(false) }
    val longClick by rememberUpdatedState(onLongClick)
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    if (onLongClick != null) {
        LaunchedEffect(interactions) {
            interactions.interactions.collectLatest { interaction ->
                if (interaction is PressInteraction.Press) {
                    heldLong = false
                    delay(longPressMillis)
                    heldLong = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    longClick?.invoke()
                }
            }
        }
    }
    Button(
        onClick = {
            if (heldLong) {
                heldLong = false
            } else {
                if (vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        },
        shapes = shapes,
        // Typing on a keyboard never moves focus onto a key (SPEC §5.5); TalkBack still reaches them
        modifier = modifier.height(height).focusProperties { canFocus = false }.semantics {
            contentDescription = description
            if (state != null) stateDescription = state
            if (onLongClick != null) onLongClick(label = longClickLabel) {
                onLongClick()
                true
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = onContainer),
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactions,
        enabled = enabled,
    ) {
        Column(Modifier.clearAndSetSemantics { }) { content() }
    }
}

@Composable
internal fun KeyText(text: String, style: TextStyle) = Text(text, style = style.tabular, maxLines = 1)

@Composable
internal fun KeyIcon(@DrawableRes icon: Int, color: Color = Color.Unspecified) =
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = if (color == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color)

/**
 * The basic keypad, 4 × 5 (SPEC §5.3): ⌫/C, brackets, %, ÷ / 7 8 9 × / 4 5 6 − / 1 2 3 + / ± 0 , =.
 * [showsResult] turns ⌫ into C.
 */
@Composable
fun BasicKeypad(
    decimalSeparator: Char,
    showsResult: Boolean,
    vibrate: Boolean,
    onKey: (Key) -> Unit,
    onEquals: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val digitStyle = MaterialTheme.typography.headlineMedium
    @Composable
    fun RowScope.digit(d: Int) = CalcKey(KeyGroup.DIGIT, d.toString(), vibrate, { onKey(Key.Insert(Token.Digit(d))) }, Modifier.weight(1f)) { KeyText(d.toString(), digitStyle) }

    @Composable
    fun RowScope.operator(op: Op, glyph: String, description: Int) =
        CalcKey(KeyGroup.OPERATOR, stringResource(description), vibrate, { onKey(Key.Insert(Token.Operator(op))) }, Modifier.weight(1f)) { KeyText(glyph, digitStyle) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showsResult) {
                CalcKey(KeyGroup.CLEAR, stringResource(R.string.content_description_clear), vibrate, onClear, Modifier.weight(1f)) {
                    KeyText(stringResource(R.string.button_clear), digitStyle)
                }
            } else {
                CalcKey(
                    KeyGroup.CLEAR, stringResource(R.string.content_description_delete), vibrate, { onKey(Key.Backspace) }, Modifier.weight(1f),
                    onLongClick = onClear, longClickLabel = stringResource(R.string.content_description_clear),
                    state = stringResource(R.string.desc_delete_long_press),
                ) { KeyIcon(R.drawable.ic_backspace) }
            }
            CalcKey(
                KeyGroup.OPERATOR, stringResource(R.string.content_description_bracket), vibrate, { onKey(Key.Bracket) }, Modifier.weight(1f),
                onLongClick = { onKey(Key.OpenBracket) }, longClickLabel = stringResource(R.string.content_description_open_bracket),
            ) { KeyText("( )", digitStyle) }
            CalcKey(KeyGroup.OPERATOR, stringResource(R.string.content_description_percent), vibrate, { onKey(Key.Insert(Token.Postfix(PostOp.PERCENT))) }, Modifier.weight(1f)) {
                KeyText("%", digitStyle)
            }
            operator(Op.DIVIDE, "÷", R.string.content_description_divide)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            digit(7); digit(8); digit(9)
            operator(Op.TIMES, "×", R.string.content_description_multiply)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            digit(4); digit(5); digit(6)
            operator(Op.MINUS, "−", R.string.content_description_minus)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            digit(1); digit(2); digit(3)
            operator(Op.PLUS, "+", R.string.content_description_plus)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalcKey(KeyGroup.DIGIT, stringResource(R.string.content_description_sign), vibrate, { onKey(Key.ToggleSign) }, Modifier.weight(1f)) { KeyText("±", digitStyle) }
            digit(0)
            CalcKey(KeyGroup.DIGIT, stringResource(R.string.content_description_decimal), vibrate, { onKey(Key.Insert(Token.Point)) }, Modifier.weight(1f)) {
                KeyText(decimalSeparator.toString(), digitStyle)
            }
            CalcKey(KeyGroup.EQUALS, stringResource(R.string.content_description_equals), vibrate, onEquals, Modifier.weight(1f)) { KeyText("=", digitStyle) }
        }
    }
}

/** What the scientific panel's keys do beyond inserting one element. */
class ScientificActions(
    val onKey: (Key) -> Unit,
    val onExp: () -> Unit,
    val onPowerOfTen: () -> Unit,
    val onAns: () -> Unit,
    val onToggleSecond: () -> Unit,
    val onPanel: (Boolean) -> Unit,
)

/**
 * The scientific panel (SPEC §5.4): its bottom row always shows (√ x² xʸ π x! and the expand key);
 * open, two rows appear above it (2nd sin cos tan ln log / e EE Ans mod 1/x |x|) and the display
 * gives way, the keypad never moves. A vertical swipe on the panel opens (up) or closes (down) it.
 * With 2nd on, sin cos tan ln log xʸ become sin⁻¹ cos⁻¹ tan⁻¹ eˣ 10ˣ ʸ√x.
 */
@Composable
fun ScientificPanel(open: Boolean, second: Boolean, hasAns: Boolean, vibrate: Boolean, actions: ScientificActions, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleMedium
    val motion = MaterialTheme.motionScheme
    var dragged by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { dragged += it }
    Column(
        modifier.fillMaxWidth().draggable(
            dragState,
            Orientation.Vertical,
            onDragStarted = { dragged = 0f },
            onDragStopped = { velocity ->
                val up = dragged < 0 || velocity < 0
                if (kotlin.math.abs(dragged) > 24f || kotlin.math.abs(velocity) > 300f) actions.onPanel(up)
            },
        ),
    ) {
        @Composable
        fun RowScope.key(label: String, description: Int, onClick: () -> Unit, enabled: Boolean = true) {
            CalcKey(KeyGroup.FUNCTION, stringResource(description), vibrate, onClick, Modifier.weight(1f), height = 48.dp, enabled = enabled) { KeyText(label, style) }
        }

        @Composable
        fun RowScope.pair(plain: Pair<String, Int>, inverse: Pair<String, Int>, onPlain: () -> Unit, onInverse: () -> Unit) {
            val description = if (second) inverse.second else plain.second
            CalcKey(KeyGroup.FUNCTION, stringResource(description), vibrate, if (second) onInverse else onPlain, Modifier.weight(1f), height = 48.dp) {
                Crossfade(second, animationSpec = motion.fastEffectsSpec(), label = "second") { inv ->
                    KeyText(if (inv) inverse.first else plain.first, style)
                }
            }
        }

        fun insert(token: Token): () -> Unit = { actions.onKey(Key.Insert(token)) }

        AnimatedVisibility(
            open,
            enter = expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondKey(second, vibrate, actions.onToggleSecond, Modifier.weight(1f))
                    pair("sin" to R.string.content_description_sin, "sin⁻¹" to R.string.content_description_asin, insert(Token.Function(Fn.SIN)), insert(Token.Function(Fn.ASIN)))
                    pair("cos" to R.string.content_description_cos, "cos⁻¹" to R.string.content_description_acos, insert(Token.Function(Fn.COS)), insert(Token.Function(Fn.ACOS)))
                    pair("tan" to R.string.content_description_tan, "tan⁻¹" to R.string.content_description_atan, insert(Token.Function(Fn.TAN)), insert(Token.Function(Fn.ATAN)))
                    pair("ln" to R.string.content_description_ln, "eˣ" to R.string.content_description_exp, insert(Token.Function(Fn.LN)), actions.onExp)
                    pair("log" to R.string.content_description_log, "10ˣ" to R.string.content_description_exp10, insert(Token.Function(Fn.LOG)), actions.onPowerOfTen)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    key("e", R.string.content_description_e, insert(Token.Constant(Const.E)))
                    key("EE", R.string.content_description_ee, insert(Token.Exponent))
                    key("Ans", R.string.content_description_ans, actions.onAns, enabled = hasAns)
                    key("mod", R.string.content_description_mod, insert(Token.Operator(Op.MOD)))
                    key("1/x", R.string.content_description_reciprocal, { actions.onKey(Key.Reciprocal) })
                    key("|x|", R.string.content_description_abs, insert(Token.Function(Fn.ABS)))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            key("√", R.string.content_description_square_root, insert(Token.Sqrt))
            key("x²", R.string.content_description_square, insert(Token.Postfix(PostOp.SQUARE)))
            pair("xʸ" to R.string.content_description_power, "ʸ√x" to R.string.content_description_root, insert(Token.Operator(Op.POWER)), insert(Token.Operator(Op.ROOT)))
            key("π", R.string.content_description_pi, insert(Token.Constant(Const.PI)))
            key("x!", R.string.content_description_factorial, insert(Token.Postfix(PostOp.FACTORIAL)))
            CalcKey(
                KeyGroup.FUNCTION,
                stringResource(if (open) R.string.content_description_hide_scientific else R.string.content_description_show_scientific),
                vibrate,
                { actions.onPanel(!open) },
                Modifier.weight(1f),
                height = 48.dp,
            ) { KeyIcon(if (open) R.drawable.ic_expand_more else R.drawable.ic_expand_less) }
        }
    }
}

/** 2nd: a toggle that stays on until tapped again; off a 12 dp square-ish key, on round in primary (SPEC §5.4). */
@Composable
private fun SecondKey(on: Boolean, vibrate: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    val description = stringResource(R.string.content_description_second)
    ToggleButton(
        checked = on,
        onCheckedChange = {
            if (vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onToggle()
        },
        shapes = ToggleButtonShapes(RoundedCornerShape(12.dp), RoundedCornerShape(8.dp), CircleShape),
        colors = ToggleButtonDefaults.colors(
            containerColor = scheme.surfaceContainer,
            contentColor = scheme.onSurface,
            checkedContainerColor = scheme.primary,
            checkedContentColor = scheme.onPrimary,
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }.semantics { contentDescription = description },
    ) {
        Column(Modifier.clearAndSetSemantics { }) { KeyText("2nd", MaterialTheme.typography.titleMedium) }
    }
}
