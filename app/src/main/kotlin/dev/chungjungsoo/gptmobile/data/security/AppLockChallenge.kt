package dev.chungjungsoo.gptmobile.data.security

import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

/** A single-use proof that the prompt authorized this particular Keystore operation. */
internal class AppLockChallenge(
    val signature: Signature,
    private val publicKey: PublicKey
) {
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
    private var consumed = false

    @Synchronized
    fun complete(authorizedSignature: Signature?): Boolean {
        if (consumed) return false
        consumed = true
        return try {
            if (authorizedSignature !== signature) return false
            authorizedSignature.update(nonce)
            val proof = authorizedSignature.sign()
            try {
                Signature.getInstance(ALGORITHM).run {
                    initVerify(publicKey)
                    update(nonce)
                    verify(proof)
                }
            } finally {
                proof.fill(0)
            }
        } catch (_: Exception) {
            false
        } finally {
            nonce.fill(0)
        }
    }

    companion object {
        const val ALGORITHM = "SHA256withECDSA"
    }
}
