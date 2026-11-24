package app.moonmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.moonmobile.core.AppState
import app.moonmobile.core.Phase
import app.moonmobile.core.Session

/**
 * What the app shows before it is connected. With no `servers.enc` yet: start a list of cores
 * on the phone, or bring the terminal's. With one: only the password, when it is not remembered,
 * or the reason the last connect failed. With the password remembered the app connects by
 * itself and this is only a moment's "connecting".
 */
@Composable
fun SetupScreen(state: AppState) {
    val context = platformContext()
    val busy = state.phase == Phase.CONNECTING
    // The result does not matter: without the permission the session still runs, only its
    // notification stays out of the shade.
    val askNotifications = rememberNotificationPermission { }
    val ensureNotifications = { askNotifications.launch(null) }
    val connectingByItself = state.hasServersFile && state.savedPassword && Session.autoConnect && state.error == null

    // In the logo's style, like the lock screen.
    NeonScreen {
        Text(
            "Прямое подключение к вашим ядрам Moonbot, без сервера и бота.",
            style = MaterialTheme.typography.bodyMedium,
            color = Neon.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            busy || connectingByItself -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Подключаюсь к ядрам…", style = MaterialTheme.typography.bodyMedium, color = Neon.text)
            }
            !state.hasServersFile -> Welcome(state, ensureNotifications)
            else -> Login(state, ensureNotifications)
        }
        val error = state.error
        if (error != null && !busy) {
            Text(error, color = Neon.red, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** No list of cores yet: make one here, or bring the terminal's. */
@Composable
private fun Welcome(state: AppState, ensureNotifications: () -> Unit) {
    val context = platformContext()
    var way by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var keep by rememberSaveable { mutableStateOf(true) }
    val pickServers = rememberDocumentPicker { uri ->
        if (uri != null) Session.importServers(context, uri)
    }
    val pickSettings = rememberDocumentPicker { uri ->
        if (uri != null) Session.importSettings(context, uri)
    }

    WayCard(
        title = "Создать список ядер",
        text = "Для тех, у кого нет MoonTerminal: задайте пароль, и приложение заведёт свой " +
            "зашифрованный servers.enc. Ядра добавляются по их ключам из Moonbot.",
        open = way == "create",
        onOpen = { way = if (way == "create") "" else "create" },
    ) {
        PasswordField("Пароль servers.enc (от 6 символов)", password) { password = it }
        PasswordField("Пароль ещё раз", repeat) { repeat = it }
        if (repeat.isNotEmpty() && repeat != password) {
            Text("Пароли не совпадают", color = Neon.red, style = MaterialTheme.typography.bodySmall)
        }
        KeepRow(keep) { keep = it }
        Text(
            "Пароль нужен, чтобы перенести список на другой телефон или в MoonTerminal. " +
                "Восстановить его нельзя.",
            style = MaterialTheme.typography.labelMedium,
            color = Neon.dim,
        )
        Button(
            onClick = {
                ensureNotifications()
                Session.createServers(context, password, keep)
            },
            enabled = password.length >= 6 && password == repeat,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Создать и подключиться") }
    }

    WayCard(
        title = "Перенести из MoonTerminal",
        text = "В MoonTerminal на ПК задайте пароль: Настройки → Безопасность → «Шифрование файла " +
            "подключений», сохраните. Перенесите на телефон servers.enc и, по желанию, settings.toml.",
        open = way == "import",
        onOpen = { way = if (way == "import") "" else "import" },
    ) {
        FileRow("servers.enc", state.hasServersFile) { pickServers.launch(arrayOf("*/*")) }
        FileRow("settings.toml (необязательно)", state.hasSettingsFile) { pickSettings.launch(arrayOf("*/*")) }
        Text(
            "Когда servers.enc выбран, приложение спросит его пароль.",
            style = MaterialTheme.typography.labelMedium,
            color = Neon.dim,
        )
    }
}

/** A list of cores is on the phone, but its password is not remembered, or the last try failed. */
@Composable
private fun Login(state: AppState, ensureNotifications: () -> Unit) {
    val context = platformContext()
    var password by remember { mutableStateOf("") }
    var keep by rememberSaveable { mutableStateOf(true) }
    val pickServers = rememberDocumentPicker { uri ->
        if (uri != null) Session.importServers(context, uri)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Neon.card, contentColor = Neon.text),
        border = BorderStroke(1.dp, Neon.edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.savedPassword) {
                Text("Пароль servers.enc сохранён на телефоне.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        ensureNotifications()
                        Session.autoConnect = true
                        Session.connectSaved(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Подключиться") }
                TextButton(onClick = { Session.forgetPassword(context) }) { Text("Ввести пароль заново") }
            } else {
                PasswordField("Пароль servers.enc", password) { password = it }
                KeepRow(keep) { keep = it }
                Button(
                    onClick = {
                        ensureNotifications()
                        Session.connect(context, password, keep)
                        password = ""
                    },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Подключиться") }
            }
            TextButton(onClick = { pickServers.launch(arrayOf("*/*")) }) { Text("Выбрать другой servers.enc") }
        }
    }
}

@Composable
private fun WayCard(title: String, text: String, open: Boolean, onOpen: () -> Unit, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Neon.card, contentColor = Neon.text),
        border = BorderStroke(1.dp, Neon.edge),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(if (open) "▾" else "▸", color = Neon.dim)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = Neon.dim)
            if (open) {
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeepRow(keep: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = keep, onCheckedChange = onChange)
        Column {
            Text("Запомнить на этом телефоне", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Тогда приложение будет подключаться само, сразу при запуске.",
                style = MaterialTheme.typography.labelMedium,
                color = Neon.dim,
            )
        }
    }
}

@Composable
private fun FileRow(label: String, present: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (present) "загружен" else "не выбран",
                style = MaterialTheme.typography.labelMedium,
                color = if (present) Neon.green else Neon.dim,
            )
        }
        OutlinedButton(onClick = onPick) { Text(if (present) "Заменить" else "Выбрать") }
    }
}
