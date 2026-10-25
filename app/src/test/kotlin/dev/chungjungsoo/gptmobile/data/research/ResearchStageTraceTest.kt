package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.RequestAdmissionReceipt
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import java.net.UnknownHostException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchStageTraceTest {
    private val receipt = RequestAdmissionReceipt("research-extract", "test", RuntimeFingerprint("c", "route", "credential", "m", "m", "r", "t", "a"), "p", 20, 256, 0, 4096, CapacityProvenance.DECLARED, TokenMeasurementKind.UTF8_ESTIMATE, true, physicalAttemptId = "generation")

    @Test fun serializationAndDnsFailureAreNotCountedAsTransmittedGenerations() {
        val trace = ResearchStageTrace("root", "logical", "stage")
        val dispatch = RequestDispatchContext(emptySet(), "run", "invocation", researchTrace = trace)
        dispatch.serialized("{}", receipt)
        dispatch.armHttp()
        val http = requireNotNull(dispatch.takeHttpTrace())
        http.mark(RunDiagnosticKind.HTTP_CALL_START)
        assertEquals("PREPARED", trace.snapshot().transports.single().state)
        http.failed(UnknownHostException("fixture"))
        assertEquals(setOf("generation"), dispatch.provenUnsentAttemptIds())
        assertEquals("NOT_DISPATCHED", trace.snapshot().transports.single().state)
    }

    @Test fun queueReadsHaveTheirOwnIdsAndDoNotMultiplyGenerationCounts() {
        val trace = ResearchStageTrace("root", "logical", "stage")
        val dispatch = RequestDispatchContext(emptySet(), "run", "invocation", researchTrace = trace)
        dispatch.serialized("{}", receipt)
        dispatch.armHttp()
        val generation = requireNotNull(dispatch.takeHttpTrace())
        generation.mark(RunDiagnosticKind.REQUEST_BODY_END)
        generation.mark(RunDiagnosticKind.RESPONSE_HEADERS)
        repeat(2) {
            dispatch.armQueuePoll("accepted-job")
            requireNotNull(dispatch.takeHttpTrace()).mark(RunDiagnosticKind.RESPONSE_HEADERS)
        }
        val metrics = trace.snapshot()
        assertEquals(1, metrics.transports.count { it.operation == "GENERATION" && it.state == "TRANSMITTED" })
        assertEquals(2, metrics.transports.count { it.operation == "QUEUE_POLL" && it.state == "TRANSMITTED" })
        assertEquals(3, metrics.transports.map { it.id }.distinct().size)
        assertEquals(metrics, Json.decodeFromString<ResearchStageMetrics>(Json.encodeToString(metrics)))
    }

    @Test fun replayedTelemetryDeduplicatesAndLocalAdmissionEstimatesDoNotBecomeUsage() {
        val trace = ResearchStageTrace("root", "logical", "stage")
        trace.invocation("sent")
        trace.prepared("generation", "sent")
        trace.transmitted("generation", 12)
        trace.prepared("poll", "sent", "QUEUE_POLL", "job")
        trace.transmitted("poll", 8)
        val success = ResearchModelEvent("stage", "EXTRACT", "profile", "model", false, "SUCCESS", 1, invocationId = "sent", stageAccepted = true, metrics = trace.snapshot())
        val blocked = ResearchModelEvent("blocked-stage", "EXTRACT", "profile", "model", false, "SKIPPED", 2, invocationId = "blocked", reason = "PROVIDER_COOLDOWN", metrics = ResearchStageTrace("root", "other", "blocked-stage").also { it.invocation("blocked") }.snapshot())
        val session = ResearchSession("run", 1, ResearchSnapshot("task", modelEvents = listOf(success, blocked)), "root")
        val request = ModelInvocation("sent", "run", "root", "FREE", "m", "research-extract", 100, 20, estimated = false, status = "COMPLETED", profileUid = "profile", inputTokenSource = "provider_reported", outputTokenSource = "provider_reported")
        val local = request.copy(id = "blocked", status = "FAILED", inputTokens = 999, outputTokens = 999, estimated = true, inputTokenSource = "estimated", outputTokenSource = "estimated")
        val stats = researchProfileStatistics("root", setOf("profile"), listOf(session, session), listOf(request, request, local), emptyList()).single()
        assertEquals(100L, stats.inputTokens)
        assertEquals(20L, stats.outputTokens)
        assertEquals(1, stats.physicalGenerations)
        assertEquals(1, stats.queuePolls)
        assertEquals(1, stats.admissionBlocks)
        assertEquals(2, stats.logicalStages)
        assertEquals(0, stats.uncorrelatedInvocations)
        assertTrue(stats.tokenProvenance.keys.all { it == "provider_reported" })
    }

    @Test fun acceptedJobResumptionAndCachedResultsDoNotMultiplyItsUsage() {
        val submitted = ResearchStageTrace("root", "logical", "submitted")
        val resumed = ResearchStageTrace("root", "logical", "resumed")
        val cached = ResearchStageTrace("root", "logical", "cached")
        val dispatch = RequestDispatchContext(emptySet(), "run", "submit", researchTrace = submitted)
        dispatch.serialized("{}", receipt)
        dispatch.armHttp()
        requireNotNull(dispatch.takeHttpTrace()).mark(RunDiagnosticKind.RESPONSE_HEADERS)
        dispatch.queueJob("AI_HORDE:job", resumed = false)
        RequestDispatchContext(emptySet(), "run", "resume", researchTrace = resumed).apply {
            queueJob("AI_HORDE:job", resumed = true)
            armQueuePoll("AI_HORDE:job")
            requireNotNull(takeHttpTrace()).mark(RunDiagnosticKind.RESPONSE_HEADERS)
        }
        RequestDispatchContext(emptySet(), "run", "cached", researchTrace = cached).queueJob("AI_HORDE:job", resumed = true, cached = true)
        val stages = listOf(submitted, resumed, cached).mapIndexed { index, trace ->
            val metrics = trace.snapshot()
            ResearchModelEvent(metrics.stageAttemptId, "EXTRACT", "profile", "model", false, "SUCCESS", index.toLong(), metrics = metrics)
        }
        val first = ModelInvocation("submit", "run", "root", "FREE", "m", "research-extract", 100, 0, status = "FAILED", profileUid = "profile")
        val requests = listOf(first, first.copy(id = "resume", outputTokens = 20, status = "COMPLETED"), first.copy(id = "cached", inputTokens = 999, outputTokens = 999, status = "COMPLETED"))
        val session = ResearchSession("run", 1, ResearchSnapshot("task", modelEvents = stages), "root")
        val stats = researchProfileStatistics("root", setOf("profile"), listOf(session), requests, emptyList()).single()
        assertEquals(100L, stats.inputTokens)
        assertEquals(20L, stats.outputTokens)
        assertEquals(1, stats.physicalGenerations)
        assertEquals(1, stats.queuePolls)
        assertEquals(1, stats.queuedJobs)
        assertEquals(1, stats.cachedQueueResults)
        assertEquals(3, stats.modelRequests)
        assertEquals("AI_HORDE:job", submitted.snapshot().transports.single().queueJobId)
    }

    @Test fun allCoolingCandidatesDeferWithTheirCorrelatedEventsRetained() = runTest {
        val first = ProviderLimitDecision("AI_HORDE", "a", "model", 4000, "scope-a", "LOCAL_COOLDOWN_ACTIVE", true)
        val second = first.copy(profileUid = "b", retryAt = 3000, scope = "scope-b")
        fun cooling(limit: ProviderLimitDecision) = ResearchModelEndpoint(limit.profileUid, "model", limit.profileUid, false, { true }, admission = { ResearchAdmission.PROVIDER_COOLDOWN }, providerLimit = { limit }) { _, _, _ -> error("Cooling profile must not dispatch") }
        val coordinator = ResearchModelCoordinator(listOf(cooling(first), cooling(second)), healthScope = "all-cooling")
        val failure = runCatching { coordinator.generate(ResearchModelStage.EXTRACT, "frozen prompt", 256, workItemId = "packet") }.exceptionOrNull()
        assertTrue(failure is ResearchProviderDeferred)
        assertEquals(second, (failure as ResearchProviderDeferred).checkpoint.limit)
        assertEquals(2, coordinator.events.size)
        assertTrue(coordinator.events.all { it.outcome == "WAITING_PROVIDER_LIMIT" && it.metrics?.rootWorkId == "all-cooling" && it.metrics?.transports?.isEmpty() == true })
        assertEquals(1, coordinator.events.map { it.metrics?.logicalStageId }.distinct().size)
    }

    @Test fun admittedAlternativeCompletesInsteadOfDeferringTheWholeResearchTask() = runTest {
        val limit = ProviderLimitDecision("AI_HORDE", "cooling", "model", 4000, "scope", "LOCAL_COOLDOWN_ACTIVE", true)
        val cooling = ResearchModelEndpoint("cooling", "model", "cooling", false, { true }, admission = { ResearchAdmission.PROVIDER_COOLDOWN }, providerLimit = { limit }) { _, _, _ -> error("Cooling profile must not dispatch") }
        val alternative = ResearchModelEndpoint("alternative", "model", "alternative", false, { true }) { _, _, _ ->
            requireNotNull(currentCoroutineContext()[ResearchStageTrace]).apply {
                prepared("physical", "invocation")
                transmitted("physical", 10)
            }
            ResearchModelReply("{\"claims\":[]}", "alternative", "invocation")
        }
        val coordinator = ResearchModelCoordinator(listOf(cooling, alternative), healthScope = "fallback")
        assertEquals("alternative", coordinator.generate(ResearchModelStage.EXTRACT, "prompt", 256, workItemId = "packet")?.modelKey)
        assertEquals(listOf("WAITING_PROVIDER_LIMIT", "SUCCESS"), coordinator.events.map { it.outcome })
        assertEquals("invocation", coordinator.events.last().invocationId)
        assertEquals(1, coordinator.events.last().metrics?.transports?.size)
    }

    @Test fun changedContinuationCursorKeepsItsLogicalWorkIdAndCircuitSkipHasTelemetry() = runTest {
        val failing = ResearchModelEndpoint("profile", "model", "model", false, { true }) { _, _, _ ->
            val trace = requireNotNull(currentCoroutineContext()[ResearchStageTrace])
            trace.prepared(java.util.UUID.randomUUID().toString(), "invocation-${trace.snapshot().stageAttemptId}")
            throw IllegalStateException("fixture failure")
        }
        val coordinator = ResearchModelCoordinator(listOf(failing), healthScope = "stable-packet", monotonicClock = { 0 })
        repeat(3) { assertNull(coordinator.generate(ResearchModelStage.EXTRACT, "cursor $it", 256, workItemId = "same-packet")) }
        assertEquals(listOf("FAILED", "FAILED", "SKIPPED"), coordinator.events.map { it.outcome })
        assertTrue(coordinator.events.all { it.metrics != null })
        assertEquals(1, coordinator.events.map { it.metrics?.logicalStageId }.distinct().size)
        assertEquals("MODEL_CIRCUIT_OPEN", coordinator.events.last().reason)
        assertTrue(coordinator.events.last().metrics!!.transports.isEmpty())
    }
}
