package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.PromptBudgets
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestByteBudgetTest {
    private val config = ProviderRequestConfig("https://provider.example/v1", "private-token", profileUid = "profile", permissionRevision = "revision")
    private val payload = """{"messages":[{"role":"user","content":"Keep the accepted goal 😀"},{"role":"tool","content":"${"source finding ".repeat(1000)}"}],"tools":[{"name":"read","description":"Read source"}]}"""
    private fun budget() = PromptBudgets.capture(payload, RequestAdmission.estimate(payload), 256, 32_768, "provider-json-v1")

    @Test fun finalWireCountsIncludeUtf8WithoutRetainingPrivateContents() {
        val receipt = budget()
        assertEquals(payload.toByteArray(Charsets.UTF_8).size.toLong(), receipt.serializedRequestBytes)
        val fields = RequestByteBudget.fields(receipt)
        assertTrue((fields["request_tool_schema_bytes"] as JsonPrimitive).longOrNull!! > 0)
        assertTrue((fields["request_evidence_bytes"] as JsonPrimitive).longOrNull!! > 10_000)
        assertFalse(Json.encodeToString(receipt).contains("source finding"))
        assertFalse(fields.toString().contains("private-token"))
        assertEquals(JsonPrimitive("UNREPORTED"), fields["provider_request_limit_source"])
    }

    @Test fun responseLengthAndMalformedHeadersNeverCreateRequestCapacity() {
        assertNull(RequestByteBudget.responseLimit { if (it == "Content-Length") "1000" else null })
        for (invalid in listOf("0", "-1", "1e6", "Infinity", "2147483648", "2000, 3000")) {
            assertNull(RequestByteBudget.responseLimit { invalid })
        }
        assertEquals(2048L, RequestByteBudget.responseLimit { if (it == "X-Gateway-Max-Request-Bytes") "4096" else "2048" })
    }

    @Test fun allProviderToolResultEnvelopesRetainSeparateEvidenceByteCounts() {
        val envelopes = listOf(
            """{"input":[{"role":"user","content":"Keep goal"},{"type":"function_call_output","call_id":"call","output":"source"}]}""",
            """{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"call","content":"source"}]}]}""",
            """{"contents":[{"role":"user","parts":[{"functionResponse":{"name":"read","response":{"source":"finding"}}}]}]}"""
        )
        for (envelope in envelopes) {
            val receipt = PromptBudgets.capture(envelope, RequestAdmission.estimate(envelope), 256, 4096, "provider-json-v1")
            assertTrue(receipt.components.any { it.name == "tool_results" && it.serializedBytes > 0 })
            assertEquals(envelope.toByteArray().size.toLong(), receipt.serializedRequestBytes)
        }
    }

    @Test fun onlyObservedRejectedRequestMintsOneUseReplanEvidence() = runTest {
        val scope = RequestByteBudgetScope()
        withContext(scope) {
            val rejected = requestPayloadRejection(413, payload, config, "model", false) { null }
            assertNotNull(RequestByteBudget.replayTarget(rejected, 5000, 5000))
            assertTrue(scope.consumeReplan(rejected))
            assertFalse(scope.consumeReplan(rejected))
            assertFalse(scope.consumeReplan(JsonObject(rejected + ("request_serialized_bytes" to JsonPrimitive(1)))))
            assertTrue(requestPayloadRejection(500, payload, config, "model", false) { null }.isEmpty())
        }
    }

    @Test fun acceptedOutputAndRemoteJobIdentityCannotAuthorizeReplay() = runTest {
        val scope = RequestByteBudgetScope()
        withContext(scope) {
            for ((route, partial, job) in listOf(Triple(config, true, null), Triple(config, false, "saved-job"), Triple(config.copy(requireGatewayConversation = true), false, null))) {
                val fields = requestPayloadRejection(413, payload, route, "model", partial) { if (it == "X-Gateway-Job-ID") job else null }
                assertEquals(JsonPrimitive(false), fields["payload_replan_allowed"])
                assertNull(RequestByteBudget.replayTarget(fields, 5000, 5000))
                assertFalse(scope.consumeReplan(fields))
            }
        }
    }

    @Test fun observedByteCeilingRejectsBeforeDispatchAndDoesNotCrossRouteIdentity() = runTest {
        val scope = RequestByteBudgetScope()
        withContext(scope) {
            requestPayloadRejection(413, payload, config, "model", false) { if (it == "X-Max-Request-Bytes") "4096" else null }
            val failure = runCatching { requireRequestByteBudget(budget(), config, "model") }.exceptionOrNull()
            assertTrue(failure is RequestByteLimitException)
            val detail = providerExceptionDetails(failure as RequestByteLimitException, false)
            assertEquals(RequestByteBudget.CODE, detail?.code)
            assertEquals(JsonPrimitive("NOT_DISPATCHED"), detail?.failureDetails?.get("attempt_dispatch_state"))
            assertTrue(scope.consumeReplan(detail?.failureDetails))
            for (changed in listOf(config.copy(token = "new-token"), config.copy(permissionRevision = "new-revision"), config.copy(apiUrl = "https://other.example/v1"))) {
                requireRequestByteBudget(budget(), changed, "model")
            }
            requireRequestByteBudget(budget(), config, "other-model")
        }
    }

    @Test fun impossibleFixedEnvelopeDoesNotLoopOrDropAcceptedRequest() {
        val fields = JsonObject(RequestByteBudget.fields(budget(), 100) + ("payload_replan_allowed" to JsonPrimitive(true)))
        assertNull(RequestByteBudget.replayTarget(fields, 5000, 5000))
        val missing = JsonObject(fields - "request_serialized_bytes")
        assertNull(RequestByteBudget.replayTarget(missing, 5000, 5000))
    }

    @Test fun providerSchemaWindowUsesOnlyMeasuredBytesAndCannotHideAnOversizedFixedRequest() {
        val fields = JsonObject(
            mapOf(
                "request_serialized_bytes" to JsonPrimitive(10000),
                "request_tool_schema_bytes" to JsonPrimitive(6000),
                "payload_replan_allowed" to JsonPrimitive(true)
            )
        )
        assertEquals(500, RequestByteBudget.schemaTarget(fields, 1000))
        assertEquals(320, RequestByteBudget.schemaTarget(JsonObject(fields + ("provider_request_byte_limit" to JsonPrimitive(6040))), 1000))
        assertNull(RequestByteBudget.schemaTarget(JsonObject(fields + ("provider_request_byte_limit" to JsonPrimitive(3000))), 1000))
        assertNull(RequestByteBudget.schemaTarget(JsonObject(fields + ("payload_replan_allowed" to JsonPrimitive(false))), 1000))
        assertNull(RequestByteBudget.schemaTarget(JsonObject(fields - "request_serialized_bytes"), 1000))
    }

    @Test fun reportsKeepLargeByteCountsAndRequireMatchingServiceFeature() {
        val fields = mapOf<String, Any?>(
            "diagnostic_area" to "PROVIDER", "diagnostic_capture_mode" to "INCIDENT",
            "provider_failure_code" to RequestByteBudget.CODE,
            "request_serialized_bytes" to 20_000_000L, "request_tool_schema_bytes" to 1000L,
            "request_evidence_bytes" to 19_000_000L, "provider_request_byte_limit" to 16_777_216L,
            "provider_request_limit_source" to "RESPONSE_HEADER", "payload_replan_allowed" to false
        )
        val report = IssueReportDiagnostics.fromFields("STRUCTURED_EVENT", fields, 1234L)
        assertNotNull(report)
        assertTrue(requireNotNull(report).isValid())
        assertEquals(JsonPrimitive(20_000_000L), report.values["request_serialized_bytes"])
        assertTrue(RequestByteBudget.FEATURE in report.requiredServiceFeatures())
    }
}
