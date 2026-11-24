package app.moonmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.moonmobile.core.Reply
import app.moonmobile.core.Snapshot
import app.moonmobile.core.StationClient
import app.moonmobile.core.StationClient.Address
import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject
import app.moonmobile.platform.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Settings → Станция: a station on the user's Linux server, set up from the phone the way
 * MoonTerminal sets it up (its own code in the bridge), and its upkeep: cores, the Telegram bot,
 * the trade tape, updates. Every server step is a job the bridge runs; its lines show below.
 */

/** The job running, or the last one: what it said and how it ended. */
private class StationJob {
    var running by mutableStateOf(false)
    var lines by mutableStateOf<List<String>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf<String?>(null)
}

/** Starts a job with [start] and follows it to its end: its result, or null with the error shown. */
private suspend fun follow(job: StationJob, done: String? = null, start: suspend () -> Reply): JSONObject? {
    job.running = true
    job.error = null
    job.done = null
    job.lines = emptyList()
    val started = start()
    if (!started.ok) {
        job.running = false
        job.error = started.message
        return null
    }
    while (true) {
        delay(500L)
        val state = StationClient.job()
        job.lines = state.optJSONArray("lines")?.strings().orEmpty()
        if (!state.optBoolean("running", false)) {
            job.running = false
            if (state.has("error")) {
                job.error = state.optString("error")
                return null
            }
            job.done = done
            return state.optJSONObject("result") ?: JSONObject()
        }
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

@Composable
internal fun StationPanel(snapshot: Snapshot?) {
    val context = platformContext()
    val job = remember { StationJob() }
    var saved by remember { mutableStateOf(StationClient.saved(context)) }
    var keyError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot != null) {
        if (snapshot != null) keyError = StationClient.ensureKey(context)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val address = saved
        when {
            snapshot == null -> Hint("Сначала подключитесь к ядрам: станция получает их ключи из этого телефона.")
            address == null -> InstallWizard(snapshot, job) { installed ->
                StationClient.save(context, installed)
                saved = installed
            }
            else -> StationView(address, snapshot, job) {
                StationClient.save(context, null)
                saved = null
            }
        }
        keyError?.let { Problem(it) }
        JobLog(job)
    }
}

@Composable
private fun InstallWizard(snapshot: Snapshot, job: StationJob, onInstalled: (Address) -> Unit) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    var addressText by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var byKey by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }
    var keyName by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    // The server's key as it presented it, confirmed by the user, and what the check found.
    var probed by remember { mutableStateOf<Pair<Address, String>?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<JSONObject?>(null) }
    var understood by remember { mutableStateOf(false) }
    var picked by remember(snapshot.cores) {
        mutableStateOf(snapshot.cores.filter { it.enabled }.map { it.id }.toSet())
    }
    val pickKey = rememberDocumentPicker { file ->
        if (file != null) {
            val temp = Platform.cacheRoot(context) + "/station_login_key"
            val problem = Platform.importFile(context, file, temp)
            if (problem == null) {
                keyText = runCatching { Platform.readText(temp) }.getOrDefault("")
                keyName = "ключ выбран"
                runCatching { writeFileText(temp, "") }
            } else {
                job.error = problem
            }
        }
    }
    val login = {
        JSONObject()
            .put("user", user.trim())
            .put("password", password)
            .put("key", if (byKey) keyText else "")
            .put("passphrase", if (byKey) passphrase else "")
    }
    // Anything changed above the check starts the steps over.
    val reset = {
        probed = null
        confirmed = false
        check = null
        understood = false
    }

    Hint(
        "Станция работает на вашем Linux-сервере круглосуточно: Telegram-бот, отчёты и лента сделок — " +
            "даже когда телефон выключен. Ставится так же, как из MoonTerminal: служба из последнего " +
            "релиза MoonTerminal (с проверкой SHA-256). Нужен Debian или Ubuntu с systemd 250+; лучше " +
            "отдельный сервер только под станцию.",
    )
    OutlinedTextField(
        value = addressText,
        onValueChange = {
            addressText = it
            reset()
        },
        label = { Text("IP сервера (или IP:порт)") },
        singleLine = true,
        enabled = !job.running,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = user,
        onValueChange = {
            user = it
            reset()
        },
        label = { Text("Логин") },
        singleLine = true,
        enabled = !job.running,
        modifier = Modifier.fillMaxWidth(),
    )
    Tick("Вход по SSH-ключу", byKey, enabled = !job.running) {
        byKey = it
        reset()
    }
    if (byKey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }, enabled = !job.running) {
                Text("Файл ключа…")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                keyName.ifEmpty { "не выбран" },
                style = MaterialTheme.typography.labelMedium,
                color = Palette.textDim,
            )
        }
        Secret("Пароль ключа (если ключ зашифрован)", passphrase, !job.running) { passphrase = it }
    }
    Secret(
        if (byKey) "Пароль sudo (если вход не под root)" else "Пароль",
        password,
        !job.running,
    ) {
        password = it
        reset()
    }
    Hint(
        "Доступ от провайдера нужен один раз и нигде не сохраняется. После установки вход по паролю и " +
            "вход root по SSH закрываются; на сервер заходит администратор moon по ключу этого телефона, " +
            "а если вы вошли своим ключом — и по нему (так же зайдут MobaXterm и MoonTerminal).",
    )

    val address = StationClient.parseAddress(addressText)
    val ready = address != null && user.isNotBlank() &&
        (if (byKey) keyText.isNotBlank() else password.isNotEmpty())
    val seen = probed
    if (seen == null) {
        Button(
            onClick = {
                val target = address ?: return@Button
                scope.launch {
                    val result = follow(job) { StationClient.start("station_probe", target) }
                    result?.optString("fingerprint")?.takeIf { it.isNotEmpty() }?.let { probed = target to it }
                }
            },
            enabled = ready && !job.running,
        ) { Text("Проверить сервер") }
        return
    }

    Text("Отпечаток ключа сервера:", style = MaterialTheme.typography.bodyMedium)
    Text(seen.second, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    if (!confirmed) {
        Hint("Если можете, сверьте его с консолью провайдера. Подтвердите — и приложение войдёт на сервер и проверит его, ничего не меняя.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    confirmed = true
                    scope.launch {
                        check = follow(job) {
                            StationClient.start("station_check", seen.first) {
                                put("login", login())
                                put("fingerprint", seen.second)
                            }
                        }
                    }
                },
                enabled = !job.running,
            ) { Text("Отпечаток верный") }
            TextButton(onClick = reset, enabled = !job.running) { Text("Отмена") }
        }
        return
    }

    val found = check ?: return
    HorizontalDivider()
    Text("Что изменится на сервере", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    val ports = found.optJSONArray("ports")?.strings().orEmpty()
    when {
        found.optBoolean("ufw_active", false) -> Hint("• Межсетевой экран уже включён: в него добавятся только правила для SSH.")
        found.optBoolean("ports_unknown", false) -> Problem(
            "• Не удалось перечислить используемые порты: межсетевой экран закроет все входящие порты, кроме SSH.",
        )
        ports.isNotEmpty() -> Problem(
            "• Межсетевой экран закроет порты, которые сейчас используются: ${ports.joinToString(", ")}. " +
                "Сервисы на них перестанут быть доступны снаружи.",
        )
        else -> Hint("• Включится межсетевой экран; других открытых портов, кроме SSH, на сервере нет.")
    }
    Hint("• Вход только по ключу; пароль и root по SSH закрываются.")
    Hint("• Появится администратор moon и служба станции.")
    Text("Ядра для станции", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    for (core in snapshot.cores) {
        Tick(core.name, core.id in picked, enabled = !job.running) { on ->
            picked = if (on) picked + core.id else picked - core.id
        }
    }
    Tick("Понимаю: сервер будет закрыт так, как написано выше", understood, enabled = !job.running) { understood = it }
    Button(
        onClick = {
            scope.launch {
                val result = follow(job, done = "Станция установлена.") {
                    StationClient.start("station_install", seen.first) {
                        put("login", login())
                        put("fingerprint", seen.second)
                        put("cores", JSONArray().also { list -> snapshot.cores.filter { it.id in picked }.forEach { list.put(it.id) } })
                    }
                }
                if (result != null) {
                    password = ""
                    passphrase = ""
                    keyText = ""
                    onInstalled(seen.first)
                }
            }
        },
        enabled = understood && picked.isNotEmpty() && !job.running,
    ) { Text("Установить станцию") }
}

@Composable
private fun StationView(address: Address, snapshot: Snapshot, job: StationJob, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var token by remember { mutableStateOf("") }
    var margin by remember { mutableStateOf("") }
    var longMin by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val refresh: () -> Unit = {
        scope.launch {
            follow(job) { StationClient.start("station_status", address) }?.let { read ->
                status = read
                read.optJSONObject("tape")?.let { tape ->
                    margin = tape.optInt("margin_s", 0).toString()
                    longMin = tape.optInt("long_position_min", 0).toString()
                }
            }
        }
    }
    LaunchedEffect(address) { refresh() }
    // One step then a fresh status, so the screen shows what the step did.
    val step: (String, String?, JSONObject.() -> Unit) -> Unit = { op, done, fill ->
        scope.launch {
            if (follow(job, done) { StationClient.start(op, address, fill) } != null) {
                follow(job, done) { StationClient.start("station_status", address) }?.let { status = it }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Станция ${address.text}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = refresh, enabled = !job.running) { Text("Обновить") }
    }
    val read = status
    if (read != null) {
        val state = when {
            !read.optBoolean("running", false) -> "остановлена"
            !read.optBoolean("answers", false) -> "запускается или не отвечает"
            else -> "на связи"
        }
        Hint("Состояние: $state")
        read.optString("version").takeIf { it.isNotEmpty() && it != "null" }?.let { Hint("Версия службы: $it") }
        if (read.has("cores_total") && !read.isNull("cores_total")) {
            Hint("Ядра на связи: ${read.optInt("cores_ready", 0)} из ${read.optInt("cores_total", 0)}")
        }
        read.optJSONArray("cores")?.let { cores ->
            val names = (0 until cores.length()).mapNotNull { cores.optJSONObject(it)?.optString("name") }
            if (names.isNotEmpty()) Hint("На станции: ${names.joinToString(", ")}")
        }
    }

    // Cores.
    SubTitle("Ядра")
    if (!sending) {
        OutlinedButton(onClick = { sending = true }, enabled = !job.running) { Text("Отправить ядра на станцию…") }
    } else {
        var picked by remember(snapshot.cores) { mutableStateOf(snapshot.cores.filter { it.enabled }.map { it.id }.toSet()) }
        for (core in snapshot.cores) {
            Tick(core.name, core.id in picked, enabled = !job.running) { on ->
                picked = if (on) picked + core.id else picked - core.id
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    sending = false
                    step("station_cores", "Ядра отправлены на станцию.") {
                        put("cores", JSONArray().also { list -> snapshot.cores.filter { it.id in picked }.forEach { list.put(it.id) } })
                    }
                },
                enabled = picked.isNotEmpty() && !job.running,
            ) { Text("Отправить") }
            TextButton(onClick = { sending = false }) { Text("Отмена") }
        }
    }

    // The Telegram bot.
    SubTitle("Telegram-бот")
    if (read != null) {
        val bot = read.optJSONObject("bot")
        Hint(
            when {
                bot == null && read.optBoolean("has_token", false) -> "Токен есть, бот ещё не запущен."
                bot == null -> "Бота на станции нет."
                bot.optBoolean("paired", false) -> "Бот работает, привязано чатов: ${bot.optInt("chats", 0)}."
                bot.optBoolean("polling", false) -> "Бот ждёт привязки чата."
                else -> "Бот: ${bot.optString("status")}."
            },
        )
        read.optString("pairing_code").takeIf { it.isNotEmpty() && it != "null" }?.let { code ->
            Text("Отправьте боту: /pair $code", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Secret("Токен бота (от @BotFather)", token, !job.running) { token = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val given = token.trim()
                token = ""
                step("station_bot", "Токен передан на станцию.") { put("token", given) }
            },
            enabled = token.isNotBlank() && !job.running,
        ) { Text("Передать токен") }
        TextButton(onClick = { confirm = "bot_off" }, enabled = !job.running && read?.optBoolean("has_token", false) == true) {
            Text("Снять бота со станции")
        }
    }

    // The trade tape and updates.
    SubTitle("Лента сделок")
    Hint("Сколько секунд ленты станция хранит вокруг сделки и с какой длительности позиции (в минутах) запись идёт как для долгой.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Number("Секунд вокруг сделки", margin, !job.running, Modifier.weight(1f)) { margin = it }
        Number("Долгая позиция, мин", longMin, !job.running, Modifier.weight(1f)) { longMin = it }
    }
    val marginS = margin.toIntOrNull()
    val longM = longMin.toIntOrNull()
    OutlinedButton(
        onClick = {
            step("station_tape", "Окно ленты отправлено.") {
                put("margin_s", marginS ?: 0)
                put("long_position_min", longM ?: 0)
            }
        },
        enabled = marginS != null && longM != null && !job.running,
    ) { Text("Сохранить окно ленты") }
    if (read != null && read.has("auto_update") && !read.isNull("auto_update")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Автообновление службы", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = read.optBoolean("auto_update", false),
                onCheckedChange = { on -> step("station_auto_update", null) { put("on", on) } },
                enabled = !job.running,
            )
        }
    }

    // Upkeep.
    SubTitle("Обслуживание")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { step("station_update", "Запрос обновления выполнен.") {} }, enabled = !job.running) {
            Text("Обновить службу")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { confirm = "forget" }, enabled = !job.running) { Text("Забыть станцию…") }
        TextButton(onClick = { confirm = "remove" }, enabled = !job.running) { Text("Удалить с сервера…", color = Palette.red) }
    }

    when (confirm) {
        "forget" -> Confirm(
            "Забыть станцию?",
            "Приложение перестанет к ней подключаться. Сама станция продолжит работать на сервере.",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            scope.launch {
                app.moonmobile.core.Session.call(
                    JSONObject().put("op", "station_forget")
                        .put("server", JSONObject().put("host", address.host).put("port", address.port)),
                )
                onGone()
            }
        }
        "remove" -> Confirm(
            "Удалить станцию с сервера?",
            "Бот и лента сделок на сервере остановятся, их данные будут удалены. Это нельзя отменить.",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            scope.launch {
                if (follow(job, "Станция удалена с сервера.") { StationClient.start("station_remove", address) } != null) onGone()
            }
        }
        "bot_off" -> Confirm(
            "Снять бота со станции?",
            "Бот на сервере выключится, токен и привязки чатов будут удалены со станции.",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            step("station_bot", "Бот снят со станции.") { put("off", true) }
        }
    }
}

@Composable
private fun JobLog(job: StationJob) {
    if (job.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.accent)
            Spacer(Modifier.width(8.dp))
            Text("Выполняется…", style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
        }
    }
    for (line in job.lines.takeLast(12)) {
        Text("• $line", style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
    }
    job.error?.let { Problem(it) }
    job.done?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.green) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
}

@Composable
private fun Problem(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.red)
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Tick(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Secret(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Number(label: String, value: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun Confirm(title: String, text: String, onDismiss: () -> Unit, onYes: () -> Unit) {
    MoonDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text("Да") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Нет") } },
    )
}
