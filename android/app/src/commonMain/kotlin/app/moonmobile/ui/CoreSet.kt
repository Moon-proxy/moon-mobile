package app.moonmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moonmobile.core.AppPrefs
import app.moonmobile.core.Core
import app.moonmobile.core.Session
import app.moonmobile.core.Snapshot
import kotlinx.coroutines.launch
import app.moonmobile.json.JSONObject

/**
 * "Остановка стратегий": one switch that stops the strategies of a chosen set of cores, and
 * switched off starts again exactly the cores it stopped. The set is whole exchanges or single
 * cores, chosen under the switch.
 */
@Composable
internal fun HaltCard(snapshot: Snapshot, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(AppPrefs.haltOn(context)) }
    var chosen by remember { mutableStateOf(AppPrefs.haltCores(context)) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val active = snapshot.cores.filter { it.active }
    val targets = active.filter { chosen?.contains(it.id) ?: true }

    val toggle: (Boolean) -> Unit = { turnOn ->
        busy = true
        scope.launch {
            val problems = ArrayList<String>()
            if (turnOn) {
                val stopped = HashSet<Long>()
                for (core in targets) {
                    when {
                        core.run.trading != true -> {}
                        !core.run.online -> problems += "${core.name}: нет связи"
                        else -> {
                            val reply = Session.call(run(core.id, false))
                            if (reply.ok) stopped += core.id else problems += "${core.name}: ${Session.describeError(reply)}"
                        }
                    }
                }
                AppPrefs.setHalted(context, AppPrefs.halted(context) + stopped)
                AppPrefs.setHaltOn(context, true)
                on = true
            } else {
                val halted = AppPrefs.halted(context)
                val left = HashSet<Long>()
                for (id in halted) {
                    val core = active.firstOrNull { it.id == id } ?: continue
                    when {
                        core.run.trading == true -> {}
                        !core.run.online -> {
                            left += id
                            problems += "${core.name}: нет связи"
                        }
                        else -> {
                            val reply = Session.call(run(core.id, true))
                            if (!reply.ok) {
                                left += id
                                problems += "${core.name}: ${Session.describeError(reply)}"
                            }
                        }
                    }
                }
                // A core that could not be started stays remembered for the next try.
                AppPrefs.setHalted(context, left)
                AppPrefs.setHaltOn(context, false)
                on = false
            }
            busy = false
            if (problems.isNotEmpty()) snackbar.showSnackbar(problems.joinToString("; "))
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (on) Palette.red.copy(alpha = 0.16f) else Palette.surface,
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Остановка стратегий",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Text(
                    when {
                        on -> "Остановлено: ${AppPrefs.halted(context).size}"
                        chosen == null -> "Ядра: все ▾"
                        else -> "Ядра: ${targets.size} из ${active.size} ▾"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) Palette.red else Palette.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = !busy) { editing = true },
                )
            }
            PowerRocker(on = on, enabled = !busy, onChange = toggle)
        }
    }

    if (editing) {
        CoreSetDialog(
            title = "Ядра для остановки стратегий",
            cores = active,
            chosen = chosen,
            onDismiss = { editing = false },
            onSave = {
                editing = false
                chosen = it
                AppPrefs.setHaltCores(context, it)
            },
        )
    }
}

/**
 * A power switch on its side: I on top switches the halt on (strategies stop), O below switches
 * it off (they start again). The lit half is where it stands.
 */
@Composable
private fun PowerRocker(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .width(34.dp)
            .clip(shape)
            .border(1.dp, Palette.textDim.copy(alpha = 0.5f), shape),
    ) {
        RockerHalf("I", lit = on, litColor = Palette.red, enabled = enabled && !on) { onChange(true) }
        RockerHalf("O", lit = !on, litColor = Palette.surfaceHigh, enabled = enabled && on) { onChange(false) }
    }
}

@Composable
private fun RockerHalf(mark: String, lit: Boolean, litColor: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(if (lit) litColor else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = when {
                lit && mark == "I" -> Color.White
                lit -> Palette.text
                else -> Palette.textDim
            },
        )
    }
}

private fun run(core: Long, on: Boolean): JSONObject =
    JSONObject().put("op", "run").put("core", core).put("switch", "trading").put("on", on)

/**
 * Choosing a set of cores: grouped by exchange, a whole exchange at once or core by core.
 * Saving every core saves `null`, which keeps cores added later in the set.
 */
@Composable
internal fun CoreSetDialog(
    title: String,
    cores: List<Core>,
    chosen: Set<Long>?,
    onDismiss: () -> Unit,
    onSave: (Set<Long>?) -> Unit,
) {
    var picked by remember { mutableStateOf(chosen ?: cores.map { it.id }.toSet()) }
    val groups = cores.groupBy { it.exchange.ifEmpty { "Биржа не известна" } }
    MoonDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                for ((exchange, members) in groups) {
                    item {
                        val count = members.count { it.id in picked }
                        val state = when (count) {
                            0 -> ToggleableState.Off
                            members.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        val flip = {
                            val ids = members.map { it.id }.toSet()
                            picked = if (state == ToggleableState.On) picked - ids else picked + ids
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = flip),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(
                                state = state,
                                onClick = flip,
                                colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                            )
                            Text(exchange, fontWeight = FontWeight.SemiBold, color = Palette.accent)
                        }
                    }
                    for (core in members) {
                        item {
                            val on = core.id in picked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { picked = if (on) picked - core.id else picked + core.id }
                                    .padding(start = 24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = on,
                                    onCheckedChange = { picked = if (it) picked + core.id else picked - core.id },
                                    colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                                )
                                Text(core.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(if (cores.all { it.id in picked }) null else picked) },
                enabled = picked.isNotEmpty(),
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { picked = cores.map { it.id }.toSet() }) { Text("Все") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
