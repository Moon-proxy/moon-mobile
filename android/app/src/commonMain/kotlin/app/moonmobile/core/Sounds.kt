package app.moonmobile.core

import app.moonmobile.platform.PickedFile
import app.moonmobile.platform.PlatformContext
import kotlinx.coroutines.flow.StateFlow

/** One of the user's own sound files. */
class SoundFile(val name: String, val path: String) {
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
}

/**
 * Detect sounds as MoonTerminal plays them: the sound a strategy chose, by name or by Moonbot's
 * number, from Moonbot's eighteen or from files the user added.
 */
expect object Sounds {
    /** Moonbot's eighteen, in its own order: their numbers are 1 to 18. */
    val builtIn: List<String>

    /** Sounds the strategies asked for that the app has neither built in nor as the user's file. */
    val missing: StateFlow<Set<String>>

    fun load(context: PlatformContext)

    /** The user's sounds, by file name. */
    fun userFiles(context: PlatformContext): List<SoundFile>

    fun play(context: PlatformContext, name: String)

    fun preview(context: PlatformContext, name: String)

    /** Copies a picked audio file into the sounds folder; its name, or why it failed. */
    fun import(context: PlatformContext, file: PickedFile): Result<String>

    /** Copies a picked audio file in as the sound [name] a strategy asks for. */
    fun importAs(context: PlatformContext, file: PickedFile, name: String): Result<String>

    fun delete(file: SoundFile)
}
