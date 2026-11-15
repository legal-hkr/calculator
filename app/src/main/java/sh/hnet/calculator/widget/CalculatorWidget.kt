package sh.hnet.calculator.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.R
import sh.hnet.calculator.engine.expr.EvalContext
import sh.hnet.calculator.engine.expr.Evaluation
import sh.hnet.calculator.engine.expr.Parser
import sh.hnet.calculator.engine.expr.Token
import sh.hnet.calculator.engine.format.ExpressionText
import sh.hnet.calculator.engine.format.Formatted
import sh.hnet.calculator.engine.format.Formatter
import sh.hnet.calculator.engine.format.NumberStyle
import sh.hnet.calculator.engine.number.CalcError
import sh.hnet.calculator.engine.number.CalcException

/**
 * The calculator widget (SPEC-widget): a key's tap comes here as a broadcast, the widget's state
 * changes and the widget is drawn again. It never opens the app and writes nothing to History.
 * Exported as the system requires (the launcher's widget service sends its updates); its keys'
 * intents name this class, so only this app's own pending intents reach it with a key.
 */
class CalculatorWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KEY) return super.onReceive(context, intent)
        // calculator-widget://key/<appWidgetId>/<KEY>
        val segments = intent.data?.pathSegments ?: return
        val id = segments.getOrNull(0)?.toIntOrNull() ?: return
        val key = WidgetKey.entries.firstOrNull { it.name == segments.getOrNull(1) } ?: return
        val pending = goAsync()
        val app = context.applicationContext as CalculatorApplication
        app.appScope.launch {
            try {
                lock.withLock {
                    val style = style(app)
                    val state = WidgetStore.get(app, id)
                    val next = WidgetCalculator.press(state, key) { ExpressionText.render(it, style).text.length }
                    WidgetStore.set(app, id, next)
                    draw(app, AppWidgetManager.getInstance(app), id, next, style)
                }
            } catch (_: Exception) {
                // A widget must never take the app down; the next key draws it again
            } finally {
                pending.finish()
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context, ids)

    /** Resized: the result is fitted to the new width. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context, intArrayOf(id))

    override fun onDeleted(context: Context, ids: IntArray) = WidgetStore.forget(context, ids)

    private fun redraw(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as CalculatorApplication
        app.appScope.launch {
            try {
                drawAll(app, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_KEY = "sh.hnet.calculator.widget.KEY"

        /** One key or redraw at a time: two quick taps must not read the same state. */
        private val lock = Mutex()

        /** Every placed widget again: the app's language or Calculation settings changed (SPEC-widget §7.5). */
        fun updateAll(context: Context) {
            val app = context.applicationContext as CalculatorApplication
            app.appScope.launch { drawAll(app, null) }
        }

        private suspend fun drawAll(app: CalculatorApplication, only: IntArray?) {
            val manager = AppWidgetManager.getInstance(app)
            val ids = only ?: manager.getAppWidgetIds(ComponentName(app, CalculatorWidget::class.java))
            if (ids.isEmpty()) return
            lock.withLock {
                val style = style(app)
                ids.forEach { id ->
                    try {
                        draw(app, manager, id, WidgetStore.get(app, id), style)
                    } catch (_: Exception) {
                        // The others are drawn all the same
                    }
                }
            }
        }

        /** Numbers in the app's language and its Calculation settings (SPEC-widget §7.3). */
        private suspend fun style(app: CalculatorApplication): NumberStyle {
            val settings = app.preferences.calcSettings.first()
            val decimalComma = app.resources.configuration.locales[0].language == "pl"
            return NumberStyle(decimalComma = decimalComma, grouping = settings.grouping, decimals = settings.decimals, notation = settings.notation)
        }

        private fun draw(context: Context, manager: AppWidgetManager, id: Int, state: WidgetState, style: NumberStyle) {
            val widthDp = manager.getAppWidgetOptions(id).width()
            val views = RemoteViews(context.packageName, R.layout.widget_calculator)
            display(context, views, state, style, widthDp)
            keys(context, views, id, style)
            manager.updateAppWidget(id, views)
        }

        /** The display (SPEC-widget §5): small line and result after "=", else the expression with its preview or error. */
        private fun display(context: Context, views: RemoteViews, state: WidgetState, style: NumberStyle, widthDp: Int) {
            fun shown(view: Int, text: String?) {
                views.setViewVisibility(view, if (text.isNullOrEmpty()) View.GONE else View.VISIBLE)
                views.setTextViewText(view, text.orEmpty())
            }
            val result = state.result
            val evaluated = state.evaluated
            val description: String
            if (result != null && evaluated != null) {
                val text = written(Formatter.format(result, style, characters(widthDp, MAIN_DP)))
                shown(R.id.small, ExpressionText.render(evaluated, style).text + " =")
                views.setTextViewText(R.id.main, text)
                views.setViewVisibility(R.id.placeholder, View.GONE)
                shown(R.id.preview, null)
                shown(R.id.error, null)
                description = context.getString(R.string.content_description_widget_result, text)
            } else {
                val expression = ExpressionText.render(state.tokens, style).text
                shown(R.id.small, null)
                views.setTextViewText(R.id.main, expression)
                views.setViewVisibility(R.id.placeholder, if (state.tokens.isEmpty()) View.VISIBLE else View.GONE)
                val error = state.error
                if (error != null) {
                    shown(R.id.preview, null)
                    shown(R.id.error, context.getString(error.message))
                } else {
                    shown(R.id.error, null)
                    shown(R.id.preview, preview(state.tokens, style, characters(widthDp, PREVIEW_DP)))
                }
                description = if (state.tokens.isEmpty()) context.getString(R.string.content_description_widget_empty)
                else context.getString(R.string.content_description_widget_expression, expression)
            }
            views.setContentDescription(R.id.display, description)
        }

        /** The live preview (SPEC-ui §6.3): only when the expression has an operator and evaluates. */
        private fun preview(tokens: List<Token>, style: NumberStyle, characters: Int): String? {
            if (tokens.isEmpty() || Parser.isLoneNumber(tokens) || (tokens.size == 1 && tokens[0] is Token.Value)) return null
            return try {
                (Parser.preview(tokens, EvalContext()) as? Evaluation.Value)?.let { written(Formatter.format(it.value, style, characters)) }
            } catch (_: CalcException) {
                null
            }
        }

        /** A result as the app writes it: the digits, "…" when it continues, "× 10ⁿ". */
        private fun written(f: Formatted): String = buildString {
            append(f.text)
            if (!f.exact) append('…')
            f.exponent?.let { append(" × 10" + Formatter.superscript(it)) }
        }

        /** The characters that fit the display (SPEC-widget §7.3): its width over tabular digits of the size. */
        private fun characters(widthDp: Int, textDp: Float): Int {
            val display = widthDp - CARD_PADDING_DP * 2 - DISPLAY_PADDING_DP * 2
            return ((display / (DIGIT_WIDTH * textDp)).toInt() - 1).coerceAtLeast(4)
        }

        private fun keys(context: Context, views: RemoteViews, id: Int, style: NumberStyle) {
            views.setTextViewText(R.id.key_point, style.decimalSeparator.toString())
            KEYS.forEach { (view, key) ->
                views.setOnClickPendingIntent(view, keyIntent(context, id, key))
                views.setContentDescription(view, description(context, key))
            }
        }

        /** Every key its own address: intents that differ only in extras would be one (Music's lesson). */
        private fun keyIntent(context: Context, id: Int, key: WidgetKey): PendingIntent {
            val intent = Intent(context, CalculatorWidget::class.java)
                .setAction(ACTION_KEY)
                .setData("calculator-widget://key/$id/${key.name}".toUri())
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun description(context: Context, key: WidgetKey): String = when (key) {
            WidgetKey.POINT -> context.getString(R.string.content_description_decimal)
            WidgetKey.PLUS -> context.getString(R.string.content_description_key_add)
            WidgetKey.MINUS -> context.getString(R.string.content_description_key_subtract)
            WidgetKey.TIMES -> context.getString(R.string.content_description_key_multiply)
            WidgetKey.DIVIDE -> context.getString(R.string.content_description_key_divide)
            WidgetKey.PERCENT -> context.getString(R.string.content_description_key_percent)
            WidgetKey.SIGN -> context.getString(R.string.content_description_sign)
            WidgetKey.BACKSPACE -> context.getString(R.string.content_description_delete)
            WidgetKey.CLEAR -> context.getString(R.string.content_description_clear)
            WidgetKey.EQUALS -> context.getString(R.string.content_description_key_equals)
            else -> key.ordinal.toString()
        }

        private val CalcError.message: Int
            get() = when (this) {
                CalcError.DIVISION_BY_ZERO -> R.string.error_divide_by_zero
                CalcError.DOMAIN -> R.string.error_domain
                CalcError.OVERFLOW -> R.string.error_overflow
                CalcError.SYNTAX -> R.string.error_malformed
            }

        private fun Bundle.width(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: DEFAULT_WIDTH_DP

        private val KEYS = listOf(
            R.id.key_0 to WidgetKey.D0, R.id.key_1 to WidgetKey.D1, R.id.key_2 to WidgetKey.D2, R.id.key_3 to WidgetKey.D3,
            R.id.key_4 to WidgetKey.D4, R.id.key_5 to WidgetKey.D5, R.id.key_6 to WidgetKey.D6, R.id.key_7 to WidgetKey.D7,
            R.id.key_8 to WidgetKey.D8, R.id.key_9 to WidgetKey.D9, R.id.key_point to WidgetKey.POINT, R.id.key_plus to WidgetKey.PLUS,
            R.id.key_minus to WidgetKey.MINUS, R.id.key_times to WidgetKey.TIMES, R.id.key_divide to WidgetKey.DIVIDE,
            R.id.key_percent to WidgetKey.PERCENT, R.id.key_sign to WidgetKey.SIGN, R.id.key_back to WidgetKey.BACKSPACE,
            R.id.key_clear to WidgetKey.CLEAR, R.id.key_equals to WidgetKey.EQUALS,
        )

        private const val DEFAULT_WIDTH_DP = 328
        private const val CARD_PADDING_DP = 10
        private const val DISPLAY_PADDING_DP = 8
        private const val MAIN_DP = 30f
        private const val PREVIEW_DP = 18f

        /** The width of a tabular digit of Roboto, as a share of the text size. */
        private const val DIGIT_WIDTH = 0.56f
    }
}

/** Each placed widget's own state (SPEC-widget §7.2), in the app's private preferences; not in backups, kept until C. */
internal object WidgetStore {
    private fun prefs(context: Context) = context.getSharedPreferences("calculator_widgets", Context.MODE_PRIVATE)

    fun get(context: Context, id: Int): WidgetState = WidgetState.decode(prefs(context).getString(key(id), null))

    fun set(context: Context, id: Int, state: WidgetState) = prefs(context).edit(commit = true) { putString(key(id), state.encode()) }

    fun forget(context: Context, ids: IntArray) = prefs(context).edit { ids.forEach { remove(key(it)) } }

    private fun key(id: Int) = "widget_$id"
}
