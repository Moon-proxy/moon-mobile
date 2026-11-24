package app.moonmobile.core

import app.moonmobile.platform.PlatformContext as Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.moonmobile.json.JSONObject
import app.moonmobile.platform.prefsOf

/** Light, dark, or as the phone is set. */
enum class ThemeMode(val key: String, val title: String) {
    SYSTEM("system", "Как в системе"),
    DARK("dark", "Тёмная"),
    LIGHT("light", "Светлая"),
}

/** Which push notifications the app sends. */
data class NotifySettings(
    /** A core that was connected lost its connection. */
    val coreLost: Boolean = true,
    /** Notify about trades: an entry filled. */
    val trades: Boolean = true,
    /** Only an entry that filled; off also notifies about an entry order being placed. */
    val entriesOnly: Boolean = true,
    /** Trades of every strategy, or only of [strategies]. */
    val allStrategies: Boolean = true,
    /** `core:strategyId` of the strategies whose trades are notified when not [allStrategies]. */
    val strategies: Set<String> = emptySet(),
    /** Ping of 250 ms and more. */
    val ping: Boolean = true,
    /** The core's machine at 90% CPU for over a minute. */
    val cpu: Boolean = true,
    /** The core's machine running out of memory. */
    val memory: Boolean = true,
    /** The exchange refusing requests over its limits; on HyperLiquid, its quota down to 5%. */
    val apiLimits: Boolean = true,
    /** Play the sound a detect's strategy chose, as MoonTerminal does. */
    val sounds: Boolean = true,
    /** Play them at the media volume, heard even when notifications are silenced. */
    val soundsMedia: Boolean = true,
)

/**
 * The app's own settings, apart from the chart's: theme, notifications, the cores the market
 * picker searches and the cores "Остановка стратегий" covers.
 */
object AppPrefs {
    private const val FILE = "moonmobile_app"

    private val themeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = themeFlow.asStateFlow()

    private val notifyFlow = MutableStateFlow(NotifySettings())
    val notify: StateFlow<NotifySettings> = notifyFlow.asStateFlow()

    /** The interface's scale against a phone's, 1.3 for 130%; null picks one by the screen. */
    private val uiScaleFlow = MutableStateFlow<Float?>(null)
    val uiScale: StateFlow<Float?> = uiScaleFlow.asStateFlow()

    private var loaded = false

    fun load(context: Context, again: Boolean = false) {
        if (loaded && !again) return
        loaded = true
        val prefs = prefs(context)
        themeFlow.value = ThemeMode.entries.firstOrNull { it.key == prefs.getString("theme", null) }
            ?: ThemeMode.SYSTEM
        uiScaleFlow.value = prefs.getFloat("ui_scale", 0f).takeIf { it > 0f }
        overridesFlow.value = coreOverrides(context)
        serverNamesFlow.value = readServerNames(context)
        notifyFlow.value = NotifySettings(
            coreLost = prefs.getBoolean("n_core_lost", true),
            trades = prefs.getBoolean("n_trades", true),
            entriesOnly = prefs.getBoolean("n_entries_only", true),
            allStrategies = prefs.getBoolean("n_all_strategies", true),
            strategies = prefs.getStringSet("n_strategies", emptySet()).orEmpty().toSet(),
            ping = prefs.getBoolean("n_ping", true),
            cpu = prefs.getBoolean("n_cpu", true),
            memory = prefs.getBoolean("n_memory", true),
            apiLimits = prefs.getBoolean("n_api", true),
            sounds = prefs.getBoolean("n_sounds", true),
            soundsMedia = prefs.getBoolean("n_sounds_media", true),
        )
    }

    fun setTheme(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString("theme", mode.key).apply()
        themeFlow.value = mode
    }

    /** Null goes back to the scale picked by the screen. */
    fun setUiScale(context: Context, scale: Float?) {
        prefs(context).edit().putFloat("ui_scale", scale ?: 0f).apply()
        uiScaleFlow.value = scale
    }

    fun setNotify(context: Context, settings: NotifySettings) {
        prefs(context).edit()
            .putBoolean("n_core_lost", settings.coreLost)
            .putBoolean("n_trades", settings.trades)
            .putBoolean("n_entries_only", settings.entriesOnly)
            .putBoolean("n_all_strategies", settings.allStrategies)
            .putStringSet("n_strategies", settings.strategies)
            .putBoolean("n_ping", settings.ping)
            .putBoolean("n_cpu", settings.cpu)
            .putBoolean("n_memory", settings.memory)
            .putBoolean("n_api", settings.apiLimits)
            .putBoolean("n_sounds", settings.sounds)
            .putBoolean("n_sounds_media", settings.soundsMedia)
            .apply()
        notifyFlow.value = settings
    }

    /** The cores the market picker searches; `null` is every active core. */
    fun pickerCores(context: Context): Set<Long>? = idSet(context, "picker_cores")

    fun setPickerCores(context: Context, cores: Set<Long>?) = putIdSet(context, "picker_cores", cores)

    /** The cores "Остановка стратегий" stops; `null` is every active core. */
    fun haltCores(context: Context): Set<Long>? = idSet(context, "halt_cores")

    fun setHaltCores(context: Context, cores: Set<Long>?) = putIdSet(context, "halt_cores", cores)

    /** Whether "Остановка стратегий" is on. */
    fun haltOn(context: Context): Boolean = prefs(context).getBoolean("halt_on", false)

    fun setHaltOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean("halt_on", on).apply()

    /** The cores the switch stopped, to start again exactly those; empty while it is off. */
    fun halted(context: Context): Set<Long> = idSet(context, "halted").orEmpty()

    fun setHalted(context: Context, cores: Set<Long>) = putIdSet(context, "halted", cores)

    /** Markets opened from the picker, newest first, as `core:market`. */
    fun history(context: Context): List<Pair<Long, String>> =
        prefs(context).getString("history", "").orEmpty()
            .split('\n')
            .mapNotNull { line ->
                val at = line.indexOf(':')
                val core = if (at > 0) line.substring(0, at).toLongOrNull() else null
                if (core == null || at == line.length - 1) null else core to line.substring(at + 1)
            }

    fun addHistory(context: Context, core: Long, market: String) {
        val next = (listOf(core to market) + history(context).filter { it != core to market }).take(40)
        prefs(context).edit()
            .putString("history", next.joinToString("\n") { "${it.first}:${it.second}" })
            .apply()
    }

    /** The app's own changes to cores: switched off or on, or another colour (`#rrggbb`). */
    data class CoreOverride(val active: Boolean? = null, val color: String? = null)

    fun coreOverrides(context: Context): Map<Long, CoreOverride> {
        val text = prefs(context).getString("core_overrides", null) ?: return emptyMap()
        return try {
            val json = JSONObject(text)
            json.keys().asSequence().mapNotNull { key ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                val o = json.optJSONObject(key) ?: return@mapNotNull null
                id to CoreOverride(
                    active = if (o.has("active")) o.optBoolean("active") else null,
                    color = o.optString("color").takeIf { it.startsWith("#") && it.length == 7 },
                )
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setCoreOverrides(context: Context, overrides: Map<Long, CoreOverride>) {
        val json = JSONObject()
        for ((id, o) in overrides) {
            if (o.active == null && o.color == null) continue
            val entry = JSONObject()
            o.active?.let { entry.put("active", it) }
            o.color?.let { entry.put("color", it) }
            json.put(id.toString(), entry)
        }
        prefs(context).edit().putString("core_overrides", json.toString()).apply()
        overridesFlow.value = overrides.filterValues { it.active != null || it.color != null }
    }

    private val overridesFlow = MutableStateFlow<Map<Long, CoreOverride>>(emptyMap())

    /** The overrides as they change, for screens that show core colours. */
    val overrides: StateFlow<Map<Long, CoreOverride>> = overridesFlow.asStateFlow()

    /** The names the user gave servers, by address. */
    private val serverNamesFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverNames: StateFlow<Map<String, String>> = serverNamesFlow.asStateFlow()

    private fun readServerNames(context: Context): Map<String, String> {
        val text = prefs(context).getString("server_names", null) ?: return emptyMap()
        return try {
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { json.optString(it) }.filterValues { it.isNotBlank() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setServerNames(context: Context, names: Map<String, String>) {
        val clean = names.filter { it.key.isNotEmpty() && it.value.isNotBlank() }.mapValues { it.value.trim() }
        val json = JSONObject()
        clean.forEach { (host, name) -> json.put(host, name) }
        prefs(context).edit().putString("server_names", json.toString()).apply()
        serverNamesFlow.value = clean
    }

    /** A set of remembered words: the opened server and exchange groups. */
    fun words(context: Context, key: String): Set<String> =
        prefs(context).getStringSet(key, null)?.toSet() ?: emptySet()

    fun setWords(context: Context, key: String, words: Set<String>) =
        prefs(context).edit().putStringSet(key, words).apply()

    fun setHistory(context: Context, lines: List<String>) {
        prefs(context).edit().putString("history", lines.take(40).joinToString("\n")).apply()
    }

    /** A plain remembered flag: the picker's Spot and Futures filters. */
    fun flag(context: Context, key: String, default: Boolean): Boolean =
        prefs(context).getBoolean(key, default)

    fun setFlag(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    /** A plain remembered text: the picker's ranking choices. */
    fun text(context: Context, key: String, default: String): String =
        prefs(context).getString(key, null) ?: default

    fun setText(context: Context, key: String, value: String) =
        prefs(context).edit().putString(key, value).apply()

    private fun idSet(context: Context, key: String): Set<Long>? {
        val stored = prefs(context).getStringSet(key, null) ?: return null
        return stored.mapNotNull { it.toLongOrNull() }.toSet()
    }

    private fun putIdSet(context: Context, key: String, ids: Set<Long>?) {
        val edit = prefs(context).edit()
        if (ids == null) edit.remove(key) else edit.putStringSet(key, ids.map { it.toString() }.toSet())
        edit.apply()
    }

    private fun prefs(context: Context) =
        prefsOf(context, FILE)
}
