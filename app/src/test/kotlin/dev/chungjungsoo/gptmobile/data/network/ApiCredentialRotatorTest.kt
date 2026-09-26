package dev.chungjungsoo.gptmobile.data.network

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCredentialRotatorTest {

    @Test
    fun `new requests rotate across profiles sharing one connection`() {
        val id = java.util.UUID.randomUUID().toString()
        assertEquals(listOf("a", "b", "c"), ApiCredentialRotator.keysForNewRequest(id, "a\nb\nc"))
        assertEquals(listOf("b", "c", "a"), ApiCredentialRotator.keysForNewRequest(id, "a\nb\nc"))
        assertEquals(listOf("c", "a", "b"), ApiCredentialRotator.keysForNewRequest(id, "a\nb\nc"))
        assertEquals(listOf("a", "b", "c"), ApiCredentialRotator.keysForNewRequest(id, "a\nb\nc"))
    }

    @Test
    fun `rotation handles removed duplicate and blank keys`() {
        val id = java.util.UUID.randomUUID().toString()
        ApiCredentialRotator.keysForNewRequest(id, "a,b,c")
        assertEquals(listOf("b", "a"), ApiCredentialRotator.keysForNewRequest(id, "a,b,b"))
        assertEquals(listOf("a"), ApiCredentialRotator.keysForNewRequest(id, " a "))
        assertEquals(listOf(""), ApiCredentialRotator.keysForNewRequest(id, null))
    }

    @Test
    fun parseKeys_splitsNewlineAndCommaDelimitedTokens() {
        val raw = "key1\nkey2,key3\r\nkey4,  key5  \n\nkey1"
        val parsed = ApiCredentialRotator.parseKeys(raw)

        assertEquals(listOf("key1", "key2", "key3", "key4", "key5"), parsed)
    }

    @Test
    fun parseKeys_handlesEmptyOrNullStrings() {
        assertEquals(emptyList<String>(), ApiCredentialRotator.parseKeys(null))
        assertEquals(emptyList<String>(), ApiCredentialRotator.parseKeys(""))
        assertEquals(emptyList<String>(), ApiCredentialRotator.parseKeys("   \n\n  "))
    }

    @Test
    fun formatKeys_joinsDistinctKeysWithNewlines() {
        val keys = listOf("tokenA", " tokenB ", "tokenA", "")
        val formatted = ApiCredentialRotator.formatKeys(keys)

        assertEquals("tokenA\ntokenB", formatted)
    }

    @Test
    fun isRotatableError_detectsRateLimitsAndCreditErrors() {
        val rateLimitException = Exception("Rate limit reached for requests per minute (TPM/RPM exceeded)")
        val quotaException = Exception("insufficient_quota: You exceeded your current quota, please check your plan and billing details.")
        val mistralRateLimitException = Exception("HTTP 429: {\"object\":\"error\",\"message\":\"Rate limit exceeded\",\"type\":\"rate_limited\",\"param\":null,\"code\":\"1300\",\"raw_status_code\":429}")
        val regularException = IllegalArgumentException("Invalid argument")

        assertTrue(ApiCredentialRotator.isRotatableError(rateLimitException))
        assertTrue(ApiCredentialRotator.isRotatableError(quotaException))
        assertTrue(ApiCredentialRotator.isRotatableError(mistralRateLimitException))
        assertFalse(ApiCredentialRotator.isRotatableError(regularException))
    }

    @Test
    fun isRotatableError_detectsNestedQuotaCauses() {
        val root = IOException("Connection failed", RuntimeException("429 Too Many Requests: payment required"))
        assertTrue(ApiCredentialRotator.isRotatableError(root))
    }

    @Test
    fun executeWithRotation_rotatesToNextKeyOnRotatableFailure() = runTest {
        val raw = "bad_key_1\ngood_key_2\nunused_key_3"
        val attempts = mutableListOf<String>()

        val result = ApiCredentialRotator.executeWithRotation(raw, startIndex = 0) { key ->
            attempts.add(key)
            if (key == "bad_key_1") {
                throw Exception("429 rate limit reached")
            }
            "success with $key"
        }

        assertEquals("success with good_key_2", result)
        assertEquals(listOf("bad_key_1", "good_key_2"), attempts)
    }

    @Test
    fun executeWithRotation_terminatesImmediatelyOnNonRotatableFailure() = runTest {
        val raw = "key_1\nkey_2"
        val attempts = mutableListOf<String>()

        var caught: Throwable? = null
        try {
            ApiCredentialRotator.executeWithRotation(raw, startIndex = 0) { key ->
                attempts.add(key)
                throw IllegalArgumentException("Malformed JSON request body")
            }
        } catch (t: Throwable) {
            caught = t
        }

        assertTrue(caught is IllegalArgumentException)
        assertEquals(listOf("key_1"), attempts)
    }

    @Test
    fun keyRotator_roundRobinAdvancesIndices() = runTest {
        val rotator = ApiCredentialRotator.KeyRotator("key_A\nkey_B")
        val calls = mutableListOf<String>()

        rotator.execute { key ->
            calls.add(key)
            "ok"
        }
        rotator.execute { key ->
            calls.add(key)
            "ok"
        }
        rotator.execute { key ->
            calls.add(key)
            "ok"
        }

        assertEquals(listOf("key_A", "key_B", "key_A"), calls)
    }
}
