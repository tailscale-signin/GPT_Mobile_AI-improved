package dev.chungjungsoo.gptmobile.data.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockChallengeTest {
    @Test
    fun `authorized operation unlocks only once`() {
        val pair = keyPair()
        val signature = signer(pair)
        val challenge = AppLockChallenge(signature, pair.public)
        assertTrue(challenge.complete(signature))
        assertFalse(challenge.complete(signature))
    }

    @Test
    fun `callback without crypto proof cannot unlock or retry the challenge`() {
        val pair = keyPair()
        val signature = signer(pair)
        val challenge = AppLockChallenge(signature, pair.public)
        assertFalse(challenge.complete(null))
        assertFalse(challenge.complete(signature))
    }

    @Test
    fun `another prompts operation cannot unlock this challenge`() {
        val pair = keyPair()
        val challenge = AppLockChallenge(signer(pair), pair.public)
        assertFalse(challenge.complete(signer(pair)))
    }

    @Test
    fun `proof from an unexpected key cannot unlock`() {
        val signature = signer(keyPair())
        val challenge = AppLockChallenge(signature, keyPair().public)
        assertFalse(challenge.complete(signature))
    }

    @Test
    fun `failed signing operation stays locked`() {
        val pair = keyPair()
        val uninitialized = Signature.getInstance(AppLockChallenge.ALGORITHM)
        val challenge = AppLockChallenge(uninitialized, pair.public)
        assertFalse(challenge.complete(uninitialized))
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun signer(pair: KeyPair): Signature = Signature.getInstance(AppLockChallenge.ALGORITHM).apply {
        initSign(pair.private)
    }
}
