package app.moonmobile.platform

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.Os
import app.moonmobile.MainActivity
import app.moonmobile.R
import app.moonmobile.SessionService
import app.moonmobile.core.Sounds
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual typealias PlatformContext = Context

actual abstract class PickedFile {
    abstract val uri: Uri
}

private class AndroidPickedFile(override val uri: Uri) : PickedFile()

/** A document the user picked, for the shared code. */
fun Uri.picked(): PickedFile = AndroidPickedFile(this)

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun prefsOf(context: PlatformContext, name: String): Prefs =
    AndroidPrefs(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

private class AndroidPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun getString(key: String, fallback: String?): String? = prefs.getString(key, fallback)
    override fun getBoolean(key: String, fallback: Boolean): Boolean = prefs.getBoolean(key, fallback)
    override fun getInt(key: String, fallback: Int): Int = prefs.getInt(key, fallback)
    override fun getLong(key: String, fallback: Long): Long = prefs.getLong(key, fallback)
    override fun getFloat(key: String, fallback: Float): Float = prefs.getFloat(key, fallback)
    override fun getStringSet(key: String, fallback: Set<String>?): Set<String>? = prefs.getStringSet(key, fallback)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun edit(): Prefs.Editor = AndroidEditor(prefs.edit())
}

private class AndroidEditor(private val editor: SharedPreferences.Editor) : Prefs.Editor {
    override fun putString(key: String, value: String?): Prefs.Editor = apply { editor.putString(key, value) }
    override fun putBoolean(key: String, value: Boolean): Prefs.Editor = apply { editor.putBoolean(key, value) }
    override fun putInt(key: String, value: Int): Prefs.Editor = apply { editor.putInt(key, value) }
    override fun putLong(key: String, value: Long): Prefs.Editor = apply { editor.putLong(key, value) }
    override fun putFloat(key: String, value: Float): Prefs.Editor = apply { editor.putFloat(key, value) }
    override fun putStringSet(key: String, value: Set<String>?): Prefs.Editor = apply { editor.putStringSet(key, value) }
    override fun remove(key: String): Prefs.Editor = apply { editor.remove(key) }
    override fun clear(): Prefs.Editor = apply { editor.clear() }
    override fun apply() = editor.apply()
    override fun commit(): Boolean = editor.commit()
}

actual object Platform {
    private const val CHANNEL = "alerts"

    actual fun app(context: PlatformContext): PlatformContext = context.applicationContext

    actual fun dataRoot(context: PlatformContext): String = context.applicationContext.filesDir.absolutePath

    actual fun cacheRoot(context: PlatformContext): String = context.applicationContext.cacheDir.absolutePath

    actual fun isFile(path: String): Boolean = File(path).isFile

    actual fun readText(path: String): String = File(path).readText()

    actual fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    actual fun importFile(context: PlatformContext, source: PickedFile, target: String): String? {
        val file = File(target)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".import")
        val input = context.applicationContext.contentResolver.openInputStream(source.uri)
            ?: return "Не удалось открыть выбранный файл"
        input.use { from -> temp.outputStream().use { from.copyTo(it) } }
        if (temp.length() == 0L) {
            temp.delete()
            return "Выбранный файл пуст"
        }
        if (file.exists()) file.delete()
        return if (temp.renameTo(file)) null else "Не удалось сохранить файл"
    }

    actual fun setEnv(name: String, value: String) {
        try {
            Os.setenv(name, value, true)
        } catch (e: Exception) {
            // The bridge falls back to its defaults.
        }
    }

    actual fun startBackground(context: PlatformContext) = SessionService.start(context)

    actual fun stopBackground(context: PlatformContext) = SessionService.stop(context)

    actual fun appVersion(context: PlatformContext): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    actual fun finishScreen(context: PlatformContext) {
        (context as? Activity)?.finishAndRemoveTask()
    }

    actual fun exitProcess(): Nothing = kotlin.system.exitProcess(0)

    actual fun notificationsAllowed(context: PlatformContext): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    actual fun postNotice(context: PlatformContext, notice: Notice) {
        if (!notificationsAllowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Уведомления ядер", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // One notification per kind, replaced as its list grows: pulled down, it opens into the
        // list of what came, newest first.
        val style = if (notice.lines.size > 1) {
            Notification.InboxStyle().also { inbox ->
                notice.lines.take(INBOX_LINES).forEach { inbox.addLine(it) }
                if (notice.lines.size > INBOX_LINES) inbox.setSummaryText("и ещё ${notice.lines.size - INBOX_LINES}")
            }
        } else {
            Notification.BigTextStyle().bigText(notice.text)
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(style)
            .setNumber(notice.lines.size)
            .setOnlyAlertOnce(notice.quiet)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(noticeId(notice.kind), notification)
        } catch (e: SecurityException) {
            return
        }
    }

    /** Each kind's notification number: the same one is replaced. */
    private fun noticeId(kind: String): Int = when (kind) {
        "core_lost" -> 11
        "trade" -> 12
        else -> 13
    }

    /** The lines an expanded notification lists before "и ещё N". */
    private const val INBOX_LINES = 7

    actual fun playDetectSound(context: PlatformContext, name: String) = Sounds.play(context, name)
}
