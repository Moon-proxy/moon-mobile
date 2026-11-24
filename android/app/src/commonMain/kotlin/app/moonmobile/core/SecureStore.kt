package app.moonmobile.core

import app.moonmobile.platform.PlatformContext

/** Secrets kept encrypted by the platform: Android's keystore, the iOS keychain. */
expect object SecureStore {
    /** Keeps [value] under [name]; null removes it. False when it could not be kept. */
    fun put(context: PlatformContext, name: String, value: String?): Boolean

    fun get(context: PlatformContext, name: String): String?

    fun has(context: PlatformContext, name: String): Boolean
}
