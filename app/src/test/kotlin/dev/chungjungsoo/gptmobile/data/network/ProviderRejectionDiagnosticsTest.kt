package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRejectionDiagnosticsTest {
    @Test fun rejectedResponseSchemaRequiresAChangedRequest() {
        val rejection = ProviderRejectionDiagnostics.response(
            400,
            """{"error":{"code":"invalid_json_schema","param":"response_format","message":"private data"}}""",
            ProviderRejectionDiagnostics.Contract.CHAT_COMPLETIONS
        )
        assertEquals(JsonPrimitive(false), rejection["retryable"])
        assertEquals(JsonPrimitive("RETRY_AFTER_CONFIGURATION_CHANGE"), rejection["recovery_action"])
        assertFalse("private data" in rejection.toString())
    }

    @Test fun wrappedUpstreamSchemaRejectionRetainsOnlySanitizedParameterEvidence() {
        val result = ProviderRejectionDiagnostics.response(
            400,
            """{"error":{"code":400,"message":"Provider returned error","metadata":{"raw":"{\"error\":{\"code\":\"invalid_json_schema\",\"param\":\"response_format.private-field\",\"message\":\"private detail\"}}"}}}""",
            ProviderRejectionDiagnostics.Contract.CHAT_COMPLETIONS
        )
        assertEquals(JsonPrimitive(false), result["retryable"])
        assertEquals(JsonPrimitive("response_format"), result["provider_parameter"])
        assertTrue("provider_parameter_path_hash" in result)
        assertFalse("private" in result.toString())
    }

    @Test fun authenticationRejectionCanBeCorrelatedWithItsConfiguredRouteWithoutExportingSecrets() {
        fun denied(endpoint: String) = ProviderRejectionDiagnostics.response(401, "{}", ProviderRejectionDiagnostics.Contract.OPENAI_RESPONSES, endpoint)
        val plain = denied("https://example.com/v1/responses")
        val secret = denied("https://user:private@example.com/v1/responses?token=private#private")
        assertEquals(JsonPrimitive("AUTHENTICATION"), plain["provider_rejection_category"])
        assertEquals(JsonPrimitive(false), plain["retryable"])
        assertEquals(plain["provider_route_hash"], secret["provider_route_hash"])
        assertTrue(plain["provider_route_hash"] != null && plain["provider_route_hash"] != denied("https://example.com/v2/responses")["provider_route_hash"])
        assertFalse(secret.toString().contains("private"))
    }

    @Test fun responsesRejectionsRetainOnlyTheParameterRootAndTheWireContract() {
        val result = ProviderRejectionDiagnostics.response(400, """{"error":{"param":"input[3].private_field","message":"private text"}}""", ProviderRejectionDiagnostics.Contract.OPENAI_RESPONSES)
        assertEquals(JsonPrimitive("OPENAI_RESPONSES"), result["payload_contract"])
        assertEquals(JsonPrimitive("input"), result["provider_parameter"])
        assertFalse(result.toString().contains("private"))
    }

    @Test fun notFoundDistinguishesOnlyExplicitModelUnavailability() {
        val explicit = ProviderRejectionDiagnostics.response(404, """{"error":{"code":"model_not_found","message":"private model identifier"}}""", ProviderRejectionDiagnostics.Contract.CHAT_COMPLETIONS)
        assertEquals(JsonPrimitive("MODEL_UNAVAILABLE"), explicit["provider_rejection_category"])
        assertFalse(explicit.toString().contains("private model identifier"))
        val ambiguous = ProviderRejectionDiagnostics.response(404, """{"error":{"code":404,"message":"model_not_found in provider prose"}}""", ProviderRejectionDiagnostics.Contract.CHAT_COMPLETIONS)
        assertEquals(JsonPrimitive("NOT_FOUND"), ambiguous["provider_rejection_category"])
        assertFalse("provider_error_type_hash" in ambiguous)
    }

    @Test fun httpGoneRecordsStatusCategoryWithoutInventingAModelDeprecation() {
        val result = ProviderRejectionDiagnostics.response(410, """{"message":"private-model has changed","error":{"type":"private-provider-type","message":"private endpoint detail"}}""", ProviderRejectionDiagnostics.Contract.CHAT_COMPLETIONS)
        assertEquals(JsonPrimitive("GONE"), result["provider_rejection_category"])
        assertEquals(JsonPrimitive("CHAT_COMPLETIONS"), result["payload_contract"])
        assertTrue((result["provider_error_type_hash"] as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")))
        assertFalse(result.toString().contains("private-"))
        assertFalse("provider_parameter" in result)
    }

    @Test fun rejectionRetainsOnlyKnownParameterRootAndHashedFullPath() {
        val result = ProviderRejectionDiagnostics.response(400, """{"type":"error","error":{"type":"invalid_request_error","param":"messages[3].private-field","message":"secret-prompt","value":"secret-value"}}""", ProviderRejectionDiagnostics.Contract.ANTHROPIC_MESSAGES)
        assertEquals(JsonPrimitive("INVALID_REQUEST"), result["provider_rejection_category"])
        assertEquals(JsonPrimitive("messages"), result["provider_parameter"])
        assertEquals(JsonPrimitive("ANTHROPIC_MESSAGES"), result["payload_contract"])
        assertTrue((result["provider_parameter_path_hash"] as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")))
        for (secret in listOf("private-field", "secret-prompt", "secret-value", "invalid_request_error")) assertFalse(result.toString().contains(secret))
        val unknown = ProviderRejectionDiagnostics.response(400, """{"error":{"type":"private-parameter","param":"private-parameter"}}""", ProviderRejectionDiagnostics.Contract.CHAT_COMPLETIONS)
        assertFalse("provider_parameter" in unknown)
        assertTrue(unknown["provider_error_type_hash"] != unknown["provider_parameter_path_hash"])
    }

    @Test fun malformedResponseAndUnknownTypeLeaveTheMissingCauseUnclaimed() {
        for (body in listOf("not JSON", "[]", "x".repeat(256 * 1024 + 1))) {
            val result = ProviderRejectionDiagnostics.response(400, body, ProviderRejectionDiagnostics.Contract.ANTHROPIC_MESSAGES)
            assertEquals(JsonPrimitive("INVALID_REQUEST"), result["provider_rejection_category"])
            assertFalse("provider_error_type_hash" in result)
            assertFalse("provider_parameter_path_hash" in result)
        }
        val stream = ProviderRejectionDiagnostics.typed(200, "rate_limit_error", contract = ProviderRejectionDiagnostics.Contract.ANTHROPIC_MESSAGES)
        assertEquals(JsonPrimitive("RATE_LIMIT"), stream["provider_rejection_category"])
    }
}
