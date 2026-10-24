package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedGreetingEligibilityTest {
    @Test fun explicitCatalogNoReasoningAvoidsCircularReserveButObservationWins() = runTest {
        val provider = FreeAiProvider.POLLINATIONS
        val profile = PlatformV2(uid = "p", name = "Pollinations", compatibleType = ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)
        FreeCatalogState.resetForTest()
        CombinedWriterObservations.clearForTest()
        try {
            assertNotNull(CombinedWriterPolicy.unavailableReason(profile, "hi"))
            FreeCatalogState.refresh(provider, true, catalogReader = { Json.parseToJsonElement("[{\"name\":\"openai-fast\",\"reasoning\":false}]") })
            assertEquals(false, FreeCatalogState.model(provider, profile.model)?.reasoning)
            assertTrue(CombinedWriterPolicy.requiredOutput("hi", profile) <= 1500)
            assertNull(CombinedWriterPolicy.unavailableReason(profile, "hi"))
            CombinedWriterObservations.recordUsage(CombinedWriterObservations.key(profile), reasoningTokens = 1600)
            assertNotNull(CombinedWriterPolicy.unavailableReason(profile, "hi"))
        } finally {
            FreeCatalogState.resetForTest()
            CombinedWriterObservations.clearForTest()
        }
    }
}
