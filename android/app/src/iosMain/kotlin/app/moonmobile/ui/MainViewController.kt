package app.moonmobile.ui

import androidx.compose.ui.window.ComposeUIViewController
import app.moonmobile.core.AppLock
import app.moonmobile.core.Session
import app.moonmobile.core.Sounds
import app.moonmobile.platform.Ios
import app.moonmobile.platform.IosContext
import app.moonmobile.platform.IosHost
import platform.UIKit.UIViewController

/**
 * The app's screen for the Swift side: `MainViewControllerKt.MainViewController(host:)`. Starts
 * the shared code the way Android's MainActivity does.
 */
fun MainViewController(host: IosHost): UIViewController {
    Ios.host = host
    Session.refreshFiles(IosContext)
    AppLock.load(IosContext)
    Sounds.load(IosContext)
    AppLock.onLaunch()
    Session.uiVisible = true
    return ComposeUIViewController { MoonApp() }
}

/** The app went to the background (Swift calls this from the scene's phase change). */
fun appWentAway() {
    AppLock.onAway()
    Session.uiVisible = false
}

/** The app came back to the screen. */
fun appCameBack() {
    AppLock.onBack()
    Session.uiVisible = true
}
