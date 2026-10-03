package dev.chungjungsoo.gptmobile.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** This authentication key gates the UI; it does not change background vault access. */
internal object AndroidAppLock {
    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "gpt-mobile-app-lock-v1"

    @Synchronized
    fun createChallenge(): AppLockChallenge {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return try {
            createChallenge(store)
        } catch (_: KeyPermanentlyInvalidatedException) {
            // No stored data uses this key. A new operation still requires a fresh prompt.
            store.deleteEntry(KEY_ALIAS)
            createChallenge(store)
        }
    }

    private fun createChallenge(store: KeyStore): AppLockChallenge {
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                        .build()
                )
            }.generateKeyPair()
        }
        val entry = store.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val signature = Signature.getInstance(AppLockChallenge.ALGORITHM).apply { initSign(entry.privateKey) }
        return AppLockChallenge(signature, entry.certificate.publicKey)
    }
}
