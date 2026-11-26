package app.moonmobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Why the app closed last time, to be copied or photographed, before it starts as usual (as on iOS). */
@Composable
fun CrashScreen(report: String, onDone: () -> Unit) {
    val context = LocalContext.current
    MoonTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Почему MoonMobile закрылся в прошлый раз", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Нажми «Скопировать» и пришли текст, или сделай снимок экрана.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textDim,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.text)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("MoonMobile", report))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    }) { Text("Скопировать") }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onDone) { Text("Продолжить") }
                }
            }
        }
    }
}
