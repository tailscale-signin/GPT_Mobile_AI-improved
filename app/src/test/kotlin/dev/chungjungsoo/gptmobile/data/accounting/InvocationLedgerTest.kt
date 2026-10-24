package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationLedgerTest {
    @Test fun benchmarkAllowanceDenialsArePreDispatchPolicyFailuresWithoutAnswerText() = runTest {
        for (denial in listOf(SpendAllowanceReached("Spending allowance exhausted."), TokenAllowanceReached())) {
            val dao = mockk<InvocationDao>(relaxed = true)
            every { dao.recent() } returns flowOf(emptyList())
            coEvery { dao.reserve(any(), any(), any()) } throws denial
            val database = mockk<ChatDatabaseV2>()
            every { database.invocationDao() } returns dao
            var dispatched = false
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    dispatched = true
                    emit(ProviderEvent.TextDelta("must not run"))
                    emit(ProviderEvent.Completed)
                }
            }
            val wrapped = InvocationLedger(database).wrap(provider, "batch-run", "batch-run", "CUSTOM", "model", "benchmark", 100, 200, 1000)
            val events = wrapped.streamRound(emptyList(), emptyList()).toList()
            assertEquals(1, events.size)
            assertEquals("POLICY_BLOCKED", (events.single() as ProviderEvent.Failed).errorCode)
            val sample = dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRunner({ _, _ -> wrapped }).run(
                dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkCase("instruction", "Instruction", "task", "Reply READY", "READY"),
                false
            )
            assertEquals(dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome.POLICY_BLOCKED, sample.outcome)
            assertEquals(false, sample.requestDispatched)
            assertEquals("admission", sample.failure?.stage)
            assertEquals("", sample.canonicalAnswer)
            assertEquals(0, sample.outputCharacters)
            assertTrue(!dispatched)
            coVerify(exactly = 0) { dao.save(any()) }
        }
    }

    @Test fun provenUnsentDnsAttemptRefundsReservationButAmbiguousDisconnectDoesNot() = runTest {
        for (proven in listOf(true, false)) {
            val saved = mutableListOf<ModelInvocation>()
            val dao = mockk<InvocationDao>(relaxed = true)
            every { dao.recent() } returns flowOf(emptyList())
            coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
            val database = mockk<ChatDatabaseV2>()
            every { database.invocationDao() } returns dao
            val ledger = InvocationLedger(database)
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    val dispatch = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext])
                    val runtime = dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint("connection", "route", "credential", "model", "model", "epoch", "template", "allocation")
                    val receipt = dev.chungjungsoo.gptmobile.data.context.RequestAdmission.prepare("payload", "delegate", dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot(runtime, 10000), dev.chungjungsoo.gptmobile.data.context.RequestMeasurement(1, 1, dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind.UTF8_ESTIMATE), 10, "permissions").receipt.copy(physicalAttemptId = "physical-test")
                    dispatch.serialized("{}", receipt)
                    dispatch.armHttp()
                    val trace = requireNotNull(dispatch.takeHttpTrace())
                    trace.mark(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.HTTP_CALL_START)
                    if (proven) {
                        trace.failed(java.net.UnknownHostException("unresolved.example"))
                    } else {
                        trace.connectionOrRequestStarted()
                        trace.failed(java.io.IOException("connection reset"))
                    }
                    emit(ProviderEvent.Failed("Transport failed"))
                }
            }
            ledger.wrap(session, "parent", "turn", "CUSTOM", "model", "delegate", 300, 100, 1000).streamRound(emptyList(), emptyList()).toList()
            val record = saved.single()
            assertEquals(if (proven) "NOT_DISPATCHED" else "FAILED", record.status)
            assertEquals(if (proven) 0 else 300, record.inputTokens)
            assertEquals(0, record.outputTokens)
            if (proven) assertEquals(0L, record.costMicros)
        }
    }

    @Test fun interimUsageRemainsEstimatedAndCannotShrinkObservedGeneratedOutput() = runTest {
        val saved = mutableListOf<ModelInvocation>()
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.Usage(20, 1, 21, phase = dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase.INITIAL),
                ProviderEvent.TextDelta("substantial partial output ".repeat(20)),
                ProviderEvent.Failed("Interrupted")
            )
        }
        InvocationLedger(database).wrap(session, "parent", "turn", "CUSTOM", "model", "delegate", 100, 500, 1000).streamRound(emptyList(), emptyList()).toList()
        assertTrue(saved.single().estimated)
        assertTrue(saved.single().outputTokens > 1)
        assertEquals("provider_reported_initial", saved.single().outputTokenSource)
        assertEquals(1L, saved.single().observedOutputTokens)
    }

    @Test fun `outcome attribution and reservations survive completion failure and cancellation`() = runTest {
        for (outcome in listOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED")) {
            val saved = mutableListOf<ModelInvocation>()
            var reserved: ModelInvocation? = null
            val dao = mockk<InvocationDao>()
            every { dao.recent() } returns flowOf(emptyList())
            coEvery { dao.reserve(any(), any()) } answers { reserved = firstArg() }
            coEvery { dao.save(any()) } answers {
                saved.add(firstArg())
                Unit
            }
            val database = mockk<ChatDatabaseV2>()
            every { database.invocationDao() } returns dao
            val ledger = InvocationLedger(database)
            val session = object : AgentProviderSession {
                override val handlesToolsInternally = false
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                    emit(ProviderEvent.TextDelta("Hello"))
                    assertEquals(100, reserved!!.outputTokens)
                    assertEquals("profile", ledger.active.value.values.single().profileUid)
                    when (outcome) {
                        "COMPLETED" -> emit(ProviderEvent.Completed)
                        "FAILED" -> {
                            emit(ProviderEvent.Failed("Upstream"))
                            emit(ProviderEvent.Completed)
                        }
                        "CANCELED" -> throw CancellationException("User stopped")
                    }
                }
            }
            val result = runCatching { ledger.wrap(session, "parent", "turn", "provider", "model", "delegate", 10, 100, 1000, "profile").streamRound(emptyList(), emptyList()).toList() }
            if (outcome == "CANCELED") assertTrue(result.exceptionOrNull() is CancellationException) else assertTrue(result.isSuccess)
            assertEquals(outcome, saved.single().status)
            assertEquals("profile", saved.single().profileUid)
            assertTrue(saved.single().outputTokens < 100)
            assertTrue(ledger.active.value.isEmpty())
        }
    }

    @Test fun `native exact counts replace estimates and survive failed assist`() = runTest {
        val saved = mutableListOf<ModelInvocation>()
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val ledger = InvocationLedger(database)
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.TextDelta("partial"),
                ProviderEvent.Usage(outputTokens = 500, outputSource = "estimated"),
                ProviderEvent.Usage(inputTokens = 240, outputTokens = 37, inputSource = "runtime_reported", outputSource = "runtime_reported", actualRuntime = "LLAMA_CPP", finishReason = "length"),
                ProviderEvent.Failed("output limit")
            )
        }
        ledger.wrap(session, "parent", "turn", "LITERT_LM", "gguf-digest", "assist", 300, 100, 1000).streamRound(emptyList(), emptyList()).toList()
        assertEquals(37, saved.single().outputTokens)
        assertEquals("runtime_reported", saved.single().outputTokenSource)
        assertEquals("LLAMA_CPP", saved.single().actualRuntime)
        assertEquals("length", saved.single().finishReason)
        assertTrue(!saved.single().estimated)
    }

    @Test fun `ordinary requests do not collect or persist diagnostic history`() = runTest {
        dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy.setEnabled(false)
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val ledger = InvocationLedger(database)
        val events = listOf(ProviderEvent.TextDelta("Response"), ProviderEvent.Completed)
        val session = object : AgentProviderSession {
            override val handlesToolsInternally = false
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*events.toTypedArray())
        }
        assertEquals(events, ledger.wrap(session, "parent", "turn", "provider", "model", "primary", 10, 100, Int.MAX_VALUE).streamRound(emptyList(), emptyList()).toList())
        assertTrue(ledger.active.value.isEmpty())
        coVerify(exactly = 0) { dao.reserve(any(), any(), any()) }
        coVerify(exactly = 0) { dao.save(any()) }
        ledger.recover()
        coVerify(exactly = 1) { dao.recover() }
        coVerify(exactly = 0) { dao.clear() }
    }

    @Test fun `debug mode persists unlimited requests and retains history when turned off`() = runTest {
        dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy.setEnabled(true)
        try {
            val saved = mutableListOf<ModelInvocation>()
            val dao = mockk<InvocationDao>(relaxed = true)
            every { dao.recent() } returns flowOf(emptyList())
            coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
            val database = mockk<ChatDatabaseV2>()
            every { database.invocationDao() } returns dao
            val ledger = InvocationLedger(database)
            val session = object : AgentProviderSession {
                override val handlesToolsInternally = false
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("Hello"), ProviderEvent.Completed)
            }
            ledger.wrap(session, "parent", "turn", "provider", "model", "primary", 10, 100, Int.MAX_VALUE, "profile").streamRound(emptyList(), emptyList()).toList()
            assertEquals("COMPLETED", saved.single().status)
            assertEquals("profile", saved.single().profileUid)
            dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy.setEnabled(false)
            ledger.recover()
            coVerify(exactly = 1) { dao.recover() }
            coVerify(exactly = 0) { dao.clear() }
        } finally {
            dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy.setEnabled(false)
        }
    }
}
