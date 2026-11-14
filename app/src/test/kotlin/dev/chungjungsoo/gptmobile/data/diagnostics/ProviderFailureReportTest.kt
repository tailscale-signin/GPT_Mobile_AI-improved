package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailureReportTest {
    private val profile = PlatformV2(uid = "private-profile", name = "Private selected profile", model = "private-model", compatibleType = ClientType.LLAMA)

    @Test fun gatewayConflictReportIdentifiesOwnedHandoffWithoutRevealingIds() {
        val details = requireNotNull(
            dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure(
                """{"error":{"code":"conversation_conflict","failed_stage":"conversation_admission","dispatched":false,"active_job_kind":"generation","active_job_state":"WAITING_FOR_CLIENT_TOOL","conversation_id":"private-conversation","conversation_revision":7,"pending_client_tool_count":2}}""",
                409
            )
        )
        val failure = ProviderEvent.Failed("private prompt", errorCode = "CONVERSATION_CONFLICT", failureDetails = details)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        val fields = ProviderFailureReport.fields("run", failure, receipt)
        val capture = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1000))
        assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
        assertEquals(JsonPrimitive("CONVERSATION_CONFLICT"), capture.values["provider_failure_code"])
        assertEquals(JsonPrimitive("generation"), capture.values["conflict_active_job_kind"])
        assertEquals(JsonPrimitive(7), capture.values["conflict_conversation_revision"])
        assertEquals(JsonPrimitive(2), capture.values["conflict_pending_tool_count"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("conversation", "private-conversation")), capture.values["conflict_conversation_hash"])
        assertTrue("SESSION_RECOVERY_DIAGNOSTICS_V1" in capture.requiredServiceFeatures())
        assertFalse(capture.toString().contains("private"))
    }

    @Test fun cooldownReportDistinguishesWaitEligibilityFromCheckpointAvailability() {
        val limit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision(
            provider = "LLAMA",
            profileUid = profile.uid,
            model = profile.model,
            retryAt = 2_000L,
            scope = "model",
            category = "rate_limit",
            knownNotAccepted = true,
            consecutiveRejections = 3
        )
        val failure = ProviderEvent.Failed("private", errorCode = "PROVIDER_RATE_LIMITED", providerLimit = limit)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure, null)
        val fields = ProviderFailureReport.fields("run", failure, receipt)
        assertEquals(JsonPrimitive(true), fields["provider_wait_eligible"])
        assertEquals(JsonPrimitive(false), fields["provider_checkpoint_present"])
        assertEquals(JsonPrimitive(3), fields["provider_consecutive_rejections"])
        val foreign = ProviderFailureReport.fields("run", failure.copy(providerLimit = limit.copy(profileUid = "other")), receipt)
        assertNull(foreign["provider_wait_eligible"])
    }

    @Test fun actualStreamExportsCompletionBudgetCauseCapAndOwnedUsageWithoutAnotherRequest() = runBlocking {
        val failure = ProviderEvent.Failed(
            "private provider detail",
            errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED",
            failureDetails = buildJsonObject {
                put("failed_stage", "provider_generation")
                put("http_status", 200)
                put("job_id", "private-job")
                put("gateway_recovery", Json.parseToJsonElement("""{"schema_version":1,"attempts":2,"completed_attempts":1,"failed_attempt":2,"attempt_receipts":[{"attempt":1,"finish_reason":"LENGTH","status":"COMPLETED","backend_response_observed":true},{"attempt":2,"output_allowance":40,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":512,"terminal_error_code":"completion_repair_budget_exhausted","terminal_status_code":200,"status":"FAILED"}]}"""))
            }
        )
        val events = listOf(
            ProviderEvent.RequestConfigured(configuredProfileOutputTokens = 1024, effectiveOutputTokens = 256, serializedOutputTokens = 128),
            ProviderEvent.Usage(inputTokens = 30, outputTokens = 80, reasoningTokens = 70, outputSource = "gateway_reported"),
            ProviderEvent.Usage(finishReason = "length"),
            failure
        )
        val source = Session(events)
        val observed = withContext(RequestDispatchContext(emptySet(), "owned-run", "owned-invocation")) {
            withProviderFailureReceipts(source, "owned-run", profile, "primary").streamRound(emptyList(), emptyList()).toList()
        }.filterIsInstance<ProviderEvent.Failed>().single()
        val context = IssueReportContext.fromFields("CHAT_FAILURE", observed.failureDetails.orEmpty(), 1_000_000)
        val diagnostic = requireNotNull(context.diagnostics)
        val values = diagnostic.values
        assertEquals(1, source.calls)
        assertEquals(failure.errorCode, observed.errorCode)
        assertEquals(JsonPrimitive(researchHash(profile.model)), values["model_hash"])
        assertEquals(JsonPrimitive(1024), values["configured_output_limit"])
        assertEquals(JsonPrimitive(128), values["effective_output_limit"])
        assertEquals(JsonPrimitive(30), values["input_tokens"])
        assertEquals(JsonPrimitive(80), values["output_tokens"])
        assertEquals(JsonPrimitive(70), values["reasoning_tokens"])
        assertEquals(JsonPrimitive("gateway_reported"), values["usage_source"])
        assertEquals(JsonPrimitive("LENGTH"), values["finish_reason"])
        assertEquals(JsonPrimitive(2), values["backend_attempts"])
        assertEquals(JsonPrimitive(2), values["backend_failed_attempt"])
        assertEquals(JsonPrimitive(40), values["backend_output_limit"])
        assertEquals(JsonPrimitive("LAST_BACKEND_ATTEMPT"), values["output_byte_scope"])
        assertEquals(JsonPrimitive(512), values["reasoning_output_bytes"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("backend-error-code", "completion_repair_budget_exhausted")), values["backend_terminal_code_hash"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("invocation", "owned-invocation")), values["invocation_id_hash"])
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).contains("private"))
        assertTrue(diagnostic.isValid())
    }

    @Test fun unrelatedUsageCannotSupplyTokensAndDeliveryCannotRelabelTheSourceProfile() = runBlocking {
        val source = Session(
            listOf(
                ProviderEvent.RequestConfigured(configuredProfileOutputTokens = 256, effectiveOutputTokens = 128),
                ProviderEvent.Usage(outputTokens = 9876, reasoningTokens = 9000, invocationId = "other-invocation"),
                ProviderEvent.Failed("failed", errorCode = "CONNECTION_ABORTED", invocationId = "owned-invocation")
            )
        )
        val inner = withProviderFailureReceipts(source, "owned-run", profile, "primary")
        val outer = withProviderFailureReceipts(inner, "owned-run", profile.copy(uid = "other-profile", model = "other-model"), "github-delivery")
        val failure = withContext(RequestDispatchContext(emptySet(), "owned-run", "owned-invocation")) {
            outer.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
        }
        val fields = requireNotNull(failure.failureDetails)
        assertNull(fields["output_tokens"])
        assertNull(fields["reasoning_tokens"])
        assertEquals(JsonPrimitive(researchHash(profile.model)), fields["model_hash"])
        assertEquals(JsonPrimitive(128), fields["effective_output_limit"])
        assertEquals(1, source.calls)
    }

    @Test fun eachRoundAndMissingOwnerKeepUnknownMeasurementsUnknown() = runBlocking {
        val source = Session(listOf(ProviderEvent.Failed("no usage", errorCode = "PROVIDER_INVOCATION_FAILED")))
        val wrapped = withProviderFailureReceipts(source, "run", profile, "primary")
        repeat(2) {
            val failure = wrapped.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
            val fields = requireNotNull(failure.failureDetails)
            assertNull(fields["configured_output_limit"])
            assertNull(fields["output_tokens"])
            assertNull(fields["visible_output_bytes"])
            assertNull(fields["invocation_id_hash"])
        }
        val approval = ProviderEvent.Failed("approval pending", errorCode = "WAITING_FOR_APPROVAL")
        val result = withProviderFailureReceipts(Session(listOf(approval)), "run", profile, "github-delivery")
            .streamRound(emptyList(), emptyList()).toList().single()
        assertEquals(approval, result)
    }

    private class Session(private val events: List<ProviderEvent>) : AgentProviderSession {
        var calls = 0
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
            calls++
            return events.asFlow()
        }
    }

    @Test fun capacityFailuresRemainTypedAndCarryOwningBudget() {
        for (code in listOf("CAPACITY_UNVERIFIED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CONTEXT_CAPACITY_EXCEEDED")) {
            val failure = ProviderEvent.Failed(
                "private prompt",
                errorCode = code,
                failureDetails = buildJsonObject {
                    put("admission_prompt_tokens", 9639)
                    put("admission_safety_tokens", 2409)
                    put("admission_output_tokens", 4096)
                    put("admission_capacity_tokens", 5632)
                    put("admission_dominant_component", "tool_schemas")
                    put("admission_dominant_tokens", 4414)
                    put("admission_measurement", "UTF8_ESTIMATE")
                }
            )
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure, null)
            val fields = ProviderFailureReport.fields("run", failure, receipt)
            val capture = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1000))
            assertEquals(code, IssueReportCodes.select(failure.errorCode, "CHAT_FAILED"))
            assertEquals(JsonPrimitive(code), capture.values["provider_failure_code"])
            assertEquals(JsonPrimitive(5632), capture.values["admission_capacity_tokens"])
            assertEquals(JsonPrimitive("tool_schemas"), capture.values["admission_dominant_component"])
            assertTrue("ADMISSION_DIAGNOSTICS_V1" in capture.requiredServiceFeatures())
            assertFalse(capture.toString().contains("private prompt"))
        }
    }
}
