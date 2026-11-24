package app.moonmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moonmobile.core.Core
import app.moonmobile.core.Session
import app.moonmobile.core.StrategyDetails
import app.moonmobile.core.StrategyField
import app.moonmobile.core.parseStrategyDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.moonmobile.json.JSONObject

/**
 * One strategy's fields, laid out by the core's own schema. Edits collect in a draft and go to
 * the core in one command; the screen keeps re-reading the strategy, so a confirmed edit shows
 * up as the core's value changing.
 */
@Composable
fun StrategyEditor(core: Core, strategyId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var details by remember(strategyId) { mutableStateOf<StrategyDetails?>(null) }
    var problem by remember(strategyId) { mutableStateOf<String?>(null) }
    // Field name to the text the user set and has not sent yet.
    var draft by remember(strategyId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editing by remember(strategyId) { mutableStateOf<StrategyField?>(null) }
    var confirming by remember(strategyId) { mutableStateOf(false) }
    var sending by remember(strategyId) { mutableStateOf(false) }
    var notice by remember(strategyId) { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(core.id, strategyId) {
        while (true) {
            val reply = Session.call(
                JSONObject().put("op", "strategy_get").put("core", core.id).put("id", strategyId),
            )
            val body = reply.json.optJSONObject("strategy")
            if (reply.ok && body != null) {
                details = parseStrategyDetails(body)
                problem = null
            } else if (details == null) {
                problem = Session.describeError(reply)
            }
            delay(1500)
        }
    }

    val current = details
    val canEdit = current != null && current.editable && core.run.online

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Стратегии") }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    current?.name ?: "Стратегия",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(current?.kind.orEmpty(), core.name).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val status = when {
            current == null -> problem ?: "Читаю стратегию…"
            !core.run.online -> "Нет связи с ядром — только просмотр."
            !current.editable -> "Ядро ещё не прислало схему полей — только просмотр."
            current.edit == "pending" -> "Правка отправлена, жду подтверждения ядра."
            current.edit == "timed_out" -> "Ядро не подтвердило правку вовремя. Проверьте значения."
            else -> notice
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current?.edit == "timed_out" || (current == null && problem != null)) {
                    Palette.red
                } else {
                    Palette.amber
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            current?.sections?.forEach { section ->
                if (section.title.isNotEmpty()) {
                    item {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Palette.accent,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                }
                items(section.fields) { field ->
                    FieldRow(
                        field = field,
                        drafted = draft[field.name],
                        enabled = canEdit && !sending,
                        onToggle = { on ->
                            val text = if (on) "Yes" else "No"
                            draft = if (isOn(field.value) == on) draft - field.name else draft + (field.name to text)
                        },
                        onEdit = { editing = field },
                    )
                }
            }
        }

        if (draft.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { draft = emptyMap() }, enabled = !sending) { Text("Сбросить") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { confirming = true }, enabled = canEdit && !sending) {
                    Text("Сохранить (${draft.size})")
                }
            }
        }
    }

    val field = editing
    if (field != null) {
        val start = draft[field.name] ?: field.value
        val apply: (String) -> Unit = { text ->
            val clean = text.trim()
            draft = if (clean == field.value) draft - field.name else draft + (field.name to clean)
            editing = null
        }
        if (field.control == "list") {
            ChoiceDialog(field.name, field.options, start, onPick = apply, onClose = { editing = null })
        } else {
            TextDialog(field, start, onDone = apply, onClose = { editing = null })
        }
    }

    if (confirming && current != null) {
        val values = current.sections.flatMap { it.fields }.associate { it.name to it.value }
        MoonDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Отправить правки ядру?") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(draft.entries.toList()) { change ->
                        Text(
                            "${change.key}: ${values[change.key].orEmpty().ifEmpty { "—" }} → ${change.value}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    sending = true
                    val changes = JSONObject()
                    draft.forEach { (name, text) -> changes.put(name, text) }
                    scope.launch {
                        val reply = Session.call(
                            JSONObject()
                                .put("op", "strategy_edit")
                                .put("core", core.id)
                                .put("id", strategyId)
                                .put("changes", changes),
                        )
                        sending = false
                        if (reply.ok) {
                            draft = emptyMap()
                            notice = if (reply.json.optString("result") == "already") {
                                "У ядра уже такие значения."
                            } else {
                                "Отправлено. Значения обновятся, когда ядро подтвердит."
                            }
                        } else {
                            notice = Session.describeError(reply)
                        }
                    }
                }) { Text("Отправить") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Назад") }
            },
        )
    }
}

@Composable
private fun FieldRow(
    field: StrategyField,
    drafted: String?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val isCheckbox = field.control == "checkbox"
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !isCheckbox, onClick = onEdit),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(field.name, style = MaterialTheme.typography.bodyMedium)
                val note = when {
                    drafted != null -> "было: ${shown(field, field.value)}"
                    field.pending != null -> "ожидает: ${shown(field, field.pending)}"
                    else -> null
                }
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isCheckbox) {
                Switch(
                    checked = isOn(drafted ?: field.value),
                    onCheckedChange = onToggle,
                    enabled = enabled,
                )
            } else {
                Text(
                    (drafted ?: field.value).ifEmpty { "—" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (drafted != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (drafted != null) Palette.accent else Palette.text,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** A value as a row shows it: checkboxes read as words. */
private fun shown(field: StrategyField, value: String): String = when {
    field.control == "checkbox" -> if (isOn(value)) "вкл" else "выкл"
    value.isEmpty() -> "—"
    else -> value
}

private fun isOn(text: String): Boolean = when (text.trim().lowercase()) {
    "yes", "true", "on", "1" -> true
    else -> false
}

@Composable
private fun TextDialog(field: StrategyField, start: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember(field.name) { mutableStateOf(start) }
    val keyboard = when (field.typeName) {
        "Double", "Single" -> KeyboardType.Decimal
        "Int32", "Int64", "UInt32", "UInt64", "Byte", "Word" -> KeyboardType.Number
        else -> KeyboardType.Text
    }
    MoonDialog(
        onDismissRequest = onClose,
        title = { Text(field.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "У ядра сейчас: ${field.value.ifEmpty { "—" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.textDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank() || field.typeName == "String") {
                Text("Готово")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Отмена") }
        },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    MoonDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(options) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.equals(selected, ignoreCase = true),
                            onClick = { onPick(option) },
                        )
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) { Text("Отмена") }
        },
    )
}
