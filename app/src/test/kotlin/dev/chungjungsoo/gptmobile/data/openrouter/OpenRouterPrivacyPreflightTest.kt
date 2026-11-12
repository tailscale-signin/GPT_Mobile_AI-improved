package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterPrivacyPreflightTest {
    @Test fun cachedPrivacyRejectionCapturesItsOriginalObservationWithoutAnotherProbe() = runTest {
        val client = mockk<OpenRouterAccountClient>()
        val preflight = OpenRouterPrivacyPreflight(client)
        val policy = OpenRouterProviderRouting(zdr = true, allowFallbacks = false, order = listOf("private-provider"))
        preflight.recordRejection("https://openrouter.ai/api/v1", "fixture-credential", "vendor/model", policy, "POLICY_RESTRICTED")
        val failure = runCatching { preflight.check("https://openrouter.ai/api/v1", "fixture-credential", "vendor/model", policy) }.exceptionOrNull() as PrivacyAdmissionException
        val context = IssueReportContext.fromFields("CHAT_FAILURE", failure.asApiError("privacy-run").failureDetails.orEmpty(), 1_000_000)
        val snapshot = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive("CACHED_REJECTION"), snapshot.values["privacy_observation"])
        assertEquals(JsonPrimitive(false), snapshot.values["privacy_fallbacks_allowed"])
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).contains("private-provider"))
        assertEquals(IssueReportContext.identifierHash("run", "privacy-run"), snapshot.values["run_id_hash"]?.let { (it as JsonPrimitive).content })
        coVerify(exactly = 0) { client.objectResource(any(), any(), any()) }
    }

    @Test fun incompatiblePrivacyMetadataKeepsItsTypedFailureAndCachesOnlyTheObservation() = runTest {
        val client = mockk<OpenRouterAccountClient>()
        coEvery { client.objectResource(any(), any(), "endpoints/zdr") } returns Json.parseToJsonElement("""{"data":[{"model_id":"vendor/other","tag":"provider/secure"}]}""").jsonObject
        val preflight = OpenRouterPrivacyPreflight(client)
        val policy = OpenRouterProviderRouting(zdr = true, only = listOf("provider"))
        repeat(2) {
            val failure = runCatching { preflight.check("https://openrouter.ai/api/v1", "fixture-credential", "vendor/model", policy) }.exceptionOrNull()
            assertTrue(failure is PrivacyAdmissionException)
            assertTrue(failure?.message.orEmpty().startsWith("POLICY_RESTRICTED"))
            val error = (failure as PrivacyAdmissionException).asApiError("privacy-run", "private-profile")
            val context = IssueReportContext.fromFields("CHAT_FAILURE", error.failureDetails.orEmpty(), 1_000_000)
            val diagnostic = requireNotNull(context.diagnostics)
            assertEquals(JsonPrimitive("NO_COMPATIBLE_ENDPOINT"), diagnostic.values["privacy_observation"])
            assertEquals(JsonPrimitive(true), diagnostic.values["privacy_zdr_required"])
            assertEquals(JsonPrimitive(1), diagnostic.values["privacy_provider_allowlist_count"])
            assertEquals(false, context.dispatched)
            assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).contains("fixture-credential"))
            assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).contains("vendor/model"))
            assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).contains("private-profile"))
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
