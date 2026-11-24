package app.moonmobile.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import app.moonmobile.platform.PickedFile
import android.provider.OpenableColumns
import java.io.File

/**
 * Detect sounds as MoonTerminal plays them: the sound a strategy chose, by name or by Moonbot's
 * number, from Moonbot's eighteen or from files the user added. A user's file wins over a
 * built-in one of the same name; `19_MySound.wav` is the sound `MySound` holding number 19.
 */
actual object Sounds {
    /** Moonbot's eighteen, in its own order: their numbers are 1 to 18. */
    actual val builtIn = listOf(
        "Alarm", "BABYTOY", "BARK", "cork", "ERROR", "HALLO", "PFIFF", "Ringin", "ringout",
        "TurnOn", "YES_MAST", "ding1", "ding2", "Fatality", "gold", "milord", "LetsRock", "ComeGetSome",
    )
    private const val DEFAULT = "ding1"
    private const val PREFS = "moonmobile.sounds"
    private const val KEY_MISSING = "missing"

    /**
     * Sounds the strategies asked for that the app has neither built in nor as the user's file:
     * the default plays instead. Settings offers to load each under its own name.
     */
    private val missingFlow = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    actual val missing: kotlinx.coroutines.flow.StateFlow<Set<String>> = missingFlow

    @Volatile
    private var loaded = false

    /** Reads the remembered missing sounds once per process. */
    actual fun load(context: Context) {
        if (loaded) return
        loaded = true
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_MISSING, emptySet()).orEmpty()
        missingFlow.value = stored.toSet()
    }

    private fun setMissing(context: Context, names: Set<String>) {
        missingFlow.value = names
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_MISSING, names).apply()
    }
    private val extensions = setOf("wav", "mp3", "ogg", "m4a", "aac", "flac")

    /** Where the user's own sounds are kept. */
    fun folder(context: Context): File = File(context.filesDir, "sounds").apply { mkdirs() }

    /** The user's sounds, by file name. */
    actual fun userFiles(context: Context): List<SoundFile> =
        userFileList(context).map { SoundFile(it.name, it.absolutePath) }

    private fun userFileList(context: Context): List<File> =
        folder(context).listFiles()?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.sortedBy { it.name.lowercase() }.orEmpty()

    /** A file's sound name and number: `19_MySound.wav` is `MySound`, number 19. */
    private fun nameOf(file: File): Pair<String, Int?> {
        val stem = file.nameWithoutExtension
        val number = stem.substringBefore('_', "").toIntOrNull()
        return if (number != null) stem.substringAfter('_') to number else stem to null
    }

    private sealed interface Source {
        data class Asset(val path: String) : Source
        data class UserFile(val file: File) : Source
    }

    /** What a strategy's sound name plays: its file, Moonbot's sound, or the default. */
    private fun resolve(context: Context, name: String): Source {
        val wanted = name.trim().substringBeforeLast('.').ifEmpty { DEFAULT }
        val number = wanted.toIntOrNull()
        val users = userFileList(context)
        users.firstOrNull { file ->
            val (stem, n) = nameOf(file)
            (number != null && n == number) || stem.equals(wanted, ignoreCase = true) ||
                file.nameWithoutExtension.equals(wanted, ignoreCase = true)
        }?.let { return Source.UserFile(it) }
        val builtInName = when {
            number != null -> builtIn.getOrNull(number - 1)
            wanted.equals("default", ignoreCase = true) -> DEFAULT
            else -> builtIn.firstOrNull { it.equals(wanted, ignoreCase = true) }
        }
        if (builtInName == null) {
            load(context)
            if (wanted !in missingFlow.value) setMissing(context, missingFlow.value + wanted)
        }
        return Source.Asset(assetPath(context, builtInName ?: DEFAULT))
    }

    /** The asset of a built-in sound, whatever the case of its file's extension. */
    private fun assetPath(context: Context, name: String): String {
        val files = context.assets.list("sounds").orEmpty()
        val file = files.firstOrNull { it.substringBeforeLast('.').equals(name, ignoreCase = true) } ?: "$DEFAULT.wav"
        return "sounds/$file"
    }

    private val queue = ArrayDeque<Source>()
    private var player: MediaPlayer? = null

    /** Plays a sound by the name a strategy gave it; queued after one already playing. */
    actual fun play(context: Context, name: String) {
        val app = context.applicationContext
        val source = try {
            resolve(app, name)
        } catch (e: Exception) {
            return
        }
        synchronized(this) {
            if (queue.size >= 3) return
            queue.addLast(source)
            if (player == null) next(app)
        }
    }

    /** Plays one of the user's files or a built-in sound by name, for trying it in Settings. */
    actual fun preview(context: Context, name: String) = play(context, name)

    private fun next(context: Context) {
        val source = queue.removeFirstOrNull() ?: run {
            player = null
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            // At the media volume unless asked otherwise: a silenced notification stream would
            // swallow every detect.
            val media = AppPrefs.notify.value.soundsMedia
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(
                        if (media) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SONIFICATION,
                    )
                    .build(),
            )
            when (source) {
                is Source.Asset -> context.assets.openFd(source.path).use {
                    mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                is Source.UserFile -> mp.setDataSource(source.file.absolutePath)
            }
            mp.setOnCompletionListener {
                it.release()
                synchronized(this) { next(context) }
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                synchronized(this) { next(context) }
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            next(context)
        }
    }

    /** Copies a picked audio file into the sounds folder; its name, or why it failed. */
    actual fun import(context: Context, file: PickedFile): Result<String> = try {
        val uri = file.uri
        val resolver = context.contentResolver
        var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "sound.wav"
        name = name.replace('/', '_').replace('\\', '_')
        if (name.substringAfterLast('.', "").lowercase() !in extensions) {
            throw IllegalArgumentException("нужен файл wav, mp3, ogg, m4a, aac или flac")
        }
        val target = File(folder(context), name)
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("файл не открылся")
        // A file named like a missing sound is that sound now.
        load(context)
        val (stem, number) = nameOf(target)
        val now = missingFlow.value.filterNot {
            it.equals(stem, ignoreCase = true) || (number != null && it.toIntOrNull() == number)
        }.toSet()
        if (now != missingFlow.value) setMissing(context, now)
        Result.success(name)
    } catch (e: Exception) {
        Result.failure(e)
    }

    actual fun delete(file: SoundFile) {
        File(file.path).delete()
    }

    /**
     * Copies a picked audio file in as the sound [name] a strategy asks for: `name.ext`, or for a
     * sound asked for by number `19_<file>.ext`. The name is no longer missing afterwards.
     */
    actual fun importAs(context: Context, file: PickedFile, name: String): Result<String> = try {
        val uri = file.uri
        val resolver = context.contentResolver
        val picked = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "sound.wav"
        val extension = picked.substringAfterLast('.', "").lowercase()
        if (extension !in extensions) throw IllegalArgumentException("нужен файл wav, mp3, ogg, m4a, aac или flac")
        val safe = name.replace('/', '_').replace('\\', '_').trim()
        val stem = if (safe.toIntOrNull() != null) {
            "${safe}_" + picked.substringBeforeLast('.').replace('/', '_').replace('\\', '_')
        } else {
            safe
        }
        val target = File(folder(context), "$stem.$extension")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("файл не открылся")
        load(context)
        setMissing(context, missingFlow.value - name)
        Result.success(target.name)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
