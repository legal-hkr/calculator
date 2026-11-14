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
import androidx.compose.material3.Button
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
        modifier = modifier.height(height).semantics {
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
    ) {
        Column(Modifier.clearAndSetSemantics { }) { content() }
    }
}

@Composable
private fun KeyText(text: String, style: TextStyle) = Text(text, style = style.tabular, maxLines = 1)

@Composable
private fun KeyIcon(@DrawableRes icon: Int, color: Color = Color.Unspecified) =
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

/** The scientific panel's always-visible row (SPEC §5.4): √ x² xʸ π x!. Its expand key comes with stage 2. */
@Composable
fun ScientificRow(vibrate: Boolean, onKey: (Key) -> Unit, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleMedium
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple("√", R.string.content_description_square_root, Token.Sqrt),
            Triple("x²", R.string.content_description_square, Token.Postfix(PostOp.SQUARE)),
            Triple("xʸ", R.string.content_description_power, Token.Operator(Op.POWER)),
            Triple("π", R.string.content_description_pi, Token.Constant(Const.PI)),
            Triple("x!", R.string.content_description_factorial, Token.Postfix(PostOp.FACTORIAL)),
        ).forEach { (label, description, token) ->
            CalcKey(KeyGroup.FUNCTION, stringResource(description), vibrate, { onKey(Key.Insert(token)) }, Modifier.weight(1f), height = 48.dp) {
                KeyText(label, style)
            }
        }
    }
}
