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
private val SMALL_SIZES = listOf(24.sp, 20.sp)

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
@Composable
fun Display(vm: CalculatorViewModel, style: NumberStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val mainStyle = MaterialTheme.typography.displayMedium.tabular
    val previewStyle = MaterialTheme.typography.headlineSmall.tabular
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val widthPx = constraints.maxWidth
        // The room in characters at the smallest sizes, less one for the "…"
        val mainChars = remember(widthPx, mainStyle) { charsThatFit(measurer.measure("0", mainStyle.copy(fontSize = MAIN_SIZES.last())).size.width, widthPx) }
        val previewChars = remember(widthPx, previewStyle) { charsThatFit(measurer.measure("0", previewStyle.copy(fontSize = SMALL_SIZES.last())).size.width, widthPx) }
        LaunchedEffect(style, mainChars, previewChars) { vm.layout(style, mainChars, previewChars) }

        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
            val evaluated = vm.evaluated
            val result = vm.result
            if (evaluated != null) {
                val small = ExpressionText.render(evaluated, style).text + " ="
                FittedLine(AnnotatedString(small), previewStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), SMALL_SIZES, widthPx)
                if (result != null) {
                    FittedLine(resultText(result), mainStyle, MAIN_SIZES, widthPx, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                } else {
                    FittedLine(AnnotatedString(""), mainStyle, MAIN_SIZES, widthPx)
                }
            } else {
                val rendered = ExpressionText.render(vm.expression.tokens, style)
                ExpressionLine(rendered, vm.expression.cursor, mainStyle, widthPx, onPlace = vm::moveCursor)
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

private val DisplayError.message: Int
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
private fun FittedLine(text: AnnotatedString, style: TextStyle, sizes: List<TextUnit>, width: Int, modifier: Modifier = Modifier) {
    val size = fittedSize(text, style, sizes, width)
    val scroll = rememberScrollState()
    LaunchedEffect(text) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    LineBox(scroll, width, modifier) {
        Text(text, style = style.copy(fontSize = size), maxLines = 1, softWrap = false, textAlign = TextAlign.End)
    }
}

@Composable
private fun LineBox(scroll: ScrollState, width: Int, modifier: Modifier, content: @Composable () -> Unit) {
    val widthDp: Dp = with(LocalDensity.current) { width.toDp() }
    Box(modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Box(Modifier.widthIn(min = widthDp), contentAlignment = Alignment.CenterEnd) { content() }
    }
}

/**
 * The expression line (SPEC §6.2): a 2 dp cursor in primary, a tap places it between elements;
 * typing at the end keeps the line scrolled to its end, elsewhere the cursor is kept in view.
 */
@Composable
private fun ExpressionLine(rendered: ExpressionText, cursor: Int, style: TextStyle, width: Int, onPlace: (Int) -> Unit) {
    val text = AnnotatedString(rendered.text)
    val size = fittedSize(text, style, MAIN_SIZES, width)
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
    LineBox(scroll, width, Modifier) {
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
                }
                .pointerInput(rendered) {
                    detectTapGestures { position ->
                        val l = layout ?: return@detectTapGestures
                        onPlace(rendered.cursorAt(l.getOffsetForPosition(position)))
                    }
                },
        )
    }
}
