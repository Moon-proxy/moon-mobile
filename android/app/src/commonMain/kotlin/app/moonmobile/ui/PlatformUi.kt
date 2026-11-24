package app.moonmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import app.moonmobile.platform.PickedFile
import app.moonmobile.platform.PlatformContext

/** Starts a platform picker or prompt with [input]. */
class Launcher<T>(private val start: (T) -> Unit) {
    fun launch(input: T) = start(input)
}

/** The platform's context for the screen being composed. */
@Composable
expect fun platformContext(): PlatformContext

/** The system's Back (Android's button or gesture); nothing on iOS. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** One of the app's pictures by its name: the exchange logos, the lock's emblem and wordmark. */
@Composable
expect fun imagePainter(name: String): Painter

/** The status bar's icons dark on a light theme, light on a dark one. */
@Composable
expect fun SystemBarsEffect(dark: Boolean)

/** Landscape and no system bars while the chart is shown. */
@Composable
expect fun LandscapeWhileShown()

/** The scale a screen of this smallest width gets when the settings leave it to the app. */
expect fun autoUiScale(smallestWidthDp: Float): Float

/** Whether dialogs and menus must be opened at the platform's own density (see Scaling.kt). */
expect val popupsAtBaseDensity: Boolean

/** The screen's smaller side, in dp. */
@Composable
expect fun smallestScreenWidthDp(): Float

/** The system's document picker for one file of the given types. */
@Composable
expect fun rememberDocumentPicker(onPicked: (PickedFile?) -> Unit): Launcher<Array<String>>

/** The system's folder picker, for saving files into the folder. */
@Composable
expect fun rememberFolderPicker(onPicked: (PickedFolder?) -> Unit): Launcher<Unit?>

/** Asks for the right to post notifications where the system wants it asked. */
@Composable
expect fun rememberNotificationPermission(onResult: () -> Unit): Launcher<Unit?>

/** The system's "save as" for one file of type [mime]; launched with the suggested name. */
@Composable
expect fun rememberSaveAs(mime: String, onPicked: (SaveTarget?) -> Unit): Launcher<String>

/** A folder the user picked to save into. */
expect abstract class PickedFolder

/** A place the user picked to save one file. */
expect abstract class SaveTarget

/** Writes a file named [name] into [folder]; null when done, else what went wrong. */
expect fun writeIntoFolder(context: PlatformContext, folder: PickedFolder, name: String, bytes: ByteArray, mime: String): String?

/** Copies the file at [path] to [target]; null when done, else what went wrong. */
expect fun copyToTarget(context: PlatformContext, target: SaveTarget, path: String): String?

/** Hands files to an app of the user's choice; null when the sheet opened, else what went wrong. */
expect fun shareFiles(context: PlatformContext, paths: List<String>, mime: String, title: String): String?

expect fun readFileBytes(path: String): ByteArray

expect fun writeFileText(path: String, text: String)

expect fun writeFileBytes(path: String, bytes: ByteArray)

/** A text file shipped with the app (the changelog). */
expect fun readAssetText(context: PlatformContext, name: String): String

/** One chart picture of [width] × [height] pixels that the bridge draws into. */
expect class ChartFrame(width: Int, height: Int) {
    /** The picture as the screen draws it; the same object frame after frame. */
    val image: ImageBitmap

    /** Draws the frame [request] asks for, off the main thread; the bridge's reply. */
    fun draw(request: String): String

    /**
     * Hands the frame last drawn to [image]; on the main thread, the one the screen draws on,
     * so the picture never changes under a drawing of it.
     */
    fun publish()
}

/** The version of an update waiting to be installed, if any. */
@Composable
expect fun pendingUpdateVersion(): String?

/** Settings → Обновление. [onInstall] opens the update dialog. */
@Composable
expect fun UpdateSettingsBody(onInstall: () -> Unit)

/** The update's dialog: what is new, and installing it. */
@Composable
expect fun UpdateDialog(onClose: () -> Unit)

/** Whether the fingerprint or face prompt can be shown from this screen. */
@Composable
expect fun canAskBiometrics(): Boolean

/** Shows the fingerprint or face prompt; [onOk] runs once it recognised the owner. */
expect fun askBiometrics(context: PlatformContext, onOk: () -> Unit)

/** Closes the app's screen, as Back on the main screen does. */
expect fun closeScreen(context: PlatformContext)
