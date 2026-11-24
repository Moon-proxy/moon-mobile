@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package app.moonmobile.ui

import app.moonmobile.platform.IosContext
import app.moonmobile.platform.Platform
import app.moonmobile.text.DateFormat
import app.moonmobile.text.nowMs
import kotlin.native.setUnhandledExceptionHook

/** Where an exception of the shared code that nobody caught is written down: Documents/crash.txt. */
fun crashLogPath(): String = Platform.dataRoot(IosContext) + "/crash.txt"

private var installed = false

/**
 * Called by Swift before anything else: an exception the shared code did not catch is written to
 * [crashLogPath] before the app closes, and the next launch shows it (Crash.swift).
 */
fun installCrashLog() {
    if (installed) return
    installed = true
    setUnhandledExceptionHook { e ->
        try {
            val head = "MoonMobile ${Platform.appVersion(IosContext)}, " +
                DateFormat("dd.MM.yyyy HH:mm:ss").format(nowMs())
            writeFileText(crashLogPath(), head + "\n\n" + e.stackTraceToString())
        } catch (_: Throwable) {
        }
    }
}
