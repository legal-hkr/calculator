package sh.hnet.calculator.ui.calculator

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import sh.hnet.calculator.engine.number.AngleUnit
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.calculator.R
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.Formatted
import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.format.NumberStyle

private val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** Main line sizes (SPEC §6.1): 45 → 36 → 28 sp, then sideways scroll. */
private val MAIN_SIZES = listOf(45.sp, 36.sp, 28.sp)

/** Preview and small line: 24 → 20 sp. */
internal val SMALL_SIZES = listOf(24.sp, 20.sp)

/** A result as shown: digits, the mark of a value that continues, and the power of ten (SPEC §6.6). */
@Composable
fun resultText(f: Formatted): AnnotatedString {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        append(f.text)
        if (!f.exact) withStyle(SpanStyle(color = muted)) { append('…') }
        f.exponent?.let { append(" × 10" + Formatter.superscript(it)) }
    }
}

/**
 * The display (SPEC §6.1), end-aligned at the bottom: after "=" the evaluated expression on a small
 * line and the result on the main line; while typing the expression with its cursor and the
 * preview, or the error in its place.
 */
/** What the display hands to the clipboard and takes from it (the screen does the work). */
class DisplayClipboard(val copyResult: () -> Unit, val copyExpression: () -> Unit, val paste: () -> Unit)

@Composable
fun Display(vm: CalculatorViewModel, style: NumberStyle, clipboard: DisplayClipboard, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val mainStyle = MaterialTheme.typography.displayMedium.tabular
    val previewStyle = MaterialTheme.typography.headlineSmall.tabular
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val widthPx = constraints.maxWidth
        // The room in characters at the smallest sizes, less one for the "…"
        val mainChars = remember(widthPx, mainStyle) { charsThatFit(measurer.measure("0", mainStyle.copy(fontSize = MAIN_SIZES.last())).size.width, widthPx) }
        val previewChars = remember(widthPx, previewStyle) { charsThatFit(measurer.measure("0", previewStyle.copy(fontSize = SMALL_SIZES.last())).size.width, widthPx) }
        LaunchedEffect(style, mainChars, previewChars) { vm.layout(style, mainChars, previewChars) }

        if (vm.showsAngle) AngleChip(vm.angle, vm::toggleAngle, Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
            val evaluated = vm.evaluated
            val result = vm.result
            if (evaluated != null) {
                val small = ExpressionText.render(evaluated, style).text + " ="
                FittedLine(AnnotatedString(small), previewStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), SMALL_SIZES, widthPx)
                if (result != null) {
                    ResultLine(resultText(result), mainStyle, widthPx, onMore = vm::moreDigits, onCopy = clipboard.copyResult)
                } else {
                    FittedLine(AnnotatedString(""), mainStyle, MAIN_SIZES, widthPx)
                }
            } else {
                val rendered = ExpressionText.render(vm.expression.tokens, style)
                ExpressionLine(
                    rendered, vm.expression.cursor, vm.expression.unclosedAtEnd(), mainStyle, widthPx,
                    onPlace = vm::moveCursor, onCopy = clipboard.copyExpression, onPaste = clipboard.paste,
                )
                val error = vm.error
                if (error != null) {
                    Text(
                        stringResource(error.message),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = previewStyle.lineHeight),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    val preview = vm.preview
                    FittedLine(
                        if (preview != null) resultText(preview) else AnnotatedString(""),
                        previewStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        SMALL_SIZES,
                        widthPx,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** DEG or RAD at the display's top start; a tap switches (SPEC §6.7). */
@Composable
private fun AngleChip(angle: AngleUnit, onToggle: () -> Unit, modifier: Modifier) {
    val degrees = angle == AngleUnit.DEGREES
    val description = stringResource(if (degrees) R.string.content_description_angle_unit_deg else R.string.content_description_angle_unit_rad)
    val action = stringResource(if (degrees) R.string.label_switch_to_radians else R.string.label_switch_to_degrees)
    AssistChip(
        onClick = onToggle,
        label = { Text(stringResource(if (degrees) R.string.label_angle_deg else R.string.label_angle_rad), style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.focusProperties { canFocus = false }.semantics {
            contentDescription = description
            onClick(label = action) {
                onToggle()
                true
            }
        },
    )
}

/**
 * The result line (SPEC §6.6): shrunk in steps like the expression, then scrolled sideways from its
 * start; scrolling towards its end – or dragging a result that still fits – asks for more digits.
 * A long press copies it.
 */
@Composable
private fun ResultLine(text: AnnotatedString, style: TextStyle, width: Int, onMore: () -> Unit, onCopy: () -> Unit) {
    val size = fittedSize(text, style, MAIN_SIZES, width)
    val scroll = rememberScrollState()
    val more by rememberUpdatedState(onMore)
    val near = with(LocalDensity.current) { 96.dp.toPx() }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue > 0 && scroll.value >= scroll.maxValue - near }.collect { if (it) more() }
    }
    val view = LocalView.current
    LineBox(scroll, width, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text,
            style = style.copy(fontSize = size),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onCopy()
                    })
                }
                // A result that still fits can't scroll: a drag towards its start asks for more digits
                .then(
                    if (scroll.maxValue == 0) Modifier.pointerInput(Unit) {
                        var asked = false
                        detectHorizontalDragGestures(onDragStart = { asked = false }) { change, drag ->
                            if (drag < 0) {
                                change.consume()
                                // Once a drag: the line then scrolls and asks by itself
                                if (!asked) more()
                                asked = true
                            }
                        }
                    } else Modifier,
                ),
        )
    }
}

internal val DisplayError.message: Int
    get() = when (this) {
        DisplayError.DIVISION_BY_ZERO -> R.string.error_divide_by_zero
        DisplayError.DOMAIN -> R.string.error_domain
        DisplayError.OVERFLOW -> R.string.error_overflow
        DisplayError.MALFORMED -> R.string.error_malformed
        DisplayError.INCOMPLETE -> R.string.error_incomplete
    }

private fun charsThatFit(charWidth: Int, width: Int): Int = (width / charWidth.coerceAtLeast(1) - 1).coerceAtLeast(4)

/** The largest of [sizes] at which [text] fits [width], else the smallest. */
@Composable
private fun fittedSize(text: AnnotatedString, style: TextStyle, sizes: List<TextUnit>, width: Int): TextUnit {
    val measurer = rememberTextMeasurer()
    return remember(text, style, width) {
        sizes.firstOrNull { measurer.measure(text, style.copy(fontSize = it), maxLines = 1, softWrap = false).size.width <= width } ?: sizes.last()
    }
}

/** One line, end-aligned, shrunk in steps, then scrolled sideways (never wrapped); kept scrolled to its end. */
@Composable
internal fun FittedLine(text: AnnotatedString, style: TextStyle, sizes: List<TextUnit>, width: Int, modifier: Modifier = Modifier) {
    val size = fittedSize(text, style, sizes, width)
    val scroll = rememberScrollState()
    LaunchedEffect(text) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    LineBox(scroll, width, modifier) {
        Text(text, style = style.copy(fontSize = size), maxLines = 1, softWrap = false, textAlign = TextAlign.End)
    }
}

@Composable
private fun LineBox(scroll: ScrollState, width: Int, modifier: Modifier, inner: Modifier = Modifier, content: @Composable () -> Unit) {
    val widthDp: Dp = with(LocalDensity.current) { width.toDp() }
    Box(modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Box(inner.widthIn(min = widthDp), contentAlignment = Alignment.CenterEnd) { content() }
    }
}

/**
 * The expression line (SPEC §6.2): a 2 dp cursor in primary, a tap places it between elements;
 * typing at the end keeps the line scrolled to its end, elsewhere the cursor is kept in view.
 */
@Composable
internal fun ExpressionLine(
    rendered: ExpressionText,
    cursor: Int,
    ghosts: Int,
    style: TextStyle,
    width: Int,
    onPlace: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: (() -> Unit)?,
    sizes: List<TextUnit> = MAIN_SIZES,
) {
    // Unclosed brackets drawn closed in on-surface-variant: not text, not copied (SPEC §6.2)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        append(rendered.text)
        if (ghosts > 0) withStyle(SpanStyle(color = muted)) { append(")".repeat(ghosts)) }
    }
    val toolbar = LocalTextToolbar.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val size = fittedSize(text, style, sizes, width)
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val cursorColor = MaterialTheme.colorScheme.primary
    val cursorWidth = with(LocalDensity.current) { 2.dp.toPx() }
    val offset = rendered.offsets[cursor]
    val atEnd = cursor == rendered.offsets.size - 1
    LaunchedEffect(rendered.text, cursor, layout) {
        val l = layout ?: return@LaunchedEffect
        if (atEnd) {
            snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
        } else {
            // The text sits at the end of a box at least as wide as the line
            val textStart = (maxOf(width, l.size.width) - l.size.width).toFloat()
            val x = textStart + l.getCursorRect(offset).left
            val margin = cursorWidth * 12
            if (x < scroll.value + margin) scroll.scrollTo((x - margin).toInt().coerceAtLeast(0))
            else if (x > scroll.value + width - margin) scroll.scrollTo((x - width + margin).toInt())
        }
    }
    // Taps and the long press work on the whole line, also when it is empty; positions are moved
    // into the text, which sits at the end of the line
    var lineSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun inText(position: Offset, l: TextLayoutResult): Offset =
        Offset(position.x - (lineSize.width - l.size.width), position.y - (lineSize.height - l.size.height) / 2f)
    val hasText = rendered.text.isNotEmpty()
    val lineInput = Modifier
        .onGloballyPositioned {
            // Not clipped to the visible part: the line may be scrolled
            bounds = Rect(it.positionInRoot(), it.size.toSize())
            lineSize = it.size
        }
        .pointerInput(rendered) {
            detectTapGestures(
                onTap = { position ->
                    val l = layout ?: return@detectTapGestures
                    onPlace(rendered.cursorAt(l.getOffsetForPosition(inText(position, l)).coerceAtMost(rendered.text.length)))
                },
                // The system's text toolbar: Copy (when there is something) and Paste (SPEC §6.2)
                onLongPress = { position ->
                    val x = bounds.left + position.x
                    toolbar.showMenu(
                        Rect(x, bounds.top, x + 1f, bounds.bottom),
                        onCopyRequested = if (hasText) {
                            {
                                onCopy()
                                toolbar.hide()
                            }
                        } else null,
                        onPasteRequested = onPaste?.let { paste ->
                            {
                                paste()
                                toolbar.hide()
                            }
                        },
                    )
                },
            )
        }
    LineBox(scroll, width, Modifier, inner = lineInput) {
        Text(
            text,
            style = style.copy(fontSize = size),
            maxLines = 1,
            softWrap = false,
            onTextLayout = { layout = it },
            modifier = Modifier
                .drawWithContent {
                    drawContent()
                    val l = layout ?: return@drawWithContent
                    val r = l.getCursorRect(offset.coerceIn(0, l.layoutInput.text.length))
                    drawRect(cursorColor, topLeft = Offset(r.left - cursorWidth / 2, r.top), size = Size(cursorWidth, r.height))
                },
        )
    }
}
