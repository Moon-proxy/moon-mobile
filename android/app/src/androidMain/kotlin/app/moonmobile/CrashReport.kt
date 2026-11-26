package app.moonmobile

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import app.moonmobile.text.DateFormat
import java.io.File

/**
 * Why the app's process ended last time, for the next launch to show, as iOS does (Crash.swift):
 * an exception nobody caught is written to `crash.txt` before the process goes, and Android's own
 * record of the process's end (ApplicationExitInfo) says the rest — a crash in the bridge's
 * native code, an app that stopped answering, or the system closing it in the background.
 */
object CrashReport {
    private const val FILE = "crash.txt"
    private const val PREFS = "crash_report"
    private const val SEEN = "exit_seen_ms"

    /** Ends older than this are not shown: the first launch of a new version shows the last day's. */
    private const val SHOW_WITHIN_MS = 24 * 3_600_000L

    @Volatile
    private var installed = false

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Before anything else: an uncaught exception is written down, then the process ends as usual. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val head = "MoonMobile ${version(app)}, " + DateFormat("dd.MM.yyyy HH:mm:ss").format(System.currentTimeMillis()) +
                    ", поток «${thread.name}»"
                file(app).writeText(head + "\n\n" + error.stackTraceToString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * What the last ends of the process left that were not the user's or the app's own doing,
     * newest first; null when there is nothing new. Each end is shown once.
     */
    fun lastReport(context: Context): String? {
        val parts = mutableListOf<String>()
        file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let { parts.add("Исключение:\n$it") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (exit in newExits(context)) parts.add(describe(exit, withTrace = true))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n———\n\n")
    }

    /** Every end since the last report, ordinary ones too, one line each, for the log. */
    fun logLines(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return newExits(context, all = true).map { "Прошлый процесс: " + describe(it, withTrace = false) + " (код ${it.reason})" }
    }

    /** The report was seen: it is not shown again. */
    fun clear(context: Context) {
        file(context).delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) markSeen(context)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun markSeen(context: Context) {
        val newest = exits(context).maxOfOrNull { it.timestamp } ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(SEEN, newest).apply()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exits(context: Context): List<ApplicationExitInfo> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun newExits(context: Context, all: Boolean = false): List<ApplicationExitInfo> {
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SEEN, 0L)
        val since = maxOf(seen, System.currentTimeMillis() - SHOW_WITHIN_MS)
        return exits(context)
            .filter { it.timestamp > since && (all || abnormal(it)) }
            .sortedByDescending { it.timestamp }
            .take(if (all) 8 else 3)
    }

    /**
     * Not the app's own «Закрыть», a swipe out of the recent apps, an update or a permission
     * change. A stop "on the user's request" that is not a swipe stays in: phone makers' own
     * cleaners close apps that way.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun abnormal(exit: ApplicationExitInfo): Boolean {
        val swiped = exit.description?.contains("remove task", ignoreCase = true) == true
        return when (exit.reason) {
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            15, // REASON_PACKAGE_STATE_CHANGE
            16, // REASON_PACKAGE_UPDATED
            -> false
            else -> !swiped
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(exit: ApplicationExitInfo, withTrace: Boolean): String {
        val text = StringBuilder()
        text.append("Процесс завершён ").append(DateFormat("dd.MM.yyyy HH:mm:ss").format(exit.timestamp))
        text.append(": ").append(reasonText(exit))
        exit.description?.takeIf { it.isNotBlank() }?.let { text.append(" — «").append(it).append("»") }
        text.append(". Состояние: ").append(importanceText(exit.importance))
        val pss = exit.pss / 1024
        val rss = exit.rss / 1024
        if (pss > 0 || rss > 0) text.append(", память PSS $pss МБ, RSS $rss МБ")
        text.append('.')
        if (withTrace) trace(exit)?.let { text.append("\n\n").append(it) }
        return text.toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonText(exit: ApplicationExitInfo): String = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH -> "ошибка в коде приложения"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "ошибка в нативном коде (мост, графика)"
        ApplicationExitInfo.REASON_ANR -> "приложение не отвечало (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "система освобождала память"
        ApplicationExitInfo.REASON_SIGNALED -> "остановлен сигналом ${exit.status}"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "система сочла, что в фоне слишком много работы"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "не запустился"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "завершился связанный процесс"
        ApplicationExitInfo.REASON_OTHER -> "закрыт системой"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "остановлен принудительно (настройки или очистка памяти телефона)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "закрыт самим приложением"
        16 -> "обновление приложения"
        14 -> "заморожен системой"
        else -> "причина ${exit.reason}"
    }

    private fun importanceText(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "на экране"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "свёрнут, со службой подключения"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "свёрнут, заметен"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "свёрнут, служба"
        else -> "свёрнут, в кэше ($importance)"
    }

    /**
     * Android's trace of the end: the stuck threads of an ANR as they are, and a native crash's
     * tombstone (a binary record) as the readable strings in it — the signal, the abort message
     * and the frames.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun trace(exit: ApplicationExitInfo): String? {
        val bytes = try {
            exit.traceInputStream?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        } ?: return null
        return when (exit.reason) {
            ApplicationExitInfo.REASON_ANR -> String(bytes).lineSequence().take(120).joinToString("\n")
            ApplicationExitInfo.REASON_CRASH_NATIVE -> printable(bytes).take(150).joinToString("\n")
            else -> null
        }
    }

    /** Runs of printable characters of six or more, as `strings` lists them. */
    private fun printable(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                run.append(c.toChar())
            } else {
                if (run.length >= 6) out.add(run.toString())
                run.setLength(0)
            }
        }
        if (run.length >= 6) out.add(run.toString())
        return out
    }

    private fun version(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }
}
