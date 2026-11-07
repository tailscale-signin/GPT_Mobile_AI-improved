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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationLedgerTest {
    @Test fun nativeTransportFailurePersistsEachAdmittedProfileWithoutNeedingAProviderUsageEvent() = runTest {
        val saved = mutableListOf<ModelInvocation>()
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val ledger = InvocationLedger(database)
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                val dispatch = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext])
                val runtime = dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint("connection", "route", "credential", "model", "model", "epoch", "template", "allocation")
                val admission = dev.chungjungsoo.gptmobile.data.context.RequestAdmission.prepare("payload", "primary", dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot(runtime, 10000), dev.chungjungsoo.gptmobile.data.context.RequestMeasurement(1, 1, dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind.UTF8_ESTIMATE), 100, "permissions").receipt
                val config = dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig("https://private.example", "secret-credential", profileRevision = "a".repeat(64), configuredProfileReasoning = true)
                dispatch.serialized("""{"thinking":{"type":"adaptive"},"messages":"secret-prompt"}""", admission.copy(physicalAttemptId = "first"), config)
                dispatch.serialized("""{"thinking":{"type":"disabled"},"messages":"secret-prompt"}""", admission.copy(physicalAttemptId = "second", outputTokens = 50), config.copy(profileRevision = "b".repeat(64), configuredProfileReasoning = false, allowReasoning = false))
                throw java.io.IOException("Native transport failed before response headers")
            }
        }
        val failure = runCatching { ledger.wrap(session, "run", "turn", "ANTHROPIC", "model", "primary", 10, 100, 1000).streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        val retained = saved.last { it.status == "FAILED" }
        val evidence = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(retained.usageEvidenceJson)).jsonObject
        val attempts = evidence.getValue("serialized_requests") as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("first", "second"), attempts.map { it.jsonObject.getValue("physical_attempt_id").jsonPrimitive.content })
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), attempts.map { it.jsonObject.getValue("settings").jsonObject.getValue("profile_revision").jsonPrimitive.content })
        assertEquals(listOf("thinking.type=adaptive", "thinking.type=disabled"), attempts.map { it.jsonObject.getValue("settings").jsonObject.getValue("serialized_control").jsonPrimitive.content })
        assertEquals(attempts.last().jsonObject["settings"], evidence["request_settings"])
        assertTrue(attempts.all { it.jsonObject.getValue("settings").jsonObject["effective_reasoning"] == kotlinx.serialization.json.JsonNull })
        assertTrue(!evidence.toString().contains("secret") && !evidence.toString().contains("private.example"))
        assertEquals(null, retained.actualRuntime)
    }

    @Test fun terminalEvidencePersistsRequestedSerializedAndBackendObservationsIndependently() = runTest {
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val ledger = InvocationLedger(database)
        val receipt = dev.chungjungsoo.gptmobile.data.network.ReasoningControlReceipt(
            disableRequested = true,
            transmittedControl = "reasoning_effort=low",
            transmitsDisable = false,
            providerSupport = "EFFORT_ONLY",
            profileRevision = "a".repeat(64),
            configuredProfileReasoning = false,
            requestedMode = "OFF"
        )
        val provenance = dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationProvenance(1, "BACKEND_RESPONSE", "selected-model", "selected-model", "MATCHED", 200).evidence("selected-model")
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.Usage(reasoningControlReceipt = receipt, phase = dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase.INITIAL),
                ProviderEvent.Usage(gatewayProvenance = provenance, providerResponseTiming = dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming(2, 7, 30), gatewayDeliveryTiming = dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayDeliveryTiming("gateway_asgi_stream", "completion_buffered", 1.0, 2.0, 20.0, 21.0, 22.0, 3), gatewayTiming = dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationTiming("physical_backend_request", 10.0, 20.0, 10.0, 30.0, 2.0, 5.0, "completion_buffered"), phase = dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase.INTERIM),
                ProviderEvent.TextDelta("Retained answer"),
                ProviderEvent.Usage(inputTokens = 23, outputTokens = 11),
                ProviderEvent.Completed
            )
        }
        ledger.wrap(session, "run", "turn", "CUSTOM", "selected-model", "primary", 100, 500, 1000)
            .streamRound(emptyList(), emptyList()).collect { event ->
                if (event == ProviderEvent.Completed) {
                    val live = ledger.active.value.values.single()
                    val evidence = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(live.usageEvidenceJson)).jsonObject
                    assertEquals(receipt.evidence(), evidence["request_settings"])
                    assertEquals(provenance, evidence["gateway_provenance"])
                    assertEquals("30", evidence.getValue("response_parsing").jsonObject.getValue("first_parsed_payload_ms").jsonPrimitive.content)
                    assertEquals(kotlinx.serialization.json.JsonNull, evidence.getValue("gateway_delivery").jsonObject["socket_flush_confirmed"])
                    val timing = evidence.getValue("gateway_timing").jsonObject
                    assertEquals("completion_buffered", timing.getValue("delivery_mode").jsonPrimitive.content)
                    assertEquals(kotlinx.serialization.json.JsonNull, timing["first_user_visible_text_ms"])
                    assertEquals(23L, live.observedInputTokens)
                    assertEquals(11L, live.observedOutputTokens)
                    assertEquals(null, live.actualRuntime)
                    assertTrue(!live.estimated)
                }
            }
        coVerify(atLeast = 1) { dao.save(match { it.usageEvidenceJson?.contains("request_settings") == true }) }
    }

    @Test fun terminalDeliveryAlreadyExposesFinalLiveStatusAndSeparatelyMeasuredUsage() = runTest {
        for (failed in listOf(false, true)) {
            val dao = mockk<InvocationDao>(relaxed = true)
            every { dao.recent() } returns flowOf(emptyList())
            val database = mockk<ChatDatabaseV2>()
            every { database.invocationDao() } returns dao
            val ledger = InvocationLedger(database)
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                    ProviderEvent.TextDelta("Retained answer"),
                    ProviderEvent.Usage(inputTokens = 23, outputTokens = 11),
                    if (failed) ProviderEvent.Failed("Verification transport stopped") else ProviderEvent.Completed
                )
            }
            var terminalObserved = false
            ledger.wrap(session, "run", "turn", "CUSTOM", "selected-model", "primary", 100, 500, 1000)
                .streamRound(emptyList(), emptyList()).collect { event ->
                    if (event is ProviderEvent.Completed || event is ProviderEvent.Failed) {
                        terminalObserved = true
                        val live = ledger.active.value.values.single()
                        assertEquals(if (failed) "FAILED" else "COMPLETED", live.status)
                        assertEquals(23L, live.observedInputTokens)
                        assertEquals(11L, live.observedOutputTokens)
                        val evidence = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(live.usageEvidenceJson)).jsonObject
                        assertEquals("100", evidence.getValue("input_reservation").jsonPrimitive.content)
                        assertEquals("500", evidence.getValue("output_reservation").jsonPrimitive.content)
                        assertEquals("true", evidence.getValue("final_usage_complete").jsonPrimitive.content)
                        assertTrue(!live.estimated)
                    }
                }
            assertTrue(terminalObserved)
            assertTrue(ledger.active.value.isEmpty())
        }
    }

    @Test fun `empty visible completion is recorded as failed instead of completed`() = runTest {
        val saved = mutableListOf<ModelInvocation>()
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val providerEvents = listOf(
            ProviderEvent.ThinkingDelta("private reasoning only"),
            ProviderEvent.Usage(inputTokens = 20, outputTokens = 30),
            ProviderEvent.Completed
        )
        val settings = mockk<dev.chungjungsoo.gptmobile.data.repository.SettingRepository>()
        coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(
            spendBudget = SpendBudgetSettings(prices = mapOf("profile" to ModelPrice("model", 1_000_000, 2_000_000, "Test", System.currentTimeMillis())))
        )
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*providerEvents.toTypedArray())
        }

        val events = InvocationLedger(database, settings).wrap(provider, "parent", "turn", "CUSTOM", "model", "primary", 100, 500, 1000, "profile")
            .streamRound(emptyList(), emptyList()).toList()

        assertEquals(providerEvents, events)
        assertEquals("FAILED", saved.single().status)
        assertEquals(80L, saved.single().costMicros)
        assertEquals(30, saved.single().outputTokens)
    }

    @Test fun localPollingEndPersistsRemoteWaitInsteadOfInventingAGenerationFailure() = runTest {
        val saved = mutableListOf<ModelInvocation>()
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                val dispatch = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext])
                assertTrue(dispatch.queueInvocationId != null)
                emit(ProviderEvent.Failed("QUEUE_JOB_PENDING: remote receipt saved", errorCode = "QUEUE_JOB_PENDING"))
            }
        }
        InvocationLedger(database).wrap(provider, "parent", "turn", "FREE", "model", "primary", 100, 500, 1000).streamRound(emptyList(), emptyList()).toList()
        assertEquals("WAITING_REMOTE_JOB", saved.last().status)
    }

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
        assertEquals("estimated", saved.single().outputTokenSource)
        val lanes = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(saved.single().usageEvidenceJson)).jsonObject
        assertEquals("provider_reported_initial", lanes["output"]!!.jsonObject["source"]!!.jsonPrimitive.content)
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
