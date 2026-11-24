package app.moonmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moonmobile.core.Updater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.moonmobile.text.fmt

/** What the new version brings and the way to install it: download, check, hand to Android. */
@Composable
actual fun UpdateDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by Updater.update.collectAsState()
    val stage by Updater.stage.collectAsState()
    var busy by remember { mutableStateOf(false) }
    // The right to install is given on Android's own screen: looked at again every second.
    var allowed by remember { mutableStateOf(Updater.mayInstall(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            allowed = Updater.mayInstall(context)
            delay(1000)
        }
    }
    val next = update
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (next != null) "Доступна v${next.version}" else "Обновление") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (next == null) {
                    Text("Установлена последняя версия.")
                } else {
                    val size = if (next.size > 0) " · ${fmt("%.1f", next.size / 1_048_576.0)} МБ" else ""
                    Text("Сейчас: v${app.moonmobile.core.Session.appVersion(context)}$size", color = Palette.textDim)
                    if (next.notes.isNotBlank()) {
                        Text(
                            next.notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    Text(
                        "Android спросит подтверждение установки. Данные и настройки приложения " +
                            "сохранятся; подключение к ядрам переоткроется после установки.",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.textDim,
                    )
                    if (!allowed) {
                        Text(
                            "Сначала разрешите MoonMobile устанавливать приложения: кнопка «Разрешить» " +
                                "откроет нужный экран Android, включите переключатель и вернитесь.",
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.amber,
                        )
                    }
                }
                when (val s = stage) {
                    is Updater.Stage.Downloading -> {
                        val part = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { part }, modifier = Modifier.fillMaxWidth())
                        Text("Скачано ${s.done / 1_048_576} из ${s.total / 1_048_576} МБ", style = MaterialTheme.typography.labelMedium)
                    }
                    is Updater.Stage.Failed -> Text(s.text, color = Palette.red, style = MaterialTheme.typography.labelMedium)
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (next != null) {
                if (!allowed) {
                    TextButton(onClick = { Updater.askInstallRight(context) }) { Text("Разрешить") }
                } else {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                Updater.download(context, next).onSuccess { Updater.install(context, it) }
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Скачиваю…" else "Обновить") }
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Позже") } },
    )
}
