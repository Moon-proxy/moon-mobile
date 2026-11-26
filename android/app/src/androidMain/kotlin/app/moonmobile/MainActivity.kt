package app.moonmobile

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import app.moonmobile.core.AppLock
import app.moonmobile.core.Session
import app.moonmobile.ui.CrashScreen
import app.moonmobile.ui.MoonApp

/** A FragmentActivity: the fingerprint and face prompt needs one. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReport.install(this)
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
        // How the last process ended, when it was not closed by the user or the app: shown once
        // before the app starts, and written to the log when the connection opens.
        val report = if (!reportRead) {
            reportRead = true
            CrashReport.logLines(this).forEach { Session.logLater(it) }
            CrashReport.lastReport(this)
        } else {
            null
        }
        setContent {
            var shown by remember { mutableStateOf(report) }
            val text = shown
            if (text != null) {
                CrashScreen(text) {
                    CrashReport.clear(this)
                    shown = null
                }
            } else {
                MoonApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onBack()
        Session.uiVisible = true
        Session.screenLine(visible = true, memoryMb = residentMb())
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
        Session.screenLine(visible = false, memoryMb = residentMb())
    }

    /** The process's resident memory, from /proc: cheap enough for every turn of the screen. */
    private fun residentMb(): Int? = try {
        val pages = java.io.File("/proc/self/statm").readText().trim().split(' ')[1].toLong()
        (pages * android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / (1024 * 1024)).toInt()
    } catch (_: Throwable) {
        null
    }

    private companion object {
        /** The last process's end is looked at once per process, not on every new window. */
        @Volatile
        var reportRead = false
    }
}
