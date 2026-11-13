package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorDiagnosticsTest {
    @Test fun knownAttemptObservationsSurviveWithoutUsageAndNeverRetainResponseText() {
        val body = """{"error":{"code":"completion_repair_budget_exhausted","gateway_recovery":{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":1536,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":11,"terminal_error_code":"completion_repair_budget_exhausted","terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED","response":"private reasoning"}],"request":"private prompt"}}}"""
        val details = requireNotNull(structuredProviderFailure(body, 502))
        val recovery = details.getValue("gateway_recovery") as kotlinx.serialization.json.JsonObject
        val receipt = (recovery.getValue("attempt_receipts") as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(11), receipt["reasoning_output_bytes"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("LENGTH"), receipt["finish_reason"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(1536), receipt["output_allowance"])
        assertFalse(recovery.containsKey("usage_origin"))
        org.junit.Assert.assertNull(dev.chungjungsoo.gptmobile.data.accounting.GatewayRecoveryUsage.from(details))
        assertFalse(details.toString().contains("private"))
    }

    @Test fun attemptReceiptParserBoundsEveryFieldAndRejectsUnboundedOrAmbiguousRows() {
        val row = """{"attempt":1,"output_allowance":9223372036854775807,"finish_reason":"private finish","visible_output_bytes":-1,"reasoning_output_bytes":"11","terminal_error_code":"private error","terminal_status_code":900,"backend_response_observed":"true","status":"private state","text":"private output"}"""
        fun parse(rows: String, version: Int = 1) = requireNotNull(structuredProviderFailure("""{"error":{"code":"backend_timeout","gateway_recovery":{"schema_version":$version,"attempt_receipts":$rows}}}"""))
        val recovery = parse("[$row]").getValue("gateway_recovery") as kotlinx.serialization.json.JsonObject
        val receipt = (recovery.getValue("attempt_receipts") as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        for (key in listOf("output_allowance", "visible_output_bytes", "reasoning_output_bytes", "terminal_error_code", "terminal_status_code", "backend_response_observed")) {
            org.junit.Assert.assertEquals(key, kotlinx.serialization.json.JsonNull, receipt[key])
        }
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("UNKNOWN"), receipt["finish_reason"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("UNKNOWN"), receipt["status"])
        assertFalse(recovery.toString().contains("private"))
        assertFalse(dev.chungjungsoo.gptmobile.data.accounting.GatewayRecoveryEvidence.observedBackendExecution(recovery))
        for (rows in listOf("[$row,$row,$row]", "[$row,$row]", "[42]")) assertFalse(parse(rows).containsKey("gateway_recovery"))
        assertFalse(parse("[$row]", 2).containsKey("gateway_recovery"))
    }

    @Test fun conflictingCommandWithAnUnknownOutcomeCannotBeClassifiedAsUnsent() {
        val details = requireNotNull(structuredProviderFailure("""{"error":{"code":"conversation_conflict","command_outcome_unknown":true,"retryable":false,"dispatch_state":"not_dispatched","dispatched":false}}""", 409))
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("GATEWAY_OUTCOME_UNKNOWN"), details["code"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(true), details["requires_reconciliation"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details["request_replay_allowed"])
        assertFalse(details.containsKey("dispatched"))
        assertFalse(details.containsKey("dispatch_state"))
    }

    @Test fun conversationConflictRetainsActiveJobGuidanceWithoutClaimingItsOwnership() {
        val body = """{"error":{"code":"conversation_conflict","active_job_id":"old-job","active_job_kind":"generation","active_job_state":"waiting_for_client_tool","conversation_id":"conversation-1","conversation_revision":7,"failed_stage":"conversation_admission","recovery_action":"return_client_tool_results","allowed_recovery_actions":["open_active_job","return_client_tool_results"],"retryable":false,"retryable_without_new_input":false,"dispatch_state":"not_dispatched","private":"prompt"}}"""
        val details = requireNotNull(structuredProviderFailure(body, 409))
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("CONVERSATION_CONFLICT"), details["code"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("old-job"), details["active_job_id"])
        assertFalse(details.containsKey("job_id"))
        assertFalse(details.containsKey("private"))
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider("HTTP 409", "conversation_conflict", details)
        org.junit.Assert.assertEquals("CONVERSATION_CONFLICT", failure.category)
        org.junit.Assert.assertEquals(false, failure.dispatched)
        org.junit.Assert.assertEquals(listOf("RETURN_CLIENT_TOOL_RESULTS", "OPEN_ACTIVE_JOB"), failure.recoveryActions)
        assertFalse(failure.retryAllowed)
        org.junit.Assert.assertEquals("conversation_admission", failure.phase)
        val terminal = dev.chungjungsoo.gptmobile.data.agent.AgentRunTerminalDiagnostics.from("FAILED", failure)
        org.junit.Assert.assertEquals("RETURN_CLIENT_TOOL_RESULTS", terminal.recoveryAction)
        org.junit.Assert.assertEquals("CONVERSATION_CONFLICT", terminal.reason)
        val malicious = requireNotNull(structuredProviderFailure(body.replace("[\"open_active_job\",\"return_client_tool_results\"]", "[\"replay_mutation\"]"), 409))
        assertFalse(malicious.containsKey("allowed_recovery_actions"))
    }

    @Test fun templateSourceIsNotRetainedAndItsTypedFailureSurvivesLegacyHttp500() {
        val body = """{"error":{"message":"Jinja Exception: System message must be at the beginning. PRIVATE TEMPLATE SOURCE","backend_status":500,"dispatch_state":"backend_rejected","origin":"backend"}}"""
        val details = requireNotNull(structuredProviderFailure(body, 500))
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("BACKEND_TEMPLATE_INVALID"), details["code"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details["retryable"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(500), details["http_status"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("backend_template"), details["failed_stage"])
        assertFalse(providerErrorDetails(body, "HTTP 500").contains("PRIVATE TEMPLATE SOURCE"))
        assertFalse(details.toString().contains("PRIVATE TEMPLATE SOURCE"))
    }

    @Test fun failedRepairPreservesOnlyExactBoundedAttemptUsage() {
        val body = """{"error":{"code":"completion_repair_budget_exhausted","terminal_status_code":502,"gateway_recovery":{"attempts":2,"output_allowance":512,"usage_origin":"PROVIDER_REPORTED","usage_estimated":false,"attempt_usage":[{"prompt_tokens":12,"completion_tokens":0,"total_tokens":12,"raw":"private source"},{"completion_tokens":-1,"total_tokens":"23"}],"attempt_backend_tokens":[{"prompt_n":12,"predicted_n":0}],"request":"private prompt"}}}"""
        val details = requireNotNull(structuredProviderFailure(body))
        val recovery = details["gateway_recovery"] as kotlinx.serialization.json.JsonObject
        val usage = recovery["attempt_usage"] as kotlinx.serialization.json.JsonArray
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(0), (usage[0] as kotlinx.serialization.json.JsonObject)["completion_tokens"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonObject(emptyMap()), usage[1])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(502), details["terminal_status_code"])
        assertFalse(recovery.containsKey("total_tokens"))
        assertFalse(details.toString().contains("private"))
        assertFalse(requireNotNull(structuredProviderFailure(body.replace("\"usage_estimated\":false", "\"usage_estimated\":true"))).containsKey("gateway_recovery"))
    }

    @Test fun gatewayFailureRetainsOriginalCodeStageAndIdentifiersOnly() {
        val body = """{"error":{"code":"client_tool_results_required","failed_stage":"tool_continuation","job_id":"job-1","request_id":"request-1","dispatch_state":"not_dispatched","retryable":false,"request":"private prompt"}}"""
        val details = requireNotNull(structuredProviderFailure(body))
        assertTrue(details.toString().contains("tool_continuation"))
        assertTrue(details.toString().contains("job-1"))
        assertFalse(details.containsKey("request"))
        assertTrue(providerErrorDetails(body, "HTTP 409").contains("retryable=false"))
    }

    @Test fun `OpenRouter metadata explains generic errors without exposing secrets or request echoes`() {
        val result = providerErrorDetails(
            """{"id":"gen-123","error":{"code":400,"message":"Provider returned error","metadata":{"provider_name":"Example","raw":"{\"error\":{\"message\":\"Invalid tool schema. Credential custom-secret\"},\"request\":\"PRIVATE PROMPT\"}","authorization":"OTHER SECRET"}}}""",
            "HTTP 400",
            "custom-secret"
        )
        assertTrue(result.contains("Invalid tool schema"))
        assertTrue(result.contains("provider=Example"))
        assertTrue(result.contains("generation=gen-123"))
        assertFalse(result.contains("custom-secret"))
        assertFalse(result.contains("OTHER SECRET"))
        assertFalse(result.contains("PRIVATE PROMPT"))
    }

    @Test fun `non JSON errors do not dump arbitrary response bodies`() {
        assertTrue(providerErrorDetails("<html>private content</html>", "HTTP 502") == "HTTP 502")
    }
}
