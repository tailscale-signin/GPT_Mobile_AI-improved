package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonLocalHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonObservationEntity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonLocalToolTest {
    @Test fun localHistoryHonorsIndependentLocalGateWithoutRequiringRemoteTools() {
        val enabled = AppFeatureSettings().withToolPluginEnabled(ToolPluginId.AMAZON_FREE, true).withProfileToolPluginEnabled("owner", ToolPluginId.AMAZON_FREE, true)
        val profile = PlatformV2(uid = "owner", name = "Research", disableRemoteTools = true)
        assertTrue(AmazonAccessPolicy.permits(enabled, profile, network = false))
        assertFalse(AmazonAccessPolicy.permits(enabled, profile, network = true))
        assertFalse(AmazonAccessPolicy.permits(enabled, profile.copy(disableLocalTools = true), network = false))
        assertFalse(AmazonAccessPolicy.permits(enabled, profile.copy(uid = "other"), network = false))
        assertFalse(AmazonAccessPolicy.permits(enabled, null, network = false))
    }

    @Test fun boundedHistoryUsesCallerOwnerAndAlwaysReturnsValidJson() = runBlocking {
        val repository = mockk<AmazonHistoryRepository>()
        val access = mockk<AmazonAccessPolicy>()
        coEvery { access.allowed("owner", false) } returns true
        val observations = List(100) { index -> AmazonObservationEntity("$index", "owner", "$index", "series", "amazon.ca", "B000000001", "Headphones", "80.00", "CAD", "product_page", index.toLong()) }
        coEvery { repository.history("owner", AmazonFreeMarket.CANADA, "B000000001", 100) } returns AmazonLocalHistory(observations, 100, emptyList())
        val tool = AmazonLocalTool("owner", repository, access, { PluginExecutionSettings(maxOutputCharacters = 1000) })
        val result = tool.execute("history", Json.parseToJsonElement("""{"asin":"B000000001"}""") as JsonObject)
        val data = (result.content as ToolResultContent.Json).value as JsonObject
        assertFalse(result.isError)
        assertTrue(data.toString().length <= 1000)
        assertEquals((data["observations"] as JsonArray).size.toString(), data["returnedObservationCount"].toString())
        assertEquals("true", data["truncated"].toString())
        coVerify(exactly = 1) { repository.history("owner", AmazonFreeMarket.CANADA, "B000000001", 100) }
    }

    @Test fun modelCannotChooseAnotherOwnerOrSmuggleRefreshIntoLocalListing() = runBlocking {
        val repository = mockk<AmazonHistoryRepository>()
        val access = mockk<AmazonAccessPolicy>()
        val tool = AmazonLocalTool("owner", repository, access, { PluginExecutionSettings() }, listWatches = true)
        for (raw in listOf("""{"ownerProfileUid":"other"}""", """{"checkNow":true}""", """{"limit":"20"}""", """{"limit":21}""")) {
            assertTrue(tool.execute("bad", Json.parseToJsonElement(raw) as JsonObject).isError)
        }
        coVerify(exactly = 0) { repository.listWatches(any()) }
    }

    @Test fun revocationAfterReadWithholdsLocalData() = runBlocking {
        val repository = mockk<AmazonHistoryRepository>()
        val access = mockk<AmazonAccessPolicy>()
        coEvery { access.allowed("owner", false) } returnsMany listOf(true, false)
        coEvery { repository.listWatches("owner") } returns emptyList()
        val result = AmazonLocalTool("owner", repository, access, { PluginExecutionSettings() }, listWatches = true).execute("revoked", JsonObject(emptyMap()))
        assertTrue(result.isError)
        assertEquals("\"PLUGIN_DISABLED\"", ((result.content as ToolResultContent.Json).value as JsonObject)["code"].toString())
    }
}
