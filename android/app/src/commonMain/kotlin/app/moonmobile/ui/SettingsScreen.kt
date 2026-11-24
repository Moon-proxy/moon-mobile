package app.moonmobile.ui

import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import app.moonmobile.core.ChartPrefs
import app.moonmobile.core.SESSION_DAY
import app.moonmobile.core.SESSION_MONTH
import app.moonmobile.core.SESSION_NEVER
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moonmobile.core.AppPrefs
import app.moonmobile.core.Core
import app.moonmobile.core.AppLock
import app.moonmobile.core.Session
import app.moonmobile.core.Sounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.moonmobile.core.Notifier
import app.moonmobile.core.NotifySettings
import app.moonmobile.core.Snapshot
import app.moonmobile.core.ThemeMode
import app.moonmobile.core.StationClient
import app.moonmobile.core.HomeWidget
import app.moonmobile.core.WidgetFeed
import app.moonmobile.json.JSONObject
import app.moonmobile.platform.PlatformContext as Context
import app.moonmobile.platform.ioDispatcher
import app.moonmobile.platform.Platform

/** The gear's screen: the theme and which push notifications come. */
@Composable
internal fun SettingsScreen(snapshot: Snapshot?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = platformContext()
    val theme by AppPrefs.theme.collectAsState()
    val uiScale by AppPrefs.uiScale.collectAsState()
    // What the app picks by itself; the platform's density lies under the app's scaled one.
    val autoScale = autoUiScale(smallestScreenWidthDp())
    val notify by AppPrefs.notify.collectAsState()
    var allowed by remember { mutableStateOf(Notifier.allowed(context)) }
    val ask = rememberNotificationPermission { allowed = Notifier.allowed(context) }
    val change: (NotifySettings) -> Unit = { AppPrefs.setNotify(context, it) }
    // The exchanges opened in the push section's strategy list.
    val openExchanges = remember { mutableStateMapOf<String, Boolean>() }
    val overrides by AppPrefs.overrides.collectAsState()
    val changeOverride: (Long, AppPrefs.CoreOverride) -> Unit = { id, value ->
        AppPrefs.setCoreOverrides(context, overrides + (id to value))
    }
    val scope = rememberCoroutineScope()
    var coresOpen by remember { mutableStateOf(AppPrefs.flag(context, "settings_cores_open", false)) }
    var addingCore by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    val appState by Session.state.collectAsState()
    val lock by AppLock.settings.collectAsState()
    var lockDialog by remember { mutableStateOf<String?>(null) }
    var connectionNote by remember { mutableStateOf<String?>(null) }
    val pickServers = rememberDocumentPicker { uri ->
        if (uri != null) {
            Session.importServers(context, uri)
            connectionNote = "servers.enc заменён. Нажмите «Переподключиться»: если у нового файла другой " +
                "пароль, приложение его спросит."
        }
    }
    val pickSettings = rememberDocumentPicker { uri ->
        if (uri != null) {
            Session.importSettings(context, uri)
            connectionNote = "settings.toml загружен. Настройки ядер применятся после переподключения."
        }
    }
    // The user's own detect sounds, re-read after each change.
    var userSounds by remember { mutableStateOf(Sounds.userFiles(context)) }
    var soundsOpen by remember { mutableStateOf(AppPrefs.flag(context, "settings_sounds_open", false)) }
    var soundNote by remember { mutableStateOf<String?>(null) }
    var logFiles by remember { mutableStateOf(AppPrefs.flag(context, "log_files", true)) }
    var logPeriod by remember {
        mutableStateOf(AppPrefs.text(context, "log_period", "").toLongOrNull()?.takeIf { p -> LOG_PERIODS.any { it.first == p } } ?: 86_400_000L)
    }
    var logNote by remember { mutableStateOf<String?>(null) }
    var showUpdate by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val saveLogTo = rememberFolderPicker { tree ->
        if (tree != null) {
            scope.launch {
                logNote = Session.exportLogs(context, logPeriod).fold(
                    onSuccess = { path ->
                        withContext(ioDispatcher) {
                            val name = path.substringAfterLast('/')
                            writeIntoFolder(context, tree, name, readFileBytes(path), "text/plain") ?: "Сохранено: $name"
                        }
                    },
                    onFailure = { it.message },
                )
            }
        }
    }
    val missingSounds by Sounds.missing.collectAsState()
    var soundFor by remember { mutableStateOf<String?>(null) }
    val pickSoundFor = rememberDocumentPicker { uri ->
        val name = soundFor
        if (uri != null && name != null) {
            scope.launch {
                val result = withContext(ioDispatcher) { Sounds.importAs(context, uri, name) }
                soundNote = result.fold(
                    onSuccess = { "Звук «$name» добавлен" },
                    onFailure = { "Не удалось добавить: ${it.message}" },
                )
                userSounds = Sounds.userFiles(context)
            }
        }
    }
    val pickSound = rememberDocumentPicker { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(ioDispatcher) { Sounds.import(context, uri) }
                soundNote = result.fold(
                    onSuccess = { "Добавлен: $it" },
                    onFailure = { "Не удалось добавить: ${it.message}" },
                )
                userSounds = Sounds.userFiles(context)
            }
        }
    }
    val saveTo = rememberFolderPicker { tree ->
        if (tree != null) {
            scope.launch {
                exportNote = withContext(ioDispatcher) { exportToFolder(context, tree) }
            }
        }
    }
    val share: () -> Unit = {
        scope.launch {
            val problem = withContext(ioDispatcher) { shareExport(context) }
            exportNote = problem
        }
    }

    val chartPrefs by ChartPrefs.layers(context).collectAsState()
    val pendingUpdate = pendingUpdateVersion()
    // One group open at a time, remembered between visits; a tap on the open one folds it.
    var open by remember {
        mutableStateOf(SettingsGroup.entries.firstOrNull { it.name == AppPrefs.text(context, "settings_open", "") })
    }
    val listState = rememberLazyListState()
    val toggle: (SettingsGroup) -> Unit = { group ->
        val next = if (open == group) null else group
        open = next
        AppPrefs.setText(context, "settings_open", next?.name.orEmpty())
    }
    // The open group's header goes to the top once the list has folded the others: then it is
    // the group's own index.
    LaunchedEffect(open) {
        open?.let { listState.animateScrollToItem(it.ordinal) }
    }
    // What each folded group says about itself, and which ones want a look.
    val allCores = snapshot?.cores.orEmpty()
    val summaries = mapOf(
        SettingsGroup.CORES to when {
            allCores.isEmpty() -> "ядра ещё не подключены"
            else -> "${allCores.size} ${plural(allCores.size, "ядро", "ядра", "ядер")} · на связи " +
                allCores.count { it.status == "ready" }
        },
        SettingsGroup.WIDGET to "сутки с ${clockText(WidgetFeed.dayEndMinutes(context))}",
        SettingsGroup.STATION to (StationClient.saved(context)?.let { "на сервере ${it.text}" } ?: "не установлена"),
        SettingsGroup.CHART to (
            listOf(
                "марк-прайс" to chartPrefs.markLine,
                "ликвидации" to chartPrefs.liquidations,
            ).joinToString(" · ") { (name, on) -> "$name ${if (on) "вкл" else "выкл"}" } +
                " · детекты " + (if (chartPrefs.compactDetects) "компактные" else "полные")
            ),
        SettingsGroup.NOTIFY to if (!allowed) {
            "Android не разрешил уведомления"
        } else {
            val on = listOf(notify.coreLost, notify.trades, notify.ping, notify.cpu, notify.memory, notify.apiLimits)
            "включено ${on.count { it }} из ${on.size}"
        },
        SettingsGroup.SOUNDS to when {
            !notify.sounds -> "выключены"
            missingSounds.isNotEmpty() -> "нет в приложении: ${missingSounds.size}"
            notify.soundsMedia -> "включены · громкостью медиа"
            else -> "включены · громкостью уведомлений"
        },
        SettingsGroup.THEME to "${theme.title} · масштаб ${percent(uiScale ?: autoScale)}",
        SettingsGroup.SECURITY to when {
            !lock.passwordSet -> "пароль на вход не задан"
            else -> listOfNotNull(
                "пароль",
                "отпечаток".takeIf { lock.finger },
                "лицо".takeIf { lock.face },
            ).joinToString(" + ") + " · блок через " + when (lock.afterSec) {
                10 -> "10 с"
                60 -> "1 мин"
                300 -> "5 мин"
                900 -> "15 мин"
                else -> "${lock.afterSec} с"
            }
        },
        SettingsGroup.TRANSFER to "settings.toml и servers.enc для другого устройства",
        SettingsGroup.LOG to if (logFiles) "записывается · хранится 10 суток" else "не записывается",
        SettingsGroup.UPDATE to (pendingUpdate?.let { "доступна v$it" } ?: "установлена v${Session.appVersion(context)}"),
    )
    val attention = buildSet {
        if (!allowed) add(SettingsGroup.NOTIFY)
        if (notify.sounds && missingSounds.isNotEmpty()) add(SettingsGroup.SOUNDS)
        if (pendingUpdate != null) add(SettingsGroup.UPDATE)
        if (allCores.any { it.active && it.status == "failed" }) add(SettingsGroup.CORES)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .safeDrawingPaddingScaled(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Настройки", style = MaterialTheme.typography.titleMedium, color = Palette.text)
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val cores = snapshot?.cores.orEmpty()
            for (group in SettingsGroup.entries) {
                val isOpen = open == group
                item(key = "group-${group.name}") {
                    GroupHeader(
                        group = group,
                        summary = summaries[group].orEmpty(),
                        open = isOpen,
                        attention = group in attention,
                        modifier = Modifier.animateItem(),
                    ) { toggle(group) }
                }
                if (!isOpen) continue
                when (group) {
                    SettingsGroup.CORES -> {
                        body {
                            FileLine("servers.enc", "список ядер, зашифрован паролем", "Заменить") {
                                pickServers.launch(arrayOf("*/*"))
                            }
                        }
                        body {
                            FileLine(
                                "settings.toml",
                                if (appState.hasSettingsFile) "загружен" else "не загружен (необязательно)",
                                if (appState.hasSettingsFile) "Заменить" else "Загрузить",
                            ) { pickSettings.launch(arrayOf("*/*")) }
                        }
                        if (appState.savedPassword) {
                            body {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Пароль servers.enc сохранён: приложение подключается само при запуске.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Palette.textDim,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        Session.forgetPassword(context)
                                        connectionNote = "При следующем запуске приложение спросит пароль servers.enc"
                                    }) { Text("Забыть") }
                                }
                            }
                        }
                        body {
                            OutlinedButton(onClick = {
                                Session.reconnect(context)
                                onBack()
                            }) { Text("Переподключиться") }
                        }
                        connectionNote?.let { body { Note(it) } }

                        body {
                            FoldHeading("Подключенные ядра к приложению", cores.size, coresOpen) {
                                coresOpen = !coresOpen
                                AppPrefs.setFlag(context, "settings_cores_open", coresOpen)
                            }
                        }
                        if (coresOpen) {
                            if (cores.isEmpty()) {
                                body { Note("Список ядер появится, когда приложение подключится.") }
                            }
                            for (core in cores) {
                                body(key = "core-${core.id}") { CoreRow(core, overrides[core.id]) { changeOverride(core.id, it) } }
                            }
                            body {
                                Note(
                                    "Переключатель — подключается ли приложение к ядру; вступает в силу при следующем " +
                                        "подключении. Квадрат — цвет ядра, меняется сразу.",
                                )
                            }
                        }
                        body {
                            OutlinedButton(
                                onClick = { addingCore = true },
                                enabled = snapshot != null,
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text("Добавить подключение к ядру") }
                        }
                    }
                    SettingsGroup.WIDGET -> {
                        body(key = "widget") { WidgetSettings() }
                    }
                    SettingsGroup.STATION -> {
                        body(key = "station") { StationPanel(appState.snapshot) }
                    }
                    SettingsGroup.CHART -> {
                        body(key = "chart-mark") {
                            Check(
                                "Марк-прайс",
                                chartPrefs.markLine,
                                hint = "Оранжевая ступенчатая линия на фьючерсах, как в Moonbot: рыночная марк-цена " +
                                    "контракта, засечка на каждом обновлении.",
                            ) { ChartPrefs.setMarkLine(context, it) }
                        }
                        body(key = "chart-liq") {
                            Check(
                                "Ликвидации",
                                chartPrefs.liquidations,
                                hint = "Жёлтые точки там, где на рынке ликвидировали позиции.",
                            ) { ChartPrefs.setLiquidations(context, it) }
                        }
                        body(key = "chart-detects") {
                            Text(
                                "Детекты справа от графика",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.text,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        body(key = "chart-detects-full") {
                            Choice("Полные — с графиком дня, изменением за 1ч и 24ч, стратегией", selected = !chartPrefs.compactDetects) {
                                ChartPrefs.setCompactDetects(context, false)
                            }
                        }
                        body(key = "chart-detects-compact") {
                            Choice("Компактные — монета, изменение, тип, ядро и секунды", selected = chartPrefs.compactDetects) {
                                ChartPrefs.setCompactDetects(context, true)
                            }
                        }
                        body(key = "chart-session") {
                            Text(
                                "Сессия",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.text,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        body(key = "chart-session-day") {
                            Choice("Сбрасывать раз в сутки", selected = chartPrefs.session == SESSION_DAY) {
                                ChartPrefs.setSession(context, SESSION_DAY)
                            }
                        }
                        body(key = "chart-session-month") {
                            Choice("Сбрасывать раз в месяц", selected = chartPrefs.session == SESSION_MONTH) {
                                ChartPrefs.setSession(context, SESSION_MONTH)
                            }
                        }
                        body(key = "chart-session-never") {
                            Choice("Не сбрасывать сессию", selected = chartPrefs.session == SESSION_NEVER) {
                                ChartPrefs.setSession(context, SESSION_NEVER)
                            }
                        }
                        body(key = "chart-join-split") {
                            Check("Разделение и объединение ордеров", chartPrefs.joinSplit) {
                                ChartPrefs.setJoinSplit(context, it)
                            }
                        }
                        body {
                            Check(
                                "Поток сделок для графиков",
                                appState.chartsEnabled,
                                hint = "Пока график открыт, ядро шлёт поток сделок биржи — это трафик. Без галочки — " +
                                    "только счета, ордера и стратегии. Меняется со следующего подключения.",
                            ) {
                                Session.setChartsEnabled(context, it)
                                connectionNote = "Применится после переподключения"
                            }
                        }
                    }
                    SettingsGroup.NOTIFY -> {
                        if (!allowed) {
                            body {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Palette.surfaceHigh)
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        "Android не разрешил приложению показывать уведомления.",
                                        color = Palette.text,
                                    )
                                    Spacer(Modifier.padding(top = 6.dp))
                                    Button(onClick = {
                                        ask.launch(null)
                                    }) { Text("Разрешить") }
                                }
                            }
                        }
                        body {
                            Note(
                                "Приходят, пока приложение подключено к ядрам и не открыто на экране, в том " +
                                    "числе с выключенным экраном; одного рода собираются в одно уведомление " +
                                    "со списком. На Vivo отключите для MoonMobile ограничение работы в фоне.",
                            )
                        }
                        body {
                            Check("Ядро потеряло связь", notify.coreLost) { change(notify.copy(coreLost = it)) }
                        }
                        body {
                            Check("Сделки", notify.trades) { change(notify.copy(trades = it)) }
                        }
                        body {
                            Check(
                                "Только вход в позицию",
                                notify.entriesOnly,
                                enabled = notify.trades,
                                hint = "Выключено — также о выставленном ордере на вход",
                                indent = true,
                            ) { change(notify.copy(entriesOnly = it)) }
                        }
                        body {
                            Choice("Все типы стратегий", notify.allStrategies, enabled = notify.trades, indent = true) {
                                change(notify.copy(allStrategies = true))
                            }
                        }
                        body {
                            Choice("Только выбранные типы стратегий", !notify.allStrategies, enabled = notify.trades, indent = true) {
                                change(notify.copy(allStrategies = false))
                            }
                        }
                        if (notify.trades && !notify.allStrategies) {
                            val cores = snapshot?.cores.orEmpty().filter { it.active }
                            if (cores.isEmpty()) {
                                body { Note("Список стратегий появится, когда ядра подключатся.") }
                            }
                            // One folding list per exchange; inside it the strategies of its cores.
                            val groups = cores.groupBy { exchangeGroupTitle(it) }.toList().sortedBy { it.first }
                            for ((title, groupCores) in groups) {
                                val byCore = groupCores.map { core ->
                                    core to snapshot?.strategies.orEmpty().filter { it.core == core.id }
                                }
                                val keys = byCore.flatMap { (core, list) -> list.map { "${core.id}:${it.id}" } }
                                val open = openExchanges[title] == true
                                body(key = "push-exchange-$title") {
                                    ExchangeGroupRow(
                                        title = title,
                                        brand = groupCores.first().brand,
                                        chosen = keys.count { it in notify.strategies },
                                        total = keys.size,
                                        open = open,
                                        onOpen = { openExchanges[title] = !open },
                                        onAll = { on ->
                                            change(
                                                notify.copy(
                                                    strategies = if (on) notify.strategies + keys else notify.strategies - keys.toSet(),
                                                ),
                                            )
                                        },
                                    )
                                }
                                if (open) {
                                    for ((core, strategies) in byCore) {
                                        if (byCore.size > 1) {
                                            body(key = "push-core-${core.id}") {
                                                Text(
                                                    core.name,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Palette.accent,
                                                    modifier = Modifier.padding(start = 48.dp, top = 6.dp),
                                                )
                                            }
                                        }
                                        for (strategy in strategies) {
                                            val key = "${core.id}:${strategy.id}"
                                            body(key = "push-strategy-$key") {
                                                Check(
                                                    strategy.name.ifEmpty { "без имени" },
                                                    key in notify.strategies,
                                                    hint = strategy.kind.ifEmpty { null },
                                                    indent = true,
                                                    deep = true,
                                                ) { change(notify.copy(strategies = notify.strategies.toggled(key, it))) }
                                            }
                                        }
                                        if (strategies.isEmpty()) {
                                            body(key = "push-empty-${core.id}") {
                                                Note("${core.name}: стратегий нет или ядро ещё не прислало их.", indent = true)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        body { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = Palette.outline) }
                        body {
                            Check(
                                "Высокий пинг ядра к бирже",
                                notify.ping,
                                hint = "От 250 мс, у HyperLiquid — от 1000 мс, дольше 10 секунд.",
                            ) { change(notify.copy(ping = it)) }
                        }
                        body {
                            Check(
                                "Процессор 90% свыше 1 минуты",
                                notify.cpu,
                                hint = "Одно уведомление на сервер, сколько бы ядер на нём ни было.",
                            ) { change(notify.copy(cpu = it)) }
                        }
                        body {
                            Check(
                                "Оперативная память",
                                notify.memory,
                                hint = "Когда на сервере свободно меньше 150 МБ. Одно уведомление на сервер, " +
                                    "сколько бы ядер на нём ни было.",
                            ) { change(notify.copy(memory = it)) }
                        }
                        body {
                            Check(
                                "Превышены лимиты API",
                                notify.apiLimits,
                                hint = "Ошибки лимитов API бирж. У HyperLiquid — когда остаток запросов в сутки " +
                                    "опускается до 5%.",
                            ) { change(notify.copy(apiLimits = it)) }
                        }
                    }
                    SettingsGroup.SOUNDS -> {
                        body {
                            Check(
                                "Звук детектов",
                                notify.sounds,
                                hint = "Как в MoonTerminal: играет звук, выбранный в стратегии, когда у неё в Moonbot " +
                                    "включён звуковой сигнал.",
                            ) { change(notify.copy(sounds = it)) }
                        }
                        body {
                            Check(
                                "Громкость детектов",
                                notify.soundsMedia,
                                hint = "Громкостью медиа: слышно, даже когда звук уведомлений на телефоне выключен. " +
                                    "Без галочки — громкостью уведомлений.",
                            ) { change(notify.copy(soundsMedia = it)) }
                        }
                        if (missingSounds.isNotEmpty()) {
                            body { SubHeading("Нет в приложении") }
                            body {
                                Note(
                                    "Эти звуки выбраны в стратегиях Moonbot, но в приложении их нет — вместо них " +
                                        "играет стандартный. Загрузите файл: он сохранится под тем же именем.",
                                )
                            }
                            for (name in missingSounds.sorted()) {
                                body(key = "missing-sound-$name") {
                                    FileLine(name, "звук из Moonbot", "Загрузить") {
                                        soundFor = name
                                        pickSoundFor.launch(arrayOf("audio/*"))
                                    }
                                }
                            }
                        }
                        body {
                            OutlinedButton(
                                onClick = { pickSound.launch(arrayOf("audio/*")) },
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text("Добавить свой звук") }
                        }
                        soundNote?.let { body { Note(it) } }
                        body {
                            Note(
                                "Файл с тем же именем, что звук в Moonbot, заменяет встроенный. Файл «19_Имя.wav» " +
                                    "получает номер 19 — его можно выбрать в стратегии по номеру.",
                            )
                        }
                        body {
                            FoldHeading("Все звуки", userSounds.size + Sounds.builtIn.size, soundsOpen) {
                                soundsOpen = !soundsOpen
                                AppPrefs.setFlag(context, "settings_sounds_open", soundsOpen)
                            }
                        }
                        if (soundsOpen) {
                            body { SubHeading("Свои звуки") }
                            if (userSounds.isEmpty()) {
                                body { Note("Пока нет. Подойдут wav, mp3, ogg, m4a, aac и flac.") }
                            }
                            for (file in userSounds) {
                                body(key = "user-sound-${file.name}") {
                                    SoundRow(file.name, onPlay = { Sounds.preview(context, file.nameWithoutExtension) }) {
                                        Sounds.delete(file)
                                        userSounds = Sounds.userFiles(context)
                                    }
                                }
                            }
                            body { SubHeading("Звуки Moonbot") }
                            Sounds.builtIn.forEachIndexed { index, name ->
                                body(key = "sound-$name") {
                                    SoundRow("${index + 1}. $name", onPlay = { Sounds.preview(context, name) }, onDelete = null)
                                }
                            }
                        }
                    }
                    SettingsGroup.THEME -> {
                        for (mode in ThemeMode.entries) {
                            body {
                                Choice(mode.title, selected = theme == mode) { AppPrefs.setTheme(context, mode) }
                            }
                        }
                        body { SubHeading("Масштаб интерфейса") }
                        body { ScaleRow(uiScale, autoScale) { AppPrefs.setUiScale(context, it) } }
                    }
                    SettingsGroup.SECURITY -> {
                        body {
                            FileLine(
                                "Пароль на вход",
                                if (lock.passwordSet) "задан" else "не задан",
                                if (lock.passwordSet) "Сменить" else "Задать",
                            ) { lockDialog = if (lock.passwordSet) "change" else "set" }
                        }
                        if (lock.passwordSet) {
                            body { TextButton(onClick = { lockDialog = "remove" }) { Text("Убрать пароль", color = Palette.red) } }
                        }
                        body {
                            val can = AppLock.available(context, strong = true)
                            Check(
                                "Вход по отпечатку пальца",
                                lock.finger,
                                hint = when {
                                    !lock.passwordSet -> "Сначала задайте пароль на вход: без него отпечаток не включить."
                                    !can -> "На телефоне нет отпечатка: добавьте его в настройках Android."
                                    else -> "Вместо пароля; пароль остаётся запасным способом."
                                },
                                enabled = lock.passwordSet && can,
                            ) { AppLock.setBiometrics(context, it, lock.face) }
                        }
                        body {
                            val can = AppLock.available(context, strong = false)
                            Check(
                                "Вход по лицу",
                                lock.face,
                                hint = when {
                                    !lock.passwordSet -> "Сначала задайте пароль на вход: без него лицо не включить."
                                    !can -> "На телефоне нет распознавания лица: добавьте его в настройках Android."
                                    else -> "Android сам предложит отпечаток или лицо из включённых."
                                },
                                enabled = lock.passwordSet && can,
                            ) { AppLock.setBiometrics(context, lock.finger, it) }
                        }
                        if (lock.passwordSet) {
                            body {
                                Text(
                                    "Блокировать, если приложение было свёрнуто дольше",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            for ((seconds, title) in listOf(10 to "10 секунд", 60 to "1 минуты", 300 to "5 минут", 900 to "15 минут")) {
                                body(key = "lock-after-$seconds") {
                                    Choice(title, selected = lock.afterSec == seconds) { AppLock.setAfter(context, seconds) }
                                }
                            }
                        }
                    }
                    SettingsGroup.TRANSFER -> {
                        body {
                            Note(
                                "Один settings.toml несёт настройки ядер из терминала и все настройки приложения; " +
                                    "servers.enc — список ядер (зашифрован паролем). На другом устройстве выберите оба " +
                                    "файла на стартовой странице («Перенести из MoonTerminal»). Пароль не экспортируется.",
                            )
                        }
                        body {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { saveTo.launch(null) }) { Text("Сохранить в папку") }
                                OutlinedButton(onClick = { share() }) { Text("Отправить") }
                            }
                        }
                        exportNote?.let { body { Note(it) } }
                    }
                    SettingsGroup.LOG -> {
                        body {
                            Check(
                                "Записывать журнал в файлы",
                                logFiles,
                                hint = "Подключения, ошибки, команды ядрам, уведомления и обновления. Файлы старше " +
                                    "10 суток удаляются сами; адреса серверов в журнале скрыты.",
                            ) {
                                logFiles = it
                                Session.setLogFiles(context, it)
                            }
                        }
                        body {
                            Text(
                                "Выгрузить журнал за",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.text,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        for ((period, title) in LOG_PERIODS) {
                            body(key = "log-period-$period") {
                                Choice(title, selected = logPeriod == period) {
                                    logPeriod = period
                                    AppPrefs.setText(context, "log_period", period.toString())
                                }
                            }
                        }
                        body {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { saveLogTo.launch(null) }) { Text("Сохранить в папку") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        logNote = Session.exportLogs(context, logPeriod).fold(
                                            onSuccess = { path -> shareFiles(context, listOf(path), "text/plain", "Журнал MoonMobile") },
                                            onFailure = { it.message },
                                        )
                                    }
                                }) { Text("Отправить") }
                            }
                        }
                        body {
                            TextButton(onClick = { showLog = true }) { Text("Последние записи") }
                        }
                        logNote?.let { body { Note(it) } }
                    }
                    SettingsGroup.UPDATE -> {
                        body { UpdateSettingsBody(onInstall = { showUpdate = true }) }
                    }
                }
                item(key = "group-end-${group.name}") { GroupFooter(Modifier.animateItem()) }
            }
            item {
                val version = remember { Session.appVersion(context) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HorizontalDivider(color = Palette.outline, modifier = Modifier.padding(bottom = 14.dp))
                    // The version and what it brought, side by side.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "MoonMobile",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Palette.textDim,
                            )
                            Text(
                                "версия $version",
                                style = MaterialTheme.typography.labelMedium,
                                color = Palette.textDim,
                            )
                        }
                        OutlinedButton(onClick = { showChanges = true }) { Text("Что нового?") }
                    }
                }
            }
        }
    }

    if (addingCore) {
        AddCoreDialog(snapshot) { addingCore = false }
    }
    lockDialog?.let { mode -> LockPasswordDialog(mode) { lockDialog = null } }
    if (showUpdate) UpdateDialog { showUpdate = false }
    if (showChanges) ChangesDialog { showChanges = false }
    if (showLog) LogDialog { showLog = false }
}

/** The settings, group by group, in their order on the screen. */
private enum class SettingsGroup(val title: String, val icon: String) {
    CORES("Ядра и подключение", "🔌"),
    STATION("Станция", "🛰"),
    WIDGET("Виджет", "🧩"),
    CHART("График", "📈"),
    NOTIFY("Уведомления", "🔔"),
    SOUNDS("Звуки детектов", "🔊"),
    THEME("Внешний вид", "🎨"),
    SECURITY("Безопасность", "🔒"),
    TRANSFER("Перенос настроек", "📦"),
    LOG("Журнал", "📝"),
    UPDATE("Обновление", "⬆"),
}

/** `1 ядро`, `2 ядра`, `5 ядер`. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val tens = n % 100
    val ones = n % 10
    return when {
        tens in 11..14 -> many
        ones == 1 -> one
        ones in 2..4 -> few
        else -> many
    }
}

/** One row of an open group: on the group's card, under its header. */
private fun LazyListScope.body(key: Any? = null, content: @Composable () -> Unit) {
    item(key = key) {
        Box(
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
                .background(Palette.surface)
                .padding(horizontal = 14.dp, vertical = 2.dp),
        ) { content() }
    }
}

/**
 * A group's header: its icon, its name and what it holds in one line. A tap opens or folds it; a
 * dot asks for a look inside.
 */
@Composable
private fun GroupHeader(
    group: SettingsGroup,
    summary: String,
    open: Boolean,
    attention: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = if (open) RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp) else RoundedCornerShape(14.dp)
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "group-chevron")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(shape)
            .background(Palette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (open) Palette.accent.copy(alpha = 0.22f) else Palette.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Text(group.icon, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (open) Palette.accent else Palette.text,
            )
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = if (attention) Palette.red else Palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (attention) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.red),
            )
        }
        Text(
            "›",
            fontSize = 24.sp,
            color = Palette.textDim,
            modifier = Modifier
                .padding(start = 10.dp)
                .rotate(turn),
        )
    }
}

/** The rounded bottom of an open group's card. */
@Composable
private fun GroupFooter(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .background(Palette.surface),
    )
}

/** One version of the changelog: its name and its lines. */
private data class Release(val version: String, val lines: List<String>)

/** The app's changelog from its assets, newest version first. */
private fun readChangelog(context: Context): List<Release> = try {
    readAssetText(context, "changelog.txt")
        .split(Regex("""\n\s*\n"""))
        .mapNotNull { block ->
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) null else Release(lines.first().trim(), lines.drop(1))
        }
} catch (e: Exception) {
    emptyList()
}

/** «Что нового?»: fixes and additions version by version, the installed one marked. */
@Composable
private fun ChangesDialog(onClose: () -> Unit) {
    val context = platformContext()
    val releases = remember { readChangelog(context) }
    val installed = remember { "v" + Session.appVersion(context) }
    MoonDialog(
        onDismissRequest = onClose,
        title = { Text("Что нового") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(releases.size) { at ->
                    val release = releases[at]
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            release.version + if (release.version == installed) " — установлена" else "",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (release.version == installed) Palette.accent else Palette.text,
                        )
                        for (line in release.lines) {
                            Text(line, style = MaterialTheme.typography.bodySmall, color = Palette.text)
                        }
                    }
                }
                if (releases.isEmpty()) item { Text("Список изменений не найден.") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

/** Periods the log is exported for. */
private val LOG_PERIODS = listOf(
    3_600_000L to "последний час",
    6 * 3_600_000L to "6 часов",
    86_400_000L to "сутки",
    3 * 86_400_000L to "3 суток",
    10 * 86_400_000L to "10 суток (всё, что хранится)",
)

/** A file or a setting with its state and one button. */
@Composable
private fun FileLine(title: String, state: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Palette.text)
            Text(state, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
        }
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

/**
 * Setting, changing or removing the app's password: changing and removing ask for the current
 * one first, and removing it switches the fingerprint and face off too.
 */
@Composable
private fun LockPasswordDialog(mode: String, onClose: () -> Unit) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val needsCurrent = mode != "set"
    val needsNew = mode != "remove"
    val field: @Composable (String, String, (String) -> Unit) -> Unit = { label, value, onChange ->
        OutlinedTextField(
            value = value,
            onValueChange = {
                onChange(it)
                error = null
            },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    MoonDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                when (mode) {
                    "set" -> "Пароль на вход"
                    "change" -> "Сменить пароль на вход"
                    else -> "Убрать пароль на вход"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsCurrent) field("Текущий пароль", current) { current = it }
                if (needsNew) {
                    field("Новый пароль (от ${AppLock.MIN_PASSWORD} символов)", fresh) { fresh = it }
                    field("Новый пароль ещё раз", repeat) { repeat = it }
                }
                if (mode == "remove") {
                    Text("Вход по отпечатку и лицу тоже выключится.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = Palette.red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (needsNew && fresh.length < AppLock.MIN_PASSWORD) {
                        error = "Не короче ${AppLock.MIN_PASSWORD} символов"
                        return@TextButton
                    }
                    if (needsNew && fresh != repeat) {
                        error = "Пароли не совпадают"
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val ok = !needsCurrent || withContext(Dispatchers.Default) { AppLock.verify(context, current) }
                        if (!ok) {
                            busy = false
                            error = "Неверный текущий пароль"
                            return@launch
                        }
                        withContext(Dispatchers.Default) {
                            AppLock.setPassword(context, if (needsNew) fresh else null)
                        }
                        busy = false
                        onClose()
                    }
                },
            ) { Text(if (mode == "remove") "Убрать" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

/** One sound: its name, a button that plays it, and for the user's own a delete button. */
@Composable
private fun SoundRow(label: String, onPlay: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onPlay) { Text("▶") }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Удалить", color = Palette.red) }
        }
    }
}

/** A section heading that folds the list under it: the long list of cores. */
@Composable
private fun FoldHeading(text: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Palette.textDim,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text("$count", style = MaterialTheme.typography.labelLarge, color = Palette.textDim)
            Spacer(Modifier.width(6.dp))
        }
        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium, color = Palette.textDim)
    }
}

/**
 * Adding a core by its key: the key goes to the bridge, which connects to the core as the
 * terminal does and writes it into `servers.enc` only once it answered.
 */
@Composable
internal fun AddCoreDialog(snapshot: Snapshot?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var started by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val adding = snapshot?.adding?.takeIf { it.core == started }
    val checking = started != null && (adding == null || adding.state == "checking")
    MoonDialog(
        onDismissRequest = { if (!checking) onClose() },
        title = { Text("Добавить подключение к ядру") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (started == null) {
                    Text(
                        "Вставьте ключ ядра из Moonbot — тот же, что вставляют в MoonTerminal. " +
                            "Приложение подключится к ядру и сохранит его в servers.enc, только если " +
                            "ядро ответит.",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.textDim,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            error = null
                        },
                        label = { Text("Ключ ядра") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = Palette.red, style = MaterialTheme.typography.bodySmall) }
                } else {
                    val title = adding?.name ?: name
                    when (adding?.state) {
                        null, "checking" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Проверяю соединение с ядром «$title»…" +
                                    adding?.text?.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty(),
                            )
                        }
                        "saved" -> Text("Ядро «$title» подключено и сохранено в servers.enc. Оно уже на главном экране.")
                        "not_saved" -> Text(
                            "Ядро «$title» подключилось, но записать servers.enc не удалось: ${adding?.text.orEmpty()}. " +
                                "Оно работает до следующего подключения.",
                        )
                        else -> Text(
                            "Ядро не ответило за 45 секунд" +
                                adding?.text?.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty() +
                                ". Ничего не сохранено: проверьте ключ и что ядро запущено.",
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                started == null -> TextButton(
                    enabled = key.isNotBlank() && !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            val reply = Session.call(
                                JSONObject().put("op", "add_core").put("key", key.trim()).put("name", name.trim()),
                            )
                            sending = false
                            if (reply.ok) {
                                started = reply.json.optLong("core")
                                name = reply.json.optString("name", name)
                            } else {
                                error = when (reply.code) {
                                    "bad_key" -> "Это не ключ Moonbot, или в нём нет адреса ядра."
                                    "exists" -> "Ядро с этим ключом уже есть в списке."
                                    "busy" -> "Другое ядро ещё проверяется, подождите."
                                    "no_servers_file" -> "Ядра добавляются в servers.enc: подключитесь с ним."
                                    else -> Session.describeError(reply)
                                }
                            }
                        }
                    },
                ) { Text("Подтвердить") }
                !checking -> TextButton(onClick = onClose) { Text("Готово") }
            }
        },
        dismissButton = {
            if (started == null) TextButton(onClick = onClose) { Text("Отмена") }
        },
    )
}

/** The colours a core can take in the app, besides the terminal's own. */
private val coreColors = listOf(
    "#e5645f", "#f0a33c", "#e8d341", "#4fc38a", "#3cb4c8", "#4f8ee5",
    "#8f6ae5", "#d95fc0", "#c8c8c8", "#8a8f99",
)

/** One core: its colour, which a tap changes, and its switch. */
@Composable
private fun CoreRow(core: Core, override: AppPrefs.CoreOverride?, onChange: (AppPrefs.CoreOverride) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val color = override?.color ?: core.color
    val on = override?.active ?: core.enabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parseHex(color) ?: Palette.grey)
                .clickable { picking = true },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(core.name, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(core.exchange.ifEmpty { "биржа не известна" })
                    if (core.group.isNotEmpty()) append(" · группа ${core.group}")
                    if (core.enabled && !core.active) append(" · группа выключена в терминале")
                },
                style = MaterialTheme.typography.labelSmall,
                color = Palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = { onChange((override ?: AppPrefs.CoreOverride()).copy(active = it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent),
        )
    }
    if (picking) {
        MoonDialog(
            onDismissRequest = { picking = false },
            title = { Text("Цвет: ${core.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in coreColors.chunked(5)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (hex in row) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(parseHex(hex) ?: Palette.grey)
                                        .clickable {
                                            picking = false
                                            onChange((override ?: AppPrefs.CoreOverride()).copy(color = hex))
                                        },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    onChange((override ?: AppPrefs.CoreOverride()).copy(color = null))
                }) { Text("Как в терминале") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        )
    }
}

/** `#rrggbb` as a colour. */
internal fun parseHex(text: String): Color? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}

/** Writes the two files into a folder the user picked; a line saying what came of it. */
private fun exportToFolder(context: Context, tree: PickedFolder): String {
    val export = Session.exportFiles(context).getOrElse { return it.message ?: "Не удалось экспортировать" }
    return try {
        val files = listOf(
            "servers.enc" to readFileBytes(export.serversPath),
            "settings.toml" to export.settings.encodeToByteArray(),
        )
        for ((name, bytes) in files) {
            writeIntoFolder(context, tree, name, bytes, "application/octet-stream")?.let { return it }
        }
        "Сохранено: servers.enc и settings.toml"
    } catch (e: Exception) {
        "Не удалось сохранить: ${e.message}"
    }
}

/** Hands the two files to an app of the user's choice; a line only when it failed. */
private fun shareExport(context: Context): String? {
    val export = Session.exportFiles(context).getOrElse { return it.message ?: "Не удалось экспортировать" }
    return try {
        val dir = Platform.cacheRoot(context) + "/export"
        Platform.mkdirs(dir)
        val servers = "$dir/servers.enc"
        val settings = "$dir/settings.toml"
        writeFileBytes(servers, readFileBytes(export.serversPath))
        writeFileText(settings, export.settings)
        shareFiles(context, listOf(servers, settings), "application/octet-stream", "Настройки MoonMobile")
    } catch (e: Exception) {
        "Не удалось отправить: ${e.message}"
    }
}

private fun Set<String>.toggled(key: String, on: Boolean): Set<String> = if (on) this + key else this - key

/** The exchange a core trades on as the push section groups strategies: `Binance Futures`. */
private fun exchangeGroupTitle(core: Core): String {
    val kind = when (core.kind) {
        "spot" -> " Spot"
        "futures", "quarterly" -> " Futures"
        else -> ""
    }
    return exchangeTitle(core) + kind
}

/**
 * An exchange in the push section's strategy list: a tap opens or folds its strategies, the box
 * ticks or clears all of them at once.
 */
@Composable
private fun ExchangeGroupRow(
    title: String,
    brand: String,
    chosen: Int,
    total: Int,
    open: Boolean,
    onOpen: () -> Unit,
    onAll: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surfaceHigh)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▾" else "▸", color = Palette.textDim, modifier = Modifier.width(16.dp))
        brandLogo(brand)?.let { logo ->
            Image(
                painter = imagePainter(logo),
                contentDescription = null,
                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = Palette.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (total == 0) "стратегий пока нет" else "выбрано $chosen из $total",
                style = MaterialTheme.typography.labelMedium,
                color = if (chosen > 0) Palette.accent else Palette.textDim,
            )
        }
        Checkbox(
            checked = total > 0 && chosen == total,
            onCheckedChange = { onAll(it) },
            enabled = total > 0,
            colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Palette.textDim,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** A smaller heading inside a section. */
/**
 * The interface's scale against a phone's: − and + step through [uiScaleSteps]; the tick below
 * goes back to the scale picked by the screen.
 */
@Composable
private fun ScaleRow(chosen: Float?, auto: Float, onChange: (Float?) -> Unit) {
    val current = chosen ?: auto
    val smaller = uiScaleSteps.lastOrNull { it < current - 0.01f }
    val larger = uiScaleSteps.firstOrNull { it > current + 0.01f }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { smaller?.let(onChange) }, enabled = smaller != null) { Text("−") }
            Text(
                percent(current),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { larger?.let(onChange) }, enabled = larger != null) { Text("+") }
        }
        Choice("Подбирать по экрану (${percent(auto)})", selected = chosen == null) { onChange(null) }
    }
}

/**
 * The home-screen widget: how to place it, and when its day starts over — the day's trades are
 * counted from this time to the same time next day.
 */
@Composable
private fun WidgetSettings() {
    val context = platformContext()
    var minutes by remember { mutableStateOf(WidgetFeed.dayEndMinutes(context)) }
    val set: (Int) -> Unit = { next ->
        minutes = (next + 1440) % 1440
        WidgetFeed.setDayEndMinutes(context, minutes)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(HomeWidget.howToPlace, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
        val status = remember { HomeWidget.status() }
        if (status != null) {
            Text(status, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
        }
        Text("Сутки для сделок начинаются в", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { set(minutes - 30) }) { Text("−30 мин") }
            Text(
                clockText(minutes),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { set(minutes + 30) }) { Text("+30 мин") }
        }
    }
}

/** `07:30` for 450 minutes. */
private fun clockText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "${if (h < 10) "0" else ""}$h:${if (m < 10) "0" else ""}$m"
}

/** `130%` for 1.3. */
private fun percent(scale: Float): String = "${(scale * 100).roundToInt()}%"

@Composable
private fun SubHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Palette.textDim,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun Note(text: String, indent: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.textDim,
        modifier = Modifier.padding(start = if (indent) 56.dp else 0.dp, bottom = 4.dp),
    )
}

@Composable
private fun Check(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    hint: String? = null,
    indent: Boolean = false,
    deep: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = if (deep) 40.dp else if (indent) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onChange(it) },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
        )
        Column {
            Text(
                label,
                color = if (enabled) Palette.text else Palette.textDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
            }
        }
    }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    indent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (indent) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = Palette.accent),
        )
        Text(label, color = if (enabled) Palette.text else Palette.textDim)
    }
}
