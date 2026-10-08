package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFetchResult
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonItemFailure
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductObservation
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductRequest
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadContext
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadError
import dev.chungjungsoo.gptmobile.data.amazon.AmazonSearchRequest
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonNativeToolTest {
    private val market = AmazonFreeMarket.CANADA
    private val item = AmazonProductObservation("B000000001", market, "Headphones", Instant.parse("2026-10-08T00:00:00Z"), "product_page")
    private fun arguments(raw: String) = Json.parseToJsonElement(raw) as JsonObject
    private fun payload(result: AgentToolResult) = (result.content as ToolResultContent.Json).value as JsonObject
    private fun code(result: AgentToolResult) = AmazonProducts.text((payload(result)["errors"] as JsonArray).first() as JsonObject, "code")

    @Test
    fun invalidInputsAndDisabledProfileNeverContactProvider() = runBlocking {
        val provider = FakeProvider()
        val tool = AmazonNativeTool(provider, false, { PluginExecutionSettings() }, { true })
        for (raw in listOf("{}", """{"query":""}""", """{"query":"audio","url":"https://evil.example"}""", """{"query":"audio","maxResults":"2"}""", """{"query":"audio","maxResults":11}""", """{"query":"audio","minPrice":10}""", """{"query":"audio","minPrice":"20","maxPrice":"10"}""", """{"query":"audio","sort":"popular"}""")) {
            assertEquals(raw, "INVALID_ARGUMENT", code(tool.execute("bad", arguments(raw))))
        }
        assertEquals("UNSUPPORTED_MARKETPLACE", code(tool.execute("market", arguments("""{"query":"audio","marketplace":"amazon.ca.evil.example"}"""))))
        val disabled = AmazonNativeTool(provider, false, { PluginExecutionSettings() }, { false })
        assertEquals("PLUGIN_DISABLED", code(disabled.execute("disabled", arguments("""{"query":"audio"}"""))))
        assertEquals(0, provider.calls)
    }

    @Test
    fun detailBoundsAndCaseInsensitiveDuplicatesAreRejected() = runBlocking {
        val provider = FakeProvider()
        val tool = AmazonNativeTool(provider, true, { PluginExecutionSettings() }, { true })
        for (raw in listOf("""{"asins":[]}""", """{"asins":["BAD"]}""", """{"asins":["B000000001","b000000001"]}""", """{"asins":["B000000001"],"query":"extra"}""")) {
            assertEquals("INVALID_ARGUMENT", code(tool.execute("bad", arguments(raw))))
        }
        assertEquals(0, provider.calls)
    }

    @Test
    fun liveConfigurationAndPartialFactsKeepTheirIdentity() = runBlocking {
        val provider = FakeProvider(AmazonFetchResult(listOf(item), listOf(AmazonItemFailure(AmazonReadError.PRICE_UNAVAILABLE, "Missing price", item.asin))))
        val tool = AmazonNativeTool(provider, false, { PluginExecutionSettings(searchResults = 2, amazonDailyRequests = 7) }, { true })
        val result = tool.execute("partial", arguments("""{"query":"audio","maxResults":10,"minPrice":"10.20"}"""))
        assertFalse(result.isError)
        assertEquals(2, provider.request?.maxResults)
        assertEquals("10.20", provider.request?.minimum?.toPlainString())
        assertEquals(7, provider.context?.dailyLimit)
        assertEquals("partial", AmazonProducts.text(payload(result), "status"))
        assertTrue(java.util.UUID.fromString(AmazonProducts.text(payload(result), "requestId")) != null)
        assertEquals("PRICE_UNAVAILABLE", code(result))
        assertEquals(1, (payload(result)["products"] as JsonArray).size)
    }

    @Test
    fun permissionRevocationCancelsInflightLookupAndReturnsTypedFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val permissions = MutableStateFlow(true)
        val provider = object : AmazonProvider {
            override suspend fun search(request: AmazonSearchRequest, context: AmazonReadContext): AmazonFetchResult {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            override suspend fun products(request: AmazonProductRequest, context: AmazonReadContext) = error("unused")
        }
        val tool = AmazonNativeTool(provider, false, { PluginExecutionSettings() }, { permissions.value }, permissions)
        val result = async { tool.execute("revoked", arguments("""{"query":"audio"}""")) }
        started.await()
        permissions.value = false
        assertEquals("PLUGIN_DISABLED", code(result.await()))
        cancelled.await()
    }

    @Test
    fun parentCancellationPropagatesInsteadOfReturningProductFacts() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val provider = object : AmazonProvider {
            override suspend fun search(request: AmazonSearchRequest, context: AmazonReadContext): AmazonFetchResult {
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun products(request: AmazonProductRequest, context: AmazonReadContext) = error("unused")
        }
        val tool = AmazonNativeTool(provider, false, { PluginExecutionSettings() }, { true })
        val result = async { tool.execute("cancelled", arguments("""{"query":"audio"}""")) }
        started.await()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
    }

    @Test
    fun disablingAfterProviderReturnsPreventsPublication() = runBlocking {
        var allowed = true
        val provider = object : AmazonProvider {
            override suspend fun search(request: AmazonSearchRequest, context: AmazonReadContext): AmazonFetchResult {
                allowed = false
                return AmazonFetchResult(listOf(item))
            }
            override suspend fun products(request: AmazonProductRequest, context: AmazonReadContext) = error("unused")
        }
        val result = AmazonNativeTool(provider, false, { PluginExecutionSettings() }, { allowed }).execute("stopped", arguments("""{"query":"audio"}"""))
        assertEquals("PLUGIN_DISABLED", code(result))
        assertTrue((payload(result)["products"] as JsonArray).isEmpty())
    }

    @Test
    fun outputLimitPreservesTypedErrorCoverageAndRequestIdentity() = runBlocking {
        val provider = FakeProvider(AmazonFetchResult(List(10) { item.copy(title = "x".repeat(300)) }, List(10) { AmazonItemFailure(AmazonReadError.PRICE_UNAVAILABLE, "x".repeat(300)) }))
        val result = AmazonNativeTool(provider, false, { PluginExecutionSettings(maxOutputCharacters = 1000) }, { true }).execute("limited", arguments("""{"query":"audio"}"""))
        val data = payload(result)
        assertTrue(data.toString().length <= 1000)
        assertTrue(java.util.UUID.fromString(AmazonProducts.text(data, "requestId")) != null)
        assertEquals("partial", AmazonProducts.text(data, "status"))
        assertEquals(AmazonProducts.SCHEMA, AmazonProducts.text(data, "schema"))
        assertTrue(data.containsKey("coverage"))
        assertEquals("PRICE_UNAVAILABLE", code(result))
    }

    @Test
    fun permissionObserverRemainsActiveWhileHistoryIsBeingSaved() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val permissions = MutableStateFlow(true)
        val tool = AmazonNativeTool(FakeProvider(AmazonFetchResult(listOf(item))), false, { PluginExecutionSettings() }, { permissions.value }, permissions, onFetched = { _, _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        })
        val result = async { tool.execute("revoked-save", arguments("""{"query":"audio"}""")) }
        started.await()
        permissions.value = false
        assertEquals("PLUGIN_DISABLED", code(result.await()))
        cancelled.await()
    }

    @Test
    fun repeatedModelCallIdsStillGetDistinctAcquisitionIdentities() = runBlocking {
        val ids = mutableListOf<String>()
        val tool = AmazonNativeTool(FakeProvider(AmazonFetchResult(listOf(item))), false, { PluginExecutionSettings() }, { true }, onFetched = { requestId, _, _ -> ids += requestId })
        val first = tool.execute("same-model-id", arguments("""{"query":"audio"}"""))
        val second = tool.execute("same-model-id", arguments("""{"query":"audio"}"""))
        assertEquals(2, ids.distinct().size)
        assertEquals(ids, listOf(AmazonProducts.text(payload(first), "requestId"), AmazonProducts.text(payload(second), "requestId")))
    }

    private class FakeProvider(private val result: AmazonFetchResult = AmazonFetchResult(emptyList())) : AmazonProvider {
        var calls = 0
        var request: AmazonSearchRequest? = null
        var context: AmazonReadContext? = null
        override suspend fun search(request: AmazonSearchRequest, context: AmazonReadContext): AmazonFetchResult {
            calls++
            this.request = request
            this.context = context
            return result
        }
        override suspend fun products(request: AmazonProductRequest, context: AmazonReadContext): AmazonFetchResult {
            calls++
            return result
        }
    }
}
