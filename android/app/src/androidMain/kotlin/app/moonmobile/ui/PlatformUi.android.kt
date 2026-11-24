package app.moonmobile.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import app.moonmobile.R
import app.moonmobile.core.AppLock
import app.moonmobile.core.Native
import app.moonmobile.core.Updater
import app.moonmobile.platform.PickedFile
import app.moonmobile.platform.PlatformContext
import app.moonmobile.platform.picked
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.launch

@Composable
actual fun platformContext(): PlatformContext = LocalContext.current

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)

@Composable
actual fun imagePainter(name: String): Painter = painterResource(
    when (name) {
        "ex_binance" -> R.drawable.ex_binance
        "ex_bybit" -> R.drawable.ex_bybit
        "ex_okx" -> R.drawable.ex_okx
        "ex_gate" -> R.drawable.ex_gate
        "ex_bitget" -> R.drawable.ex_bitget
        "ex_htx" -> R.drawable.ex_htx
        "ex_hyperliquid" -> R.drawable.ex_hyperliquid
        "lock_emblem" -> R.drawable.lock_emblem
        "lock_wordmark" -> R.drawable.lock_wordmark
        else -> R.drawable.logo_large
    },
)

@Composable
actual fun SystemBarsEffect(dark: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
actual fun LandscapeWhileShown() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before
        }
    }
}

/** A tablet or an open Fold shows the phone's layout scaled up to its width (drawn for 420 dp). */
actual fun autoUiScale(smallestWidthDp: Float): Float = (smallestWidthDp / 420f).coerceIn(1f, 2.2f)

/** Android's dialogs and menus are windows of their own and take the app's scale as they are. */
actual val popupsAtBaseDensity: Boolean = false

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@Composable
actual fun smallestScreenWidthDp(): Float = LocalConfiguration.current.smallestScreenWidthDp.toFloat()

@Composable
actual fun rememberDocumentPicker(onPicked: (PickedFile?) -> Unit): Launcher<Array<String>> {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPicked(uri?.picked())
    }
    return Launcher { types -> launcher.launch(types) }
}

@Composable
actual fun rememberFolderPicker(onPicked: (PickedFolder?) -> Unit): Launcher<Unit?> {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onPicked(uri?.let { AndroidFolder(it) })
    }
    return Launcher { launcher.launch(null) }
}

@Composable
actual fun rememberNotificationPermission(onResult: () -> Unit): Launcher<Unit?> {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    return Launcher {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onResult()
        }
    }
}

@Composable
actual fun rememberSaveAs(mime: String, onPicked: (SaveTarget?) -> Unit): Launcher<String> {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        onPicked(uri?.let { AndroidSaveTarget(it) })
    }
    return Launcher { name -> launcher.launch(name) }
}

actual abstract class PickedFolder {
    abstract val uri: Uri
}

private class AndroidFolder(override val uri: Uri) : PickedFolder()

actual abstract class SaveTarget {
    abstract val uri: Uri
}

private class AndroidSaveTarget(override val uri: Uri) : SaveTarget()

actual fun writeIntoFolder(context: PlatformContext, folder: PickedFolder, name: String, bytes: ByteArray, mime: String): String? = try {
    val resolver = context.contentResolver
    val tree = folder.uri
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val target = DocumentsContract.createDocument(resolver, parent, mime, name)
        ?: throw IllegalStateException("не удалось создать $name в выбранной папке")
    resolver.openOutputStream(target)?.use { it.write(bytes) } ?: throw IllegalStateException("не удалось записать $name")
    null
} catch (e: Exception) {
    "Не удалось сохранить: ${e.message}"
}

actual fun copyToTarget(context: PlatformContext, target: SaveTarget, path: String): String? = try {
    context.contentResolver.openOutputStream(target.uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        ?: throw IllegalStateException("файл не открылся")
    null
} catch (e: Exception) {
    "Не удалось сохранить: ${e.message}"
}

actual fun shareFiles(context: PlatformContext, paths: List<String>, mime: String, title: String): String? = try {
    val authority = "${context.packageName}.files"
    val uris = ArrayList(paths.map { FileProvider.getUriForFile(context, authority, File(it)) })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (e: Exception) {
    "Не удалось отправить: ${e.message}"
}

actual fun readFileBytes(path: String): ByteArray = File(path).readBytes()

actual fun writeFileText(path: String, text: String) {
    File(path).apply { parentFile?.mkdirs() }.writeText(text)
}

actual fun writeFileBytes(path: String, bytes: ByteArray) {
    File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
}

actual fun readAssetText(context: PlatformContext, name: String): String =
    context.assets.open(name).bufferedReader().use { it.readText() }

actual class ChartFrame actual constructor(width: Int, height: Int) {
    // The bridge draws into this memory directly; the bitmap is what the screen shows.
    private val buffer = ByteBuffer.allocateDirect(width * height * 4)
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    actual val image: ImageBitmap = bitmap.asImageBitmap()

    actual fun draw(request: String): String {
        val reply = Native.chart(request, buffer)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return reply
    }

    /** Android's bitmap takes the pixels as they are drawn. */
    actual fun publish() {}
}

@Composable
actual fun pendingUpdateVersion(): String? {
    val update by Updater.update.collectAsState()
    return update?.version
}

@Composable
actual fun UpdateSettingsBody(onInstall: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by Updater.update.collectAsState()
    val stage by Updater.stage.collectAsState()
    val next = update
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                !Updater.configured ->
                    "Обновление по воздуху выключено в этой сборке: сервер обновлений ещё не подключён."
                next != null -> "Доступна v${next.version}."
                stage is Updater.Stage.Checking -> "Проверяю…"
                stage is Updater.Stage.Failed -> (stage as Updater.Stage.Failed).text
                stage is Updater.Stage.UpToDate -> "Установлена последняя версия."
                else -> "Приложение проверяет обновления при запуске, при возвращении в него " +
                    "(не чаще раза в 10 минут) и каждый час."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textDim,
        )
        if (Updater.configured) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (next != null) {
                    Button(onClick = onInstall) { Text("Установить v${next.version}") }
                }
                OutlinedButton(onClick = { scope.launch { Updater.check(context) } }) {
                    Text("Проверить")
                }
            }
        }
    }
}

@Composable
actual fun canAskBiometrics(): Boolean = LocalContext.current.findActivity() is FragmentActivity

actual fun askBiometrics(context: PlatformContext, onOk: () -> Unit) {
    val activity = context.findActivity() as? FragmentActivity ?: return
    val settings = AppLock.settings.value
    val authenticators = when {
        settings.face -> BiometricManager.Authenticators.BIOMETRIC_WEAK
        settings.finger -> BiometricManager.Authenticators.BIOMETRIC_STRONG
        else -> 0
    }
    if (authenticators == 0) return
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Вход в MoonMobile")
        .setSubtitle("Отпечаток или лицо")
        .setNegativeButtonText("Пароль")
        .setAllowedAuthenticators(authenticators)
        .build()
    try {
        prompt.authenticate(info)
    } catch (e: Exception) {
        // The phone refused the prompt: the password stays the way in.
    }
}

actual fun closeScreen(context: PlatformContext) {
    context.findActivity()?.finish()
}
