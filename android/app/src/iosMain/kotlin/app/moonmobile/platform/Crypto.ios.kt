package app.moonmobile.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Foundation.NSProcessInfo
import platform.posix.arc4random_buf

@OptIn(ExperimentalForeignApi::class)
actual object Crypto {
    actual fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        val secret = password.encodeToByteArray()
        val out = ByteArray(bytes)
        salt.usePinned { s ->
            out.usePinned { o ->
                CCKeyDerivationPBKDF(
                    kCCPBKDF2,
                    password,
                    secret.size.convert(),
                    if (salt.isEmpty()) null else s.addressOf(0).reinterpret(),
                    salt.size.convert(),
                    kCCPRFHmacAlgSHA256,
                    iterations.convert(),
                    o.addressOf(0).reinterpret(),
                    bytes.convert(),
                )
            }
        }
        return out
    }

    actual fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        if (count > 0) out.usePinned { arc4random_buf(it.addressOf(0), count.convert()) }
        return out
    }

    actual fun elapsedMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

    actual fun biometricsAvailable(context: PlatformContext, strong: Boolean): Boolean = Ios.host.biometricsAvailable()
}
