package app.moonmobile.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.moonmobile.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.moonmobile.json.JSONObject

/**
 * Updates over the air. CI puts every build on the owner's server next to a `version.json`:
 * `{"version":"1.11b","code":467000,"apk":"MoonMobile-v1.11b.apk","sha256":"…","size":…,
 * "notes":"…"}`. The app reads it at start, every time it comes back to the screen (at most once
 * in [FOREGROUND_GAP_MS]) and every hour while it runs; a newer build shows a button with its
 * number beside the settings. The APK is checked against its SHA-256 before Android's
 * own installer is asked to put it over this one, and Android itself refuses a file signed by
 * any other key.
 */
object Updater {
    /** A build newer than the one installed. */
    data class Update(
        val version: String,
        val code: Long,
        val url: String,
        val sha256: String,
        val size: Long,
        val notes: String,
    )

    sealed interface Stage {
        data object Idle : Stage
        data object Checking : Stage
        data object UpToDate : Stage
        data class Downloading(val done: Long, val total: Long) : Stage
        data class Failed(val text: String) : Stage
    }

    private val updateFlow = MutableStateFlow<Update?>(null)
    val update: StateFlow<Update?> = updateFlow

    private val stageFlow = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = stageFlow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timer: Job? = null

    /** While the app runs: a check an hour. The app kept alive by its service runs for days. */
    private const val CHECK_EVERY_MS = 3_600_000L
    /** Coming back to the screen checks again once this much has passed since the last check. */
    private const val FOREGROUND_GAP_MS = 10 * 60_000L

    /** When the last check started, on the uptime clock; 0 before the first. */
    @Volatile
    private var checkedAt = 0L

    /** Whether this build knows where to look: CI sets it once the server is set up. */
    val configured: Boolean get() = BuildConfig.UPDATE_URL.isNotEmpty()

    /** Check now and then every hour while the process lives. */
    fun start(context: Context) {
        if (!configured || timer?.isActive == true) return
        val app = context.applicationContext
        checkedAt = android.os.SystemClock.elapsedRealtime()
        timer = scope.launch {
            while (isActive) {
                check(app)
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** The app came back to the screen: check unless a check ran a few minutes ago. */
    fun onForeground(context: Context) {
        if (!configured) return
        val since = android.os.SystemClock.elapsedRealtime() - checkedAt
        if (checkedAt != 0L && since < FOREGROUND_GAP_MS) return
        if (stageFlow.value is Stage.Downloading || stageFlow.value is Stage.Checking) return
        val app = context.applicationContext
        scope.launch { check(app) }
    }

    private fun installedCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    /** Reads `version.json`; a newer build becomes [update]. */
    suspend fun check(context: Context): Stage = withContext(Dispatchers.IO) {
        if (!configured) return@withContext Stage.Failed("Обновления не настроены в этой сборке").also { stageFlow.value = it }
        stageFlow.value = Stage.Checking
        checkedAt = android.os.SystemClock.elapsedRealtime()
        val result = try {
            // The minute in the address keeps any cache on the way from answering with an old file.
            val text = fetchText(BuildConfig.UPDATE_URL + "version.json?m=" + System.currentTimeMillis() / 60_000L)
            val json = JSONObject(text)
            val code = json.optLong("code", 0L)
            val apk = json.optString("apk")
            if (code <= 0L || apk.isEmpty()) throw IllegalStateException("version.json без номера сборки")
            if (code > installedCode(context)) {
                updateFlow.value = Update(
                    version = json.optString("version"),
                    code = code,
                    url = if (apk.startsWith("https://")) apk else BuildConfig.UPDATE_URL + apk,
                    sha256 = json.optString("sha256").lowercase(),
                    size = json.optLong("size", 0L),
                    notes = json.optString("notes"),
                )
                Stage.Idle
            } else {
                updateFlow.value = null
                Stage.UpToDate
            }
        } catch (e: Exception) {
            Stage.Failed("Не удалось проверить: ${e.message ?: e.javaClass.simpleName}")
        }
        stageFlow.value = result
        result
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "MoonMobile/${BuildConfig.VERSION_NAME} (Android)")
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code != 200) throw IllegalStateException("сервер ответил $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the update into the app's cache and checks its SHA-256; the file, or why not. */
    suspend fun download(context: Context, update: Update): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "MoonMobile-v${update.version}.apk")
        try {
            val connection = open(update.url)
            try {
                val code = connection.responseCode
                if (code != 200) throw IllegalStateException("сервер ответил $code")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.size
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            stageFlow.value = Stage.Downloading(done, total)
                        }
                    }
                }
                val sum = digest.digest().joinToString("") { "%02x".format(it) }
                if (update.sha256.isNotEmpty() && sum != update.sha256) {
                    target.delete()
                    throw IllegalStateException("контрольная сумма не совпала — файл повреждён")
                }
            } finally {
                connection.disconnect()
            }
            stageFlow.value = Stage.Idle
            Session.log("Обновление: скачана v${update.version}")
            Result.success(target)
        } catch (e: Exception) {
            target.delete()
            val text = "Не удалось скачать: ${e.message ?: e.javaClass.simpleName}"
            stageFlow.value = Stage.Failed(text)
            Result.failure(IllegalStateException(text))
        }
    }

    /** Whether Android lets this app hand an APK to the installer. */
    fun mayInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user allows MoonMobile to install apps (once). */
    fun askInstallRight(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands the APK to Android's installer, which asks the user and installs over this build. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        Session.log("Обновление: установка ${apk.name}")
        context.startActivity(intent)
    }
}
