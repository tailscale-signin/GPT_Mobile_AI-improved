package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason
import dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.context.RequestMeasurement
import dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.ModelRequestPreparation
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationPreflightAccountingTest {
    @Test fun actualReasoningAdmissionReleasesOrdinaryTokenAndMoneyReservationsWithZeroInferenceCalls() = runBlocking {
        val requests = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath
                check(request.url.encodedPath == "/props") { "Inference must not be called" }
                respond("""{"model_alias":"selected","build_info":"fixture-build","default_generation_settings":{"n_ctx":8192},"chat_template":"no-toggle"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        val network = mockk<NetworkClient>()
        every { network() } returns client
        every { network.gatewayReadClient() } returns client
        val preparation = ModelRequestPreparation(network)
        try {
            val (saved, events) = execute {
                try {
                    preparation.prepare("""{"model":"selected","messages":[{"role":"user","content":"Fixture question"}],"max_tokens":256}""", ProviderRequestConfig("https://unsent-fixture.invalid/v1", null, providerType = "LLAMA", reasoningMode = ReasoningMode.OFF), "selected", 256)
                    error("Expected strict reasoning rejection")
                } catch (rejected: ReasoningAdmissionException) {
                    emit(ProviderEvent.Failed(requireNotNull(rejected.message)))
                }
            }
            assertEquals(listOf("/props"), requests)
            assertEquals("NOT_DISPATCHED", saved.status)
            assertEquals(0, saved.inputTokens)
            assertEquals(0, saved.outputTokens)
            assertEquals(0L, saved.costMicros)
            assertFalse(saved.estimated)
            val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals("REASONING_OFF_UNSUPPORTED", failure.errorCode)
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
            assertEquals(JsonPrimitive(saved.id), failure.failureDetails?.get("invocation_id"))
            assertEquals(JsonPrimitive(saved.parentRunId), failure.failureDetails?.get("run_id"))
            assertTrue(saved.usageEvidenceJson.orEmpty().contains("LOCAL_INFERENCE_ADMISSION"))
        } finally {
            client.close()
        }
    }

    @Test fun absentReceiptOrAReasonLookingMessageIsNotProofThatInferenceWasUnsent() = runBlocking {
        val (saved, events) = execute { emit(ProviderEvent.Failed("REASONING_OFF_UNSUPPORTED from an untrusted response")) }
        assertEquals("FAILED", saved.status)
        assertEquals(100, saved.inputTokens)
        assertEquals(600L, saved.costMicros)
        assertTrue(saved.estimated)
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(JsonPrimitive(saved.id), failure.failureDetails?.get("invocation_id"))
        assertEquals(null, failure.failureDetails?.get("dispatched"))
    }

    @Test fun gatewayIsolationPrecheckRetainsItsSpecificReasonAndConnectionStage() = runBlocking {
        val (saved, events) = execute {
            requireNotNull(currentCoroutineContext()[RequestDispatchContext]).rejectedBeforeInference(InferencePreflightReason.GATEWAY_ISOLATION_UNAVAILABLE)
            emit(ProviderEvent.Failed("The required gateway isolation contract is unavailable"))
        }
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("GATEWAY_ISOLATION_UNAVAILABLE", failure.errorCode)
        assertEquals(JsonPrimitive("connection_preflight"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals("NOT_DISPATCHED", saved.status)
        assertEquals(0L, saved.costMicros)
    }

    @Test fun laterLocalRejectionCannotRefundAnEarlierUncertainAttempt() = runBlocking {
        val (saved, events) = execute {
            val context = requireNotNull(currentCoroutineContext()[RequestDispatchContext])
            val runtime = RuntimeFingerprint("route", "route", "credential", "selected", "selected", "runtime", "template", "allocation")
            val admission = RequestAdmission.prepare("payload", "primary", CapacitySnapshot(runtime, 8192), RequestMeasurement(100, 0, TokenMeasurementKind.UTF8_ESTIMATE), 500, "permissions").receipt
            context.serialized("{}", admission.copy(physicalAttemptId = "uncertain-request"))
            context.rejectedBeforeInference(InferencePreflightReason.REASONING_OFF_UNSUPPORTED)
            emit(ProviderEvent.Failed("The next local attempt was rejected"))
        }
        assertEquals("FAILED", saved.status)
        assertEquals(100, saved.inputTokens)
        assertEquals(600L, saved.costMicros)
        assertEquals(null, events.filterIsInstance<ProviderEvent.Failed>().single().failureDetails?.get("dispatched"))
    }

    @Test fun measuredUsageAndVisibleOutputSurviveALaterLocalRejection() = runBlocking {
        val (saved, _) = execute {
            emit(ProviderEvent.TextDelta("Retained answer"))
            emit(ProviderEvent.Usage(inputTokens = 20, outputTokens = 30))
            requireNotNull(currentCoroutineContext()[RequestDispatchContext]).rejectedBeforeInference(InferencePreflightReason.REASONING_OFF_UNSUPPORTED)
            emit(ProviderEvent.Failed("Later rejected preflight"))
        }
        assertEquals(20, saved.inputTokens)
        assertEquals(30, saved.outputTokens)
        assertEquals(50L, saved.costMicros)
    }

    private suspend fun execute(block: suspend FlowCollector<ProviderEvent>.() -> Unit): Pair<ModelInvocation, List<ProviderEvent>> {
        val saved = mutableListOf<ModelInvocation>()
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val settings = mockk<SettingRepository>()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(spendBudget = SpendBudgetSettings(prices = mapOf("selected" to ModelPrice("selected", 1_000_000, 1_000_000, "fixture", System.currentTimeMillis()))))
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow(block)
        }
        val ledger = InvocationLedger(database, settings)
        val events = ledger.wrap(session, "run", "turn", "LLAMA", "selected", "primary", 100, 500, 10000, "selected").streamRound(emptyList(), emptyList()).toList()
        assertTrue(ledger.active.value.isEmpty())
        return saved.last() to events
    }
}
