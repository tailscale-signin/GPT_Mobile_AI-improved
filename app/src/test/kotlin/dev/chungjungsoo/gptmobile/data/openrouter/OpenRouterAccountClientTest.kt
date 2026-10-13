package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountException
import dev.chungjungsoo.gptmobile.data.openrouter.account.money
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterAccountClientTest {
    @Test fun validationUsesKeyEndpointWithoutRedirectsAndPreservesDecimalMoney() = runBlocking {
        lateinit var captured: FakeConnection
        val client = OpenRouterAccountClient { url -> FakeConnection(url, 200, """{"data":{"label":"fixture","usage":"0.00000001","is_free_tier":true,"is_management_key":false}}""").also { captured = it } }
        val result = client.key("https://openrouter.ai/api/v1", "synthetic-key")
        assertEquals("/api/v1/key", captured.url.path)
        assertFalse(captured.instanceFollowRedirects)
        assertEquals("Bearer synthetic-key", captured.getRequestProperty("Authorization"))
        assertEquals("0.00000001", result.data.money("usage"))
        assertTrue(captured.closed)
    }

    @Test fun httpFailuresAreSanitizedAndCategorized() = runBlocking {
        for ((status, category) in listOf(401 to AccountFailure.INVALID, 402 to AccountFailure.BILLING, 403 to AccountFailure.FORBIDDEN, 429 to AccountFailure.RATE_LIMITED, 302 to AccountFailure.NETWORK)) {
            val client = OpenRouterAccountClient { FakeConnection(it, status, "sensitive raw response") }
            val failure = try {
                client.key("https://openrouter.ai/api/v1", "synthetic")
                null
            } catch (e: OpenRouterAccountException) {
                e
            }
            assertEquals(category, failure?.category)
            assertFalse(failure!!.message.orEmpty().contains("sensitive"))
            assertEquals(23L, failure.retryAfterSeconds)
        }
    }

    @Test fun untrustedOriginNeverReceivesCredential() = runBlocking {
        var opened = false
        val client = OpenRouterAccountClient {
            opened = true
            FakeConnection(it, 200, "{}")
        }
        try {
            client.key("https://example.invalid/api/v1", "synthetic")
            fail()
        } catch (_: OpenRouterAccountException) { }
        assertFalse(opened)
    }
    private class FakeConnection(url: URL, private val code: Int, private val body: String) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() {
            closed = true
        }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getHeaderField(name: String?) = if (name == "Retry-After") "23" else null
    }
}
