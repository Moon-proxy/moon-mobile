package app.moonmobile

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.moonmobile.core.AppLock
import app.moonmobile.core.Session
import app.moonmobile.ui.MoonApp

/** A FragmentActivity: the fingerprint and face prompt needs one. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars; the theme sets their icons dark or light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // The screen stays on while the app is on it, as a video player's does; Android lets it
        // dim again the moment the app leaves the screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Session.refreshFiles(this)
        AppLock.load(this)
        app.moonmobile.core.Updater.start(this)
        app.moonmobile.core.Sounds.load(this)
        // A launch, not a turn of the screen: the window state is saved only across a recreation.
        if (savedInstanceState == null) AppLock.onLaunch()
        setContent { MoonApp() }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onBack()
        Session.uiVisible = true
        app.moonmobile.core.Updater.onForeground(this)
    }

    override fun onDestroy() {
        // Closed for good (Back on the main screen, swiped away): the next window starts locked.
        if (isFinishing && !isChangingConfigurations) AppLock.onLaunch()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        AppLock.onAway()
        Session.uiVisible = false
    }
}
