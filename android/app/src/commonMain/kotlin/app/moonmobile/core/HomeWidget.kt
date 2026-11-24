package app.moonmobile.core

import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject
import app.moonmobile.platform.PlatformContext as Context
import app.moonmobile.text.localDayStart
import app.moonmobile.text.nowMs

/** One kind of strategy in the widget's day: Drops, Spread, MoonShot… */
data class WidgetKind(val kind: String, val trades: Int, val wins: Int, val losses: Int)

/** What the home-screen widget shows. */
data class WidgetState(
    /** Connected to the cores at all; when not, the rest is the last thing seen. */
    val connected: Boolean,
    val coresOnline: Int,
    val coresTotal: Int,
    /** The cores without a connection, by name. */
    val offline: List<String>,
    /** Open positions: entered trades, without their PnL. */
    val positions: Int,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val kinds: List<WidgetKind>,
    val updatedMs: Long,
) {
    /** Any core without a connection, or none at all: the widget's frame turns red. */
    val alarm: Boolean get() = !connected || offline.isNotEmpty() || coresOnline < coresTotal

    fun toJson(): JSONObject = JSONObject()
        .put("connected", connected)
        .put("online", coresOnline)
        .put("total", coresTotal)
        .put("offline", JSONArray().also { list -> offline.forEach { list.put(it) } })
        .put("positions", positions)
        .put("trades", trades)
        .put("wins", wins)
        .put("losses", losses)
        .put(
            "kinds",
            JSONArray().also { list ->
                kinds.forEach {
                    list.put(JSONObject().put("kind", it.kind).put("trades", it.trades).put("wins", it.wins).put("losses", it.losses))
                }
            },
        )
        .put("updated", updatedMs)

    /** The same picture, the time apart. */
    fun sameAs(other: WidgetState?): Boolean = other != null && copy(updatedMs = 0) == other.copy(updatedMs = 0)

    companion object {
        fun fromJson(o: JSONObject): WidgetState = WidgetState(
            connected = o.optBoolean("connected", false),
            coresOnline = o.optInt("online", 0),
            coresTotal = o.optInt("total", 0),
            offline = o.optJSONArray("offline")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            positions = o.optInt("positions", 0),
            trades = o.optInt("trades", 0),
            wins = o.optInt("wins", 0),
            losses = o.optInt("losses", 0),
            kinds = o.optJSONArray("kinds")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    WidgetKind(it.optString("kind"), it.optInt("trades", 0), it.optInt("wins", 0), it.optInt("losses", 0))
                }
            }.orEmpty(),
            updatedMs = o.optLong("updated", 0L),
        )
    }
}

/** The home-screen widget, drawn by the platform: a long one and a square one. */
expect object HomeWidget {
    /** Draws [state] on every widget placed, and keeps it for when the app is not running. */
    fun show(context: Context, state: WidgetState)

    /** Whether any widget is on the home screen: nothing to work out otherwise. */
    fun placed(context: Context): Boolean

    /** How to put the widget on the home screen, for the settings. */
    val howToPlace: String

    /** How the widget gets its state on this device, for the settings; null when nothing to say. */
    fun status(): String?
}

/**
 * Feeds the widget from the session: the cores and positions as they change, the day's trades
 * once a minute. The day ends at the hour the settings give (Настройки → Виджет).
 */
object WidgetFeed {
    private const val TRADES_EVERY_MS = 60_000L
    private const val DAY_MS = 86_400_000L
    private var tradesAt = 0L
    private var day: JSONObject? = null
    private var shown: WidgetState? = null
    private var shownAt = 0L

    /** Minutes after midnight the day's count starts over (0 for midnight). */
    fun dayEndMinutes(context: Context): Int =
        AppPrefs.text(context, "widget_day_end_min", "0").toIntOrNull()?.coerceIn(0, 1439) ?: 0

    fun setDayEndMinutes(context: Context, minutes: Int) {
        AppPrefs.setText(context, "widget_day_end_min", minutes.coerceIn(0, 1439).toString())
        tradesAt = 0L
    }

    /** Where the current day began: the last day's end before now. */
    fun dayStartMs(context: Context, now: Long): Long {
        val start = localDayStart(now) + dayEndMinutes(context) * 60_000L
        return if (start > now) start - DAY_MS else start
    }

    suspend fun step(context: Context, snapshot: Snapshot) {
        if (!HomeWidget.placed(context)) return
        val now = nowMs()
        if (now - tradesAt >= TRADES_EVERY_MS) {
            tradesAt = now
            val reply = Session.call(
                JSONObject().put("op", "day_stats").put("from_ms", dayStartMs(context, now)).put("to_ms", now),
            )
            if (reply.ok) day = reply.json
        }
        val cores = snapshot.cores.filter { it.active }
        val offline = cores.filter { it.status != "ready" }.map { it.name }
        val today = day
        val state = WidgetState(
            connected = true,
            coresOnline = cores.size - offline.size,
            coresTotal = cores.size,
            offline = offline,
            positions = cores.sumOf { it.positions },
            trades = today?.optInt("trades", 0) ?: 0,
            wins = today?.optInt("wins", 0) ?: 0,
            losses = today?.optInt("losses", 0) ?: 0,
            kinds = today?.optJSONArray("kinds")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    WidgetKind(it.optString("kind"), it.optInt("trades", 0), it.optInt("wins", 0), it.optInt("losses", 0))
                }
            }.orEmpty(),
            updatedMs = now,
        )
        show(context, state, now)
    }

    /** The app let go of the cores: the widget says so, red, with the last day it saw. */
    fun disconnected(context: Context) {
        if (!HomeWidget.placed(context)) return
        val last = shown ?: WidgetState(false, 0, 0, emptyList(), 0, 0, 0, 0, emptyList(), 0L)
        val now = nowMs()
        show(context, last.copy(connected = false, coresOnline = 0, positions = 0, updatedMs = now), now)
    }

    private fun show(context: Context, state: WidgetState, now: Long) {
        // The same picture is drawn again once a minute at most, for its time.
        if (state.sameAs(shown) && now - shownAt < TRADES_EVERY_MS) return
        shown = state
        shownAt = now
        HomeWidget.show(context, state)
    }
}
