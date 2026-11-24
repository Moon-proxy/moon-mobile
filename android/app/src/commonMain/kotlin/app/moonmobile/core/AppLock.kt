package app.moonmobile.core

import app.moonmobile.platform.Crypto
import app.moonmobile.platform.PlatformContext as Context
import app.moonmobile.platform.prefsOf
import app.moonmobile.platform.sameSecret
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's own lock: a password, and with it — never without it — the phone's fingerprint or
 * face. The connections keep running behind a locked screen; only the screen is locked.
 */
@OptIn(ExperimentalEncodingApi::class)
object AppLock {
    private const val FILE = "moonmobile_lock"
    private const val ITERATIONS = 60_000
    const val MIN_PASSWORD = 4

    data class Settings(
        val passwordSet: Boolean = false,
        val finger: Boolean = false,
        val face: Boolean = false,
        /** Locks again when the app comes back after this many seconds away; 0 locks every time. */
        val afterSec: Int = 60,
    )

    private val settingsFlow = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = settingsFlow.asStateFlow()

    private val lockedFlow = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = lockedFlow.asStateFlow()

    @Volatile
    private var loaded = false
    private var awayAt = 0L

    private fun prefs(context: Context) = prefsOf(context, FILE)

    /** Reads the settings once per process; a process that starts with a password starts locked. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        val set = p.getString("hash", null) != null
        settingsFlow.value = Settings(
            passwordSet = set,
            finger = set && p.getBoolean("finger", false),
            face = set && p.getBoolean("face", false),
            afterSec = p.getInt("after", 60),
        )
        lockedFlow.value = set
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        Crypto.pbkdf2Sha256(password, salt, ITERATIONS, 32)

    /** Sets or changes the password; null removes it, and the fingerprint and face with it. */
    fun setPassword(context: Context, password: String?) {
        val p = prefs(context)
        if (password == null) {
            p.edit().remove("hash").remove("salt").putBoolean("finger", false).putBoolean("face", false).apply()
            settingsFlow.value = settingsFlow.value.copy(passwordSet = false, finger = false, face = false)
            lockedFlow.value = false
            return
        }
        val salt = Crypto.randomBytes(16)
        p.edit()
            .putString("salt", Base64.Default.encode(salt))
            .putString("hash", Base64.Default.encode(hash(password, salt)))
            .apply()
        settingsFlow.value = settingsFlow.value.copy(passwordSet = true)
    }

    /** Whether [password] is the app's password. Slow on purpose: call it off the UI thread. */
    fun verify(context: Context, password: String): Boolean {
        val p = prefs(context)
        val stored = p.getString("hash", null) ?: return false
        val salt = Base64.Default.decode(p.getString("salt", "") ?: "")
        val computed = hash(password, salt)
        return sameSecret(Base64.Default.decode(stored), computed)
    }

    /** Fingerprint and face are allowed only with a password set. */
    fun setBiometrics(context: Context, finger: Boolean, face: Boolean) {
        val set = settingsFlow.value.passwordSet
        val f = set && finger
        val fc = set && face
        prefs(context).edit().putBoolean("finger", f).putBoolean("face", fc).apply()
        settingsFlow.value = settingsFlow.value.copy(finger = f, face = fc)
    }

    fun setAfter(context: Context, seconds: Int) {
        prefs(context).edit().putInt("after", seconds).apply()
        settingsFlow.value = settingsFlow.value.copy(afterSec = seconds)
    }

    fun unlock() {
        lockedFlow.value = false
    }

    /**
     * The app's window opened anew — launched from the icon after it was swiped away or closed
     * with Back — rather than turned or returned to: always locked, however short the absence.
     * The process outlives the window (the connection service keeps it), so only this tells a
     * fresh launch apart.
     */
    fun onLaunch() {
        if (settingsFlow.value.passwordSet) lockedFlow.value = true
    }

    /** The app left the screen. */
    fun onAway() {
        awayAt = Crypto.elapsedMs()
    }

    /**
     * The app came back: locks when it was away long enough. The absence counts once: iOS makes
     * the app active again after its own Face ID sheet too, and a second look at the same absence
     * would lock it again right after the face unlocked it, over and over.
     */
    fun onBack() {
        val s = settingsFlow.value
        val away = awayAt
        awayAt = 0L
        if (!s.passwordSet || away == 0L) return
        if (Crypto.elapsedMs() - away >= s.afterSec * 1000L) lockedFlow.value = true
    }

    /** Whether this phone can use a kind of biometrics, and has one enrolled. */
    fun available(context: Context, strong: Boolean): Boolean = Crypto.biometricsAvailable(context, strong)

    /** Whether the fingerprint or face is switched on. */
    fun biometricsOn(): Boolean = settingsFlow.value.let { it.face || it.finger }
}
