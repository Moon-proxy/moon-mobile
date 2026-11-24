package app.moonmobile.core

import app.moonmobile.platform.PlatformContext as Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.moonmobile.platform.prefsOf
import kotlin.concurrent.Volatile
import kotlin.math.roundToLong

/**
 * What the chart screen remembers across launches: the six order sizes and the six take-profit
 * percentages of its top bar with the chosen one of each, the price scale and the time window.
 */
data class ChartSettings(
    /** Order sizes in USD, as the terminal's F1–F6 presets are. */
    val sizes: List<Double>,
    val sizeSel: Int,
    /** Take-profit distances from the entry price, in percent. */
    val takes: List<Double>,
    val takeSel: Int,
    /** Visible price range as a percentage of the price; 0 fits it to what is on screen. */
    val scalePct: Double,
    val spanMs: Long,
    /** Work the chart with a crosshair moved like a touchpad instead of the bare touch screen. */
    val cursorMode: Boolean,
    /** On a spot market one tap on the book places the order; otherwise a double tap does. */
    val spotSingleTap: Boolean,
    /** Put a stop loss [stopPct] from the entry on each order placed from the chart. */
    val stopOn: Boolean,
    val stopPct: Double,
)

/** What the chart draws besides prices and trades (Settings → График). */
data class ChartLayers(
    /** The futures mark price line, orange, as Moonbot draws it. */
    val markLine: Boolean = true,
    /** The market's liquidations as small dots. */
    val liquidations: Boolean = true,
    /** Detect cards with only their first two lines: the coin, the move, the kind, the core. */
    val compactDetects: Boolean = false,
    /**
     * When the chart's session result (`Сес:` beside the funding) starts over: [SESSION_DAY] at
     * the widget's day end, [SESSION_MONTH] on the first of the month at that time, or
     * [SESSION_NEVER].
     */
    val session: String = SESSION_DAY,
    /** Join and Split on the chart: a held take's sells into one, or in two. */
    val joinSplit: Boolean = true,
)

const val SESSION_DAY = "day"
const val SESSION_MONTH = "month"
const val SESSION_NEVER = "never"

object ChartPrefs {
    private const val FILE = "moonmobile_chart"

    private val layersFlow = MutableStateFlow(ChartLayers())

    @Volatile
    private var layersRead = false

    /** The chart's layers, read from the preferences the first time. */
    fun layers(context: Context): StateFlow<ChartLayers> {
        if (!layersRead) {
            val prefs = prefsOf(context, FILE)
            layersFlow.value = ChartLayers(
                markLine = prefs.getBoolean("mark_line", true),
                liquidations = prefs.getBoolean("liquidations", true),
                compactDetects = prefs.getBoolean("compact_detects", false),
                session = prefs.getString("session", SESSION_DAY)
                    ?.takeIf { it in listOf(SESSION_DAY, SESSION_MONTH, SESSION_NEVER) } ?: SESSION_DAY,
                joinSplit = prefs.getBoolean("join_split", true),
            )
            layersRead = true
        }
        return layersFlow
    }

    fun setMarkLine(context: Context, on: Boolean) = setLayers(context, layers(context).value.copy(markLine = on))

    fun setLiquidations(context: Context, on: Boolean) = setLayers(context, layers(context).value.copy(liquidations = on))

    fun setCompactDetects(context: Context, on: Boolean) =
        setLayers(context, layers(context).value.copy(compactDetects = on))

    fun setSession(context: Context, session: String) = setLayers(context, layers(context).value.copy(session = session))

    fun setJoinSplit(context: Context, on: Boolean) = setLayers(context, layers(context).value.copy(joinSplit = on))

    private fun setLayers(context: Context, layers: ChartLayers) {
        prefsOf(context, FILE).edit()
            .putBoolean("mark_line", layers.markLine)
            .putBoolean("liquidations", layers.liquidations)
            .putBoolean("compact_detects", layers.compactDetects)
            .putString("session", layers.session)
            .putBoolean("join_split", layers.joinSplit)
            .apply()
        layersFlow.value = layers
    }

    /**
     * When the chart's trading session began, as [ChartLayers.session] has it: the day's start
     * at the widget's day end, the month's first day at that time, or the start of everything
     * the report keeps (a year at most).
     */
    fun sessionStartMs(context: Context, now: Long): Long {
        val day = WidgetFeed.dayStartMs(context, now)
        return when (layers(context).value.session) {
            SESSION_MONTH -> {
                val date = app.moonmobile.text.DateFormat("d").format(day).toIntOrNull() ?: 1
                val first = app.moonmobile.text.localDayStart(day - (date - 1) * DAY_MS + DAY_MS / 2)
                first + WidgetFeed.dayEndMinutes(context) * 60_000L
            }
            SESSION_NEVER -> 1L
            else -> day
        }
    }

    private const val DAY_MS = 86_400_000L

    /** The terminal's own default order sizes. */
    val defaultSizes: List<Double> = listOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0)
    val defaultTakes: List<Double> = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0)

    /** The scale menu, in its order; 0 stands for "Авто". */
    val scaleChoices: List<Double> =
        listOf(0.0, 2.0, 5.0, 10.0, 20.0, 30.0, 50.0, 100.0, 200.0, 500.0, 1000.0)

    const val DEFAULT_SPAN_MS: Long = 600_000L

    /** The stop loss slider's range, percent from the entry. */
    const val STOP_MIN = 0.1
    const val STOP_MAX = 20.0

    fun load(context: Context): ChartSettings {
        val prefs = prefsOf(context, FILE)
        return ChartSettings(
            sizes = six(prefs.getString("sizes", null), defaultSizes),
            sizeSel = prefs.getInt("size_sel", 2).coerceIn(0, 5),
            takes = six(prefs.getString("takes", null), defaultTakes),
            takeSel = prefs.getInt("take_sel", 1).coerceIn(0, 5),
            scalePct = prefs.getFloat("scale_pct", 0f).toDouble().coerceAtLeast(0.0),
            spanMs = prefs.getLong("span_ms", DEFAULT_SPAN_MS),
            cursorMode = prefs.getBoolean("cursor_mode", false),
            spotSingleTap = prefs.getBoolean("spot_single_tap", false),
            stopOn = prefs.getBoolean("stop_on", false),
            stopPct = prefs.getFloat("stop_pct", 2f).toDouble().coerceIn(STOP_MIN, STOP_MAX),
        )
    }

    fun save(context: Context, settings: ChartSettings) {
        prefsOf(context, FILE).edit()
            .putString("sizes", settings.sizes.joinToString(";"))
            .putInt("size_sel", settings.sizeSel)
            .putString("takes", settings.takes.joinToString(";"))
            .putInt("take_sel", settings.takeSel)
            .putFloat("scale_pct", settings.scalePct.toFloat())
            .putLong("span_ms", settings.spanMs)
            .putBoolean("cursor_mode", settings.cursorMode)
            .putBoolean("spot_single_tap", settings.spotSingleTap)
            .putBoolean("stop_on", settings.stopOn)
            .putFloat("stop_pct", settings.stopPct.toFloat())
            .apply()
    }

    /**
     * A take profit on the grid the core keeps: 0.02% steps up to 2%, whole percents up to 100%,
     * tens up to 900%. The core confirms only such a value, and the order waits for it.
     */
    fun snapTake(pct: Double): Double = when {
        pct <= 2.0 -> ((pct * 50.0).roundToLong() / 50.0).coerceAtLeast(0.02)
        pct <= 100.0 -> pct.roundToLong().toDouble()
        else -> ((pct / 10.0).roundToLong() * 10.0).coerceAtMost(900.0)
    }

    /** Six positive numbers out of a stored list; a value that does not read keeps its default. */
    private fun six(stored: String?, defaults: List<Double>): List<Double> {
        val parts = stored?.split(';').orEmpty()
        return List(6) { index ->
            parts.getOrNull(index)?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                ?: defaults[index]
        }
    }
}
