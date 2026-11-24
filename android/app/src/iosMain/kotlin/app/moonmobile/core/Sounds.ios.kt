package app.moonmobile.core

import app.moonmobile.platform.Ios
import app.moonmobile.platform.PickedFile
import app.moonmobile.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Moonbot's sounds, played by the Swift app; the user's own sounds are not on iOS yet. */
actual object Sounds {
    actual val builtIn: List<String> = listOf(
        "Alarm", "BABYTOY", "BARK", "cork", "ERROR", "HALLO", "PFIFF", "Ringin", "ringout",
        "TurnOn", "YES_MAST", "ding1", "ding2", "Fatality", "gold", "milord", "LetsRock", "ComeGetSome",
    )

    actual val missing: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    actual fun load(context: PlatformContext) {}

    actual fun userFiles(context: PlatformContext): List<SoundFile> = emptyList()

    actual fun play(context: PlatformContext, name: String) = Ios.host.playSound(name)

    actual fun preview(context: PlatformContext, name: String) = Ios.host.playSound(name)

    actual fun import(context: PlatformContext, file: PickedFile): Result<String> =
        Result.failure(IllegalStateException("на iPhone свои звуки пока не поддерживаются"))

    actual fun importAs(context: PlatformContext, file: PickedFile, name: String): Result<String> =
        Result.failure(IllegalStateException("на iPhone свои звуки пока не поддерживаются"))

    actual fun delete(file: SoundFile) {}
}
