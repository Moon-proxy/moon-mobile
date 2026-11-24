package app.moonmobile.core

import app.moonmobile.platform.Ios
import app.moonmobile.platform.PlatformContext

/** The iOS keychain, through the Swift app. */
actual object SecureStore {
    actual fun put(context: PlatformContext, name: String, value: String?): Boolean = Ios.host.keychainSet(name, value)
    actual fun get(context: PlatformContext, name: String): String? = Ios.host.keychainGet(name)
    actual fun has(context: PlatformContext, name: String): Boolean = Ios.host.keychainGet(name) != null
}
