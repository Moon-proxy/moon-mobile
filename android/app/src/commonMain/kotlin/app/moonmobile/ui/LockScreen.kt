package app.moonmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.moonmobile.core.AppLock
import app.moonmobile.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's lock: its password, or the fingerprint or face when they are switched on. */
@Composable
fun LockScreen() {
    val context = platformContext()
    val canBio = canAskBiometrics()
    val scope = rememberCoroutineScope()
    val settings by AppLock.settings.collectAsState()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    val bio = AppLock.biometricsOn()

    LaunchedEffect(Unit) {
        if (canBio && bio) askBiometrics(context) { AppLock.unlock() }
    }

    val form: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Приложение заблокировано", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = null
                },
                label = { Text("Пароль на вход") },
                singleLine = true,
                enabled = !checking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = Color(0x99FFFFFF),
                    focusedBorderColor = NEON_GREEN,
                    unfocusedBorderColor = Color(0x59FFFFFF),
                    focusedLabelColor = NEON_GREEN,
                    unfocusedLabelColor = Color(0x99FFFFFF),
                    cursorColor = NEON_GREEN,
                    focusedContainerColor = Color(0x14FFFFFF),
                    unfocusedContainerColor = Color(0x0DFFFFFF),
                ),
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(),
            )
            error?.let { Text(it, color = Color(0xFFFF4A5A)) }
            Button(
                enabled = password.isNotEmpty() && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { AppLock.verify(context, password) }
                        checking = false
                        if (ok) {
                            password = ""
                            AppLock.unlock()
                        } else {
                            error = "Неверный пароль"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NEON_GREEN,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0x33FFFFFF),
                    disabledContentColor = Color(0x80FFFFFF),
                ),
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(),
            ) { Text("Войти", fontWeight = FontWeight.SemiBold) }
            if (bio && canBio) {
                OutlinedButton(
                    onClick = { askBiometrics(context) { AppLock.unlock() } },
                    border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Color(0xFFFF2D4A), Color(0xFFFFE23D), NEON_GREEN))),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(),
                ) {
                    Text(
                        when {
                            settings.finger && settings.face -> "Отпечаток или лицо"
                            settings.face -> "Распознавание лица"
                            else -> "Отпечаток пальца"
                        },
                    )
                }
            }
            if (Session.hasSavedPassword(context)) {
                TextButton(onClick = { forgot = true }) { Text("Забыли пароль?", color = Color(0xB3FFFFFF)) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        NeonFrame()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPaddingScaled()
                .padding(horizontal = 30.dp, vertical = 26.dp),
        ) {
            // Read here: inside the Row and the Column below this scope is not the receiver.
            val width = maxWidth
            val height = maxHeight
            if (width > height * 1.15f) {
                // Landscape: the logo on the left, the lock on the right.
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Logo(Modifier.weight(1f), emblem = (height * 0.5f).coerceAtMost(240.dp))
                    form(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
                ) {
                    Logo(Modifier.fillMaxWidth(), emblem = (width * 0.62f).coerceAtMost(280.dp))
                    form(Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (forgot) {
        var serversPassword by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        MoonDialog(
            onDismissRequest = { forgot = false },
            title = { Text("Забыли пароль на вход") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Введите пароль servers.enc. Пароль на вход и вход по отпечатку и лицу будут " +
                            "сняты — задайте их заново в «Настройки → Безопасность».",
                    )
                    OutlinedTextField(
                        value = serversPassword,
                        onValueChange = {
                            serversPassword = it
                            wrong = false
                        },
                        label = { Text("Пароль servers.enc") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (wrong) Text("Не тот пароль", color = Palette.red)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Session.checkSavedPassword(context, serversPassword)) {
                        forgot = false
                        AppLock.setPassword(context, null)
                    } else {
                        wrong = true
                    }
                }) { Text("Снять блокировку") }
            },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("Отмена") } },
        )
    }
}
