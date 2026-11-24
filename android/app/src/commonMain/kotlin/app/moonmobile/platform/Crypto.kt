package app.moonmobile.platform

/** What the app lock needs from the platform: key derivation, randomness, a clock and biometrics. */
expect object Crypto {
    /** PBKDF2 with HMAC-SHA256: [bytes] bytes derived from [password] and [salt]. */
    fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray

    fun randomBytes(count: Int): ByteArray

    /** Milliseconds of a clock that keeps running while the phone sleeps and is never set back. */
    fun elapsedMs(): Long

    /** Whether the phone can use biometrics of this kind and has one enrolled. */
    fun biometricsAvailable(context: PlatformContext, strong: Boolean): Boolean
}
