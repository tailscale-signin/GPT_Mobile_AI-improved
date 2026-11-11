package dev.chungjungsoo.gptmobile.data.openrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterCatalogLimitsTest {
    @Test fun hintsAreIsolatedToOfficialEndpointAndRoute() {
        OpenRouterCatalogLimits.remember(listOf(OpenRouterModelItem("audit/test", contextLength = 200_000, topProvider = OpenRouterTopProvider(maxCompletionTokens = 131_072))))
        assertEquals(131_072, OpenRouterCatalogLimits.forRequest("OPENROUTER", "https://openrouter.ai/api/v1", "audit/test")?.output)
        assertNull(OpenRouterCatalogLimits.forRequest("CUSTOM", "https://openrouter.ai/api/v1", "audit/test"))
        assertNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", "https://other.example", "audit/test"))
        assertNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, "audit/test", "pinned-route")?.output)
        assertNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, "unknown/model"))
    }

    @Test fun rejectsLookalikeOrCredentialBearingUrls() {
        assertFalse(OpenRouterCatalogLimits.officialEndpoint("https://openrouter.ai.example/api/v1"))
        assertFalse(OpenRouterCatalogLimits.officialEndpoint("http://openrouter.ai/api/v1"))
        assertFalse(OpenRouterCatalogLimits.officialEndpoint("https://user:secret@openrouter.ai/api/v1"))
    }

    @Test fun extractionReasoningRequiresAdvertisedSupportOnTheSelectedDefaultRoute() {
        OpenRouterCatalogLimits.remember(
            listOf(
                OpenRouterModelItem("audit/supported", supportedParameters = listOf("reasoning")),
                OpenRouterModelItem("audit/unsupported", supportedParameters = listOf("temperature"))
            )
        )
        assertTrue(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, "audit/supported")?.reasoningControlAdvertised == true)
        assertFalse(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, "audit/unsupported")?.reasoningControlAdvertised == true)
        assertFalse(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, "audit/supported", "pinned-route")?.reasoningControlAdvertised == true)
        assertNull(OpenRouterCatalogLimits.forRequest("CUSTOM", null, "audit/supported"))
    }
}
