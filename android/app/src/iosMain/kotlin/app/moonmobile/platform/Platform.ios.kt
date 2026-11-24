package app.moonmobile.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile

actual abstract class PlatformContext

/** The one context on iOS: there is nothing to carry. */
object IosContext : PlatformContext()

actual abstract class PickedFile {
    /** Where the picked file can be read. */
    abstract val path: String
}

/** A file the document picker handed over, already where the app may read it. */
class IosPickedFile(override val path: String) : PickedFile()

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun prefsOf(context: PlatformContext, name: String): Prefs =
    IosPrefs(NSUserDefaults(suiteName = name))

private class IosPrefs(private val defaults: NSUserDefaults) : Prefs {
    override fun getString(key: String, fallback: String?): String? = defaults.stringForKey(key) ?: fallback
    override fun getBoolean(key: String, fallback: Boolean): Boolean =
        if (contains(key)) defaults.boolForKey(key) else fallback
    override fun getInt(key: String, fallback: Int): Int =
        if (contains(key)) defaults.integerForKey(key).toInt() else fallback
    override fun getLong(key: String, fallback: Long): Long =
        if (contains(key)) defaults.integerForKey(key) else fallback
    override fun getFloat(key: String, fallback: Float): Float =
        if (contains(key)) defaults.floatForKey(key) else fallback
    override fun getStringSet(key: String, fallback: Set<String>?): Set<String>? =
        defaults.arrayForKey(key)?.filterIsInstance<String>()?.toSet() ?: fallback
    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null
    override fun edit(): Prefs.Editor = IosEditor(defaults)
}

private class IosEditor(private val defaults: NSUserDefaults) : Prefs.Editor {
    private val changes = ArrayList<() -> Unit>()
    private fun change(block: () -> Unit): Prefs.Editor = apply { changes += block }

    override fun putString(key: String, value: String?) =
        change { if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key) }
    override fun putBoolean(key: String, value: Boolean) = change { defaults.setBool(value, forKey = key) }
    override fun putInt(key: String, value: Int) = change { defaults.setInteger(value.toLong(), forKey = key) }
    override fun putLong(key: String, value: Long) = change { defaults.setInteger(value, forKey = key) }
    override fun putFloat(key: String, value: Float) = change { defaults.setFloat(value, forKey = key) }
    override fun putStringSet(key: String, value: Set<String>?) =
        change { if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value.toList(), forKey = key) }
    override fun remove(key: String) = change { defaults.removeObjectForKey(key) }
    override fun clear() = change {
        for (key in defaults.dictionaryRepresentation().keys) (key as? String)?.let { defaults.removeObjectForKey(it) }
    }
    override fun apply() {
        changes.forEach { it() }
        changes.clear()
    }
    override fun commit(): Boolean {
        apply()
        return true
    }
}

@OptIn(ExperimentalForeignApi::class)
actual object Platform {
    private fun folder(kind: ULong): String =
        NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String ?: ""

    actual fun app(context: PlatformContext): PlatformContext = context

    actual fun dataRoot(context: PlatformContext): String = folder(NSDocumentDirectory)

    actual fun cacheRoot(context: PlatformContext): String = folder(NSCachesDirectory)

    actual fun isFile(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun readText(path: String): String =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: throw IllegalStateException("cannot read $path")

    actual fun mkdirs(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    }

    actual fun importFile(context: PlatformContext, source: PickedFile, target: String): String? {
        val files = NSFileManager.defaultManager
        if (!files.fileExistsAtPath(source.path)) return "Не удалось открыть выбранный файл"
        val size = (files.attributesOfItemAtPath(source.path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (size == 0L) return "Выбранный файл пуст"
        mkdirs(target.substringBeforeLast('/'))
        val temp = "$target.import"
        files.removeItemAtPath(temp, null)
        if (!files.copyItemAtPath(source.path, temp, null)) return "Не удалось сохранить файл"
        files.removeItemAtPath(target, null)
        return if (files.moveItemAtPath(temp, target, null)) null else "Не удалось сохранить файл"
    }

    actual fun setEnv(name: String, value: String) {
        platform.posix.setenv(name, value, 1)
    }

    /** iOS keeps no app running in the background: the connections pause with the app. */
    actual fun startBackground(context: PlatformContext) {}

    actual fun stopBackground(context: PlatformContext) {}

    actual fun appVersion(context: PlatformContext): String =
        NSBundle.mainBundle.infoDictionary?.get("MMVersionName") as? String
            ?: NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
            ?: ""

    /** An iOS app does not close its own screen. */
    actual fun finishScreen(context: PlatformContext) {}

    actual fun exitProcess(): Nothing = kotlin.system.exitProcess(0)

    actual fun notificationsAllowed(context: PlatformContext): Boolean = Ios.host.notificationsAllowed()

    actual fun postNotice(context: PlatformContext, notice: Notice) {
        val body = if (notice.lines.size > 1) notice.lines.take(12).joinToString("\n") else notice.text
        Ios.host.notify(notice.kind, notice.title, body, notice.quiet)
    }

    actual fun playDetectSound(context: PlatformContext, name: String) = Ios.host.playSound(name)
}
