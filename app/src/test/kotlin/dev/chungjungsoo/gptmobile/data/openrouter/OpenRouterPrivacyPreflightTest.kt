package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterPrivacyPreflightTest {
    @Test fun incompatiblePrivacyMetadataKeepsItsTypedFailureAndCachesOnlyTheObservation() = runTest {
        val client = mockk<OpenRouterAccountClient>()
        coEvery { client.objectResource(any(), any(), "endpoints/zdr") } returns Json.parseToJsonElement("""{"data":[{"model_id":"vendor/other","tag":"provider/secure"}]}""").jsonObject
        val preflight = OpenRouterPrivacyPreflight(client)
        val policy = OpenRouterProviderRouting(zdr = true, only = listOf("provider"))
        repeat(2) {
            val failure = runCatching { preflight.check("https://openrouter.ai/api/v1", "fixture-credential", "vendor/model", policy) }.exceptionOrNull()
            assertTrue(failure is PrivacyAdmissionException)
            assertTrue(failure?.message.orEmpty().startsWith("POLICY_RESTRICTED"))
        }
        coVerify(exactly = 1) { client.objectResource(any(), "fixture-credential", "endpoints/zdr") }
        assertEquals(true, policy.zdr)
        assertEquals(listOf("provider"), policy.only)
    }

    private val rows = Json.parseToJsonElement("""[{"model_id":"vendor/model","tag":"provider/secure"},{"model_id":"vendor/other","tag":"other"}]""") as JsonArray

    @Test fun requiresMatchingModelAndPreservesProviderRestrictions() {
        assertEquals(true, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true)))
        assertEquals(false, OpenRouterPrivacyPreflight.compatible(rows, "vendor/missing", OpenRouterProviderRouting(zdr = true)))
        assertEquals(false, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true, ignore = listOf("provider"))))
        assertEquals(false, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true, only = listOf("other"))))
        assertEquals(true, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true, only = listOf("provider"))))
    }

    @Test fun incompleteMetadataIsUnknownAndCannotInventCompatibilityOrIncompatibility() {
        assertNull(OpenRouterPrivacyPreflight.compatible(Json.parseToJsonElement("""[{"model_id":"vendor/model"}]""") as JsonArray, "vendor/model", OpenRouterProviderRouting(zdr = true)))
    }

    @Test fun dynamicRoutersAndFamilyAliasesRequireServerResolution() {
        for (model in listOf("openrouter/auto", "openrouter/free", "~vendor/latest")) {
            assertNull(OpenRouterPrivacyPreflight.compatible(rows, model, OpenRouterProviderRouting(zdr = true)))
        }
    }
}
