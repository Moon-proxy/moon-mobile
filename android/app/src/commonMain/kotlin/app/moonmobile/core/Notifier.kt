package app.moonmobile.core

import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject
import app.moonmobile.platform.Notice
import app.moonmobile.platform.Platform
import app.moonmobile.platform.PlatformContext

/**
 * Shows what the bridge decided to notify about — a core that lost its connection, a trade, a
 * server that runs hot or short of memory, a slow ping and the exchange's limits — and plays the
 * detect sounds. The rules themselves are in the bridge (rust/src/alerts.rs): this side sends the
 * user's switches and hands what comes back to the platform. Polled from the session's poller for
 * as long as the app is connected.
 */
object Notifier {
    /** The switches and server names the bridge has; null until the session's first poll. */
    private var sent: Pair<NotifySettings, Map<String, String>>? = null

    /** A new session: its bridge has not had the settings yet. */
    fun reset() {
        sent = null
    }

    /**
     * Sends the switches when they changed, then posts the notices and plays the sounds the
     * bridge decided on since the last poll.
     */
    fun poll(context: PlatformContext) {
        val settings = AppPrefs.notify.value
        val names = AppPrefs.serverNames.value
        if (sent != settings to names) {
            if (!JSONObject(Native.call(configRequest(settings, names))).optBoolean("ok", false)) return
            sent = settings to names
        }
        val reply = JSONObject(Native.call("""{"op":"alerts"}"""))
        if (!reply.optBoolean("ok", false)) return
        reply.optJSONArray("sounds")?.let { sounds ->
            for (i in 0 until sounds.length()) Platform.playDetectSound(context, sounds.getString(i))
        }
        val notices = reply.optJSONArray("notices") ?: return
        // While the app is on the screen nothing goes to the notification shade: what it would
        // say is in front of the user already. What gathered while it was away starts over.
        if (Session.uiVisible) {
            recent.clear()
            return
        }
        val items = (0 until notices.length()).map { notices.getJSONObject(it) }
        for ((kind, fresh) in items.groupBy { it.optString("kind").ifEmpty { "server" } }) {
            val lines = recent.getOrPut(kind) { mutableListOf() }
            val before = lines.size
            for (item in fresh) lines.add(0, line(kind, item.optString("title"), item.optString("text")))
            while (lines.size > MAX_LINES) lines.removeAt(lines.lastIndex)
            val newest = fresh.last()
            val count = before + fresh.size
            Platform.postNotice(
                context,
                Notice(
                    kind = kind,
                    title = if (count == 1) newest.optString("title") else groupTitle(kind, count),
                    text = if (count == 1) newest.optString("text") else lines.first(),
                    lines = lines.toList(),
                    // A lost core after another: the list grows without a sound for each.
                    quiet = before > 0 && kind == "core_lost",
                ),
            )
        }
    }

    /** Each kind's notices since the app was last on the screen, newest first. */
    private val recent = mutableMapOf<String, MutableList<String>>()

    /** The most lines one gathered notification keeps. */
    private const val MAX_LINES = 20

    /** A notice as one line of its kind's list. */
    private fun line(kind: String, title: String, text: String): String = when (kind) {
        "core_lost" -> title.removePrefix("Ядро ").removeSuffix(" потеряло связь") + " — " + text
        else -> if (text.isEmpty()) title else "$title · ${text.lineSequence().first()}"
    }

    private fun groupTitle(kind: String, count: Int): String = when (kind) {
        "core_lost" -> "Потеряли связь ядер: $count"
        "trade" -> "Сделки: $count"
        else -> "Уведомления: $count"
    }

    private fun configRequest(settings: NotifySettings, names: Map<String, String>): String =
        JSONObject()
            .put("op", "alerts_config")
            .put(
                "settings",
                JSONObject()
                    .put("core_lost", settings.coreLost)
                    .put("trades", settings.trades)
                    .put("entries_only", settings.entriesOnly)
                    .put("all_strategies", settings.allStrategies)
                    .put("strategies", JSONArray(settings.strategies.toList()))
                    .put("ping", settings.ping)
                    .put("cpu", settings.cpu)
                    .put("memory", settings.memory)
                    .put("api_limits", settings.apiLimits)
                    .put("sounds", settings.sounds),
            )
            .put("server_names", JSONObject(names))
            .toString()

    /** Whether the app may post notifications at all. */
    fun allowed(context: PlatformContext): Boolean = Platform.notificationsAllowed(context)

    /** Which server a core runs on: its machine's address, or the core alone when unknown. */
    fun serverKey(core: Core): String = core.host.ifEmpty { "core:${core.id}" }

    /** A server as the user named it, else its address, else its one core's name. */
    fun serverTitle(core: Core, names: Map<String, String>): String =
        names[core.host] ?: core.host.ifEmpty { core.name }
}
