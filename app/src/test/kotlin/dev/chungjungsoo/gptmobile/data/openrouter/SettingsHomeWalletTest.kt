package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountException
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletSnapshot
import dev.chungjungsoo.gptmobile.data.openrouter.account.readHomeWallet
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeWalletTest {
    @Test fun authenticatedWalletReadsOnlyTheManagementKeyAndCreditsResources() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val client = OpenRouterAccountClient { url ->
            paths += url.path
            FakeConnection(url, 200, if (url.path.endsWith("/key")) key(true) else """{"data":{"total_credits":"10.00000001","total_usage":"3.00000002"}}""")
        }
        val result = client.readHomeWallet("https://openrouter.ai/api/v1", "synthetic-key", "connection", "Wallet", 7) { 1_000 }
        assertEquals(listOf("/api/v1/key", "/api/v1/credits"), paths)
        assertEquals("6.99999999", result.remainingUsd)
        assertEquals("connection", result.connectionUid)
        assertEquals(7L, result.connectionRevision)
        assertTrue(result.isFresh(1_600))
        assertFalse(result.isFresh(1_601))
        assertFalse(result.isFresh(999))
    }

    @Test fun ordinaryKeyLimitNeverBecomesAWalletBalance() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val client = OpenRouterAccountClient { url ->
            paths += url.path
            FakeConnection(url, 200, key(false, "\"limit_remaining\":\"99\","))
        }
        val failure = runCatching { client.readHomeWallet("https://openrouter.ai/api/v1", "synthetic-key", "connection", "Wallet", 0) }.exceptionOrNull()
        assertEquals(AccountFailure.ROLE, (failure as OpenRouterAccountException).category)
        assertEquals(listOf("/api/v1/key"), paths)
    }

    @Test fun malformedOrOfflineResponsesNeverProduceAnInventedZeroBalance() = runBlocking<Unit> {
        for (payload in listOf("{}", """{"total_credits":"3"}""", """{"total_credits":"NaN","total_usage":"1"}""", """{"total_credits":"-1","total_usage":"1"}""")) {
            assertTrue(runCatching { SettingsHomeWalletSnapshot.fromCredits("id", "Wallet", 0, Json.parseToJsonElement(payload).jsonObject, 0) }.isFailure)
        }
        val client = OpenRouterAccountClient { url -> FakeConnection(url, 503, "private error body") }
        val failure = runCatching { client.readHomeWallet("https://openrouter.ai/api/v1", "synthetic-key", "connection", "Wallet", 0) }.exceptionOrNull()
        assertEquals(AccountFailure.NETWORK, (failure as OpenRouterAccountException).category)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test fun zeroAndNegativeBalancesRemainExact() {
        fun balance(used: String) = SettingsHomeWalletSnapshot.fromCredits("id", "Wallet", 0, Json.parseToJsonElement("""{"total_credits":"10","total_usage":"$used"}""").jsonObject, 0).remainingUsd
        assertEquals("0", balance("10"))
        assertEquals("-0.25", balance("10.25"))
    }

    private fun key(management: Boolean, extra: String = "") = """{"data":{$extra"label":"fixture","usage":"0","is_free_tier":false,"is_management_key":$management}}"""
    private class FakeConnection(url: URL, private val code: Int, private val body: String) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }
}
