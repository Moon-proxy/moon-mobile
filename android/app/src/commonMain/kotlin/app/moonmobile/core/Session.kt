package app.moonmobile.core

import app.moonmobile.platform.PlatformContext as Context
import app.moonmobile.platform.PickedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.moonmobile.json.JSONObject
import app.moonmobile.text.DateFormat
import app.moonmobile.text.nowMs
import app.moonmobile.platform.ioDispatcher
import app.moonmobile.platform.sameSecret
import app.moonmobile.json.JSONArray
import app.moonmobile.platform.prefsOf
import app.moonmobile.platform.Platform
import kotlin.concurrent.Volatile

enum class Phase { SETUP, CONNECTING, ONLINE }

data class AppState(
    val phase: Phase = Phase.SETUP,
    /** What went wrong with the last connect, in the user's language. */
    val error: String? = null,
    val snapshot: Snapshot? = null,
    val hasServersFile: Boolean = false,
    val hasSettingsFile: Boolean = false,
    val savedPassword: Boolean = false,
    val demo: Boolean = false,
    /** Balances are drawn as dots until the eye is pressed again. */
    val balanceHidden: Boolean = false,
    /** Connect with market data for charts. Off is the accounts-only session of earlier builds. */
    val chartsEnabled: Boolean = true,
)

/**
 * The app's one session with the bridge: owns the engine's lifetime, polls its snapshot and
 * relays commands. Lives as long as the process, so the screens can come and go.
 */
object Session {
    private const val KEY_PASSWORD = "servers_enc_password"
    private const val PREFS = "moonmobile.ui"
    private const val PREF_HIDE_BALANCE = "hide_balance"
    private const val PREF_CHARTS = "charts"
    private const val PREF_MINE = "mine_orders"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    private var poller: Job? = null

    /** For the notifications the poller posts. */
    @Volatile
    private var appContext: Context? = null

    /** Whether an activity is on screen: polling slows down without one. */
    @Volatile
    var uiVisible: Boolean = false

    /**
     * Whether the app may connect on its own with the remembered password. Off once the user
     * disconnected, or once such a connect failed, so a failure is not retried in a loop.
     */
    @Volatile
    var autoConnect: Boolean = true

    /** The bridge's data root: `servers.enc`, `cfg/`, logs and caches. */
    private fun dataDir(context: Context): String = Platform.dataRoot(context) + "/moon"

    private fun serversFile(context: Context): String = dataDir(context) + "/servers.enc"

    private fun settingsFile(context: Context): String = dataDir(context) + "/cfg/settings.toml"

    /** Re-reads what is on disk for the setup screen. */
    fun refreshFiles(context: Context) {
        val app = Platform.app(context)
        AppPrefs.load(app)
        val servers = Platform.isFile(serversFile(app))
        val settings = Platform.isFile(settingsFile(app))
        val saved = SecureStore.has(app, KEY_PASSWORD)
        val prefs = prefsOf(app, PREFS)
        val hidden = prefs.getBoolean(PREF_HIDE_BALANCE, false)
        val charts = prefs.getBoolean(PREF_CHARTS, true)
        mutableState.update {
            it.copy(
                hasServersFile = servers,
                hasSettingsFile = settings,
                savedPassword = saved,
                balanceHidden = hidden,
                chartsEnabled = charts,
            )
        }
    }

    /** Whether the next connect asks the cores for market data; remembered across launches. */
    fun chartsEnabled(context: Context): Boolean =
        prefsOf(context, PREFS)
            .getBoolean(PREF_CHARTS, true)

    fun setChartsEnabled(context: Context, enabled: Boolean) {
        prefsOf(context, PREFS)
            .edit().putBoolean(PREF_CHARTS, enabled).apply()
        mutableState.update { it.copy(chartsEnabled = enabled) }
    }

    /** Hides or shows every balance; remembered across launches. */
    fun setBalanceHidden(context: Context, hidden: Boolean) {
        prefsOf(context, PREFS)
            .edit().putBoolean(PREF_HIDE_BALANCE, hidden).apply()
        mutableState.update { it.copy(balanceHidden = hidden) }
    }

    /** Copies the picked `servers.enc` into the data root. */
    fun importServers(context: Context, uri: PickedFile) = importFile(context, uri, serversFile(context))

    /**
     * Copies the picked `settings.toml` into the data root's `cfg/`. One exported from the app
     * also carries the app's own settings, which are taken over.
     */
    fun importSettings(context: Context, uri: PickedFile) = importFile(context, uri, settingsFile(context)) { file ->
        val section = Backup.readSection(Platform.readText(file))
        if (section != null) {
            Backup.apply(context, section)
            AppPrefs.load(Platform.app(context), again = true)
        }
    }

    /** What an export carries: `servers.enc` as it is (its path), `settings.toml` with the app's part. */
    class Export(val serversPath: String, val settings: String)

    /** The export, or the reason there is none, in words for the screen. */
    fun exportFiles(context: Context): Result<Export> {
        val app = Platform.app(context)
        val servers = serversFile(app).takeIf { Platform.isFile(it) }
            ?: return Result.failure(IllegalStateException("Нечего экспортировать: servers.enc не загружен"))
        val terminal = settingsFile(app).takeIf { Platform.isFile(it) }?.let { Platform.readText(it) }.orEmpty()
        val merged = Backup.mergedSettings(app, terminal)
        if (!merged.ok) {
            return Result.failure(
                IllegalStateException("Не удалось собрать settings.toml: ${merged.message.ifEmpty { merged.code }}"),
            )
        }
        return Result.success(Export(servers, merged.json.optString("toml")))
    }

    private fun importFile(context: Context, uri: PickedFile, target: String, after: (String) -> Unit = {}) {
        val app = Platform.app(context)
        scope.launch(ioDispatcher) {
            val problem = try {
                Platform.importFile(app, uri, target) ?: try {
                    after(target)
                    null
                } catch (e: Exception) {
                    "Файл загружен, но настройки приложения из него не прочитались: ${e.message}"
                }
            } catch (e: Exception) {
                "Не удалось прочитать файл: ${e.message}"
            }
            mutableState.update { it.copy(error = problem) }
            refreshFiles(app)
        }
    }

    /** Connects with the encryption password of the imported `servers.enc`. */
    fun connect(context: Context, password: String, remember: Boolean) {
        val app = Platform.app(context)
        val request = JSONObject()
            .put("source", "servers_enc")
            .put("password", password)
        open(app, request, demo = false) {
            // Kept only once it has opened the file.
            SecureStore.put(app, KEY_PASSWORD, if (remember) password else null)
        }
    }

    /**
     * Starts the phone's own list of cores: a new, empty `servers.enc` encrypted with [password],
     * then connects to it. Cores are added by their keys afterwards.
     */
    fun createServers(context: Context, password: String, remember: Boolean) {
        val app = Platform.app(context)
        if (mutableState.value.phase != Phase.SETUP) return
        mutableState.update { it.copy(phase = Phase.CONNECTING, error = null) }
        scope.launch(ioDispatcher) {
            prepareProcess(app, demo = false)
            val reply = try {
                parseReply(
                    Native.tool(
                        JSONObject().put("op", "create_servers")
                            .put("data_dir", dataDir(app))
                            .put("password", password)
                            .toString(),
                    ),
                )
            } catch (e: Throwable) {
                errorReply("internal", e.toString())
            }
            mutableState.update { it.copy(phase = Phase.SETUP) }
            refreshFiles(app)
            if (!reply.ok) {
                mutableState.update {
                    it.copy(
                        error = when (reply.code) {
                            "short_password" -> "Пароль — не короче 6 символов"
                            "exists" -> "servers.enc уже есть на телефоне"
                            else -> describeError(reply)
                        },
                    )
                }
                return@launch
            }
            connect(app, password, remember)
        }
    }

    /** Closes the connections and opens them again: after a new file or setting. */
    fun reconnect(context: Context) {
        val app = Platform.app(context)
        poller?.cancel()
        poller = null
        mutableState.update { it.copy(phase = Phase.CONNECTING, snapshot = null, error = null) }
        scope.launch(ioDispatcher) {
            try {
                Native.close()
            } catch (e: Throwable) {
                // Nothing to close.
            }
            mutableState.update { it.copy(phase = Phase.SETUP) }
            refreshFiles(app)
            if (SecureStore.has(app, KEY_PASSWORD)) {
                autoConnect = true
                connectSaved(app)
            } else {
                // The start screen asks for the password.
                Platform.stopBackground(app)
            }
        }
    }

    /** Connects with the password remembered on this phone. */
    fun connectSaved(context: Context) {
        val app = Platform.app(context)
        val password = SecureStore.get(app, KEY_PASSWORD)
        if (password == null) {
            SecureStore.put(app, KEY_PASSWORD, null)
            mutableState.update {
                it.copy(savedPassword = false, error = "Сохранённый пароль недоступен — введите его заново")
            }
            return
        }
        open(app, JSONObject().put("source", "servers_enc").put("password", password), demo = false) {}
    }

    /** Whether the `servers.enc` password is remembered on this phone. */
    fun hasSavedPassword(context: Context): Boolean = SecureStore.has(Platform.app(context), KEY_PASSWORD)

    /** Whether [password] is the remembered `servers.enc` password: the way back past a lost lock. */
    fun checkSavedPassword(context: Context, password: String): Boolean {
        val saved = SecureStore.get(Platform.app(context), KEY_PASSWORD) ?: return false
        return sameSecret(saved.encodeToByteArray(), password.encodeToByteArray())
    }

    fun forgetPassword(context: Context) {
        SecureStore.put(Platform.app(context), KEY_PASSWORD, null)
        refreshFiles(context)
    }

    /** One synthetic core, no network: every screen with invented data. */
    fun connectDemo(context: Context) {
        open(Platform.app(context), JSONObject().put("source", "demo"), demo = true) {}
    }

    private fun open(app: Context, request: JSONObject, demo: Boolean, onOpened: () -> Unit) {
        if (mutableState.value.phase != Phase.SETUP) return
        mutableState.update { it.copy(phase = Phase.CONNECTING, error = null) }
        scope.launch(ioDispatcher) {
            prepareProcess(app, demo)
            request.put("data_dir", dataDir(app))
            val charts = prefsOf(app, PREFS)
                .getBoolean(PREF_CHARTS, true)
            request.put("market_data", if (charts) "scoped" else "none")
            // The app's own changes to cores, and the cores' log only for the API-limit alerts.
            AppPrefs.load(app)
            val overrides = JSONArray()
            for ((id, o) in AppPrefs.coreOverrides(app)) {
                val entry = JSONObject().put("core", id)
                o.active?.let { entry.put("active", it) }
                o.color?.let { entry.put("color", it) }
                overrides.put(entry)
            }
            request.put("overrides", overrides)
            request.put("core_log", AppPrefs.notify.value.apiLimits)
            loadMine(app)
            request.put("mine", JSONArray(mineFlow.value.toList()))
            val reply = try {
                parseReply(Native.open(request.toString()))
            } catch (e: Throwable) {
                errorReply("internal", e.toString())
            }
            if (!reply.ok) {
                autoConnect = false
                mutableState.update { it.copy(phase = Phase.SETUP, error = describeError(reply)) }
                return@launch
            }
            onOpened()
            // The logger is up now: apply the log-file switch and note the app's version.
            setLogFiles(app, AppPrefs.flag(app, "log_files", true))
            log("MoonMobile ${appVersion(app)}: подключение открыто")
            for (line in waitingLines.getAndUpdate { emptyList() }) log(line)
            mutableState.update {
                it.copy(phase = Phase.ONLINE, error = null, demo = demo, snapshot = null)
            }
            refreshFiles(app)
            appContext = app
            Notifier.reset()
            Platform.startBackground(app)
            startPolling()
        }
    }

    /**
     * Process environment the bridge reads. Android gives an app no writable temporary
     * directory by default, and SQLite inside the bridge needs one; the demo core fills its
     * account (orders, balance) only when asked to.
     */
    private fun prepareProcess(app: Context, demo: Boolean) {
        Platform.setEnv("TMPDIR", Platform.cacheRoot(app))
        if (demo) Platform.setEnv("MOON_SYNTH_CORE_STATE", "1")
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch(Dispatchers.Default) {
            var rev = -1L
            while (isActive) {
                try {
                    val json = JSONObject(Native.snapshot(rev))
                    if (json.optBoolean("ok", false) && json.optBoolean("changed", true)) {
                        val snapshot = parseSnapshot(json)
                        rev = snapshot.rev
                        mutableState.update {
                            if (it.phase == Phase.ONLINE) it.copy(snapshot = snapshot) else it
                        }
                        appContext?.let { context -> rememberMine(context, snapshot) }
                    }
                } catch (e: Throwable) {
                    // A malformed snapshot is skipped; the next poll asks again.
                }
                try {
                    // The home-screen widget, from the latest snapshot.
                    val context = appContext
                    val latest = state.value.snapshot
                    if (context != null && latest != null) WidgetFeed.step(context, latest)
                } catch (e: Throwable) {
                    // The widget keeps its last picture; the next poll tries again.
                }
                try {
                    // What the bridge decided to notify about since the last poll.
                    appContext?.let { Notifier.poll(it) }
                } catch (e: Throwable) {
                    // Nothing taken from the bridge is lost: the next poll asks again.
                }
                delay(if (uiVisible) 400L else 2000L)
            }
        }
    }

    /**
     * Orders placed from this phone, as `core:uid`, newest last: the bridge marks them while it
     * runs, and the app keeps them across restarts.
     */
    private val mineFlow = MutableStateFlow<Set<String>>(emptySet())
    val mine: StateFlow<Set<String>> = mineFlow.asStateFlow()
    private const val MINE_MAX = 500

    private fun loadMine(context: Context) {
        val text = prefsOf(context, PREFS).getString(PREF_MINE, null).orEmpty()
        mineFlow.value = LinkedHashSet(text.split('\n').filter { it.contains(':') })
    }

    private fun rememberMine(context: Context, snapshot: Snapshot) {
        val fresh = snapshot.orders.filter { it.mine }.map { "${it.core}:${it.uid}" }
        if (fresh.all { it in mineFlow.value }) return
        val next = LinkedHashSet(mineFlow.value).apply { addAll(fresh) }
        while (next.size > MINE_MAX) next.remove(next.first())
        mineFlow.value = next
        prefsOf(context, PREFS).edit()
            .putString(PREF_MINE, next.joinToString("\n")).apply()
    }

    /** Whether an order or position was placed from this phone. */
    fun isMine(order: Order): Boolean = order.mine || "${order.core}:${order.uid}" in mineFlow.value

    /** Stops the engine and every core connection. */
    /**
     * The «Закрыть» button: the connections close and the app with them, leaving nothing in
     * the background. The next launch starts afresh — locked if a password is set, then connected.
     */
    fun closeApp(context: Context) {
        Session.log("Приложение закрыто кнопкой «Закрыть»")
        Platform.finishScreen(context)
        disconnect(context, exit = true)
    }

    fun disconnect(context: Context, exit: Boolean = false) {
        val app = Platform.app(context)
        WidgetFeed.disconnected(app)
        // Stays disconnected until the user connects again.
        autoConnect = false
        poller?.cancel()
        poller = null
        mutableState.update {
            it.copy(phase = Phase.SETUP, snapshot = null, error = null, demo = false)
        }
        Platform.stopBackground(app)
        scope.launch(ioDispatcher) {
            try {
                Native.close()
            } catch (e: Throwable) {
                // Nothing to close.
            }
            // Disconnected from the notification with no screen open: leave no process behind,
            // which is also what hands the engine's memory back at once.
            if (!uiVisible || exit) {
                Platform.exitProcess()
            }
        }
    }

    /** Lines for the log from before the logger is up: written once the connection opens. */
    private val waitingLines = MutableStateFlow<List<String>>(emptyList())

    /** A line for the log that may come before the first connection of the process. */
    fun logLater(text: String) {
        waitingLines.update { it + text }
    }

    /** The app left the screen or came back to it: a line in the log, with the memory it holds. */
    fun screenLine(visible: Boolean, memoryMb: Int?) {
        val memory = memoryMb?.let { ", память $it МБ" }.orEmpty()
        log(if (visible) "Приложение снова на экране$memory" else "Приложение свёрнуто$memory")
    }

    /** The installed app's version name, `1.10b`. */
    fun appVersion(context: Context): String = Platform.appVersion(context)

    /** A line from the app into its log file (after the first connection in this process). */
    fun log(text: String) {
        try {
            Native.tool(JSONObject().put("op", "app_log").put("text", text).toString())
        } catch (e: Throwable) {
            // No logger yet: the line is simply not kept.
        }
    }

    /** Write log files or not, as set in Settings → Журнал. */
    fun setLogFiles(context: Context, on: Boolean) {
        AppPrefs.setFlag(context, "log_files", on)
        try {
            Native.tool(JSONObject().put("op", "log_files").put("on", on).toString())
        } catch (e: Throwable) {
        }
    }

    /**
     * The log lines of the last [periodMs] into one text file in the app's cache; the file, or
     * why there is none.
     */
    suspend fun exportLogs(context: Context, periodMs: Long): Result<String> = withContext(ioDispatcher) {
        val app = Platform.app(context)
        val now = nowMs()
        val stamp = DateFormat("yyyy-MM-dd_HH-mm").format(now)
        val folder = Platform.cacheRoot(app) + "/export"
        Platform.mkdirs(folder)
        val out = "$folder/MoonMobile-log-$stamp.txt"
        val reply = try {
            parseReply(
                Native.tool(
                    JSONObject()
                        .put("op", "export_logs")
                        .put("data_dir", dataDir(app))
                        .put("from_ms", now - periodMs)
                        .put("to_ms", now + 60_000)
                        .put("path", out)
                        .toString(),
                ),
            )
        } catch (e: Throwable) {
            errorReply("internal", e.toString())
        }
        when {
            !reply.ok -> Result.failure(IllegalStateException(describeError(reply)))
            reply.json.optInt("lines", 0) == 0 -> Result.failure(IllegalStateException("За этот период в журнале ничего нет"))
            else -> Result.success(out)
        }
    }

    /** Sends one command and waits for the engine's answer. */
    suspend fun call(request: JSONObject): Reply = withContext(ioDispatcher) {
        try {
            parseReply(Native.call(request.toString()))
        } catch (e: Throwable) {
            errorReply("internal", e.toString())
        }
    }

    /** The user-facing text for a failed reply. */
    fun describeError(reply: Reply): String = when (reply.code) {
        "wrong_password" -> "Неверный пароль шифрования"
        "no_password_slot" ->
            "В этом servers.enc нет пароля шифрования. Задайте его в терминале на ПК " +
                "(Настройки → Безопасность → Шифрование файла подключений), сохраните и " +
                "скопируйте файл заново."
        "no_servers_file" -> "Сначала выберите файл servers.enc"
        "damaged" -> "Файл servers.enc повреждён или от более новой версии терминала"
        "offline" -> "Ядро не подключено — команда не отправлена"
        "not_found" -> "Не найдено: возможно, ордер уже закрыт"
        "unavailable" -> "Ядро не приняло команду"
        "bad_value" -> "Недопустимое значение — ${reply.message}"
        "bad_field" -> "У стратегии нет такого поля — ${reply.message}"
        "no_market_data" -> "Графики выключены при подключении"
        "spot_short" -> "На спотовом рынке шорт невозможен"
        "quote_mismatch" -> "Счёт ядра в другой валюте, чем этот рынок — ${reply.message}"
        "no_market_info" -> "Данные рынка ещё не пришли — попробуйте через пару секунд"
        "below_min" -> "Сумма меньше минимального ордера — ${reply.message}"
        "no_rate" -> "Курс валюты счёта ещё неизвестен — попробуйте через пару секунд"
        "above_max" -> "Выше максимального плеча — ${reply.message}"
        "timeout" -> "Нет ответа от движка"
        "closed" -> "Соединение закрыто"
        "already_open" -> "Уже подключено"
        else -> if (reply.message.isNotEmpty()) "Ошибка: ${reply.message}" else "Ошибка: ${reply.code}"
    }
}
