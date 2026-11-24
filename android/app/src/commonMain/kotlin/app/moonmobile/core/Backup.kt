package app.moonmobile.core

import app.moonmobile.platform.PlatformContext as Context
import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject
import app.moonmobile.platform.Platform

/**
 * Every setting of the app as one JSON object: the `[moonmobile]` section the bridge writes into
 * `settings.toml` on export and reads back on import, so one file carries the terminal's core
 * settings and the phone's own.
 */
object Backup {
    private const val VERSION = 1

    fun section(context: Context): JSONObject {
        val app = Platform.app(context)
        AppPrefs.load(app)
        val chart = ChartPrefs.load(app)
        val notify = AppPrefs.notify.value
        return JSONObject()
            .put("version", VERSION)
            .put("theme", AppPrefs.theme.value.key)
            .put("charts", Session.chartsEnabled(app))
            .put(
                "chart",
                JSONObject()
                    .put("sizes", JSONArray(chart.sizes))
                    .put("size_sel", chart.sizeSel)
                    .put("takes", JSONArray(chart.takes))
                    .put("take_sel", chart.takeSel)
                    .put("scale_pct", chart.scalePct)
                    .put("span_ms", chart.spanMs)
                    .put("cursor_mode", chart.cursorMode)
                    .put("single_tap", chart.spotSingleTap)
                    .put("stop_on", chart.stopOn)
                    .put("stop_pct", chart.stopPct),
            )
            .put(
                "notify",
                JSONObject()
                    .put("core_lost", notify.coreLost)
                    .put("trades", notify.trades)
                    .put("entries_only", notify.entriesOnly)
                    .put("all_strategies", notify.allStrategies)
                    .put("strategies", JSONArray(notify.strategies.sorted()))
                    .put("ping", notify.ping)
                    .put("cpu", notify.cpu)
                    .put("memory", notify.memory)
                    .put("api_limits", notify.apiLimits)
                    .put("sounds", notify.sounds)
                    .put("sounds_media", notify.soundsMedia),
            )
            .put(
                "picker",
                JSONObject().apply {
                    AppPrefs.pickerCores(app)?.let { put("cores", JSONArray(it.sorted())) }
                    put("spot", AppPrefs.flag(app, "pick_spot", true))
                    put("futures", AppPrefs.flag(app, "pick_futures", true))
                    put("liquidity", AppPrefs.flag(app, "pick_liquidity", false))
                    put("rank_by", AppPrefs.text(app, "rank_by", "volatility"))
                    put("interval", AppPrefs.text(app, "rank_interval", "5m"))
                    put("history", JSONArray(AppPrefs.history(app).map { "${it.first}:${it.second}" }))
                },
            )
            .put(
                "halt",
                JSONObject().apply {
                    AppPrefs.haltCores(app)?.let { put("cores", JSONArray(it.sorted())) }
                },
            )
            .put(
                "servers",
                JSONObject().apply {
                    AppPrefs.serverNames.value.forEach { (host, name) -> put(host, name) }
                },
            )
            .put(
                "cores_view",
                JSONObject()
                    .put("group", AppPrefs.text(app, "cores_group", "servers"))
                    .put("open_servers", JSONArray(AppPrefs.words(app, "open_servers").sorted()))
                    .put("open_exchanges", JSONArray(AppPrefs.words(app, "open_exchanges").sorted())),
            )
            .put(
                "cores",
                JSONObject().apply {
                    for ((id, o) in AppPrefs.coreOverrides(app)) {
                        val entry = JSONObject()
                        o.active?.let { entry.put("active", it) }
                        o.color?.let { entry.put("color", it) }
                        if (entry.length() > 0) put(id.toString(), entry)
                    }
                },
            )
    }

    /** Takes over the settings of an imported section; what it lacks stays as it is. */
    fun apply(context: Context, section: JSONObject) {
        val app = Platform.app(context)
        AppPrefs.load(app)
        section.optString("theme").let { key ->
            ThemeMode.entries.firstOrNull { it.key == key }?.let { AppPrefs.setTheme(app, it) }
        }
        if (section.has("charts")) Session.setChartsEnabled(app, section.optBoolean("charts", true))

        section.optJSONObject("chart")?.let { o ->
            val base = ChartPrefs.load(app)
            val sizes = o.optJSONArray("sizes").doubles().takeIf { it.size == base.sizes.size } ?: base.sizes
            val takes = o.optJSONArray("takes").doubles().takeIf { it.size == base.takes.size } ?: base.takes
            ChartPrefs.save(
                app,
                base.copy(
                    sizes = sizes,
                    sizeSel = o.optInt("size_sel", base.sizeSel).coerceIn(0, sizes.size - 1),
                    takes = takes,
                    takeSel = o.optInt("take_sel", base.takeSel).coerceIn(0, takes.size - 1),
                    scalePct = o.optDouble("scale_pct", base.scalePct).takeIf { !it.isNaN() } ?: base.scalePct,
                    spanMs = o.optLong("span_ms", base.spanMs),
                    cursorMode = o.optBoolean("cursor_mode", base.cursorMode),
                    spotSingleTap = o.optBoolean("single_tap", base.spotSingleTap),
                    stopOn = o.optBoolean("stop_on", base.stopOn),
                    stopPct = o.optDouble("stop_pct", base.stopPct)
                        .takeIf { !it.isNaN() }?.coerceIn(ChartPrefs.STOP_MIN, ChartPrefs.STOP_MAX) ?: base.stopPct,
                ),
            )
        }
        section.optJSONObject("notify")?.let { o ->
            val base = AppPrefs.notify.value
            AppPrefs.setNotify(
                app,
                NotifySettings(
                    coreLost = o.optBoolean("core_lost", base.coreLost),
                    trades = o.optBoolean("trades", base.trades),
                    entriesOnly = o.optBoolean("entries_only", base.entriesOnly),
                    allStrategies = o.optBoolean("all_strategies", base.allStrategies),
                    strategies = o.optJSONArray("strategies")?.strings()?.toSet() ?: base.strategies,
                    ping = o.optBoolean("ping", base.ping),
                    cpu = o.optBoolean("cpu", base.cpu),
                    memory = o.optBoolean("memory", base.memory),
                    apiLimits = o.optBoolean("api_limits", base.apiLimits),
                    sounds = o.optBoolean("sounds", base.sounds),
                    soundsMedia = o.optBoolean("sounds_media", base.soundsMedia),
                ),
            )
        }
        section.optJSONObject("picker")?.let { o ->
            AppPrefs.setPickerCores(app, o.optJSONArray("cores")?.longs()?.toSet())
            for (key in listOf("spot", "futures", "liquidity")) {
                if (o.has(key)) AppPrefs.setFlag(app, "pick_$key", o.optBoolean(key))
            }
            o.optString("rank_by").takeIf { it.isNotEmpty() }?.let { AppPrefs.setText(app, "rank_by", it) }
            o.optString("interval").takeIf { it.isNotEmpty() }?.let { AppPrefs.setText(app, "rank_interval", it) }
            o.optJSONArray("history")?.strings()?.let { AppPrefs.setHistory(app, it) }
        }
        section.optJSONObject("halt")?.let { o ->
            AppPrefs.setHaltCores(app, o.optJSONArray("cores")?.longs()?.toSet())
        }
        section.optJSONObject("cores")?.let { o ->
            val overrides = o.keys().asSequence().mapNotNull { key ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                val entry = o.optJSONObject(key) ?: return@mapNotNull null
                id to AppPrefs.CoreOverride(
                    active = if (entry.has("active")) entry.optBoolean("active") else null,
                    color = entry.optString("color").takeIf { it.startsWith("#") && it.length == 7 },
                )
            }.toMap()
            AppPrefs.setCoreOverrides(app, overrides)
        }
        section.optJSONObject("servers")?.let { o ->
            AppPrefs.setServerNames(app, o.keys().asSequence().associateWith { o.optString(it) })
        }
        section.optJSONObject("cores_view")?.let { o ->
            o.optString("group").takeIf { it == "servers" || it == "exchanges" }?.let {
                AppPrefs.setText(app, "cores_group", it)
            }
            o.optJSONArray("open_servers")?.let { AppPrefs.setWords(app, "open_servers", it.strings().toSet()) }
            o.optJSONArray("open_exchanges")?.let { AppPrefs.setWords(app, "open_exchanges", it.strings().toSet()) }
        }
    }

    /** The phone's `settings.toml` with the app's section at its end, as the export carries it. */
    fun mergedSettings(context: Context, terminalPart: String): Reply {
        val request = JSONObject()
            .put("op", "settings_merge")
            .put("toml", terminalPart)
            .put("section", section(context))
        return parseReply(Native.tool(request.toString()))
    }

    /** The app's section of a `settings.toml`, or `null` when it has none. */
    fun readSection(text: String): JSONObject? {
        val reply = parseReply(Native.tool(JSONObject().put("op", "settings_read").put("toml", text).toString()))
        if (!reply.ok) return null
        return reply.json.optJSONObject("section")
    }

    private fun JSONArray?.doubles(): List<Double> =
        if (this == null) emptyList() else List(length()) { optDouble(it) }.filter { !it.isNaN() }

    private fun JSONArray.strings(): List<String> = List(length()) { optString(it) }.filter { it.isNotEmpty() }

    private fun JSONArray.longs(): List<Long> = List(length()) { optLong(it) }.filter { it != 0L }
}
