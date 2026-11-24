package app.moonmobile.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import app.moonmobile.MainActivity
import app.moonmobile.R
import app.moonmobile.json.JSONObject
import app.moonmobile.platform.PlatformContext
import app.moonmobile.text.DateFormat

/** The long widget: cores, positions, the day's trades and their kinds of strategy. */
class MoonWidgetWide : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = HomeWidget.render(context)
}

/** The square widget: the same, shorter. */
class MoonWidgetSquare : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = HomeWidget.render(context)
}

actual object HomeWidget {
    private const val PREFS = "home_widget"
    private val GREEN = Color.parseColor("#FF4FC38A")
    private val RED = Color.parseColor("#FFE5645F")

    actual fun show(context: PlatformContext, state: WidgetState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", state.toJson().toString())
            .apply()
        render(context)
    }

    actual fun placed(context: PlatformContext): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return providers(context).any { manager.getAppWidgetIds(it).isNotEmpty() }
    }

    actual val howToPlace: String =
        "На Android: долгое нажатие на рабочем столе → Виджеты → MoonMobile, широкий или квадратный. " +
            "Рамка красная, если хоть одно ядро без связи. Нажатие на виджет открывает приложение."

    actual fun status(): String? = null

    private fun providers(context: Context) = listOf(
        ComponentName(context, MoonWidgetWide::class.java),
        ComponentName(context, MoonWidgetSquare::class.java),
    )

    /** Draws the kept state on every placed widget: on the app's word, and when the launcher asks. */
    fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val kept = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null)
        val state = kept?.let { runCatching { WidgetState.fromJson(JSONObject(it)) }.getOrNull() }
        for (provider in providers(context)) {
            val ids = manager.getAppWidgetIds(provider)
            if (ids.isEmpty()) continue
            val wide = provider.className == MoonWidgetWide::class.java.name
            manager.updateAppWidget(ids, views(context, state, wide))
        }
    }

    private fun views(context: Context, state: WidgetState?, wide: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, if (wide) R.layout.widget_wide else R.layout.widget_square)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        if (state == null) {
            views.setTextViewText(R.id.widget_cores, "Откройте приложение и подключитесь к ядрам")
            for (id in listOf(R.id.widget_time, R.id.widget_positions, R.id.widget_trades, R.id.widget_kinds)) {
                views.setTextViewText(id, "")
            }
            return views
        }
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (state.alarm) R.drawable.widget_frame_alarm else R.drawable.widget_frame,
        )
        views.setTextViewText(R.id.widget_time, DateFormat("HH:mm").format(state.updatedMs))
        views.setTextViewText(R.id.widget_cores, coresLine(state, wide))
        views.setTextViewText(
            R.id.widget_positions,
            when {
                !state.connected -> ""
                state.positions > 0 -> "Позиции: есть (${state.positions})"
                else -> "Позиций нет"
            },
        )
        views.setTextViewText(R.id.widget_trades, tradesLine(state, wide))
        views.setTextViewText(R.id.widget_kinds, kindsLine(state, wide))
        return views
    }

    private fun coresLine(state: WidgetState, wide: Boolean): CharSequence = when {
        !state.connected -> colored("Нет подключения к ядрам", RED)
        state.offline.isNotEmpty() -> colored(
            if (wide) "Нет связи: ${state.offline.joinToString(", ")}" else "Ядра ${state.coresOnline}/${state.coresTotal}",
            RED,
        )
        else -> "Ядра ${state.coresOnline}/${state.coresTotal} на связи"
    }

    /** "Сделки за сутки: 14  +11 −3", the plus green and the minus red. */
    private fun tradesLine(state: WidgetState, wide: Boolean): CharSequence {
        val line = SpannableStringBuilder(if (wide) "Сделки за сутки: ${state.trades}" else "Сделки: ${state.trades}")
        if (state.trades > 0) {
            line.append("  ")
            append(line, "+${state.wins}", GREEN)
            line.append(" ")
            append(line, "−${state.losses}", RED)
        }
        return line
    }

    /** "Drops 8 (+7 −1) · Spread 4 (+3 −1)"; the square one without the split. */
    private fun kindsLine(state: WidgetState, wide: Boolean): CharSequence {
        val line = SpannableStringBuilder()
        state.kinds.filter { it.trades > 0 }.forEachIndexed { at, kind ->
            if (at > 0) line.append(" · ")
            line.append("${kind.kind} ${kind.trades}")
            if (wide) {
                line.append(" (")
                append(line, "+${kind.wins}", GREEN)
                line.append(" ")
                append(line, "−${kind.losses}", RED)
                line.append(")")
            }
        }
        return line
    }

    private fun colored(text: String, color: Int): CharSequence =
        SpannableStringBuilder().also { append(it, text, color) }

    private fun append(line: SpannableStringBuilder, text: String, color: Int) {
        val start = line.length
        line.append(text)
        line.setSpan(ForegroundColorSpan(color), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
