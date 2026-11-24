package app.moonmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.moonmobile.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.moonmobile.json.JSONObject
import app.moonmobile.text.DateFormat
import app.moonmobile.text.nowMs
import app.moonmobile.platform.PlatformContext as Context
import app.moonmobile.platform.ioDispatcher
import app.moonmobile.platform.Platform
import app.moonmobile.text.timeZoneId

/**
 * The report written to a file for the period and filters on screen: an Excel workbook with real
 * numbers in its cells, or CSV for other programs. The bridge reads the trades and writes the file
 * (rust/src/export.rs), the same on every platform; this side picks where it goes.
 */
internal object ReportExport {
    enum class Format(val title: String, val extension: String, val mime: String) {
        XLSX("Excel (.xlsx)", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        CSV("CSV", "csv", "text/csv"),
    }

    /** The file's name: what it is and the day it was made. */
    fun fileName(format: Format): String =
        "MoonMobile-report-" + DateFormat("yyyy-MM-dd_HH-mm").format(nowMs()) + "." + format.extension

    /** Where in the app's cache the bridge writes a file for sending: its path. */
    fun cacheFile(context: Context, name: String): String {
        val dir = Platform.cacheRoot(context) + "/export"
        Platform.mkdirs(dir)
        return "$dir/$name"
    }
}

/**
 * Export of the report as it is filtered now: Excel or CSV, sent to another app or saved where
 * the user picks.
 */
@Composable
internal fun ExportDialog(
    period: String,
    onClose: () -> Unit,
    /** The report's request as the screen reads it: period, cores and filters. */
    request: () -> JSONObject,
    onNote: (String?) -> Unit,
) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ReportExport.Format.XLSX) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }

    /** The bridge writes the file into the cache; null, with a note, when there is nothing to write. */
    suspend fun build(): String? {
        val file = withContext(ioDispatcher) { ReportExport.cacheFile(context, ReportExport.fileName(format)) }
        val ask = request().apply {
            remove("offset")
            remove("limit")
            put("op", "report_file")
            put("format", format.extension)
            put("path", file)
            put("tz", timeZoneId())
        }
        val reply = Session.call(ask)
        if (!reply.ok) {
            onNote("Не удалось выгрузить: ${Session.describeError(reply)}")
            return null
        }
        val rows = reply.json.optInt("rows", 0)
        if (rows == 0) {
            onNote("За этот период сделок нет — выгружать нечего")
            return null
        }
        onNote("Выгружено сделок: $rows")
        return file
    }

    val saveAs = rememberSaveAs(format.mime) { target ->
        val file = pending
        pending = null
        if (target != null && file != null) {
            scope.launch {
                val problem = withContext(ioDispatcher) { copyToTarget(context, target, file) }
                onNote(problem ?: "Сохранено")
            }
        }
        busy = false
        onClose()
    }

    MoonDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Выгрузить отчёт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Период: $period. Фильтры по монете, стратегии и ядру — как сейчас на экране.")
                for (option in ReportExport.Format.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = format == option, onClick = { format = option })
                        Text(option.title)
                    }
                }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val file = build()
                        if (file == null) {
                            busy = false
                            onClose()
                            return@launch
                        }
                        pending = file
                        saveAs.launch(file.substringAfterLast('/'))
                    }
                }) { Text("Сохранить") }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val file = build()
                        if (file != null) {
                            shareFiles(context, listOf(file), format.mime, "Отчёт MoonMobile")?.let { onNote(it) }
                        }
                        busy = false
                        onClose()
                    }
                }) { Text("Отправить") }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } },
    )
}
