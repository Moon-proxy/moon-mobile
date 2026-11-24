package app.moonmobile.platform

import android.os.SystemClock
import androidx.biometric.BiometricManager
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

actual object Crypto {
    actual fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, bytes * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    actual fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

    actual fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    actual fun biometricsAvailable(context: PlatformContext, strong: Boolean): Boolean {
        val kind = if (strong) BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK
        return BiometricManager.from(context).canAuthenticate(kind) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
