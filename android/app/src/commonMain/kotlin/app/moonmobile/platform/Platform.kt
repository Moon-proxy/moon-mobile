package app.moonmobile.platform

import kotlinx.coroutines.CoroutineDispatcher

/** What the platform hands the shared code for its services: Android's Context; a token on iOS. */
expect abstract class PlatformContext

/** A file the user picked to import: Android's content Uri; on iOS the picked file's path. */
expect abstract class PickedFile

/** The dispatcher for blocking work: bridge calls and files. */
expect val ioDispatcher: CoroutineDispatcher

/** What the shared code asks of the phone it runs on. */
expect object Platform {
    /** The app-wide context (Android's application context). */
    fun app(context: PlatformContext): PlatformContext

    /** The app's private folder; the bridge keeps its data in `moon/` inside. */
    fun dataRoot(context: PlatformContext): String

    /** The app's cache folder: temporary files and files made for sending. */
    fun cacheRoot(context: PlatformContext): String

    fun isFile(path: String): Boolean
    fun readText(path: String): String
    fun mkdirs(path: String)

    /**
     * Copies the picked file to [target] through a temporary file beside it: null when it is in
     * place, else what went wrong, in words for the screen.
     */
    fun importFile(context: PlatformContext, source: PickedFile, target: String): String?

    /** Process environment the bridge reads. */
    fun setEnv(name: String, value: String)

    /** Keeps the connections alive with the app in the background, as far as the platform lets it. */
    fun startBackground(context: PlatformContext)
    fun stopBackground(context: PlatformContext)

    /** The app's version name, `1.21b`. */
    fun appVersion(context: PlatformContext): String

    /** Closes the app's screen. */
    fun finishScreen(context: PlatformContext)

    /** Ends the process: nothing is left running. */
    fun exitProcess(): Nothing

    /** Whether the app may post notifications at all. */
    fun notificationsAllowed(context: PlatformContext): Boolean

    /**
     * Shows one notification per [Notice.kind], replacing the one shown before for that kind:
     * [Notice.lines] are the kind's recent notices, newest first, for its expanded view.
     */
    fun postNotice(context: PlatformContext, notice: Notice)

    /** Plays a detect's sound by its name in the strategy. */
    fun playDetectSound(context: PlatformContext, name: String)
}

/** Compares two secrets in time that does not depend on where they differ. */
fun sameSecret(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** One notification: a kind of notice with its recent ones gathered under it. */
data class Notice(
    /** `core_lost`, `trade` or `server` (rust/src/alerts.rs): one notification each. */
    val kind: String,
    val title: String,
    val text: String,
    /** Every notice of the kind since the app was last open, newest first. */
    val lines: List<String>,
    /** Update without a sound or a banner: more of the same kind of news. */
    val quiet: Boolean,
)
