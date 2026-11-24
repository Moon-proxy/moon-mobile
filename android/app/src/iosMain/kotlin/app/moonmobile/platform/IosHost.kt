package app.moonmobile.platform

/**
 * What the Swift app (ios/) hands the shared code: the Rust bridge's C functions, which Swift
 * links directly, and the iOS services that are simpler in Swift — the keychain, notifications,
 * sounds. Set once at start-up through [Ios.host].
 */
interface IosHost {
    fun bridgeOpen(request: String): String
    fun bridgeCall(request: String): String
    fun bridgeSnapshot(since: Long): String
    fun bridgeTool(request: String): String

    /** One chart frame into [length] bytes at the address [pixels] (premultiplied RGBA). */
    fun bridgeChart(request: String, pixels: Long, length: Long): String
    fun bridgeClose()
    fun bridgeIsOpen(): Boolean
    fun bridgeVersion(): String

    /** The keychain item [name]; null when there is none. */
    fun keychainGet(name: String): String?

    /** Keeps [value] in the keychain under [name]; null removes it. False when it failed. */
    fun keychainSet(name: String, value: String?): Boolean

    fun notificationsAllowed(): Boolean
    /**
     * Shows one notification per [kind], replacing the one shown before for it; [body] is what
     * it says, the kind's list when there is one. [quiet] updates it without a sound.
     */
    fun notify(kind: String, title: String, body: String, quiet: Boolean)

    /** Plays a detect's sound by its name in the strategy. */
    fun playSound(name: String)

    /** Whether Face ID or Touch ID can be used. */
    fun biometricsAvailable(): Boolean

    /** Asks for Face ID or Touch ID; [done] gets whether the owner was recognised. */
    fun askBiometrics(reason: String, done: (Boolean) -> Unit)

    /** Shares files from the app's folders through the iOS share sheet. */
    fun shareFiles(paths: List<String>, title: String)

    /** The document picker; [done] gets the picked file's path in the app's own folder, or null. */
    fun pickDocument(done: (String?) -> Unit)

    /** Holds the app in landscape while [on] (the chart), and lets it turn freely again after. */
    fun setLandscapeOnly(on: Boolean)

    /** Keeps the home-screen widget's state ([json], `WidgetState`) where the widget reads it, and redraws it. */
    fun widgetShow(json: String)

    /** Whether any of the app's widgets is on the home screen, as iOS last said. */
    fun widgetPlaced(): Boolean

    /** How the widget gets its state under this signature, in a line. */
    fun widgetStatus(): String
}

object Ios {
    lateinit var host: IosHost
}
