package app.moonmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import app.moonmobile.core.AppPrefs
import app.moonmobile.core.Notifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moonmobile.core.AppState
import app.moonmobile.core.Core
import app.moonmobile.core.Detect
import app.moonmobile.core.Order
import app.moonmobile.core.Reply
import app.moonmobile.core.Session
import app.moonmobile.core.Snapshot
import app.moonmobile.core.Strategy
import app.moonmobile.core.Total
import kotlinx.coroutines.launch
import app.moonmobile.json.JSONObject
import app.moonmobile.text.fmt

/** A command waiting for the user's "yes". */
internal data class Confirm(
    val title: String,
    val text: String,
    val action: String,
    val request: JSONObject,
)

private val tabs = listOf("Ядра", "Ордера", "Детекты", "Стратегии", "График")
private val tabGlyphs = listOf("◉", "≡", "⚑", "⚙", "∿")

/** The chart takes the whole screen in landscape instead of sitting under the tab bar. */
private const val CHART_TAB = 4

/** The connected app: its tabs over the engine's latest snapshot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: AppState) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The tab the chart was opened from, to return to.
    var tabBeforeChart by rememberSaveable { mutableIntStateOf(0) }
    var chartCore by rememberSaveable { mutableLongStateOf(-1L) }
    var chartMarket by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var askDisconnect by remember { mutableStateOf(false) }
    // Leaving with something of the phone's still on the exchange: "back" or "disconnect".
    var exitAsk by remember { mutableStateOf<String?>(null) }
    var exitStep by remember { mutableIntStateOf(1) }
    // The orders tab showing one core's orders, opened from that core's card.
    var ordersCore by rememberSaveable { mutableLongStateOf(-1L) }
    var manualOnly by remember { mutableStateOf(AppPrefs.flag(context, "orders_manual_only", false)) }
    var showUpdate by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot

    // Sends a command and reports what the engine answered.
    val send: (JSONObject) -> Unit = { request ->
        scope.launch {
            val reply = Session.call(request)
            snackbar.showSnackbar(describeResult(reply))
        }
    }

    if (showSettings) {
        SettingsScreen(snapshot = snapshot, onBack = { showSettings = false })
        return
    }

    val mineKeys by Session.mine.collectAsState()
    val warnings = remember(snapshot, mineKeys) { exitWarnings(snapshot) }
    // Back from a core's orders returns to the cores.
    BackHandler(enabled = tab == 1 && ordersCore >= 0) {
        tab = 0
        ordersCore = -1L
    }
    // Back out of the app warns first when the phone left orders, positions or a watcher off.
    BackHandler(enabled = tab != CHART_TAB && !(tab == 1 && ordersCore >= 0) && warnings.isNotEmpty()) {
        exitStep = 1
        exitAsk = "back"
    }

    val openChart: (Long, String) -> Unit = { core, market ->
        chartCore = core
        chartMarket = market
        if (tab != CHART_TAB) tabBeforeChart = tab
        tab = CHART_TAB
    }

    if (tab == CHART_TAB) {
        ChartScreen(
            state = state,
            core = chartCore,
            market = chartMarket,
            snackbar = snackbar,
            onPick = { core, market ->
                chartCore = core
                chartMarket = market
            },
            onBack = { tab = tabBeforeChart },
        )
    } else Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MoonMobile", style = MaterialTheme.typography.titleMedium)
                        Text(
                            headerLine(state, snapshot),
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.textDim,
                        )
                    }
                },
                actions = {
                    UpdateButton { showUpdate = true }
                    if (tab == 1) {
                        // «✋ Ручные»: only what was placed by hand from a terminal — this app or
                        // MoonTerminal; as the market search's «Ликвидность».
                        Toggle("✋ Ручные", manualOnly) {
                            manualOnly = !manualOnly
                            AppPrefs.setFlag(context, "orders_manual_only", manualOnly)
                        }
                    }
                    TextButton(onClick = { showSettings = true }) {
                        GearIcon(size = 24.dp)
                    }
                    TextButton(onClick = {
                        if (warnings.isEmpty()) {
                            askDisconnect = true
                        } else {
                            exitStep = 1
                            exitAsk = "disconnect"
                        }
                    }) { Text("Закрыть") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Palette.surface) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            if (index == CHART_TAB) tabBeforeChart = tab
                            tab = index
                            ordersCore = -1L
                        },
                        icon = { Text(tabGlyphs[index], style = MaterialTheme.typography.titleMedium) },
                        label = { Text(tabLabel(index, title, snapshot)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Palette.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (snapshot == null) {
                Text(
                    "Подключаюсь к ядрам…",
                    modifier = Modifier.padding(16.dp),
                    color = Palette.textDim,
                )
            } else {
                when (tab) {
                    0 -> CoresTab(
                        snapshot,
                        snackbar = snackbar,
                        hidden = state.balanceHidden,
                        onToggleHidden = { Session.setBalanceHidden(context, !state.balanceHidden) },
                        onConfirm = { confirm = it },
                        onSend = send,
                        onOrders = { core ->
                            ordersCore = core
                            tab = 1
                        },
                    )
                    1 -> OrdersTab(
                        snapshot,
                        manualOnly = manualOnly,
                        filterCore = ordersCore.takeIf { it >= 0 },
                        onClearFilter = { ordersCore = -1L },
                        onConfirm = { confirm = it },
                        onChart = openChart,
                    )
                    2 -> DetectsTab(snapshot, onChart = openChart)
                    else -> StrategiesTab(snapshot, onSend = send)
                }
            }
        }
    }

    val pending = confirm
    if (pending != null) {
        MoonDialog(
            onDismissRequest = { confirm = null },
            title = { Text(pending.title) },
            text = { Text(pending.text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    send(pending.request)
                }) { Text(pending.action) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Назад") }
            },
        )
    }

    if (showUpdate) {
        UpdateDialog { showUpdate = false }
    }

    exitAsk?.let { mode ->
        if (exitStep == 1) {
            MoonDialog(
                onDismissRequest = { exitAsk = null },
                title = { Text("Выключить терминал?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (line in warnings) Text(line, color = Palette.amber, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Соединения с ядрами закроются. Ядра продолжат торговать как обычно.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textDim,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        exitAsk = null
                        Session.closeApp(context)
                    }) { Text("Да") }
                },
                dismissButton = { TextButton(onClick = { exitStep = 2 }) { Text("Нет") } },
            )
        } else {
            MoonDialog(
                onDismissRequest = { exitAsk = null },
                title = { Text("Терминал остаётся включён") },
                text = {
                    Text(
                        "Соединения с ядрами сохранятся: приложение будет работать в фоне и присылать " +
                            "уведомления. Закрыть приложение или остаться?",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        exitAsk = null
                        closeScreen(context)
                    }) { Text("Закрыть приложение") }
                },
                dismissButton = { TextButton(onClick = { exitAsk = null }) { Text("Остаться") } },
            )
        }
    }

    if (askDisconnect) {
        MoonDialog(
            onDismissRequest = { askDisconnect = false },
            title = { Text("Закрыть приложение?") },
            text = {
                Text(
                    "Соединения с ядрами закроются, приложение закроется, в фоне ничего не останется. " +
                        "Ядра продолжат торговать как обычно, а сделки этого времени придут в отчёт при " +
                        "следующем запуске.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDisconnect = false
                    Session.closeApp(context)
                }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(onClick = { askDisconnect = false }) { Text("Назад") }
            },
        )
    }
}

/** What the phone leaves behind on leaving: its own orders and positions, a watcher switched off. */
private fun exitWarnings(snapshot: Snapshot?): List<String> {
    if (snapshot == null) return emptyList()
    val mine = snapshot.orders.filter { Session.isMine(it) }
    val names = snapshot.cores.associate { it.id to it.name }
    return buildList {
        val orders = mine.filter { !it.filled }
        val positions = mine.filter { it.filled }
        if (orders.isNotEmpty()) add("У вас остались ордера: ${orders.size}")
        if (positions.isNotEmpty()) add("У вас остались позиции: ${positions.size}")
        val off = snapshot.cores.filter { it.control == "off" }
        if (off.isNotEmpty()) {
            add("У вас выключен контроль слежуном: ${off.joinToString(", ") { names[it.id] ?: it.name }}")
        }
    }
}

private fun headerLine(state: AppState, snapshot: Snapshot?): String {
    if (snapshot == null) return "подключение…"
    val ready = snapshot.cores.count { it.status == "ready" }
    val active = snapshot.cores.count { it.active }
    val demo = if (state.demo) "демо · " else ""
    return "${demo}на связи $ready из $active"
}

private fun tabLabel(index: Int, title: String, snapshot: Snapshot?): String {
    if (snapshot == null) return title
    return when (index) {
        1 -> if (snapshot.orders.isEmpty()) title else "$title ${snapshot.orders.size}"
        else -> title
    }
}

private fun describeResult(reply: Reply): String {
    if (!reply.ok) return Session.describeError(reply)
    return when (reply.json.optString("result")) {
        "already" -> "Уже в этом состоянии"
        "queued" -> "Переподключаю…"
        else -> {
            val count = reply.json.optInt("count", -1)
            if (count >= 0) "Отправлено: снять бай-ордеров — $count" else "Команда отправлена"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cores
// ---------------------------------------------------------------------------------------------

@Composable
private fun CoresTab(
    snapshot: Snapshot,
    snackbar: SnackbarHostState,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onConfirm: (Confirm) -> Unit,
    onSend: (JSONObject) -> Unit,
    onOrders: (Long) -> Unit,
) {
    val context = platformContext()
    // Grouped by server or by exchange; which, and which groups are open, survive a restart.
    var byServer by remember { mutableStateOf(AppPrefs.text(context, "cores_group", "servers") == "servers") }
    var openServers by remember { mutableStateOf(AppPrefs.words(context, "open_servers")) }
    var openExchanges by remember { mutableStateOf(AppPrefs.words(context, "open_exchanges")) }
    val serverNames by AppPrefs.serverNames.collectAsState()
    var renaming by remember { mutableStateOf<String?>(null) }
    // Pings change on screen every 5 seconds, not with every snapshot.
    val latest by rememberUpdatedState(snapshot)
    var pings by remember { mutableStateOf(pingsOf(snapshot)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PING_EVERY_MS)
            pings = pingsOf(latest)
        }
    }

    val groups: List<CoreGroup> = remember(snapshot.cores, byServer, serverNames) {
        if (byServer) {
            snapshot.cores.groupBy { Notifier.serverKey(it) }.map { (key, cores) ->
                val first = cores.first()
                CoreGroup(
                    key = key,
                    title = Notifier.serverTitle(first, serverNames),
                    subtitle = first.host.takeIf { serverNames.containsKey(it) }.orEmpty(),
                    brand = "",
                    cores = cores.sortedBy { it.name.lowercase() },
                )
            }.sortedBy { it.title.lowercase() }
        } else {
            snapshot.cores.groupBy { it.brand }.map { (brand, cores) ->
                CoreGroup(
                    key = brand.ifEmpty { "-" },
                    title = exchangeTitle(brand),
                    subtitle = "",
                    brand = brand,
                    cores = cores.sortedBy { it.name.lowercase() },
                )
            }.sortedWith(compareBy({ it.brand.isEmpty() }, { -it.cores.size }, { it.title }))
        }
    }
    var addingCore by remember { mutableStateOf(false) }
    if (addingCore) AddCoreDialog(snapshot) { addingCore = false }
    val opened = if (byServer) openServers else openExchanges
    val toggle: (String) -> Unit = { key ->
        if (byServer) {
            openServers = if (key in openServers) openServers - key else openServers + key
            AppPrefs.setWords(context, "open_servers", openServers)
        } else {
            openExchanges = if (key in openExchanges) openExchanges - key else openExchanges + key
            AppPrefs.setWords(context, "open_exchanges", openExchanges)
        }
    }

    ListGrid(contentPadding = PaddingValues(12.dp), spacing = 10.dp) { columns ->
        wide(key = "total") { TotalCard(snapshot.total, hidden, onToggleHidden) }
        wide(key = "bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HaltCard(
                    snapshot,
                    snackbar,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                GroupSwitch(
                    byServer = byServer,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    byServer = !byServer
                    AppPrefs.setText(context, "cores_group", if (byServer) "servers" else "exchanges")
                }
            }
        }
        if (snapshot.cores.isEmpty()) {
            wide(key = "empty") {
                Column {
                    EmptyNote("В списке пока нет ни одного ядра. Добавьте ядро по его ключу из MoonBot.")
                    Button(onClick = { addingCore = true }) { Text("Добавить ядро") }
                }
            }
        }
        for (group in groups) {
            val open = group.key in opened
            val key = "group:${if (byServer) "s" else "e"}:${group.key}"
            val header: @Composable () -> Unit = {
                GroupHeader(
                    group = group,
                    pings = pings,
                    byServer = byServer,
                    hidden = hidden,
                    open = open,
                    onToggle = { toggle(group.key) },
                    onRename = if (byServer && group.cores.first().host.isNotEmpty()) {
                        { renaming = group.cores.first().host }
                    } else {
                        null
                    },
                )
            }
            val card: @Composable (Core) -> Unit = { core ->
                CoreCard(core, pings[core.id], hidden, onConfirm, onSend, onOrders = { onOrders(core.id) })
            }
            if (columns > 1) {
                // Two columns: each server or exchange, with its cores under it, takes one.
                item(key = key) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        header()
                        if (open) group.cores.forEach { card(it) }
                    }
                }
            } else {
                wide(key = key) { header() }
                if (open) {
                    items(group.cores, key = { "core:${group.key}:${it.id}" }) { card(it) }
                }
            }
        }
    }

    renaming?.let { host ->
        RenameServerDialog(
            host = host,
            current = serverNames[host].orEmpty(),
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                val next = if (name.isBlank()) serverNames - host else serverNames + (host to name)
                AppPrefs.setServerNames(context, next)
            },
        )
    }
}

/** Cores that run on one server, or trade on one exchange. */
private class CoreGroup(
    val key: String,
    val title: String,
    /** The server's address under the name the user gave it. */
    val subtitle: String,
    /** The exchange's brand, for its logo; empty for a server. */
    val brand: String,
    val cores: List<Core>,
)

/** The exchange's name as people write it. */
internal fun exchangeTitle(brand: String): String = when (brand) {
    "binance" -> "Binance"
    "bybit" -> "Bybit"
    "okx" -> "OKX"
    "gate" -> "Gate"
    "bitget" -> "Bitget"
    "htx" -> "HTX"
    "hyperliquid" -> "HyperLiquid"
    "" -> "Биржа ещё не известна"
    else -> brand.replaceFirstChar { it.uppercase() }
}

/** One tap shows the cores by exchange, the next by server: the button names what is shown. */
@Composable
private fun GroupSwitch(byServer: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        modifier = modifier.clickable(onClick = onToggle),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (byServer) "Сервера" else "Биржи",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.accent,
                    modifier = Modifier.weight(1f),
                )
                Text("⇄", style = MaterialTheme.typography.titleMedium, color = Palette.textDim)
            }
            Text(
                if (byServer) "Нажмите — по биржам" else "Нажмите — по серверам",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.textDim,
            )
        }
    }
}

/**
 * A server or an exchange: its status, how many cores, and for a server its load as the cores
 * report it. A tap opens or closes its cores; holding a server renames it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(
    group: CoreGroup,
    pings: Map<Long, Pair<Int?, Int?>>,
    byServer: Boolean,
    hidden: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onRename: (() -> Unit)?,
) {
    val cores = group.cores
    val online = cores.filter { it.run.online }
    // With the balances hidden the server's address is too; a name the user gave it stays.
    val host = cores.first().host
    val title = if (hidden && byServer && group.title == host) maskedAddress(host) else group.title
    val subtitle = if (hidden) maskedAddress(group.subtitle) else group.subtitle
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surfaceHigh),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardDefaults.shape)
            .combinedClickable(onClick = onToggle, onLongClick = onRename),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(groupColor(cores))
                Spacer(Modifier.width(8.dp))
                val logo = if (byServer) null else brandLogo(group.brand)
                if (logo != null) {
                    Image(
                        painter = imagePainter(logo),
                        contentDescription = group.title,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
                    }
                }
                Text(
                    "${online.size}/${cores.size} ${coresWord(cores.size)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.textDim,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium, color = Palette.textDim)
            }
            if (byServer) {
                Text(serverLoad(cores, pings), style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
            }
            if (byServer) {
                // Which exchanges the server's cores trade on, by their logos.
                val brands = cores.map { it.brand }.filter { it.isNotEmpty() }.distinct()
                if (brands.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        for (brand in brands) {
                            val logo = brandLogo(brand) ?: continue
                            Image(
                                painter = imagePainter(logo),
                                contentDescription = exchangeTitle(brand),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** An address with its letters and digits hidden, the dots and colons kept: `•••.•••.•••.•••`. */
private fun maskedAddress(host: String): String = host.map { if (it.isLetterOrDigit()) '•' else it }.joinToString("")

/** The machine's load as its cores report it: they share it, so one figure stands for all. */
private fun serverLoad(cores: List<Core>, pings: Map<Long, Pair<Int?, Int?>>): AnnotatedString {
    val online = cores.filter { it.run.online }
    if (online.isEmpty()) return AnnotatedString("нет связи")
    val cpu = online.mapNotNull { it.health.cpuPct }.maxOrNull()
    val free = online.mapNotNull { it.health.freeMemMb }.minOrNull()
    val ping = online.mapNotNull { (pings[it.id] ?: (it.health.pingMs to it.health.exchangeMs)).first }.minOrNull()
    val exchange = online.mapNotNull { (pings[it.id] ?: (it.health.pingMs to it.health.exchangeMs)).second }.minOrNull()
    val slow = if (online.all { it.brand == "hyperliquid" }) PING_SLOW_HYPERLIQUID_MS else PING_SLOW_MS
    return joined(
        listOfNotNull(
            cpu?.let { AnnotatedString("CPU $it%") },
            free?.let { AnnotatedString("своб. RAM ${if (it >= 1024) fmt("%.1f ГБ", it / 1024.0) else "$it МБ"}") },
            pingText(ping, exchange, slow),
        ),
    )
}

/** A ping counted as slow, red from it: the core's, and its exchange's (Hyperliquid answers slower). */
private const val PING_SLOW_MS = 250
private const val PING_SLOW_HYPERLIQUID_MS = 1000

/**
 * "пинг 67/20мс": to the core, then from the core to its exchange; each green under its slow mark,
 * red from it. The slash goes in the core's colour, «мс» in the exchange's.
 */
private fun pingText(core: Int?, exchange: Int?, exchangeSlow: Int): AnnotatedString? {
    if (core == null && exchange == null) return null
    return buildAnnotatedString {
        append("пинг ")
        shaded(core, PING_SLOW_MS, "/")
        shaded(exchange, exchangeSlow, "мс")
    }
}

/** [ms] and the [tail] after it, both in the ping's colour; a dash and a plain tail when not known. */
private fun AnnotatedString.Builder.shaded(ms: Int?, slow: Int, tail: String) {
    if (ms == null) {
        append("–")
        append(tail)
    } else {
        withStyle(SpanStyle(color = if (ms < slow) Palette.green else Palette.red)) { append("$ms$tail") }
    }
}

private fun joined(parts: List<AnnotatedString>): AnnotatedString = buildAnnotatedString {
    parts.forEachIndexed { at, part ->
        if (at > 0) append(" · ")
        append(part)
    }
}

private fun groupColor(cores: List<Core>): Color = when {
    cores.all { it.status == "inactive" } -> Palette.grey
    cores.any { it.status == "failed" || it.status == "disconnected" } -> Palette.red
    cores.any { it.status == "connecting" || it.status == "stage" } -> Palette.amber
    else -> Palette.green
}

private fun coresWord(n: Int): String {
    val tens = n % 100
    val ones = n % 10
    return when {
        tens in 11..14 -> "ядер"
        ones == 1 -> "ядро"
        ones in 2..4 -> "ядра"
        else -> "ядер"
    }
}

/** Naming a server; an empty name goes back to its address. */
@Composable
private fun RenameServerDialog(host: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    MoonDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название сервера") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(host, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(host) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(onClick = { onSave("") }) { Text("Как адрес") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** What a hidden amount is drawn as. */
private const val MASKED = "••••••"

/** Every account together, with the eye that hides all balances on this screen. */
@Composable
private fun TotalCard(total: Total, hidden: Boolean, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surfaceHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Общий баланс", style = MaterialTheme.typography.labelLarge, color = Palette.textDim)
                val sum = total.total
                Text(
                    when {
                        hidden -> "$MASKED USDT"
                        sum == null -> "—"
                        else -> "${formatAmount(sum)} USDT"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val free = total.free
                val notes = buildList {
                    if (!hidden && free != null) add("свободно ${formatAmount(free)}")
                    if (total.stale > 0) add("устарело ядер: ${total.stale}")
                    if (total.excluded > 0) add("без данных: ${total.excluded}")
                }
                if (notes.isNotEmpty()) {
                    Text(
                        notes.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.textDim,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                EyeIcon(open = !hidden, tint = Palette.textDim)
            }
        }
    }
}

/** An eye, struck through when the balances are hidden. */
@Composable
private fun EyeIcon(open: Boolean, tint: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val outline = Path().apply {
            moveTo(w * 0.06f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.02f, w * 0.94f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.98f, w * 0.06f, h * 0.5f)
            close()
        }
        drawPath(outline, tint, style = Stroke(width = w * 0.08f))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.5f))
        if (!open) {
            drawLine(
                tint,
                start = Offset(w * 0.16f, h * 0.88f),
                end = Offset(w * 0.84f, h * 0.12f),
                strokeWidth = w * 0.09f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoreCard(
    core: Core,
    ping: Pair<Int?, Int?>?,
    hidden: Boolean,
    onConfirm: (Confirm) -> Unit,
    onSend: (JSONObject) -> Unit,
    onOrders: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statusColor(core))
                Spacer(Modifier.width(8.dp))
                val logo = brandLogo(core.brand)
                if (logo != null) {
                    Image(
                        painter = imagePainter(logo),
                        contentDescription = core.exchange,
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    core.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val venue = listOf(core.exchange, core.base).filter { it.isNotEmpty() }.joinToString(" · ")
                if (venue.isNotEmpty()) {
                    Text(venue, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
                }
            }
            Text(statusLine(core), style = MaterialTheme.typography.bodyMedium, color = statusColor(core))

            val balance = core.balance
            if (balance != null) {
                Text(
                    if (hidden) "Баланс $MASKED" else "Баланс ${formatAmount(balance.total)} ${balance.currency}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val health = healthLine(core, ping)
            if (health.isNotEmpty()) {
                Text(health, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
            }
            if (core.active) {
                Text(
                    "Позиции ${core.positions} · Ордера ${core.orders} · " +
                        "Стратегии ${core.strategiesChecked}/${core.strategiesTotal}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.textDim,
                )
            }

            if (core.active) {
                // A flow, not a row: in a narrow column (a tablet upright, two to a row) the
                // buttons go on to a second line rather than squeeze.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val trading = core.run.trading
                    if (core.run.online && trading != null) {
                        if (trading) {
                            OutlinedButton(contentPadding = TIGHT_BUTTON, onClick = {
                                onConfirm(
                                    Confirm(
                                        title = "Остановить стратегии?",
                                        text = "Ядро «${core.name}» перестанет открывать новые сделки по стратегиям. Открытые позиции останутся.",
                                        action = "Стоп",
                                        request = runRequest(core.id, false),
                                    ),
                                )
                            }) { Text("Стоп", maxLines = 1, softWrap = false) }
                        } else {
                            Button(
                                contentPadding = TIGHT_BUTTON,
                                onClick = {
                                    onConfirm(
                                        Confirm(
                                            title = "Запустить стратегии?",
                                            text = "Ядро «${core.name}» начнёт торговать по отмеченным стратегиям (${core.strategiesChecked}).",
                                            action = "Старт",
                                            request = runRequest(core.id, true),
                                        ),
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.green, contentColor = Palette.onAccent),
                            ) { Text("Старт", maxLines = 1, softWrap = false) }
                        }
                    }
                    if (core.run.online && core.orders > core.positions) {
                        TextButton(contentPadding = TIGHT_BUTTON, onClick = {
                            onConfirm(
                                Confirm(
                                    title = "Снять бай-ордера?",
                                    text = "Ядро «${core.name}»: будут сняты все покупки, которые ещё ждут исполнения. Позиции и их селлы останутся.",
                                    action = "Снять",
                                    request = JSONObject().put("op", "cancel_buys").put("core", core.id),
                                ),
                            )
                        }) { Text("Снять ордера", maxLines = 1, softWrap = false) }
                    }
                    if (core.run.online) {
                        TextButton(contentPadding = TIGHT_BUTTON, onClick = {
                            onConfirm(
                                Confirm(
                                    title = "Рестарт ядра?",
                                    text = "Штатный «Старт / Рестарт» Moonbot на ядре «${core.name}»: " +
                                        "перезапускает рыночный модуль, выводит из пассивного режима и " +
                                        "запускает отмеченные стратегии. Открытые позиции остаются.",
                                    action = "Рестарт",
                                    request = JSONObject().put("op", "restart").put("core", core.id),
                                ),
                            )
                        }) { Text("Рестарт", maxLines = 1, softWrap = false) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!core.run.online) {
                        TextButton(contentPadding = TIGHT_BUTTON, onClick = {
                            onSend(JSONObject().put("op", "reconnect").put("core", core.id))
                        }) { Text("Переподключить", maxLines = 1, softWrap = false) }
                    }
                    TextButton(contentPadding = TIGHT_BUTTON, onClick = onOrders) { Text("Ордера", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** How often pings change on screen. */
private const val PING_EVERY_MS = 5_000L

/** Each core's ping and its exchange's, as shown until the next refresh. */
private fun pingsOf(snapshot: Snapshot): Map<Long, Pair<Int?, Int?>> =
    snapshot.cores.associate { it.id to (it.health.pingMs to it.health.exchangeMs) }

/** The core card's buttons, tighter than Material's: five of them fit one line. */
private val TIGHT_BUTTON = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

/** Ping, the exchange's latency, the machine's load and free memory, as far as reported. */
private fun healthLine(core: Core, ping: Pair<Int?, Int?>?): AnnotatedString {
    if (!core.run.online) return AnnotatedString("")
    val h = core.health
    val (toCore, toExchange) = ping ?: (h.pingMs to h.exchangeMs)
    val slow = if (core.brand == "hyperliquid") PING_SLOW_HYPERLIQUID_MS else PING_SLOW_MS
    return joined(
        listOfNotNull(
            pingText(toCore, toExchange, slow),
            h.cpuPct?.let { AnnotatedString("CPU $it%") },
            h.freeMemMb?.let { AnnotatedString("своб. RAM ${if (it >= 1024) fmt("%.1f ГБ", it / 1024.0) else "$it МБ"}") },
        ),
    )
}

private fun runRequest(core: Long, on: Boolean): JSONObject =
    JSONObject().put("op", "run").put("core", core).put("switch", "trading").put("on", on)

private fun statusColor(core: Core): Color = when (core.status) {
    "ready" -> if (core.run.trading == true) Palette.green else Palette.amber
    "failed" -> Palette.red
    "inactive" -> Palette.grey
    "disconnected" -> Palette.red
    else -> Palette.amber
}

private fun statusLine(core: Core): String = when (core.status) {
    "ready" -> when (core.run.trading) {
        true -> "Подключено · стратегии запущены"
        false -> "Подключено · стратегии остановлены"
        null -> "Подключено"
    }
    "connecting" -> "Подключение…"
    "stage" -> "Подключение: ${core.statusText}"
    "failed" -> "Ошибка: ${core.statusText}"
    "disconnected" -> "Отключено"
    "inactive" -> "Выключено в настройках терминала"
    else -> core.status
}

// ---------------------------------------------------------------------------------------------
// Orders
// ---------------------------------------------------------------------------------------------

@Composable
private fun OrdersTab(
    snapshot: Snapshot,
    manualOnly: Boolean,
    filterCore: Long?,
    onClearFilter: () -> Unit,
    onConfirm: (Confirm) -> Unit,
    onChart: (Long, String) -> Unit,
) {
    var selected by remember { mutableStateOf<Order?>(null) }
    val coreNames = remember(snapshot.cores) { snapshot.cores.associate { it.id to it.name } }
    val byCore = if (filterCore == null) snapshot.orders else snapshot.orders.filter { it.core == filterCore }
    val orders = if (manualOnly) byCore.filter { it.manual || it.mine } else byCore

    ListGrid(contentPadding = PaddingValues(12.dp), spacing = 8.dp) {
        if (filterCore != null) {
            wide(key = "filter") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ядро ${coreNames[filterCore] ?: filterCore}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearFilter) { Text("Все ядра") }
                }
            }
        }
        if (orders.isEmpty()) {
            wide {
                EmptyNote(
                    if (manualOnly) {
                        "Ордеров и позиций, выставленных вручную из MoonMobile или MoonTerminal, нет."
                    } else {
                        "Открытых ордеров и позиций нет."
                    },
                )
            }
        }
        items(orders) { order ->
            OrderCard(order, coreNames[order.core] ?: "", onClick = { selected = order })
        }
    }

    val picked = selected
    if (picked != null) {
        // The row as it is NOW: the dialog may stay open while the order fills or closes.
        val live = snapshot.orders.firstOrNull { it.core == picked.core && it.uid == picked.uid }
        MoonDialog(
            onDismissRequest = { selected = null },
            title = { Text("${picked.market} · ${if (picked.short) "SHORT" else "LONG"}") },
            text = {
                if (live == null) {
                    Text("Ордер уже закрыт.")
                } else {
                    OrderDetails(live, coreNames[live.core] ?: "")
                }
            },
            confirmButton = {
                if (live != null) {
                    if (live.filled) {
                        val arm = !live.panicSell
                        TextButton(onClick = {
                            selected = null
                            onConfirm(
                                Confirm(
                                    title = if (arm) "Panic Sell?" else "Снять Panic Sell?",
                                    text = if (arm) {
                                        "Позиция ${live.market} будет закрываться по рынку, цена селла пойдёт за стаканом."
                                    } else {
                                        "Panic Sell по ${live.market} будет выключен."
                                    },
                                    action = if (arm) "Panic Sell" else "Снять",
                                    request = JSONObject()
                                        .put("op", "order_panic")
                                        .put("core", live.core)
                                        .put("uid", live.uid)
                                        .put("on", arm),
                                ),
                            )
                        }) { Text(if (arm) "Panic Sell" else "Снять Panic Sell", color = Palette.red) }
                    } else {
                        TextButton(onClick = {
                            selected = null
                            onConfirm(
                                Confirm(
                                    title = "Отменить ордер?",
                                    text = "Ордер на покупку ${live.market} будет отменён.",
                                    action = "Отменить ордер",
                                    request = JSONObject()
                                        .put("op", "cancel_order")
                                        .put("core", live.core)
                                        .put("uid", live.uid),
                                ),
                            )
                        }) { Text("Отменить ордер", color = Palette.red) }
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        selected = null
                        onChart(picked.core, picked.market)
                    }) { Text("График") }
                    TextButton(onClick = { selected = null }) { Text("Закрыть") }
                }
            },
        )
    }
}

@Composable
private fun OrderCard(order: Order, coreName: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.market,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (order.short) "SHORT" else "LONG",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (order.short) Palette.red else Palette.green,
                )
                if (order.emulator) {
                    Spacer(Modifier.width(8.dp))
                    Text("эмулятор", style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
                }
                Spacer(Modifier.weight(1f))
                val pnl = order.pnlPct
                if (pnl != null) {
                    // The percent with what it is worth in the market's quote currency beside it.
                    val money = positionMoney(order)
                    Text(
                        if (money == null) formatPercent(pnl) else "${formatPercent(pnl)}  ${signedQuote(money, order.quote)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (pnl < 0) Palette.red else Palette.green,
                        maxLines = 1,
                    )
                } else {
                    Text(orderState(order), style = MaterialTheme.typography.labelLarge, color = Palette.amber)
                }
            }
            Row {
                Text(
                    "Вход ${formatPrice(order.buyPrice)} → TP ${formatPrice(order.sellPrice)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text("Текущая ${formatPrice(order.price)}", style = MaterialTheme.typography.bodyMedium, color = Palette.textDim)
            }
            Text(
                listOf(coreName, order.strategy).filter { it.isNotEmpty() }.joinToString(" · ") +
                    if (order.panicSell) " · PANIC SELL" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (order.panicSell) Palette.red else Palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OrderDetails(order: Order, coreName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailRow("Ядро", coreName)
        DetailRow("Стратегия", order.strategy.ifEmpty { "вручную" })
        DetailRow("Состояние", orderState(order))
        // The size in the quote currency (USDT…), as the order's money: the coins at the entry.
        val cost = order.size * order.buyPrice
        DetailRow("Размер", if (cost > 0.0) "${formatAmount(cost)} ${order.quote}" else "—")
        DetailRow("Entry price", formatPrice(order.buyPrice))
        DetailRow("Цена TP", formatPrice(order.sellPrice))
        // The futures' mark price where the core reports one; otherwise the last price.
        DetailRow("Mark price", formatPrice(order.markPrice ?: order.price))
        val pnl = order.pnlPct
        if (pnl != null) {
            val money = positionMoney(order)
            DetailRow(
                "PNL",
                if (money == null) formatPercent(pnl) else "${formatPercent(pnl)}  ${signedQuote(money, order.quote)}",
                valueColor = if (pnl < 0.0) Palette.red else Palette.green,
            )
        }
        order.liqPrice?.let { DetailRow("Liq. price", formatPrice(it)) }
        val stop = order.stopLoss
        if (stop != null) DetailRow("Стоп-лосс", formatPrice(stop))
        val take = order.takeProfit
        if (take != null) DetailRow("Тейк-профит", formatPrice(take))
    }
}

/** A name and its value; [valueColor] paints both, as the PNL's gain or loss. */
@Composable
private fun DetailRow(label: String, value: String, valueColor: Color? = null) {
    Row {
        Text(
            label,
            color = valueColor ?: Palette.textDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: Color.Unspecified)
    }
}

private fun orderState(order: Order): String = when {
    order.panicSell -> "Panic Sell"
    order.filled -> "позиция"
    order.pending -> "ждёт условия"
    else -> "ждёт покупки"
}

// ---------------------------------------------------------------------------------------------
// Detects
// ---------------------------------------------------------------------------------------------

@Composable
private fun DetectsTab(snapshot: Snapshot, onChart: (Long, String) -> Unit) {
    val coreNames = remember(snapshot.cores) { snapshot.cores.associate { it.id to it.name } }
    ListGrid(contentPadding = PaddingValues(12.dp), spacing = 6.dp) {
        if (snapshot.detects.isEmpty()) {
            wide { EmptyNote("Детектов пока нет. Они появятся здесь, как только ядро их пришлёт.") }
        }
        items(snapshot.detects) { detect ->
            DetectRow(
                detect,
                coreNames[detect.core] ?: "",
                onClick = { onChart(detect.core, detect.market) },
            )
        }
    }
}

@Composable
private fun DetectRow(detect: Detect, coreName: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detect.market, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (detect.alert) "алерт" else if (detect.short) "SHORT" else "LONG",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (detect.alert) Palette.amber else if (detect.short) Palette.red else Palette.green,
                )
                Spacer(Modifier.weight(1f))
                Text(formatTime(detect.timeMs), style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
            }
            val source = listOf(coreName, detect.strategy).filter { it.isNotEmpty() }.joinToString(" · ")
            if (source.isNotEmpty()) {
                Text(source, style = MaterialTheme.typography.labelMedium, color = Palette.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detect.msg.isNotEmpty()) {
                Text(detect.msg, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Strategies
// ---------------------------------------------------------------------------------------------

@Composable
private fun StrategiesTab(snapshot: Snapshot, onSend: (JSONObject) -> Unit) {
    // The core whose strategies are open; null shows the list of cores.
    var openCore by rememberSaveable { mutableStateOf<Long?>(null) }
    val byCore: Map<Long, List<Strategy>> =
        remember(snapshot.strategies) { snapshot.strategies.groupBy { it.core } }
    val core = snapshot.cores.firstOrNull { it.id == openCore }

    if (core == null) {
        StrategyCores(snapshot.cores, onOpen = { openCore = it.id })
    } else {
        BackHandler { openCore = null }
        CoreStrategies(core, byCore[core.id].orEmpty(), onBack = { openCore = null }, onSend = onSend)
    }
}

/** The cores (bots), each with how many of its strategies are ticked. */
@Composable
private fun StrategyCores(cores: List<Core>, onOpen: (Core) -> Unit) {
    ListGrid(contentPadding = PaddingValues(12.dp), spacing = 8.dp) {
        if (cores.isEmpty()) {
            wide { EmptyNote("В файле нет ни одного ядра.") }
        }
        items(cores, key = { it.id }) { core ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Palette.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(core) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(statusColor(core))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            core.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            strategiesLine(core),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.textDim,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = Palette.textDim)
                }
            }
        }
    }
}

private fun strategiesLine(core: Core): String = when {
    !core.active -> "Выключено в настройках терминала"
    !core.run.online -> "Нет связи с ядром"
    core.strategiesTotal == 0 -> "Стратегий нет"
    else -> {
        val engine = when (core.run.trading) {
            true -> " · запущены"
            false -> " · остановлены"
            null -> ""
        }
        "Отмечено ${core.strategiesChecked} из ${core.strategiesTotal}$engine"
    }
}

/** One core's strategies, grouped by their folders in the core's own order. */
@Composable
private fun CoreStrategies(
    core: Core,
    rows: List<Strategy>,
    onBack: () -> Unit,
    onSend: (JSONObject) -> Unit,
) {
    // The strategy open in the editor; null shows the list.
    var openStrategy by rememberSaveable(core.id) { mutableStateOf<String?>(null) }
    val editing = rows.firstOrNull { it.id == openStrategy }
    if (editing != null) {
        StrategyEditor(core, editing.id, onBack = { openStrategy = null })
        return
    }
    val byFolder: Map<String, List<Strategy>> = remember(rows) { rows.groupBy { it.folder } }
    val showFolders = byFolder.size > 1 || byFolder.keys.any { it.isNotEmpty() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Ядра") }
            Spacer(Modifier.width(4.dp))
            StatusDot(statusColor(core))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    core.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    strategiesLine(core),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.textDim,
                )
            }
        }
        ListGrid(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            spacing = 6.dp,
        ) {
            if (rows.isEmpty()) {
                wide {
                    EmptyNote(
                        if (core.run.online) {
                            "У этого ядра нет стратегий."
                        } else {
                            "Стратегии появятся, когда ядро подключится и пришлёт их список."
                        },
                    )
                }
            }
            byFolder.forEach { (folder, strategies) ->
                if (showFolders) {
                    wide(key = "folder:$folder") {
                        Text(
                            folder.ifEmpty { "Без папки" },
                            style = MaterialTheme.typography.titleSmall,
                            color = Palette.accent,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                }
                items(strategies, key = { "strategy:${it.id}" }) { strategy ->
                    StrategyRow(
                        strategy,
                        enabled = core.run.online,
                        onOpen = { openStrategy = strategy.id },
                        onToggle = { on ->
                            onSend(
                                JSONObject()
                                    .put("op", "strategy")
                                    .put("core", strategy.core)
                                    .put("id", strategy.id)
                                    .put("on", on),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StrategyRow(strategy: Strategy, enabled: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strategy.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOf(strategy.kind, if (strategy.short) "short" else "")
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.labelMedium, color = Palette.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Shows what the CORE says: it flips when the core confirms the change.
            Switch(checked = strategy.checked, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Log
// ---------------------------------------------------------------------------------------------

/** The bridge's newest log lines: why a core does not connect is written there. */
@Composable
internal fun LogDialog(onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        val reply = Session.call(JSONObject().put("op", "log").put("max", 300))
        val rows = reply.json.optJSONArray("lines")
        val out = ArrayList<String>()
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                out.add("${row.optString("ts")} ${row.optString("level")} ${row.optString("msg")}")
            }
        }
        if (!reply.ok) out.add(Session.describeError(reply))
        lines = out
    }
    val current = lines
    MoonDialog(
        onDismissRequest = onClose,
        title = { Text("Журнал") },
        text = {
            if (current == null) {
                Text("Читаю…")
            } else if (current.isEmpty()) {
                Text("Записей нет.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(current) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(current.orEmpty().joinToString("\n"))) },
                enabled = !current.isNullOrEmpty(),
            ) { Text("Скопировать") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Закрыть") }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.textDim,
        modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
    )
}
