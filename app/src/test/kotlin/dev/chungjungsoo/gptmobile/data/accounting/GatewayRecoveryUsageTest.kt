package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRecoveryUsageTest {
    private fun attemptDetails(completed: Boolean = true): JsonObject = buildJsonObject {
        put("failed_stage", "provider_generation")
        put("gateway_recovery", Json.parseToJsonElement("""{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":1536,"finish_reason":"${if (completed) "LENGTH" else "UNKNOWN"}","visible_output_bytes":${if (completed) "0" else "null"},"reasoning_output_bytes":${if (completed) "11" else "null"},"terminal_error_code":"${if (completed) "completion_repair_budget_exhausted" else "backend_stream_interrupted"}","terminal_status_code":200,"backend_response_observed":true,"status":"${if (completed) "COMPLETED" else "UNKNOWN"}"}]}"""))
    }

    @Test fun noUsageBackendProofSurvivesLocalPreflightWithoutInventingTokens() = runTest {
        val dao = mockk<InvocationDao>(relaxed = true)
        var saved: ModelInvocation? = null
        coEvery { dao.save(any()) } answers { saved = firstArg() }
        val dispatch = RequestDispatchContext(emptySet(), "run", "review")
        dispatch.rejectedBeforeInference(dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_CAPABILITY_UNAVAILABLE)
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 900, 1536), null, dispatch, null, dao, true, MutableStateFlow(emptyMap()), null, "turn")
        val failure = ProviderEvent.Failed("Repair exhausted", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = "review", failureDetails = attemptDetails())
        val retained = round.preservePreflightFailure(failure) as ProviderEvent.Failed
        assertEquals(failure.errorCode, retained.errorCode)
        repeat(2) { round.observe(retained) }
        val later = round.preservePreflightFailure(failure.copy(failureDetails = null)) as ProviderEvent.Failed
        assertEquals(failure.errorCode, later.errorCode)
        assertNull(later.failureDetails?.get("dispatch_state"))
        assertNull(round.input)
        assertNull(round.output)
        assertNull(round.recoveredUsageEvent())
        assertTrue(round.estimatedUsage())
        assertEquals("length", round.finishReason)
        assertEquals(0L, round.reasoningBytes)
        round.finish()
        val record = requireNotNull(saved)
        assertNull(record.observedInputTokens)
        assertNull(record.observedOutputTokens)
        val evidence = Json.parseToJsonElement(requireNotNull(record.usageEvidenceJson)).jsonObject
        assertFalse(evidence.containsKey("known_recovery_output_tokens"))
        val physical = evidence.getValue("gateway_attempt_evidence").jsonObject.getValue("physical_requests") as JsonArray
        assertEquals(1, physical.size)
        val receipt = (physical.single().jsonObject.getValue("recovery").jsonObject.getValue("attempt_receipts") as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive(11), receipt["reasoning_output_bytes"])
        val diagnostic = Json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.serializer(), evidence.getValue("failure"))
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.YES, diagnostic.remoteAcceptance)
        assertEquals(true, diagnostic.dispatched)
    }

    @Test fun observedHeadersAndUnknownTransportDoNotProveModelAcceptance() = runTest {
        val dao = mockk<InvocationDao>(relaxed = true)
        var saved: ModelInvocation? = null
        coEvery { dao.save(any()) } answers { saved = firstArg() }
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 900, 1536), null, RequestDispatchContext(emptySet()), null, dao, true, MutableStateFlow(emptyMap()), null, "turn")
        val callReceipts = mutableListOf<String>()
        mockkObject(dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder)
        try {
            every { dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", match { it.startsWith("PROVIDER_RECEIPT") }, any()) } answers {
                callReceipts.add(secondArg())
                Unit
            }
            round.observe(ProviderEvent.Usage(providerResponseTiming = dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming(httpStatus = 200), invocationId = "review"))
            round.observe(ProviderEvent.Failed("Backend interrupted", errorCode = "TRANSPORT_ERROR", invocationId = "review", failureDetails = attemptDetails(completed = false)))
            assertNull(round.recoveredUsageEvent())
            round.finish()
        } finally {
            unmockkObject(dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder)
        }
        val evidence = Json.parseToJsonElement(requireNotNull(saved?.usageEvidenceJson)).jsonObject
        assertTrue(evidence.containsKey("gateway_attempt_evidence"))
        assertEquals(JsonPrimitive("android_http_attempt"), evidence.getValue("response_parsing").jsonObject["measurement_scope"])
        assertEquals(JsonPrimitive("YES"), evidence.getValue("response_parsing").jsonObject["remote_acceptance"])
        assertEquals(JsonPrimitive("UNKNOWN"), Json.parseToJsonElement(callReceipts.single().substringAfter("receipt=")).jsonObject["remoteAcceptance"])
        val diagnostic = Json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.serializer(), evidence.getValue("failure"))
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, diagnostic.remoteAcceptance)
        assertTrue(diagnostic.dispatched != true)
        assertNull(saved?.observedOutputTokens)
    }

    @Test fun savedJobEvidenceIsBoundToItsOriginalJobAndDoesNotChargeTheResultRead() = runTest {
        for (failed in listOf(false, true)) {
            val dao = mockk<InvocationDao>(relaxed = true)
            var saved: ModelInvocation? = null
            coEvery { dao.save(any()) } answers { saved = firstArg() }
            val round = InvocationRoundAccounting(ModelInvocation("result-read", "run", "turn", "LLAMA", "selected", "github-review", 900, 1536), null, RequestDispatchContext(emptySet()), null, dao, true, MutableStateFlow(emptyMap()), null, "turn")
            val recovery = JsonObject(attemptDetails().getValue("gateway_recovery").jsonObject + details().getValue("gateway_recovery").jsonObject)
            round.observe(ProviderEvent.GatewayMetadataCaptured(dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata(jobId = "original-job", singleflightRole = "saved_result", transportVerified = true)))
            round.observe(ProviderEvent.Usage(gatewayRecovery = recovery, phase = dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase.INTERIM, invocationId = "result-read"))
            round.observe(ProviderEvent.Usage(providerResponseTiming = dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming(httpStatus = 409), invocationId = "result-read"))
            if (failed) round.observe(ProviderEvent.Failed("Original repair failed", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = "result-read", failureDetails = JsonObject(mapOf("gateway_recovery" to recovery))))
            assertNull(round.input)
            assertNull(round.output)
            assertNull(round.recoveredUsageEvent())
            round.finish()
            val evidence = Json.parseToJsonElement(requireNotNull(saved?.usageEvidenceJson)).jsonObject
            val attempts = evidence.getValue("gateway_attempt_evidence").jsonObject
            assertEquals(JsonPrimitive("backend_attempts_by_observation_source"), attempts["scope"])
            assertTrue((attempts.getValue("physical_requests") as JsonArray).isEmpty())
            val original = (attempts.getValue("saved_jobs") as JsonArray).single().jsonObject
            assertEquals(JsonPrimitive("original-job"), original["job_id"])
            assertEquals(JsonPrimitive(false), original["usage_accounted_to_current_invocation"])
            assertNull(saved?.observedInputTokens)
            assertNull(saved?.observedOutputTokens)
            assertFalse(evidence.containsKey("known_recovery_output_tokens"))
            if (failed) {
                val diagnostic = Json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.serializer(), evidence.getValue("failure"))
                assertTrue(diagnostic.remoteAcceptance != dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.YES)
            }
        }
    }

    @Test fun successfulRepairMetadataDoesNotDoubleCountTheFinalAggregateUsage() = runTest {
        val first = (attemptDetails().getValue("gateway_recovery").jsonObject.getValue("attempt_receipts") as JsonArray).single().jsonObject
        val second = JsonObject(first + mapOf("attempt" to JsonPrimitive(2), "output_allowance" to JsonPrimitive(1518), "finish_reason" to JsonPrimitive("STOP"), "visible_output_bytes" to JsonPrimitive(9), "reasoning_output_bytes" to JsonPrimitive(0), "terminal_error_code" to JsonNull))
        val recovery = buildJsonObject {
            put("schema_version", 1)
            put("attempt_receipts", JsonArray(listOf(first, second)))
        }
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 900, 1536), null, RequestDispatchContext(emptySet()), null, mockk(relaxed = true), false, MutableStateFlow(emptyMap()), null, "turn")
        round.observe(ProviderEvent.Usage(inputTokens = 12, outputTokens = 18, totalTokens = 30, invocationId = "review"))
        repeat(2) { round.observe(ProviderEvent.Usage(gatewayRecovery = recovery, phase = dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase.INTERIM, invocationId = "review")) }
        assertEquals(12, round.input)
        assertEquals(18, round.output)
        assertNull(round.recoveredUsageEvent())
        assertFalse(round.estimatedUsage())
        val evidence = Json.parseToJsonElement(round.usageEvidence(false)).jsonObject
        val physical = evidence.getValue("gateway_attempt_evidence").jsonObject.getValue("physical_requests") as JsonArray
        assertEquals(1, physical.size)
        assertEquals(2, (physical.single().jsonObject.getValue("recovery").jsonObject.getValue("attempt_receipts") as JsonArray).size)
        assertEquals("stop", round.finishReason)
    }

    private fun details(body: String = """{"attempts":1,"output_allowance":1536,"attempt_usage":[{"prompt_tokens":17237,"completion_tokens":1536,"total_tokens":18773}]}"""): JsonObject = buildJsonObject {
        put("failed_stage", "provider_generation")
        put("gateway_recovery", JsonObject(Json.parseToJsonElement(body).jsonObject + mapOf("usage_origin" to JsonPrimitive("PROVIDER_REPORTED"), "usage_estimated" to JsonPrimitive(false))))
    }

    @Test fun laterLocalPreflightCannotOverwriteKnownFailedResponseUsage() {
        val dispatch = RequestDispatchContext(emptySet(), "run", "review")
        dispatch.rejectedBeforeInference(dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_CAPABILITY_UNAVAILABLE)
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 16894, 1536), null, dispatch, null, mockk(relaxed = true), true, MutableStateFlow(emptyMap()), null, "turn")
        val failure = ProviderEvent.Failed("Repair output exhausted", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = "review", failureDetails = details())
        val retained = round.preservePreflightFailure(failure) as ProviderEvent.Failed
        assertEquals(failure.errorCode, retained.errorCode)
        assertNull(retained.failureDetails?.get("dispatch_state"))
        round.observe(retained)
        assertEquals(17237, round.retainedInput())
        assertEquals(1536, round.retainedOutput())
    }

    @Test fun failedReviewConsumptionIsReconciledOnceIntoInvocationAndKnownRunSubtotal() = runTest {
        val dao = mockk<InvocationDao>(relaxed = true)
        var saved: ModelInvocation? = null
        coEvery { dao.save(any()) } answers { saved = firstArg() }
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 16894, 1536), null, RequestDispatchContext(emptySet(), "run", "review"), null, dao, true, MutableStateFlow(emptyMap()), null, "turn")
        round.observe(ProviderEvent.Usage(inputTokens = 17000, outputTokens = 500, phase = dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase.INTERIM, invocationId = "review"))
        val failure = ProviderEvent.Failed("Repair output exhausted", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = "review", failureDetails = details())
        repeat(2) { round.observe(failure) }
        assertEquals(17237, round.retainedInput())
        assertEquals(1536, round.retainedOutput())
        assertFalse(round.estimatedUsage())
        assertEquals(18773, round.recoveredUsageEvent()?.totalTokens)
        round.finish()
        coVerify(exactly = 1) { dao.save(any()) }
        val measured = requireNotNull(saved)
        assertEquals("FAILED", measured.status)
        assertEquals("gateway_reported", measured.inputTokenSource)
        assertEquals(17237L, measured.observedInputTokens)
        val evidence = Json.parseToJsonElement(requireNotNull(measured.usageEvidenceJson)).jsonObject
        assertEquals(JsonPrimitive(true), evidence["final_usage_complete"])
        assertEquals(JsonPrimitive(1536), evidence["known_recovery_output_tokens"])
        val previous = measured.copy(id = "earlier", inputTokens = 96122, outputTokens = 3857, observedInputTokens = 96122, observedOutputTokens = 3857, usageEvidenceJson = null)
        val unknown = measured.copy(id = "preflight-unknown", inputTokenSource = "estimated", outputTokenSource = "estimated", observedInputTokens = null, observedOutputTokens = null, usageEvidenceJson = null)
        val run = RunTokenUsage.from(listOf(previous, measured, measured, unknown))
        assertEquals(113359, run.inputTokens)
        assertEquals(5393, run.outputTokens)
        assertEquals(118752, run.measuredSubtotalTokens)
        assertEquals(1, run.unmeasuredInvocations)
        assertNull(run.totalTokens)
    }

    @Test fun knownCompletedAttemptsRemainPartialWhenAnotherAttemptHasNoUsage() = runTest {
        val dao = mockk<InvocationDao>(relaxed = true)
        var saved: ModelInvocation? = null
        coEvery { dao.save(any()) } answers { saved = firstArg() }
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 900, 500), null, RequestDispatchContext(emptySet()), null, dao, true, MutableStateFlow(emptyMap()), null, "turn")
        round.observe(ProviderEvent.Usage(inputTokens = 12, outputTokens = 30, invocationId = "review"))
        round.observe(ProviderEvent.Failed("Backend repair failed", invocationId = "review", failureDetails = details("""{"completed_attempts":1,"failed_attempt":2,"failed_attempt_usage_available":false,"attempt_usage":[{"prompt_tokens":12,"completion_tokens":30,"total_tokens":42}]}""")))
        assertTrue(round.estimatedUsage())
        assertEquals("gateway_reported_partial", round.inputSource)
        assertEquals(12, round.input)
        round.finish()
        val run = RunTokenUsage.from(listOf(requireNotNull(saved)))
        assertEquals(12, run.inputTokens)
        assertEquals(30, run.outputTokens)
        assertEquals(42, run.measuredSubtotalTokens)
        assertEquals(1, run.unmeasuredInvocations)
        assertNull(run.totalTokens)
    }

    @Test fun estimatesInvalidCountersAndForeignInvocationsCannotBecomeMeasuredUsage() {
        assertNull(GatewayRecoveryUsage.from(details("""{"attempts":1,"attempt_usage":[{"prompt_tokens":-1,"completion_tokens":"20"}]}""")))
        assertNull(GatewayRecoveryUsage.from(details("""{"attempts":1,"attempt_usage":[{"prompt_tokens":12,"completion_tokens":30,"total_tokens":99}]}""")))
        assertNull(GatewayRecoveryUsage.from(buildJsonObject { put("gateway_recovery", JsonObject(details().getValue("gateway_recovery").jsonObject + ("usage_estimated" to JsonPrimitive(true)))) }))
        val round = InvocationRoundAccounting(ModelInvocation("primary", "run", "turn", "LLAMA", "selected", "primary", 100, 500), null, RequestDispatchContext(emptySet()), null, mockk(relaxed = true), false, MutableStateFlow(emptyMap()), null, "turn")
        round.observe(ProviderEvent.Failed("Foreign reviewer", invocationId = "review", failureDetails = details()))
        round.observe(ProviderEvent.Failed("Foreign attempt receipt", invocationId = "review", failureDetails = attemptDetails()))
        round.observe(ProviderEvent.Usage(gatewayRecovery = attemptDetails().getValue("gateway_recovery").jsonObject, invocationId = "review"))
        assertNull(round.input)
        assertNull(round.output)
        assertNull(round.recoveredUsageEvent())
        assertFalse(Json.parseToJsonElement(round.usageEvidence(false)).jsonObject.containsKey("gateway_attempt_evidence"))
    }

    @Test fun physicalRequestSnapshotsReplaceDuplicatesAndSumIndependentAttempts() {
        val usage = requireNotNull(GatewayRecoveryUsage.from(details()))
        val attempts = linkedMapOf("physical-1" to usage)
        attempts["physical-1"] = usage
        assertEquals(18773, GatewayRecoveryUsage.combine(attempts, true)?.event("invocation")?.totalTokens)
        attempts["physical-2"] = usage
        assertEquals(37546, GatewayRecoveryUsage.combine(attempts, true)?.event("invocation")?.totalTokens)
        assertFalse(requireNotNull(GatewayRecoveryUsage.combine(attempts, false)).inputComplete)
    }
}
