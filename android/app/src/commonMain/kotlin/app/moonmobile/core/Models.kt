package app.moonmobile.core

import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject

data class RunState(
    val online: Boolean,
    val started: Boolean?,
    val trading: Boolean?,
    val tradingConfirmed: Boolean,
    val autoDetect: Boolean?,
)

data class Balance(
    val currency: String,
    val total: Double,
    val free: Double,
    val pnl: Double,
    /** Really free, in [currency]: not held by orders or positions. */
    val available: Double = 0.0,
    /** Each coin's free balance, on a coin-margined (quarterly) account. */
    val coins: Map<String, Double> = emptyMap(),
)

/** How a core's machine and connections are doing, as the core reports it. */
data class Health(
    /** Phone to core, one way, as Moonbot prints its ping. */
    val pingMs: Int?,
    /** The core's order requests to the exchange. */
    val exchangeMs: Int?,
    /** Load of the core's whole machine, percent. */
    val cpuPct: Int?,
    val memMb: Int?,
    /** Free physical memory on the core's machine, MB. */
    val freeMemMb: Int?,
    /** Requests the exchange still allows (HyperLiquid only). */
    val apiQuota: Long?,
)

/** Something a core reported that may be worth a notification. */
data class CoreEvent(
    val seq: Long,
    val core: Long,
    /** `api_limit`. */
    val kind: String,
    val text: String,
    val timeMs: Long,
)

data class Core(
    val id: Long,
    val name: String,
    val group: String,
    val active: Boolean,
    /** `#rrggbb`. */
    val color: String,
    /** The core's own switch, apart from its group's. */
    val enabled: Boolean,
    /** connecting, stage, ready, failed, disconnected or inactive. */
    val status: String,
    val statusText: String,
    val exchange: String,
    /** The exchange as one word (`binance`), for its logo. */
    val brand: String,
    /** The address of the core's machine: cores sharing it share a server. Empty when unknown. */
    val host: String = "",
    /** `spot`, `futures` or `quarterly`; empty while unknown. */
    val kind: String,
    val base: String,
    val run: RunState,
    val orders: Int,
    val positions: Int,
    val strategiesTotal: Int,
    val strategiesChecked: Int,
    val balance: Balance?,
    val health: Health,
    /**
     * "Следить за ордерами этого бота": `on` or `off` with a watcher address set, `none` without
     * one, empty while unknown.
     */
    val control: String = "",
    /**
     * The core's trade history still coming into the phone's report: trades arrived so far and
     * percent done; `null` when nothing is being caught up.
     */
    val reportSync: Pair<Long, Int?>? = null,
)

data class Order(
    val core: Long,
    /** Decimal string: order ids use the full unsigned 64-bit range. */
    val uid: String,
    val market: String,
    val coin: String,
    val quote: String,
    val short: Boolean,
    val size: Double,
    val buyPrice: Double,
    val sellPrice: Double,
    val price: Double,
    val pnlPct: Double?,
    val status: String,
    val pending: Boolean,
    val filled: Boolean,
    val panicSell: Boolean,
    val emulator: Boolean,
    val strategy: String,
    val strategyId: String,
    val stopLoss: Double?,
    val takeProfit: Double?,
    /** Placed from this phone, as the bridge saw it this session. */
    val mine: Boolean = false,
    /** Placed by hand from a terminal (this app or MoonTerminal), not by a strategy. */
    val manual: Boolean = false,
    /** A futures position's liquidation price; null on spot, while flat or not reported. */
    val liqPrice: Double? = null,
    /** The market's mark price, futures only. */
    val markPrice: Double? = null,
)

data class Detect(
    val core: Long,
    val market: String,
    val timeMs: Double,
    val short: Boolean,
    val alert: Boolean,
    val msg: String,
    val strategy: String,
    /** Grows by one per detect on its core. */
    val seq: Long = 0,
    /** The sound the detect asks for (`default` for an alert without one); null for none. */
    val sound: String? = null,
)

data class Strategy(
    val core: Long,
    val id: String,
    val name: String,
    val kind: String,
    val folder: String,
    val checked: Boolean,
    val short: Boolean,
)

/**
 * Every connected account in USDT. [total] is the exchange's wallets, as «Балансы бирж» shows them;
 * [free] and the counts are the cores' own figures, cores on one exchange account counted once.
 */
data class Total(
    val total: Double?,
    val free: Double?,
    val counted: Int,
    val stale: Int,
    val excluded: Int,
)

/** One field of a strategy as its editor shows it. */
data class StrategyField(
    val name: String,
    /** checkbox, list, color or text. */
    val control: String,
    val typeName: String,
    val value: String,
    /** What an edit still on its way asks for, when it differs from [value]. */
    val pending: String?,
    val options: List<String>,
)

data class StrategySection(
    val title: String,
    val fields: List<StrategyField>,
)

data class StrategyDetails(
    val core: Long,
    val id: String,
    val name: String,
    val kind: String,
    val folder: String,
    val checked: Boolean,
    /** pending, timed_out or null. */
    val edit: String?,
    val editable: Boolean,
    val sections: List<StrategySection>,
)

/**
 * A detect that is still a card in the detect feed: it lives [ttlMs] from [bornMs], and the
 * screen counts that down by its own clock.
 */
data class Card(
    val core: Long,
    val coreName: String,
    /** `#rrggbb`, the core's colour from its settings. */
    val coreColor: String,
    val seq: Long,
    val market: String,
    val coin: String,
    val bornMs: Long,
    val ttlMs: Long,
    /** The detect kind's short code (`PMP`, `DRP`, ...) and its colour. */
    val badge: String,
    val badgeColor: String,
    val short: Boolean,
    val alert: Boolean,
    val delta1h: Double?,
    val delta24h: Double?,
    /** The market's last day as closes, oldest first; empty when there was no history. */
    val line: List<Float>,
    /** The strategy that fired and the detect's own line, as Moonbot logs it. */
    val strategy: String = "",
    val msg: String = "",
    /** How the price moved since the detect, percent; null until a price is known. */
    val sincePct: Double? = null,
)

data class Snapshot(
    val rev: Long,
    val marketData: String,
    val total: Total,
    val cores: List<Core>,
    val orders: List<Order>,
    val detects: List<Detect>,
    val cards: List<Card>,
    val strategies: List<Strategy>,
    val events: List<CoreEvent>,
    /** The core being added by its key, while it is checked and after. */
    val adding: Adding? = null,
    /** The newest closed trade of the cores, from the report. */
    val lastDeal: LastDeal? = null,
)

/**
 * The newest closed trade: its coin and chart market, and the result in USDT when the report
 * could value it, in the trade's own currency otherwise.
 */
data class LastDeal(
    val core: Long,
    val coin: String,
    val market: String,
    val short: Boolean,
    val closeMs: Long,
    val profitUsdt: Double?,
    val profitNative: Double?,
    val currency: String,
    val profitPct: Double?,
)

/**
 * Adding a core by its key: [state] is `checking`, `saved`, `not_saved` (connected, the file not
 * written) or `failed` (did not answer; taken out again).
 */
data class Adding(val core: Long, val name: String, val state: String, val text: String)

/** The reply to a command or to `open`. */
data class Reply(
    val ok: Boolean,
    /** Stable error code when not [ok], empty otherwise. */
    val code: String,
    val message: String,
    val json: JSONObject,
)

fun parseReply(text: String): Reply {
    val json = try {
        JSONObject(text)
    } catch (e: Exception) {
        return Reply(false, "internal", "bad reply: $text", JSONObject())
    }
    return Reply(
        ok = json.optBoolean("ok", false),
        code = json.optString("error", ""),
        message = json.optString("message", ""),
        json = json,
    )
}

fun errorReply(code: String, message: String): Reply =
    Reply(false, code, message, JSONObject())

fun parseSnapshot(json: JSONObject): Snapshot = Snapshot(
    rev = json.optLong("rev", 0L),
    marketData = json.optString("market_data", "none"),
    total = parseTotal(json.optJSONObject("total")),
    cores = json.optJSONArray("cores").mapObjects(::parseCore),
    orders = json.optJSONArray("orders").mapObjects(::parseOrder),
    detects = json.optJSONArray("detects").mapObjects(::parseDetect),
    cards = json.optJSONArray("cards").mapObjects(::parseCard),
    strategies = json.optJSONArray("strategies").mapObjects(::parseStrategy),
    events = json.optJSONArray("events").mapObjects { o ->
        CoreEvent(
            seq = o.optLong("seq"),
            core = o.optLong("core"),
            kind = o.optString("kind"),
            text = o.optString("text"),
            timeMs = o.optLong("time_ms"),
        )
    },
    adding = json.optJSONObject("adding")?.let { o ->
        Adding(o.optLong("core"), o.optString("name"), o.optString("state"), o.optString("text"))
    },
    lastDeal = json.optJSONObject("last_deal")?.let { o ->
        fun num(key: String): Double? = if (o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }
        LastDeal(
            core = o.optLong("core"),
            coin = o.optString("coin"),
            market = o.optString("market"),
            short = o.optBoolean("short"),
            closeMs = o.optLong("close_ms"),
            profitUsdt = num("profit_usdt"),
            profitNative = num("profit_native"),
            currency = o.optString("currency"),
            profitPct = num("profit_pct"),
        )
    },
)

private fun parseCard(o: JSONObject): Card {
    val line = o.optJSONArray("line")
    return Card(
        core = o.optLong("core"),
        coreName = o.optString("core_name"),
        coreColor = o.optString("core_color"),
        seq = o.optLong("seq"),
        market = o.optString("market"),
        coin = o.optString("coin"),
        bornMs = o.optLong("born_ms"),
        ttlMs = o.optLong("ttl_ms"),
        badge = o.optString("badge"),
        badgeColor = o.optString("badge_color"),
        short = o.optBoolean("short", false),
        alert = o.optBoolean("alert", false),
        delta1h = o.doubleOrNull("delta_1h"),
        delta24h = o.doubleOrNull("delta_24h"),
        line = if (line == null) emptyList() else List(line.length()) { line.optDouble(it, 0.0).toFloat() },
        strategy = o.optString("strategy"),
        msg = o.optString("msg"),
        sincePct = o.doubleOrNull("since_pct"),
    )
}

private fun parseTotal(o: JSONObject?): Total {
    if (o == null) return Total(null, null, 0, 0, 0)
    return Total(
        total = o.doubleOrNull("total"),
        free = o.doubleOrNull("free"),
        counted = o.optInt("counted"),
        stale = o.optInt("stale"),
        excluded = o.optInt("excluded"),
    )
}

/** The `strategy` object of a `strategy_get` reply. */
fun parseStrategyDetails(o: JSONObject): StrategyDetails = StrategyDetails(
    core = o.optLong("core"),
    id = o.optString("id"),
    name = o.optString("name"),
    kind = o.optString("kind"),
    folder = o.optString("folder"),
    checked = o.optBoolean("checked", false),
    edit = o.stringOrNull("edit"),
    editable = o.optBoolean("editable", false),
    sections = o.optJSONArray("sections").mapObjects { section ->
        StrategySection(
            title = section.optString("title"),
            fields = section.optJSONArray("fields").mapObjects { field ->
                val options = field.optJSONArray("options")
                StrategyField(
                    name = field.optString("name"),
                    control = field.optString("control", "text"),
                    typeName = field.optString("type_name"),
                    value = field.optString("value"),
                    pending = field.stringOrNull("pending"),
                    options = if (options == null) {
                        emptyList()
                    } else {
                        List(options.length()) { options.optString(it) }
                    },
                )
            },
        )
    },
)

private fun parseCore(o: JSONObject): Core {
    val run = o.optJSONObject("run") ?: JSONObject()
    val balance = o.optJSONObject("balance")
    val health = o.optJSONObject("health") ?: JSONObject()
    return Core(
        id = o.optLong("id"),
        name = o.optString("name"),
        group = o.optString("group"),
        active = o.optBoolean("active", true),
        color = o.optString("color"),
        enabled = o.optBoolean("enabled", true),
        status = o.optString("status"),
        statusText = o.optString("status_text"),
        exchange = o.optString("exchange"),
        brand = o.optString("brand"),
        host = o.optString("host"),
        kind = o.optString("kind"),
        base = o.optString("base"),
        run = RunState(
            online = run.optBoolean("online", false),
            started = run.boolOrNull("started"),
            trading = run.boolOrNull("trading"),
            tradingConfirmed = run.optBoolean("trading_confirmed", false),
            autoDetect = run.boolOrNull("auto_detect"),
        ),
        orders = o.optInt("orders"),
        positions = o.optInt("positions"),
        strategiesTotal = o.optInt("strategies_total"),
        strategiesChecked = o.optInt("strategies_checked"),
        balance = balance?.let {
            Balance(
                currency = it.optString("currency"),
                total = it.optDouble("total", 0.0),
                free = it.optDouble("free", 0.0),
                pnl = it.optDouble("pnl", 0.0),
                available = it.optDouble("available", 0.0),
                coins = it.optJSONArray("coins")?.let { list ->
                    (0 until list.length()).mapNotNull { i -> list.optJSONObject(i) }
                        .associate { c -> c.optString("coin") to c.optDouble("free", 0.0) }
                }.orEmpty(),
            )
        },
        health = Health(
            pingMs = health.intOrNull("ping_ms"),
            exchangeMs = health.intOrNull("exchange_ms"),
            cpuPct = health.intOrNull("cpu_pct"),
            memMb = health.intOrNull("mem_mb"),
            freeMemMb = health.intOrNull("free_mem_mb"),
            apiQuota = if (!health.has("api_quota") || health.isNull("api_quota")) null else health.optLong("api_quota"),
        ),
        control = o.optString("control"),
        reportSync = o.optJSONObject("report_sync")?.let { r ->
            r.optLong("rows") to (if (r.isNull("percent")) null else r.optInt("percent"))
        },
    )
}

private fun parseOrder(o: JSONObject): Order = Order(
    core = o.optLong("core"),
    uid = o.optString("uid"),
    market = o.optString("market"),
    coin = o.optString("coin"),
    quote = o.optString("quote"),
    short = o.optBoolean("short", false),
    size = o.optDouble("size", 0.0),
    buyPrice = o.optDouble("buy_price", 0.0),
    sellPrice = o.optDouble("sell_price", 0.0),
    price = o.optDouble("price", 0.0),
    pnlPct = o.doubleOrNull("pnl_pct"),
    status = o.optString("status"),
    pending = o.optBoolean("pending", false),
    filled = o.optBoolean("filled", false),
    panicSell = o.optBoolean("panic_sell", false),
    emulator = o.optBoolean("emulator", false),
    strategy = o.optString("strategy"),
    strategyId = o.optString("strategy_id"),
    stopLoss = o.doubleOrNull("stop_loss"),
    takeProfit = o.doubleOrNull("take_profit"),
    mine = o.optBoolean("mine", false),
    manual = o.optBoolean("manual", false),
    liqPrice = o.doubleOrNull("liq_price")?.takeIf { it > 0.0 },
    markPrice = o.doubleOrNull("mark_price")?.takeIf { it > 0.0 },
)

private fun parseDetect(o: JSONObject): Detect = Detect(
    core = o.optLong("core"),
    market = o.optString("market"),
    timeMs = o.optDouble("time_ms", 0.0),
    short = o.optBoolean("short", false),
    alert = o.optBoolean("alert", false),
    msg = o.optString("msg"),
    strategy = o.optString("strategy"),
    seq = o.optLong("seq"),
    sound = if (o.isNull("sound")) null else o.optString("sound").takeIf { it.isNotEmpty() },
)

private fun parseStrategy(o: JSONObject): Strategy = Strategy(
    core = o.optLong("core"),
    id = o.optString("id"),
    name = o.optString("name"),
    kind = o.optString("kind"),
    folder = o.optString("folder"),
    checked = o.optBoolean("checked", false),
    short = o.optBoolean("short", false),
)

private fun <T> JSONArray?.mapObjects(parse: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val item = optJSONObject(i) ?: continue
        out.add(parse(item))
    }
    return out
}

private fun JSONObject.boolOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

private fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

private fun JSONObject.doubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isNaN()) null else value
}
