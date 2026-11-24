package app.moonmobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.moonmobile.core.AppState
import app.moonmobile.core.AppLock
import app.moonmobile.core.AppPrefs
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.moonmobile.core.Phase
import app.moonmobile.core.Session
import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject

@Composable
fun MoonApp() {
    val state by Session.state.collectAsState()
    val chosen by AppPrefs.uiScale.collectAsState()
    // Larger screens — a tablet, the inside of a Fold — show the same layout scaled up, so every
    // element keeps the proportions it has on a phone. A phone-sized screen stays as it is; the
    // settings can set the scale by hand.
    val base = LocalDensity.current
    val factor = chosen ?: autoUiScale(smallestScreenWidthDp())
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * factor, base.fontScale),
        LocalBaseDensity provides base.takeIf { popupsAtBaseDensity && factor != 1f },
    ) {
        MoonContent(state)
    }
}

@Composable
private fun MoonContent(state: AppState) {
    val context = platformContext()
    val locked by AppLock.locked.collectAsState()
    // Every chart tab keeps its market streaming — the one shown, the ones behind it, and all of
    // them while another screen is open — so their trades draw on without a gap.
    val tabs = ChartSession.tabs.toList()
    LaunchedEffect(tabs, state.phase) {
        if (state.phase != Phase.ONLINE) return@LaunchedEffect
        val charts = JSONArray()
        for ((core, market) in tabs) charts.put(JSONObject().put("core", core).put("market", market))
        Session.call(JSONObject().put("op", "keep").put("charts", charts))
    }
    // With the password remembered the app goes straight to work: no start screen, once the
    // lock (when there is one) is open.
    LaunchedEffect(locked, state.phase, state.hasServersFile, state.savedPassword) {
        if (!locked && state.phase == Phase.SETUP && state.hasServersFile && state.savedPassword &&
            Session.autoConnect && state.error == null
        ) {
            Session.connectSaved(context)
        }
    }
    MoonTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    locked -> LockScreen()
                    state.phase == Phase.ONLINE -> MainScreen(state)
                    else -> SetupScreen(state)
                }
            }
        }
    }
}
