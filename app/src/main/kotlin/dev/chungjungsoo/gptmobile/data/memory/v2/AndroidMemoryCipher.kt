package dev.chungjungsoo.gptmobile.data.memory.v2

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/** Separate key lifecycle; a missing key never causes record deletion or regeneration on read. */
@Singleton
class AndroidMemoryCipher @Inject constructor() : MemoryCipher {
    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Memory encryption key is unavailable. Encrypted records have been preserved." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    override fun encrypt(plaintext: ByteArray, context: MemoryCipherContext): ByteArray = AesGcmMemoryCipher(key(true)).encrypt(plaintext, context)
    override fun decrypt(envelope: ByteArray, context: MemoryCipherContext): ByteArray = AesGcmMemoryCipher(key(false)).decrypt(envelope, context)
    private companion object {
        const val KEY_ALIAS = "gpt-mobile-memory-v2"
    }
}
