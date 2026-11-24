@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.moonmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.moonmobile.core.Native
import app.moonmobile.platform.Ios
import app.moonmobile.platform.IosContext
import app.moonmobile.platform.IosPickedFile
import app.moonmobile.platform.PickedFile
import app.moonmobile.platform.Platform
import app.moonmobile.platform.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.posix.memcpy

@Composable
actual fun platformContext(): PlatformContext = IosContext

/** iOS has no Back button; the screens' own buttons go back. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}

private val pictures = HashMap<String, ImageBitmap?>()

@Composable
actual fun imagePainter(name: String): Painter {
    val image = remember(name) {
        pictures.getOrPut(name) {
            NSBundle.mainBundle.pathForResource(name, "png")?.let { decodePicture(it) }
        }
    }
    return image?.let { BitmapPainter(it) } ?: ColorPainter(Color.Transparent)
}

/**
 * A picture from the app's bundle. Xcode may have rewritten a PNG in Apple's own variant (CgBI),
 * which Skia does not read: UIKit reads it and encodes it again as a plain PNG.
 */
private fun decodePicture(path: String): ImageBitmap? {
    runCatching { return Image.makeFromEncoded(readFileBytes(path)).toComposeImageBitmap() }
    val png = UIImage.imageWithContentsOfFile(path)?.let { UIImagePNGRepresentation(it) } ?: return null
    return runCatching { Image.makeFromEncoded(png.toByteArray()).toComposeImageBitmap() }.getOrNull()
}

@Composable
actual fun SystemBarsEffect(dark: Boolean) {}

/** The chart and its market search turn the app to landscape, as on Android. */
@Composable
actual fun LandscapeWhileShown() {
    DisposableEffect(Unit) {
        Ios.host.setLandscapeOnly(true)
        onDispose { Ios.host.setLandscapeOnly(false) }
    }
}

/**
 * An iPad gets less of the phone's layout scaled up than an Android tablet: 40% of a
 * point-for-point scale-up (an 11-inch iPad, 834 points, comes to about 140%), so the chart's bar
 * and the market search keep their room.
 */
actual fun autoUiScale(smallestWidthDp: Float): Float =
    (1f + (smallestWidthDp / 420f - 1f) * 0.4f).coerceIn(1f, 1.8f)

actual val popupsAtBaseDensity: Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun smallestScreenWidthDp(): Float =
    UIScreen.mainScreen.bounds.useContents { minOf(size.width, size.height).toFloat() }

@Composable
actual fun rememberDocumentPicker(onPicked: (PickedFile?) -> Unit): Launcher<Array<String>> {
    val current by rememberUpdatedState(onPicked)
    return remember { Launcher { _ -> Ios.host.pickDocument { path -> current(path?.let { IosPickedFile(it) }) } } }
}

/** «Сохранить в папку» on iOS: the app's own folder, which Files shows under On My iPhone. */
@Composable
actual fun rememberFolderPicker(onPicked: (PickedFolder?) -> Unit): Launcher<Unit?> {
    val current by rememberUpdatedState(onPicked)
    return remember { Launcher { current(IosFolder(exportFolder())) } }
}

/** iOS asks for notifications once, when the app starts. */
@Composable
actual fun rememberNotificationPermission(onResult: () -> Unit): Launcher<Unit?> {
    val current by rememberUpdatedState(onResult)
    return remember { Launcher { current() } }
}

@Composable
actual fun rememberSaveAs(mime: String, onPicked: (SaveTarget?) -> Unit): Launcher<String> {
    val current by rememberUpdatedState(onPicked)
    return remember { Launcher { name -> current(IosSaveTarget(exportFolder() + "/" + name)) } }
}

private fun exportFolder(): String {
    val folder = Platform.dataRoot(IosContext) + "/Экспорт"
    Platform.mkdirs(folder)
    return folder
}

actual abstract class PickedFolder {
    abstract val path: String
}

private class IosFolder(override val path: String) : PickedFolder()

actual abstract class SaveTarget {
    abstract val path: String
}

private class IosSaveTarget(override val path: String) : SaveTarget()

actual fun writeIntoFolder(context: PlatformContext, folder: PickedFolder, name: String, bytes: ByteArray, mime: String): String? = try {
    writeFileBytes(folder.path + "/" + name, bytes)
    null
} catch (e: Exception) {
    "Не удалось сохранить: ${e.message}"
}

actual fun copyToTarget(context: PlatformContext, target: SaveTarget, path: String): String? = try {
    writeFileBytes(target.path, readFileBytes(path))
    null
} catch (e: Exception) {
    "Не удалось сохранить: ${e.message}"
}

actual fun shareFiles(context: PlatformContext, paths: List<String>, mime: String, title: String): String? {
    Ios.host.shareFiles(paths, title)
    return null
}

@OptIn(ExperimentalForeignApi::class)
actual fun readFileBytes(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path) ?: throw IllegalStateException("не удалось прочитать $path")
    return data.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

actual fun writeFileText(path: String, text: String) = writeFileBytes(path, text.encodeToByteArray())

@OptIn(ExperimentalForeignApi::class)
actual fun writeFileBytes(path: String, bytes: ByteArray) {
    Platform.mkdirs(path.substringBeforeLast('/'))
    val data = if (bytes.isEmpty()) {
        NSData()
    } else {
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
    if (!data.writeToFile(path, true)) throw IllegalStateException("не удалось записать $path")
}

actual fun readAssetText(context: PlatformContext, name: String): String {
    val path = NSBundle.mainBundle.pathForResource(name.substringBeforeLast('.'), name.substringAfterLast('.', ""))
        ?: return ""
    return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
}

/**
 * The bridge draws into [pixels] off the main thread; [publish] copies them into the bitmap on
 * the main thread. Replacing a Skia bitmap's pixels while the screen turns it into an image —
 * as the drawing thread once did, frame after frame — leaves the image nothing to read, and the
 * app fell over ("Failed to Image::makeFromBitmap"), most often while a line was being dragged.
 */
actual class ChartFrame actual constructor(width: Int, height: Int) {
    private val pixels = ByteArray(width * height * 4)
    private val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    private val bitmap = Bitmap().apply { allocPixels(info) }
    actual val image: ImageBitmap = bitmap.asComposeImageBitmap()

    actual fun draw(request: String): String = Native.chart(request, pixels)

    actual fun publish() {
        bitmap.installPixels(info, pixels, info.minRowBytes)
        bitmap.notifyPixelsChanged()
    }
}

@Composable
actual fun pendingUpdateVersion(): String? = null

@Composable
actual fun UpdateSettingsBody(onInstall: () -> Unit) {
    Text(
        "На iPhone новая версия ставится из той же программы, которой приложение установлено " +
            "(Sideloadly или TestFlight).",
        style = MaterialTheme.typography.bodySmall,
        color = Palette.textDim,
    )
}

@Composable
actual fun UpdateDialog(onClose: () -> Unit) {
    MoonDialog(
        onDismissRequest = onClose,
        title = { Text("Обновление") },
        text = { Text("На iPhone обновления ставятся через Sideloadly или TestFlight.") },
        confirmButton = { TextButton(onClick = onClose) { Text("Понятно") } },
    )
}

@Composable
actual fun canAskBiometrics(): Boolean = Ios.host.biometricsAvailable()

actual fun askBiometrics(context: PlatformContext, onOk: () -> Unit) {
    Ios.host.askBiometrics("Вход в MoonMobile") { ok -> if (ok) onOk() }
}

actual fun closeScreen(context: PlatformContext) {}
