package app.moonmobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moonmobile.core.AppPrefs
import app.moonmobile.core.Core
import app.moonmobile.core.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject
import app.moonmobile.text.fmt
import app.moonmobile.text.DateFormat
import app.moonmobile.text.nowMs
import app.moonmobile.text.localDayStart

/** One closed trade of the report. */
internal data class ReportRow(
    val core: Long,
    val coin: String,
    val market: String,
    val strategy: String,
    val short: Boolean,
    val leverage: Double?,
    val openMs: Long?,
    val closeMs: Long?,
    val buy: Double?,
    val sell: Double?,
    val pct: Double?,
    val usdt: Double?,
    val native: Double?,
    val currency: String,
    val volume: Double?,
    val reason: String,
)

/** The figures over the whole period. */
internal data class ReportTotals(
    val trades: Long,
    val wins: Long,
    val losses: Long,
    val winrate: Double?,
    val profit: Double?,
    val pctAvg: Double?,
    val volume: Double?,
    val maxWin: Double?,
    val maxLoss: Double?,
    val unvalued: Long,
)

/** Periods the report is read for, by close time. */
private enum class ReportPeriod(val title: String) {
    TODAY("Сегодня"),
    DAY("24 ч"),
    WEEK("7 дней"),
    MONTH("30 дней"),
    YEAR("Год"),
}

private fun periodStart(period: ReportPeriod, now: Long): Long = when (period) {
    ReportPeriod.TODAY -> localDayStart(now)
    ReportPeriod.DAY -> now - 86_400_000L
    ReportPeriod.WEEK -> now - 7 * 86_400_000L
    ReportPeriod.MONTH -> now - 30 * 86_400_000L
    ReportPeriod.YEAR -> now - 365 * 86_400_000L
}

private fun JSONObject.num(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONArray.num(at: Int): Double? =
    if (isNull(at)) null else optDouble(at).takeIf { !it.isNaN() }

internal fun parseRows(json: JSONObject): List<ReportRow> {
    val columns = json.optJSONArray("columns") ?: return emptyList()
    val index = (0 until columns.length()).associateBy { columns.optString(it) }
    val rows = json.optJSONArray("rows") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { r ->
        val row = rows.optJSONArray(r) ?: return@mapNotNull null
        fun text(name: String) = index[name]?.let { if (row.isNull(it)) "" else row.optString(it) }.orEmpty()
        fun number(name: String) = index[name]?.let { row.num(it) }
        ReportRow(
            core = text("core").toLongOrNull() ?: 0L,
            coin = text("coin"),
            market = text("market"),
            strategy = text("strategy_name"),
            short = text("side") == "short",
            leverage = number("lev"),
            openMs = number("open_utc_ms")?.toLong(),
            closeMs = number("close_utc_ms")?.toLong(),
            buy = number("buyprice"),
            sell = number("sellprice"),
            pct = number("profit_pct"),
            usdt = number("profit_usdt"),
            native = number("profit_native"),
            currency = text("currency"),
            volume = number("volume_usdt"),
            reason = text("sellreason").ifEmpty { text("reason") },
        )
    }
}

private fun parseTotals(json: JSONObject?): ReportTotals? {
    json ?: return null
    return ReportTotals(
        trades = json.optLong("trades"),
        wins = json.optLong("wins"),
        losses = json.optLong("losses"),
        winrate = json.num("winrate_pct"),
        profit = json.num("profit_usdt_sum"),
        pctAvg = json.num("profit_pct_avg"),
        volume = json.num("volume_usdt"),
        maxWin = json.num("max_win_usdt"),
        maxLoss = json.num("max_loss_usdt"),
        unvalued = json.optLong("unvalued"),
    )
}

/** `+4.00$`, `-12.50$`, `+1.2K$`. */
internal fun dealMoney(value: Double): String {
    val sign = if (value < 0) "-" else "+"
    val abs = kotlin.math.abs(value)
    val text = when {
        abs >= 1_000_000 -> fmt("%.2fM", abs / 1_000_000)
        abs >= 10_000 -> fmt("%.1fK", abs / 1_000)
        else -> fmt("%.2f", abs)
    }
    return "$sign$text$"
}

/** A coin without its quote: `SHAZUSDT` → `SHAZ`. */
internal fun bareCoin(coin: String): String {
    val upper = coin.uppercase()
    for (quote in listOf("USDT", "USDC", "FDUSD", "BUSD", "USD")) {
        if (upper.length > quote.length && upper.endsWith(quote)) return coin.dropLast(quote.length)
    }
    return coin
}

private val timeFormat = DateFormat("dd.MM HH:mm")

/**
 * The cores' report as MoonTerminal keeps it: closed trades of the period, newest first, with the
 * period's totals. A tap on a trade opens its chart. Fills the chart's area.
 */
@Composable
internal fun ReportPanel(
    cores: List<Core>,
    current: Pair<Long, String>?,
    onPick: (Long, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    val byId = remember(cores) { cores.associateBy { it.id } }
    var period by remember {
        mutableStateOf(
            ReportPeriod.entries.firstOrNull { it.name == AppPrefs.text(context, "report_period", "") } ?: ReportPeriod.TODAY,
        )
    }
    // Every core, or the open chart's core alone.
    var chartCoreOnly by remember { mutableStateOf(AppPrefs.flag(context, "report_chart_core", false)) }
    var rows by remember { mutableStateOf<List<ReportRow>>(emptyList()) }
    var totals by remember { mutableStateOf<ReportTotals?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    // Narrowing as in MoonTerminal's report: part of a coin's name, and one strategy.
    var coinInput by remember { mutableStateOf("") }
    var coin by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("") }
    var strategies by remember { mutableStateOf<List<StrategyChoice>>(emptyList()) }
    var choosingStrategy by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    // The cores' emulator trades, which the report leaves out unless asked.
    var emulator by remember { mutableStateOf(AppPrefs.flag(context, "report_emulator", false)) }
    // What the replica says about an empty period: its newest trade, the month's count.
    var emptyHint by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(coinInput) {
        delay(600)
        coin = coinInput.trim()
    }

    fun request(offset: Int, limit: Int): JSONObject {
        val now = nowMs()
        val request = JSONObject()
            .put("op", "report")
            .put("from_ms", periodStart(period, now))
            .put("to_ms", now + 60_000)
            .put("offset", offset)
            .put("limit", limit)
            .put("coin", coin)
            .put("strategy", strategy)
            .put("emulator", emulator)
        val chartCore = current?.first
        if (chartCoreOnly && chartCore != null) request.put("cores", JSONArray().put(chartCore))
        return request
    }

    suspend fun read(offset: Int): Boolean {
        val reply = Session.call(request(offset, 200))
        if (!reply.ok) {
            error = Session.describeError(reply)
            return false
        }
        error = null
        val page = parseRows(reply.json)
        rows = if (offset == 0) page else rows + page
        totals = parseTotals(reply.json.optJSONObject("totals"))
        hasMore = reply.json.optBoolean("has_more", false)
        reply.json.optJSONArray("strategies")?.let { strategies = parseStrategies(it) }
        if (offset == 0) emptyHint = reply.json.optJSONObject("empty_hint")
        return true
    }

    LaunchedEffect(period, chartCoreOnly, reload, coin, strategy, emulator) {
        loading = true
        read(0)
        loading = false
        // New trades come in while the report is open.
        while (true) {
            delay(15_000)
            if (rows.size <= 200) read(0)
        }
    }

    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Palette.chartPanelHead.copy(alpha = 0.98f))
            .border(1.dp, Palette.chartEdge, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Отчёт", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Palette.chartStrong)
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (p in ReportPeriod.entries) {
                    Chip(p.title, selected = p == period) {
                        period = p
                        AppPrefs.setText(context, "report_period", p.name)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Chip(
                    if (chartCoreOnly) "Ядро графика" else "Все ядра",
                    selected = chartCoreOnly,
                ) {
                    chartCoreOnly = !chartCoreOnly
                    AppPrefs.setFlag(context, "report_chart_core", chartCoreOnly)
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.accent)
            }
            TextButton(onClick = onClose) { Text("✕", color = Palette.chartLabel) }
        }
        // Narrowing and export.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoinField(coinInput) { coinInput = it }
            Box {
                Chip(
                    if (strategy.isEmpty()) "Стратегия: все ▾" else "Стратегия: $strategy ▾",
                    selected = strategy.isNotEmpty(),
                ) { choosingStrategy = true }
                MoonMenu(expanded = choosingStrategy, onDismissRequest = { choosingStrategy = false }) {
                    DropdownMenuItem(
                        text = { Text("Все стратегии") },
                        onClick = {
                            strategy = ""
                            choosingStrategy = false
                        },
                    )
                    for (choice in strategies) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${choice.name.ifEmpty { "ручные" }} · ${choice.trades}" +
                                        (choice.profit?.let { " · ${dealMoney(it)}" } ?: ""),
                                )
                            },
                            onClick = {
                                strategy = choice.name
                                choosingStrategy = false
                            },
                        )
                    }
                }
            }
            Chip("Эмулятор", selected = emulator) {
                emulator = !emulator
                AppPrefs.setFlag(context, "report_emulator", emulator)
            }
            Spacer(Modifier.weight(1f))
            Chip("Выгрузить", selected = false) { exporting = true }
        }
        exportNote?.let {
            Text(it, color = Palette.chartLabel, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
        }
        // Trades made while the app was closed: each core sends what the phone has not seen yet
        // as soon as it connects.
        val syncing = cores.filter { it.reportSync != null }
        if (syncing.isNotEmpty()) {
            Text(
                "Догружаю сделки с ядер: " + syncing.joinToString(", ") { c ->
                    val (got, pct) = c.reportSync!!
                    "${c.name} — $got" + (pct?.let { " ($it%)" } ?: "")
                },
                color = Palette.amber,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        totals?.let { TotalsLine(it) }
        error?.let {
            Text(it, color = Palette.terminalRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (!loading && error == null && rows.isEmpty()) {
            Text(
                emptyText(period, emptyHint, byId),
                color = Palette.textDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(rows, key = { "${it.core}:${it.closeMs}:${it.openMs}:${it.coin}:${it.buy}" }) { row ->
                TradeRow(row, byId[row.core]) {
                    if (row.market.isNotEmpty()) {
                        onPick(row.core, row.market)
                        onClose()
                    }
                }
            }
            if (hasMore) {
                item {
                    TextButton(onClick = { scope.launch { read(rows.size) } }) {
                        Text("Показать ещё", color = Palette.accent)
                    }
                }
            }
        }
    }

    if (exporting) {
        ExportDialog(
            period = period.title,
            onClose = { exporting = false },
            request = { request(0, 0) },
            onNote = { exportNote = it },
        )
    }
}

/**
 * What an empty period says: that it is empty, since when the period runs, the newest trade the
 * phone holds whatever the period, the last 30 days' count, and the emulator's trades left out.
 */
private fun emptyText(period: ReportPeriod, hint: JSONObject?, byId: Map<Long, Core>): String {
    val now = nowMs()
    val clock = DateFormat("dd.MM HH:mm")
    val h = hint ?: JSONObject()
    fun long(key: String): Long? = if (!h.has(key) || h.isNull(key)) null else h.optLong(key)
    val lines = mutableListOf("За этот период закрытых сделок нет (с ${clock.format(periodStart(period, now))}).")
    val newest = long("newest_close_ms")?.takeIf { it > 0 }
    if (newest != null) {
        val coin = if (h.isNull("newest_coin")) null else h.optString("newest_coin").takeIf { it.isNotEmpty() }
        val core = long("newest_core")?.let { byId[it]?.name }
        val about = listOfNotNull(coin, core)
        lines += "Последняя сделка на телефоне: ${clock.format(newest)}" +
            (if (about.isEmpty()) "" else " — " + about.joinToString(" · ")) + "."
    }
    val month = long("month_trades")
    when {
        month != null && month > 0 -> lines += "За 30 дней на телефоне сделок: $month — выберите «7 дней» или «30 дней»."
        month == 0L && newest == null -> lines += "Сделок с ядер на телефоне пока нет: история приходит в фоне " +
            "после подключения, строка «Догружаю сделки» показывает ход."
    }
    val emulated = long("emulator_trades")
    if (emulated != null && emulated > 0) {
        lines += "В этом периоде сделок эмулятора: $emulated — включите «Эмулятор», чтобы их увидеть."
    }
    return lines.joinToString("\n")
}

/** A strategy of the period for the filter: its name, its trades and their profit. */
internal data class StrategyChoice(val name: String, val trades: Long, val profit: Double?)

private fun parseStrategies(rows: JSONArray): List<StrategyChoice> =
    (0 until rows.length()).mapNotNull { at ->
        val row = rows.optJSONArray(at) ?: return@mapNotNull null
        StrategyChoice(
            name = if (row.isNull(0)) "" else row.optString(0),
            trades = row.optLong(1),
            profit = row.num(2),
        )
    }

/** The coin search: part of a coin's name, `BTC`, `pepe`. */
@Composable
private fun CoinField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(20)) },
        singleLine = true,
        textStyle = TextStyle(color = Palette.chartStrong, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Palette.accent),
        modifier = Modifier
            .width(110.dp)
            .height(24.dp)
            .clip(shape)
            .background(Palette.chartButton)
            .border(1.dp, if (value.isNotEmpty()) Palette.accent else Palette.chartEdge, shape),
        decorationBox = { inner ->
            Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text("Монета", color = Palette.textDim, fontSize = 12.sp)
                inner()
            }
        },
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(shape)
            .background(if (selected) Palette.accent.copy(alpha = 0.22f) else Palette.chartButton)
            .border(1.dp, if (selected) Palette.accent else Palette.chartEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, color = if (selected) Palette.accent else Palette.chartLabel, maxLines = 1)
    }
}

@Composable
private fun TotalsLine(t: ReportTotals) {
    val profit = t.profit
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Figure("Сделок", t.trades.toString())
        Figure(
            "Профит",
            profit?.let { dealMoney(it) } ?: "—",
            when {
                profit == null -> Palette.chartStrong
                profit < 0 -> Palette.terminalRed
                else -> Palette.terminalGreen
            },
        )
        Figure("Плюс / минус", "${t.wins} / ${t.losses}")
        t.winrate?.let { Figure("Винрейт", fmt("%.0f%%", it)) }
        t.pctAvg?.let { Figure("Средний", fmt("%+.2f%%", it)) }
        t.volume?.let { Figure("Объём", "${plainVolume(it)}$") }
        t.maxWin?.let { Figure("Лучшая", dealMoney(it), Palette.terminalGreen) }
        t.maxLoss?.let { Figure("Худшая", dealMoney(it), Palette.terminalRed) }
        if (t.unvalued > 0) Figure("Без курса USDT", t.unvalued.toString(), Palette.amber)
    }
}

private fun plainVolume(value: Double): String = when {
    value >= 1_000_000 -> fmt("%.2fM", value / 1_000_000)
    value >= 1_000 -> fmt("%.1fK", value / 1_000)
    else -> fmt("%.0f", value)
}

@Composable
private fun Figure(title: String, value: String, color: androidx.compose.ui.graphics.Color = Palette.chartStrong) {
    Column {
        Text(title, fontSize = 10.sp, color = Palette.textDim, maxLines = 1)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color, maxLines = 1)
    }
}

@Composable
private fun TradeRow(row: ReportRow, core: Core?, onClick: () -> Unit) {
    val money = row.usdt
    val color = when {
        money != null -> if (money < 0) Palette.terminalRed else Palette.terminalGreen
        row.pct != null -> if (row.pct < 0) Palette.terminalRed else Palette.terminalGreen
        else -> Palette.chartLabel
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.chartCard)
            .clickable(enabled = row.market.isNotEmpty(), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val logo = core?.let { brandLogo(it.brand) }
        if (logo != null) {
            Image(
                painter = imagePainter(logo),
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(6.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bareCoin(row.coin),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Palette.chartStrong,
                    maxLines = 1,
                )
                Text(
                    if (row.short) "  Short" else "  Long",
                    fontSize = 11.sp,
                    color = if (row.short) Palette.terminalRed else Palette.terminalGreen,
                )
                row.leverage?.takeIf { it > 1.0 }?.let {
                    Text(" ${it.toInt()}x", fontSize = 11.sp, color = Palette.textDim)
                }
                Text(
                    "  " + (row.closeMs?.let { timeFormat.format(it) } ?: ""),
                    fontSize = 11.sp,
                    color = Palette.textDim,
                    maxLines = 1,
                )
            }
            Text(
                listOfNotNull(
                    core?.name,
                    row.strategy.ifEmpty { null },
                    row.reason.ifEmpty { null },
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = Palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                when {
                    money != null -> dealMoney(money)
                    row.native != null -> fmt("%+.4f %s", row.native, row.currency)
                    else -> "—"
                },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = color,
                maxLines = 1,
            )
            row.pct?.let {
                Text(
                    fmt("%+.2f%%", it),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}
