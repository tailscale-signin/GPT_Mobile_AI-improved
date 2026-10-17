package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.catalog.CatalogCapabilities
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.catalog.SocVariant
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareState
import dev.chungjungsoo.gptmobile.data.localruntime.DeviceThermalState
import dev.chungjungsoo.gptmobile.data.localruntime.FakeLocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineHolder
import dev.chungjungsoo.gptmobile.data.localruntime.LocalHistoryMessage
import dev.chungjungsoo.gptmobile.data.localruntime.LocalHistoryRole
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.localruntime.LocalModelCapabilities
import dev.chungjungsoo.gptmobile.data.localruntime.LocalNpuPackageException
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeEvent
import dev.chungjungsoo.gptmobile.data.localruntime.ScriptedToolInvocation
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.FakeLocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.FakeModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmAdapterTest {
    @Test
    fun `known native quarantine rejects before metadata inspection or engine queue`() = runBlocking {
        val fake = FakeLocalRuntime()
        val blocked = object : LocalRuntime by fake {
            override suspend fun preflightModel(modelPath: String, accelerator: String?) {
                assertEquals("gpu", accelerator)
                throw dev.chungjungsoo.gptmobile.data.localruntime.LocalNativeQuarantinedException()
            }
        }
        val events = adapter(blocked).openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("Verify load"))
        assertTrue(fake.inspectModelCalls.isEmpty())
        assertTrue(fake.loadEngineCalls.isEmpty())
        assertTrue(fake.createConversationCalls.isEmpty())
    }

    @Test
    fun `native session progress deadline cancels a stalled metadata operation`() = runBlocking {
        val fake = FakeLocalRuntime().apply { metadataGate = CompletableDeferred() }
        val events = withTimeout(5000) {
            adapter(fake).openSession(
                turns("hello"),
                localPlatform(),
                progressSettings = dev.chungjungsoo.gptmobile.data.agent.ProgressDeadlineSettings(firstProgressMillis = 25, adaptiveFirstProgress = false)
            ).streamRound(emptyList(), emptyList()).toList()
        }
        assertEquals("MODEL_PROGRESS_TIMEOUT", events.filterIsInstance<ProviderEvent.Failed>().single().progressDeadline?.deadline)
        assertTrue(fake.loadEngineCalls.isEmpty())
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test
    fun `revoked request cannot inspect load or generate on the native runtime`() = runBlocking {
        val runtime = FakeLocalRuntime()
        val adapter = adapter(runtime)
        val rejected = kotlinx.coroutines.withContext(dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard("revoked") { error("Permission revoked") }) {
            runCatching { adapter.openSession(turns("private goal"), localPlatform()).streamRound(emptyList(), emptyList()).toList() }.isFailure
        }
        assertTrue(rejected)
        assertTrue(runtime.loadEngineCalls.isEmpty())
        assertTrue(runtime.createConversationCalls.isEmpty())
        assertTrue(runtime.sendMessageCalls.isEmpty())
    }

    @Test
    fun `permission revoked by a native tool stops evidence return and automatic recovery`() = runBlocking {
        var allowed = true
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.Done))
            scriptedToolInvocations = listOf(listOf(ScriptedToolInvocation("lookup", "{}", afterEventIndex = 0)))
        }
        val authority = dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard("live") { check(allowed) }
        val events = kotlinx.coroutines.withContext(authority) {
            adapter(runtime, catalog = toolsCatalog()).openSession(
                turns("look up"),
                localPlatform(),
                listOf(
                    lookupTool { id, _ ->
                        allowed = false
                        AgentToolResult(id, ToolResultContent.Text("withheld result"), false)
                    }
                )
            ).streamRound(emptyList(), emptyList()).toList()
        }
        assertTrue(events.any { it is ProviderEvent.Failed && it.message.contains("PERMISSION_REVOKED") })
        assertTrue(runtime.toolExecutorResults.isEmpty())
        assertEquals(1, runtime.sendMessageCalls.size)
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test
    fun `reused native callback inherits the current request authority`() = runBlocking {
        var priorAllowed = true
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done), listOf(LocalRuntimeEvent.Done))
            scriptedToolInvocations = listOf(emptyList(), listOf(ScriptedToolInvocation("lookup", "{}", afterEventIndex = 0)))
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())
        var callbackRevision: String? = null
        val tools = listOf(
            lookupTool { id, _ ->
                callbackRevision = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.revision
                AgentToolResult(id, ToolResultContent.Text("current result"), false)
            }
        )
        kotlinx.coroutines.withContext(dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard("old") { check(priorAllowed) }) {
            adapter.openSession(turns("first"), localPlatform(), tools).streamRound(emptyList(), emptyList()).toList()
        }
        priorAllowed = false
        val events = kotlinx.coroutines.withContext(dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard("new") {}) {
            adapter.openSession(listOf(completedTurn("first", "one"), pendingTurn("next")), localPlatform(), tools).streamRound(emptyList(), emptyList()).toList()
        }
        assertEquals("new", callbackRevision)
        assertEquals(1, runtime.createConversationCalls.size)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `status summarizer never loads a cold engine and uses no tools or thinking`() = runBlocking {
        val fake = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("Searching sources"), LocalRuntimeEvent.Done))
        }
        val holder = LocalEngineHolder(fake, timeProvider = { 1000L })
        val adapter = adapter(holder)
        assertEquals("Searching", adapter.summarizeActivity("Searching"))
        assertTrue(fake.loadEngineCalls.isEmpty())
        holder.loadEngine(dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec("/models/gemma.litertlm", LocalAccelerators.CPU, 1024))
        assertEquals("Searching", adapter.summarizeActivity("Searching"))
        assertEquals(1, fake.loadEngineCalls.size)
        assertTrue(fake.createConversationCalls.isEmpty())
        assertEquals(0, fake.closeConversationCalls)
    }

    @Test
    fun `template mismatch rebuilds warm conversation once without reloading weights`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("first answer"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.Error("The new rendered template string does not start with the previous rendered template string. old_string: private prompt")),
                listOf(LocalRuntimeEvent.TextDelta("recovered"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        adapter.openSession(turns("first"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        val events = adapter.openSession(listOf(completedTurn("first", "first answer"), pendingTurn("second")), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf("first", "second", "second"), runtime.sendMessageCalls)
        assertEquals(1, runtime.loadEngineCalls.size)
        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(2, runtime.createConversationCalls.last().initialMessages.size)
        assertTrue(events.none { it is ProviderEvent.Failed })
        assertEquals("recovered", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `repeated template failure is bounded and never exposes rendered prompts`() = runBlocking {
        val mismatch = LocalRuntimeEvent.Error("The new rendered template string does not start with the previous rendered template string. old_string: private prompt")
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done), listOf(mismatch), listOf(mismatch))
        }
        val adapter = adapter(runtime)
        adapter.openSession(turns("first"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        val events = adapter.openSession(listOf(completedTurn("first", "one"), pendingTurn("next")), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(3, runtime.sendMessageCalls.size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertFalse(events.toString().contains("private prompt"))
        assertFalse(runtime.hasOpenConversation())
    }

    @Test
    fun `template error after generated text never replays a request`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("partial"), LocalRuntimeEvent.Error("The new rendered template string does not start with the previous rendered template string."))
            )
        }
        val adapter = adapter(runtime)
        adapter.openSession(turns("first"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        val events = adapter.openSession(listOf(completedTurn("first", "one"), pendingTurn("next")), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, runtime.sendMessageCalls.size)
        assertTrue(events.any { it is ProviderEvent.Failed })
    }

    @Test
    fun `template error after a native tool call never replays the tool`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL), LocalRuntimeEvent.Error("The new rendered template string does not start with the previous rendered template string."))
            )
            scriptedToolInvocations = listOf(emptyList(), listOf(ScriptedToolInvocation("lookup", """{"query":"a"}""")))
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())
        val tools = listOf(lookupTool())
        adapter.openSession(turns("first"), localPlatform(), tools).streamRound(emptyList(), emptyList()).toList()
        val events = adapter.openSession(listOf(completedTurn("first", "one"), pendingTurn("next")), localPlatform(), tools)
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, runtime.sendMessageCalls.size)
        assertEquals(1, runtime.toolExecutorCalls.size)
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertFalse(runtime.hasOpenConversation())
    }

    @Test
    fun `reasoning preference and benchmark constraints reach native conversation config`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = List(3) { listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done) }
        }
        val adapter = adapter(runtime)
        adapter.openSession(turns("first"), localPlatform().copy(reasoning = true))
            .streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(listOf(completedTurn("first", "answer"), pendingTurn("second")), localPlatform().copy(reasoning = false))
            .streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(turns("benchmark"), localPlatform().copy(reasoning = true), constraints = RequestConstraints(allowReasoning = false))
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(true, false, false), runtime.createConversationCalls.map { it.thinkingEnabled })
    }

    @Test
    fun `oversized fixed prompt fails before allocating a native engine`() = runBlocking {
        val runtime = FakeLocalRuntime()
        val events = adapter(runtime).openSession(turns("x".repeat(30000)), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("context"))
        assertTrue(runtime.loadEngineCalls.isEmpty())
        assertTrue(runtime.sendMessageCalls.isEmpty())
    }

    @Test
    fun `compact fallback preserves the user message when optional context is oversized`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("hello"), LocalRuntimeEvent.Done))
        }
        val events = adapter(runtime).openSession(
            turns("hi"),
            localPlatform().copy(systemPrompt = "memory and tool context ".repeat(2000)),
            fallbackSystemPrompt = "Be concise"
        ).streamRound(emptyList(), emptyList()).toList()

        assertTrue(runtime.createConversationCalls.single().systemPrompt.orEmpty().startsWith("Be concise"))
        assertEquals(listOf("hi"), runtime.sendMessageCalls)
        assertTrue(events.any { it is ProviderEvent.Notice && it.message.contains("optional memory") })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `native tool results respect the local context reservation and invalidate hidden tool history`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("done"), LocalRuntimeEvent.Done))
            scriptedToolInvocations = listOf(listOf(ScriptedToolInvocation("lookup", "{}", afterEventIndex = 0)))
        }
        val events = adapter(runtime, catalog = toolsCatalog()).openSession(
            turns("look up the result"),
            localPlatform(),
            listOf(lookupTool { id, _ -> AgentToolResult(id, ToolResultContent.Text("x".repeat(50000)), false) })
        ).streamRound(emptyList(), emptyList()).toList()
        assertTrue(runtime.toolExecutorResults.single().toByteArray().size <= 2048)
        assertFalse(events.filterIsInstance<ProviderEvent.ToolResult>().single().result.outputBudgetExhausted)
        assertEquals(1, runtime.closeConversationCalls)
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `native repeated failures finish without registered tools or replaying actions`() = runBlocking {
        var dispatched = 0
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.Done), listOf(LocalRuntimeEvent.TextDelta("No verified answer was obtained."), LocalRuntimeEvent.Done))
            scriptedToolInvocations = listOf(List(3) { ScriptedToolInvocation("lookup", "{}", afterEventIndex = 0) })
        }
        val failing = lookupTool { id, _ ->
            dispatched++
            AgentToolResult(id, ToolResultContent.Text("Unavailable"), true)
        }
        val events = adapter(runtime, catalog = FakeModelCatalogRepository(listOf(CatalogEntry(id = "gemma3-1b-it", maxContextTokens = 8192)))).openSession(turns("look up this result"), localPlatform(), listOf(failing)).streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, dispatched)
        assertTrue(runtime.createConversationCalls.last().tools.isEmpty())
        assertEquals(2, runtime.sendMessageCalls.size)
        assertTrue(events.any { it is ProviderEvent.Notice && it.message.contains("REPEATED_FAILURE") })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `runtime phase events are forwarded before generated text`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL),
                    LocalRuntimeEvent.TextDelta("answer"),
                    LocalRuntimeEvent.Done
                )
            )
        }
        val events = adapter(runtime).openSession(turns("hello"), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(
            listOf(
                ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL),
                ProviderEvent.TextDelta("answer"),
                ProviderEvent.Completed
            ),
            events
        )
    }

    @Test
    fun `streams text deltas in order and maps thinking channel`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.ThinkingDelta("plan"),
                    LocalRuntimeEvent.TextDelta("hel"),
                    LocalRuntimeEvent.TextDelta("lo"),
                    LocalRuntimeEvent.Done
                )
            )
        }
        val adapter = adapter(runtime)

        val events = adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(
                ProviderEvent.ThinkingDelta("plan"),
                ProviderEvent.TextDelta("hel"),
                ProviderEvent.TextDelta("lo"),
                ProviderEvent.Completed
            ),
            events
        )
        assertEquals(listOf("hello"), runtime.sendMessageCalls)
        assertEquals(1, runtime.createConversationCalls.size)
        assertEquals(0, runtime.closeConversationCalls)
    }

    @Test
    fun `seeds conversation from prior history on first turn`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime)
        val historyTurns = listOf(
            completedTurn("first", "answer"),
            pendingTurn("second")
        )

        adapter.openSession(historyTurns, localPlatform()).streamRound(emptyList(), emptyList()).toList()

        val config = runtime.createConversationCalls.single()
        assertTrue(config.systemPrompt.orEmpty().startsWith("Be concise"))
        assertEquals(2, config.initialMessages.size)
        assertEquals(LocalHistoryRole.USER, config.initialMessages[0].role)
        assertEquals("first", config.initialMessages[0].text)
        assertEquals(LocalHistoryRole.MODEL, config.initialMessages[1].role)
        assertEquals("answer", config.initialMessages[1].text)
        assertEquals(listOf("second"), runtime.sendMessageCalls)
    }

    @Test
    fun `happy path follow-up sends only the new message`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()

        adapter.openSession(turns("first"), platform).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(completedTurn("first", "answer"), pendingTurn("second")),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(1, runtime.createConversationCalls.size)
        assertTrue(runtime.createConversationCalls.single().initialMessages.isEmpty())
        assertEquals(listOf("first", "second"), runtime.sendMessageCalls)
        assertEquals(0, runtime.closeConversationCalls)
    }

    @Test
    fun `mixed-platform history with an unconsumed user turn rebuilds the conversation`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()

        adapter.openSession(turns("first"), platform).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(
                completedTurn("first", "answer"),
                ConversationTurn(
                    userMessage = MessageV2(content = "other-platform-turn", platformType = null),
                    assistantMessage = null,
                    isCurrentTurn = false
                ),
                pendingTurn("newest")
            ),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals(
            listOf(
                LocalHistoryMessage(LocalHistoryRole.USER, "first"),
                LocalHistoryMessage(LocalHistoryRole.MODEL, "answer"),
                LocalHistoryMessage(LocalHistoryRole.USER, "other-platform-turn")
            ),
            runtime.createConversationCalls[1].initialMessages
        )
        assertEquals(listOf("first", "newest"), runtime.sendMessageCalls)
    }

    @Test
    fun `twelve sequential turns reuse one conversation and only send the new message`() = runBlocking {
        val turnCount = 12
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = List(turnCount) { index ->
                listOf(LocalRuntimeEvent.TextDelta("reply-$index"), LocalRuntimeEvent.Done)
            }
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()

        repeat(turnCount) { index ->
            val history = (0 until index).map { prior ->
                completedTurn("user-$prior", "reply-$prior")
            }
            adapter.openSession(history + pendingTurn("user-$index"), platform)
                .streamRound(emptyList(), emptyList())
                .toList()
        }

        assertEquals(1, runtime.createConversationCalls.size)
        assertEquals(0, runtime.closeConversationCalls)
        assertEquals((0 until turnCount).map { index -> "user-$index" }, runtime.sendMessageCalls)
    }

    @Test
    fun `edited early history closes and rebuilds the conversation`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()

        adapter.openSession(turns("first"), platform).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(completedTurn("edited", "answer"), pendingTurn("second")),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals(
            listOf(
                LocalHistoryMessage(LocalHistoryRole.USER, "edited"),
                LocalHistoryMessage(LocalHistoryRole.MODEL, "answer")
            ),
            runtime.createConversationCalls[1].initialMessages
        )
        assertEquals(listOf("first", "second"), runtime.sendMessageCalls)
    }

    @Test
    fun `retry of the last turn rebuilds from remaining history`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("retry"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()

        adapter.openSession(turns("first"), platform).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(completedTurn("first", "answer"), pendingTurn("second")),
            platform
        ).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(completedTurn("first", "answer"), pendingTurn("second")),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals(
            listOf(
                LocalHistoryMessage(LocalHistoryRole.USER, "first"),
                LocalHistoryMessage(LocalHistoryRole.MODEL, "answer")
            ),
            runtime.createConversationCalls[1].initialMessages
        )
        assertEquals(listOf("first", "second", "second"), runtime.sendMessageCalls)
    }

    @Test
    fun `revision switch rebuilds with the selected assistant content`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()

        adapter.openSession(turns("first"), platform).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(completedTurn("first", "revision"), pendingTurn("second")),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals("revision", runtime.createConversationCalls[1].initialMessages[1].text)
    }

    @Test
    fun `cancellation calls cancelActive`() = runBlocking {
        val pause = CompletableDeferred<Unit>()
        val runtime = FakeLocalRuntime().apply {
            pauseAfterFirst = pause
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("hel"), LocalRuntimeEvent.TextDelta("lo"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val events = mutableListOf<ProviderEvent>()

        val job = launch {
            adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).collect {
                events += it
            }
        }
        withTimeout(5_000) {
            while (events.none { it is ProviderEvent.TextDelta }) {
                yield()
            }
        }
        job.cancelAndJoin()
        pause.complete(Unit)

        assertEquals(1, runtime.cancelActiveCalls)
        assertFalse(runtime.hasOpenConversation())
        assertEquals(1, runtime.closeConversationCalls)
        assertTrue(events.any { it is ProviderEvent.TextDelta })
    }

    @Test
    fun `cancel mid-stream forces the next turn to rebuild`() = runBlocking {
        val pause = CompletableDeferred<Unit>()
        val runtime = FakeLocalRuntime().apply {
            pauseAfterFirst = pause
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("hel"), LocalRuntimeEvent.TextDelta("lo"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("rebuilt"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()
        val events = mutableListOf<ProviderEvent>()

        val job = launch {
            adapter.openSession(turns("hello"), platform).streamRound(emptyList(), emptyList()).collect {
                events += it
            }
        }
        withTimeout(5_000) {
            while (events.none { it is ProviderEvent.TextDelta }) {
                yield()
            }
        }
        job.cancelAndJoin()
        pause.complete(Unit)

        adapter.openSession(turns("hello"), platform).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals(listOf("hello", "hello"), runtime.sendMessageCalls)
    }

    @Test
    fun `different profile rebuilds even when history matches`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("two"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)

        adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(turns("hello"), localPlatform(uid = "other")).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals(1, runtime.loadEngineCalls.distinct().size)
    }

    @Test
    fun `different model rebuilds and swaps the engine`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("two"), LocalRuntimeEvent.Done)
            )
        }
        val holder = LocalEngineHolder(runtime, timeProvider = { 1_000L })
        val adapter = adapter(
            holder,
            FakeLocalModelRepository(
                downloadedPaths = mapOf(
                    "gemma3-1b-it" to "/models/gemma.litertlm",
                    "other-model" to "/models/other.litertlm"
                )
            )
        )

        adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(turns("hello"), localPlatform(model = "other-model")).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(2, runtime.loadEngineCalls.size)
        assertEquals("/models/gemma.litertlm", runtime.loadEngineCalls[0].modelPath)
        assertEquals("/models/other.litertlm", runtime.loadEngineCalls[1].modelPath)
        assertEquals(1, runtime.unloadEngineCalls)
    }

    @Test
    fun `attachment turns produce the notice event and continue with text`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime)
        val attachmentTurns = listOf(
            ConversationTurn(
                userMessage = MessageV2(
                    content = "describe this",
                    platformType = null,
                    attachments = listOf(
                        ChatAttachment(
                            localFilePath = "/tmp/photo.png",
                            preparedFilePath = "/tmp/photo.png",
                            displayName = "photo.png",
                            mimeType = "image/png",
                            sizeBytes = 12
                        )
                    )
                ),
                assistantMessage = null,
                isCurrentTurn = true
            )
        )

        val events = adapter.openSession(attachmentTurns, localPlatform()).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(
                ProviderEvent.Notice("The local platform ignored attachments", persistent = true),
                ProviderEvent.TextDelta("ok"),
                ProviderEvent.Completed
            ),
            events
        )
        assertEquals(listOf("describe this"), runtime.sendMessageCalls)
        assertTrue(runtime.sendMessageImages.single().isEmpty())
        assertEquals(false, runtime.loadEngineCalls.single().isVisionEnabled)
    }

    @Test
    fun `text-only request to a vision capable model does not initialize the vision executor`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("hello"), LocalRuntimeEvent.Done))
        }
        adapter(runtime, catalog = visionCatalog()).openSession(listOf(pendingTurn("hello")), visionPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(false, runtime.loadEngineCalls.single().isVisionEnabled)
        assertTrue(runtime.sendMessageImages.single().isEmpty())
    }

    @Test
    fun `vision model forwards image bytes without a notice`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("a cat"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime, catalog = visionCatalog())
        val attachment = imageAttachment()

        val events = adapter.openSession(
            listOf(pendingTurn("describe this", listOf(attachment))),
            visionPlatform()
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(ProviderEvent.TextDelta("a cat"), ProviderEvent.Completed),
            events
        )
        assertEquals(listOf("describe this"), runtime.sendMessageCalls)
        assertImageBytes(listOf("/tmp/photo.png".toByteArray()), runtime.sendMessageImages.single())
        assertEquals(true, runtime.loadEngineCalls.single().isVisionEnabled)
    }

    @Test
    fun `NPU vision package selects NPU for both executors`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("a cat"), LocalRuntimeEvent.Done))
        }
        val catalog = FakeModelCatalogRepository(
            listOf(
                CatalogEntry(
                    id = "gemma-3n-e2b-it",
                    capabilities = CatalogCapabilities(vision = true, npuVision = true),
                    supportedAccelerators = listOf("npu"),
                    socToModelFiles = mapOf("SM8750" to dev.chungjungsoo.gptmobile.data.catalog.SocVariant(contextSize = 1280))
                )
            )
        )
        val adapter = adapter(runtime, catalog = catalog, deviceSocModel = "SM8750")
        adapter.openSession(
            listOf(pendingTurn("describe this", listOf(imageAttachment()))),
            visionPlatform().copy(accelerator = "npu", maxTokens = 320)
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals("npu", runtime.loadEngineCalls.single().accelerator)
        assertEquals("npu", runtime.loadEngineCalls.single().visionAccelerator)
        assertTrue(runtime.sendMessageImages.single().isNotEmpty())
    }

    @Test
    fun `vision model declines a pdf with the notice and still sends text`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime, catalog = visionCatalog())

        val events = adapter.openSession(
            listOf(pendingTurn("summarize", listOf(pdfAttachment()))),
            visionPlatform()
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(
                ProviderEvent.Notice(LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS, persistent = true),
                ProviderEvent.TextDelta("ok"),
                ProviderEvent.Completed
            ),
            events
        )
        assertEquals(listOf("summarize"), runtime.sendMessageCalls)
        assertTrue(runtime.sendMessageImages.single().isEmpty())
    }

    @Test
    fun `vision model forwards an image and declines a pdf in the same turn`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime, catalog = visionCatalog())

        val events = adapter.openSession(
            listOf(pendingTurn("look", listOf(imageAttachment(), pdfAttachment()))),
            visionPlatform()
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(
                ProviderEvent.Notice(LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS, persistent = true),
                ProviderEvent.TextDelta("ok"),
                ProviderEvent.Completed
            ),
            events
        )
        assertImageBytes(listOf("/tmp/photo.png".toByteArray()), runtime.sendMessageImages.single())
    }

    @Test
    fun `non vision model declines an image with the notice`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(
            runtime,
            catalog = FakeModelCatalogRepository(
                listOf(CatalogEntry(id = "gemma3-1b-it", capabilities = CatalogCapabilities(vision = false)))
            )
        )

        val events = adapter.openSession(
            listOf(pendingTurn("describe this", listOf(imageAttachment()))),
            localPlatform()
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(
                ProviderEvent.Notice(LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS, persistent = true),
                ProviderEvent.TextDelta("ok"),
                ProviderEvent.Completed
            ),
            events
        )
        assertTrue(runtime.sendMessageImages.single().isEmpty())
        assertEquals(false, runtime.loadEngineCalls.single().isVisionEnabled)
    }

    @Test
    fun `vision model forwards ten images and notices the rest`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime, catalog = visionCatalog())
        val attachments = (1..11).map { index ->
            imageAttachment(path = "/tmp/photo-$index.png", name = "photo-$index.png")
        }

        val events = adapter.openSession(
            listOf(pendingTurn("what are these", attachments)),
            visionPlatform()
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(
                ProviderEvent.Notice(LiteRtLmAdapter.DEFAULT_TOO_MANY_IMAGES, persistent = true),
                ProviderEvent.TextDelta("ok"),
                ProviderEvent.Completed
            ),
            events
        )
        assertImageBytes(
            (1..10).map { index -> "/tmp/photo-$index.png".toByteArray() },
            runtime.sendMessageImages.single()
        )
    }

    @Test
    fun `follow-up after an image turn reuses the warm conversation`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("a cat"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime, catalog = visionCatalog())
        val platform = visionPlatform()
        val photo = imageAttachment()

        adapter.openSession(
            listOf(pendingTurn("describe this", listOf(photo))),
            platform
        ).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(
                completedTurn("describe this", "a cat", listOf(photo)),
                pendingTurn("follow up")
            ),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(1, runtime.createConversationCalls.size)
        assertEquals(0, runtime.closeConversationCalls)
        assertEquals(listOf("describe this", "follow up"), runtime.sendMessageCalls)
        assertTrue(runtime.sendMessageImages[1].isEmpty())
    }

    @Test
    fun `removing a prior image turn rebuilds the warm conversation`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("a cat"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("rebuilt"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime, catalog = visionCatalog())
        val platform = visionPlatform()
        val photo = imageAttachment()

        adapter.openSession(
            listOf(pendingTurn("describe this", listOf(photo))),
            platform
        ).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(
                completedTurn("describe this", "a cat"),
                pendingTurn("follow up")
            ),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals(listOf("describe this", "follow up"), runtime.sendMessageCalls)
        assertTrue(runtime.createConversationCalls[1].initialMessages[0].imageIds.isEmpty())
    }

    @Test
    fun `rebuild of a prior image turn seeds conversation with image bytes`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("a cat"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("retry"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime, catalog = visionCatalog())
        val platform = visionPlatform()
        val photo = imageAttachment()

        adapter.openSession(
            listOf(pendingTurn("describe this", listOf(photo))),
            platform
        ).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(
                completedTurn("describe this", "a cat", listOf(photo)),
                pendingTurn("follow up")
            ),
            platform
        ).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(
                completedTurn("describe this", "a cat", listOf(photo)),
                pendingTurn("follow up")
            ),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        val rebuiltUser = runtime.createConversationCalls[1].initialMessages[0]
        assertEquals("describe this", rebuiltUser.text)
        assertEquals(listOf("/tmp/photo.png|image/png|12"), rebuiltUser.imageIds)
        assertImageBytes(listOf("/tmp/photo.png".toByteArray()), rebuiltUser.images)
    }

    @Test
    fun `missing model produces error event`() = runBlocking {
        val runtime = FakeLocalRuntime()
        val adapter = LiteRtLmAdapter(
            localRuntime = runtime,
            localModelRepository = FakeLocalModelRepository(),
            ignoredAttachmentsNotice = LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS,
            modelNotDownloadedError = LiteRtLmAdapter.DEFAULT_MODEL_NOT_DOWNLOADED
        )

        val events = adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(ProviderEvent.Failed("This Local Model is not downloaded. Download it from Settings → Local Models.")),
            events
        )
        assertTrue(runtime.loadEngineCalls.isEmpty())
        assertTrue(runtime.sendMessageCalls.isEmpty())
    }

    @Test
    fun `contended mutex emits waiting notice and runs complete in order`() = runBlocking {
        val pause = CompletableDeferred<Unit>()
        val runtime = FakeLocalRuntime().apply {
            pauseAfterFirst = pause
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("two"), LocalRuntimeEvent.Done)
            )
        }
        val holder = LocalEngineHolder(runtime, timeProvider = { 1_000L })
        val adapter = adapter(holder)
        val platform = localPlatform()
        val firstEvents = mutableListOf<ProviderEvent>()
        val secondEvents = mutableListOf<ProviderEvent>()

        val first = launch {
            adapter.openSession(turns("a"), platform).streamRound(emptyList(), emptyList()).collect {
                firstEvents += it
            }
        }
        withTimeout(5_000) {
            while (firstEvents.none { it is ProviderEvent.TextDelta }) {
                yield()
            }
        }

        val second = launch {
            adapter.openSession(turns("b"), localPlatform(uid = "other")).streamRound(emptyList(), emptyList()).collect {
                secondEvents += it
            }
        }
        withTimeout(5_000) {
            while (secondEvents.none { it is ProviderEvent.Notice && it.message == LiteRtLmAdapter.DEFAULT_WAITING_FOR_ENGINE }) {
                yield()
            }
        }

        pause.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            listOf(ProviderEvent.TextDelta("one"), ProviderEvent.Completed),
            firstEvents
        )
        assertEquals(
            listOf(
                ProviderEvent.Notice(LiteRtLmAdapter.DEFAULT_WAITING_FOR_ENGINE),
                ProviderEvent.TextDelta("two"),
                ProviderEvent.Completed
            ),
            secondEvents
        )
        assertEquals(listOf("a", "b"), runtime.sendMessageCalls)
    }

    @Test
    fun `tool capable model registers bound tools with constrained decoding`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())
        val session = adapter.openSession(turns("hello"), localPlatform(), listOf(lookupTool()))

        assertTrue(session.handlesToolsInternally)
        session.streamRound(emptyList(), emptyList()).toList()

        val config = runtime.createConversationCalls.single()
        assertEquals(listOf("lookup"), config.tools.map { it.name })
        assertEquals("Look things up", config.tools.single().description)
        assertTrue(config.tools.single().inputSchemaJson.contains("query"))
        assertTrue(config.isConstrainedDecodingEnabled)
        assertTrue(config.toolExecutor != null)
    }

    @Test
    fun `scripted engine tool invocation executes the tool emits timeline events and continues`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.TextDelta("before"),
                    LocalRuntimeEvent.TextDelta("after"),
                    LocalRuntimeEvent.Done
                )
            )
            scriptedToolInvocations = listOf(
                listOf(ScriptedToolInvocation("lookup", """{"query":"weather"}""", afterEventIndex = 0))
            )
        }
        val executedArgs = mutableListOf<JsonObject>()
        val adapter = adapter(runtime, catalog = toolsCatalog())

        val events = adapter.openSession(
            turns("hello"),
            localPlatform(),
            listOf(
                lookupTool { callId, arguments ->
                    executedArgs += arguments
                    AgentToolResult(callId, ToolResultContent.Text("result-ok"), isError = false)
                }
            )
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(listOf("lookup" to """{"query":"weather"}"""), runtime.toolExecutorCalls)
        assertEquals("result-ok", ((kotlinx.serialization.json.Json.parseToJsonElement(runtime.toolExecutorResults.single()) as kotlinx.serialization.json.JsonObject)["data"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(1, executedArgs.size)
        assertEquals("weather", executedArgs.single()["query"]?.toString()?.trim('"'))
        val toolCall = events.filterIsInstance<ProviderEvent.ToolCall>().single()
        val toolResult = events.filterIsInstance<ProviderEvent.ToolResult>().single()
        assertEquals("lookup", toolCall.name)
        assertEquals("weather", toolCall.arguments["query"]?.toString()?.trim('"'))
        assertEquals(toolCall, toolResult.call)
        assertEquals(false, toolResult.result.isError)
        assertEquals(ToolResultContent.Text("result-ok"), toolResult.result.content)
        assertEquals(
            listOf("before", "after"),
            events.filterIsInstance<ProviderEvent.TextDelta>().map { it.text }
        )
        assertTrue(events.last() is ProviderEvent.Completed)
        assertTrue(events.indexOf(toolCall) > events.indexOfFirst { it is ProviderEvent.TextDelta })
        assertTrue(events.indexOf(toolResult) > events.indexOf(toolCall))
        assertTrue(events.indexOfLast { it is ProviderEvent.TextDelta } > events.indexOf(toolResult))
    }

    @Test
    fun `model without catalog tool recommendation still registers bound tools`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime)

        adapter.openSession(turns("hello"), localPlatform(), listOf(lookupTool()))
            .streamRound(emptyList(), emptyList())
            .toList()

        val config = runtime.createConversationCalls.single()
        assertEquals(listOf("lookup"), config.tools.map { it.name })
        assertTrue(config.isConstrainedDecodingEnabled)
        assertTrue(config.toolExecutor != null)
    }

    @Test
    fun `local model without native function calling still sees and executes authorized tools`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            capabilities = LocalModelCapabilities(backends = setOf("cpu", "gpu", "npu"), tools = false)
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("""{"type":"tool_call","name":"lookup","arguments":{"query":"weather"}}"""), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("The tool returned clear weather."), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        var invoked = false
        val events = adapter.openSession(
            turns("hello"),
            localPlatform(),
            listOf(
                lookupTool { callId, _ ->
                    invoked = true
                    AgentToolResult(callId, ToolResultContent.Text("clear weather"), isError = false)
                }
            )
        ).streamRound(emptyList(), emptyList()).toList()

        assertTrue(invoked)
        assertTrue(runtime.createConversationCalls.single().tools.isEmpty())
        assertTrue(runtime.createConversationCalls.single().systemPrompt.orEmpty().contains("lookup"))
        assertTrue(runtime.sendMessageCalls[1].contains("clear weather"))
        assertEquals("lookup", events.filterIsInstance<ProviderEvent.ToolCall>().single().name)
        assertEquals("The tool returned clear weather.", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `8192 output preference retains and dispatches prompt tools in 8192 context`() = runBlocking {
        var calls = 0
        val runtime = FakeLocalRuntime().apply {
            capabilities = LocalModelCapabilities(contextTokens = 8192, backends = setOf("gpu"), tools = false)
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("""<tool_call>{"name":"lookup","arguments":{}}</tool_call>"""), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("Verified answer"), LocalRuntimeEvent.Done)
            )
        }
        val tool = lookupTool { id, _ ->
            calls++
            AgentToolResult(id, ToolResultContent.Text("Verified observation"), false)
        }
        val events = adapter(runtime).openSession(
            turns("What can you do?"),
            localPlatform().copy(maxTokens = 8192, systemPrompt = "System instruction ".repeat(185)),
            listOf(tool)
        ).streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, calls)
        val config = runtime.createConversationCalls.first()
        assertTrue(config.tools.isEmpty())
        assertEquals(1, Regex("Authorized local tools").findAll(config.systemPrompt.orEmpty()).count())
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().any { it.text == "Verified answer" })
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `prompt bridge checks revocation before returning evidence or continuing inference`() = runBlocking {
        var allowed = true
        val runtime = FakeLocalRuntime().apply {
            capabilities = LocalModelCapabilities(backends = setOf("gpu"), tools = false)
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("""{"tool_call":"lookup","arguments":{}}"""), LocalRuntimeEvent.Done)
            )
        }
        val authority = dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard("live") { check(allowed) }
        val events = kotlinx.coroutines.withContext(authority) {
            adapter(runtime).openSession(
                turns("look up"),
                localPlatform(),
                listOf(
                    lookupTool { id, _ ->
                        allowed = false
                        AgentToolResult(id, ToolResultContent.Text("Withheld observation"), false)
                    }
                )
            ).streamRound(emptyList(), emptyList()).toList()
        }
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().any { it.message.contains("PERMISSION_REVOKED") })
        assertTrue(events.filterIsInstance<ProviderEvent.ToolResult>().isEmpty())
        assertEquals(1, runtime.sendMessageCalls.size)
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test
    fun `native capable model dispatches text tool call fallback`() = runBlocking {
        var lookupCalls = 0
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.TextDelta("""{"tool_call":"lookup","arguments":{"query":"weather"}}"""),
                    LocalRuntimeEvent.Done
                ),
                listOf(LocalRuntimeEvent.TextDelta("It is sunny."), LocalRuntimeEvent.Done)
            )
        }
        val events = adapter(runtime, catalog = toolsCatalog()).openSession(
            turns("look up the weather"),
            localPlatform(),
            listOf(
                lookupTool { id, _ ->
                    lookupCalls++
                    AgentToolResult(id, ToolResultContent.Text("sunny"), false)
                }
            )
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(1, lookupCalls)
        assertFalse(runtime.createConversationCalls.first().systemPrompt.orEmpty().contains("Authorized local tools"))
        assertEquals(listOf("lookup"), runtime.createConversationCalls.first().tools.map { it.name })
        assertFalse(events.filterIsInstance<ProviderEvent.TextDelta>().any { it.text.contains("tool_call") })
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().any { it.text.contains("It is sunny.") })
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `tool capable model without bound tools does not register tools`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())

        adapter.openSession(turns("hello"), localPlatform())
            .streamRound(emptyList(), emptyList())
            .toList()

        val config = runtime.createConversationCalls.single()
        assertTrue(config.tools.isEmpty())
        assertFalse(config.isConstrainedDecodingEnabled)
        assertEquals(null, config.toolExecutor)
    }

    @Test
    fun `same tools reuse the warm conversation without re-registering`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("two"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())
        val platform = localPlatform()
        val tools = listOf(lookupTool())

        adapter.openSession(turns("first"), platform, tools).streamRound(emptyList(), emptyList()).toList()
        adapter.openSession(
            listOf(completedTurn("first", "one"), pendingTurn("second")),
            platform,
            tools
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(1, runtime.createConversationCalls.size)
        assertEquals(0, runtime.closeConversationCalls)
        assertEquals(listOf("lookup"), runtime.createConversationCalls.single().tools.map { it.name })
    }

    @Test
    fun `adapter resolves capabilities through the cache-first catalog path`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val catalog = FakeModelCatalogRepository(
            listOf(CatalogEntry(id = "gemma-3n-e2b-it", capabilities = CatalogCapabilities(vision = true)))
        )
        val adapter = adapter(runtime, catalog = catalog)

        adapter.openSession(
            listOf(pendingTurn("describe this", listOf(imageAttachment()))),
            visionPlatform()
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(0, catalog.visibleEntriesCalls)
        assertEquals(1, catalog.cachedVisibleEntriesCalls)
        assertEquals(true, runtime.loadEngineCalls.single().isVisionEnabled)
        assertImageBytes(listOf("/tmp/photo.png".toByteArray()), runtime.sendMessageImages.single())
    }

    @Test
    fun `queued runs keep their own tools and event streams`() = runBlocking {
        val pause = CompletableDeferred<Unit>()
        val runtime = FakeLocalRuntime().apply {
            pauseAfterFirst = pause
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("two"), LocalRuntimeEvent.Done)
            )
            scriptedToolInvocations = listOf(
                listOf(ScriptedToolInvocation("lookup", """{"query":"a"}""", afterEventIndex = 0)),
                listOf(ScriptedToolInvocation("echo", """{"query":"b"}""", afterEventIndex = 0))
            )
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())
        val firstEvents = mutableListOf<ProviderEvent>()
        val secondEvents = mutableListOf<ProviderEvent>()
        val firstToolCalls = mutableListOf<String>()
        val secondToolCalls = mutableListOf<String>()

        val first = launch {
            adapter.openSession(
                turns("a"),
                localPlatform(),
                listOf(
                    lookupTool { callId, _ ->
                        firstToolCalls += "lookup"
                        AgentToolResult(callId, ToolResultContent.Text("first-ok"), isError = false)
                    }
                )
            ).streamRound(emptyList(), emptyList()).collect { firstEvents += it }
        }
        withTimeout(5_000) {
            while (firstEvents.none { it is ProviderEvent.TextDelta }) {
                yield()
            }
        }

        val second = launch {
            adapter.openSession(
                turns("b"),
                localPlatform(uid = "other"),
                listOf(
                    lookupTool(name = "echo") { callId, _ ->
                        secondToolCalls += "echo"
                        AgentToolResult(callId, ToolResultContent.Text("second-ok"), isError = false)
                    }
                )
            ).streamRound(emptyList(), emptyList()).collect { secondEvents += it }
        }
        withTimeout(5_000) {
            while (secondEvents.none { it is ProviderEvent.Notice }) {
                yield()
            }
        }

        pause.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("lookup"), firstToolCalls)
        assertEquals(listOf("echo"), secondToolCalls)
        assertEquals(listOf("lookup"), firstEvents.filterIsInstance<ProviderEvent.ToolCall>().map { it.name })
        assertEquals(listOf("echo"), secondEvents.filterIsInstance<ProviderEvent.ToolCall>().map { it.name })
        assertTrue(firstEvents.none { it is ProviderEvent.ToolCall && it.name == "echo" })
        assertTrue(secondEvents.none { it is ProviderEvent.ToolCall && it.name == "lookup" })
    }

    @Test
    fun `cold engine emits a loading notice and a warm engine does not`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("two"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime, loadingModelNotice = LiteRtLmAdapter.DEFAULT_LOADING_MODEL)
        val platform = localPlatform()

        val coldEvents = adapter.openSession(turns("first"), platform).streamRound(emptyList(), emptyList()).toList()
        val warmEvents = adapter.openSession(
            listOf(completedTurn("first", "one"), pendingTurn("second")),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(
            listOf(
                ProviderEvent.Notice(LiteRtLmAdapter.DEFAULT_LOADING_MODEL),
                ProviderEvent.TextDelta("one"),
                ProviderEvent.Completed
            ),
            coldEvents
        )
        assertEquals(
            listOf(ProviderEvent.TextDelta("two"), ProviderEvent.Completed),
            warmEvents
        )
    }

    @Test
    fun `trim during generation cancels unloads and the next turn reloads and rebuilds`() = runBlocking {
        val pause = CompletableDeferred<Unit>()
        val runtime = FakeLocalRuntime().apply {
            pauseAfterFirst = pause
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("hel"), LocalRuntimeEvent.TextDelta("lo"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("rebuilt"), LocalRuntimeEvent.Done)
            )
        }
        val holder = LocalEngineHolder(runtime, timeProvider = { 1_000L })
        val adapter = adapter(holder)
        val platform = localPlatform()
        val firstEvents = mutableListOf<ProviderEvent>()

        val first = launch {
            try {
                adapter.openSession(turns("hello"), platform).streamRound(emptyList(), emptyList()).collect {
                    firstEvents += it
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        withTimeout(5_000) {
            while (firstEvents.none { it is ProviderEvent.TextDelta }) {
                yield()
            }
        }

        holder.unloadEngine()
        first.join()

        adapter.openSession(turns("hello"), platform).streamRound(emptyList(), emptyList()).toList()

        assertTrue(runtime.cancelActiveCalls >= 1)
        assertEquals(1, runtime.unloadEngineCalls)
        assertEquals(2, runtime.loadEngineCalls.size)
        assertEquals(2, runtime.createConversationCalls.size)
    }

    @Test
    fun `changed tool set rebuilds the conversation and re-registers`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("one"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("two"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())
        val platform = localPlatform()

        adapter.openSession(turns("first"), platform, listOf(lookupTool()))
            .streamRound(emptyList(), emptyList())
            .toList()
        adapter.openSession(
            listOf(completedTurn("first", "one"), pendingTurn("second")),
            platform,
            listOf(lookupTool(name = "echo"))
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, runtime.createConversationCalls.size)
        assertEquals(1, runtime.closeConversationCalls)
        assertEquals(listOf("lookup"), runtime.createConversationCalls[0].tools.map { it.name })
        assertEquals(listOf("echo"), runtime.createConversationCalls[1].tools.map { it.name })
        assertTrue(runtime.createConversationCalls[1].isConstrainedDecodingEnabled)
    }

    @Test
    fun `throwing tool is recorded as a timeline failure and generation continues`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.TextDelta("before"),
                    LocalRuntimeEvent.TextDelta("after"),
                    LocalRuntimeEvent.Done
                )
            )
            scriptedToolInvocations = listOf(
                listOf(ScriptedToolInvocation("lookup", """{"query":"x"}""", afterEventIndex = 0))
            )
        }
        val adapter = adapter(runtime, catalog = toolsCatalog())
        val failingTool = lookupTool { _, _ -> error("boom") }

        val events = adapter.openSession(turns("hello"), localPlatform(), listOf(failingTool))
            .streamRound(emptyList(), emptyList())
            .toList()

        val toolResult = events.filterIsInstance<ProviderEvent.ToolResult>().single()
        assertEquals(true, toolResult.result.isError)
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", toolResult.result.errorCode)
        assertFalse(events.any { it is ProviderEvent.Failed })
        assertEquals(
            listOf("before", "after"),
            events.filterIsInstance<ProviderEvent.TextDelta>().map { it.text }
        )
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `gpu engine load failure falls back to cpu emits notice and later turns use cpu`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            failLoadEngineIf = { spec ->
                if (spec.accelerator == LocalAccelerators.GPU) {
                    IllegalStateException("Failed to create engine … CreateSharedMemoryManager unimplemented")
                } else {
                    null
                }
            }
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("next"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)
        val platform = localPlatform()

        val first = adapter.openSession(turns("hello"), platform).streamRound(emptyList(), emptyList()).toList()
        val second = adapter.openSession(
            listOf(completedTurn("hello", "ok"), pendingTurn("again")),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        assertTrue(first.any { it is ProviderEvent.Notice && it.message == LiteRtLmAdapter.DEFAULT_GPU_UNAVAILABLE })
        assertTrue(first.any { it is ProviderEvent.TextDelta && it.text == "ok" })
        assertTrue(first.last() is ProviderEvent.Completed)
        assertFalse(first.any { it is ProviderEvent.Failed })
        assertEquals(
            listOf(LocalAccelerators.GPU, LocalAccelerators.CPU),
            runtime.loadEngineCalls.map { it.accelerator }
        )
        assertTrue(runtime.loadEngineCalls.drop(2).isEmpty())
        assertFalse(second.any { it is ProviderEvent.Notice && it.message == LiteRtLmAdapter.DEFAULT_GPU_UNAVAILABLE })
    }

    @Test
    fun `unverified NPU selection is rejected before native loading`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            failLoadEngineIf = { spec ->
                if (spec.accelerator == LocalAccelerators.GPU || spec.accelerator == LocalAccelerators.NPU) {
                    IllegalStateException("accelerator unavailable")
                } else {
                    null
                }
            }
            scriptedEvents = listOf(
                listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done),
                listOf(LocalRuntimeEvent.TextDelta("npu"), LocalRuntimeEvent.Done)
            )
        }
        val adapter = adapter(runtime)

        val gpuEvents = adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        val npuEvents = adapter.openSession(
            listOf(completedTurn("hello", "ok"), pendingTurn("again")),
            localPlatform(uid = "local-npu").copy(accelerator = LocalAccelerators.NPU)
        ).streamRound(emptyList(), emptyList()).toList()

        assertTrue(gpuEvents.any { it is ProviderEvent.Notice && it.message == LiteRtLmAdapter.DEFAULT_GPU_UNAVAILABLE })
        assertTrue(npuEvents.any { it is ProviderEvent.Failed && it.message.contains("QNN build matched to this phone") })
        assertFalse(npuEvents.any { it is ProviderEvent.Notice && it.message == LiteRtLmAdapter.DEFAULT_GPU_UNAVAILABLE })
        assertEquals(
            listOf(LocalAccelerators.GPU, LocalAccelerators.CPU),
            runtime.loadEngineCalls.map { it.accelerator }
        )
    }

    @Test
    fun `installed exact SOC NPU package runs after dynamic catalog entry expires`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("npu ok"), LocalRuntimeEvent.Done))
        }
        val model = "hf_qwen_sm8750"
        val file = "qwen_qualcomm_sm8750_ctx1024.litertlm"
        val models = FakeLocalModelRepository(
            initialModels = listOf(installedModel(model, file)),
            downloadedPaths = mapOf(model to "/models/$file")
        )

        val events = adapter(
            runtime,
            models,
            FakeModelCatalogRepository(emptyList()),
            deviceSocModel = "SM8750-AB"
        ).openSession(
            turns("hello"),
            localPlatform(model = model).copy(accelerator = LocalAccelerators.NPU)
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(LocalAccelerators.NPU, runtime.loadEngineCalls.single().accelerator)
        assertTrue(events.any { it is ProviderEvent.TextDelta && it.text == "npu ok" })
        assertFalse(events.any { it is ProviderEvent.Failed })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `unset output limit uses a bounded reply without shrinking context`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val events = adapter(runtime).openSession(turns("hello"), localPlatform().copy(maxTokens = null))
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(4096, runtime.loadEngineCalls.single().maxTokens)
        assertEquals(512, runtime.createConversationCalls.single().maxOutputTokens)
        assertTrue(events.any { it is ProviderEvent.Notice && it.message.contains("Local reply budget: 512 tokens") })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `explicit output limit does not shrink context and request limit wins`() = runBlocking {
        for (cap in listOf(128, 256, 512)) {
            val runtime = FakeLocalRuntime().apply {
                scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
            }
            val events = adapter(runtime)
                .openSession(turns("hello"), localPlatform().copy(maxTokens = 4096), constraints = RequestConstraints(maxOutputTokens = cap))
                .streamRound(emptyList(), emptyList())
                .toList()
            assertEquals(4096, runtime.loadEngineCalls.single().maxTokens)
            assertEquals(cap, runtime.createConversationCalls.single().maxOutputTokens)
            assertEquals(cap, events.filterIsInstance<ProviderEvent.RequestConfigured>().single().effectiveOutputTokens)
        }
    }

    @Test
    fun `explicit output larger than context is clamped before model dispatch`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val events = adapter(runtime).openSession(turns("hello"), localPlatform().copy(maxTokens = 8192))
            .streamRound(emptyList(), emptyList()).toList()
        val configured = events.filterIsInstance<ProviderEvent.RequestConfigured>().single()
        assertEquals(8192, configured.requestedOutputTokens)
        assertTrue(configured.effectiveOutputTokens!! in 1 until runtime.loadEngineCalls.single().maxTokens)
        assertEquals(configured.effectiveOutputTokens, runtime.createConversationCalls.single().maxOutputTokens)
        assertEquals(listOf("hello"), runtime.sendMessageCalls)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `NPU context and reply budget respect the matching SOC variant`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(
            runtime,
            catalog = FakeModelCatalogRepository(
                listOf(
                    CatalogEntry(
                        id = "gemma3-1b-it",
                        supportedAccelerators = listOf("gpu", "cpu", "npu"),
                        socToModelFiles = mapOf(
                            "SM8750" to SocVariant(modelFile = "npu.litertlm", contextSize = 1280)
                        )
                    )
                )
            ),
            deviceSocModel = "SM8750"
        )

        adapter.openSession(
            turns("hello"),
            localPlatform().copy(accelerator = LocalAccelerators.NPU, maxTokens = null)
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(1280, runtime.loadEngineCalls.single().maxTokens)
        assertEquals(320, runtime.createConversationCalls.single().maxOutputTokens)
        assertEquals(LocalAccelerators.NPU, runtime.loadEngineCalls.single().accelerator)
    }

    @Test
    fun `high RAM device allows expanded 8192 context on GPU`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            deviceRamGb = 16L
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime)

        adapter.openSession(
            turns("hello"),
            localPlatform().copy(accelerator = LocalAccelerators.GPU, maxTokens = 512)
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(8192, runtime.loadEngineCalls.single().maxTokens)
    }

    @Test
    fun `thermal state does not silently change requested context or sampling`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            deviceRamGb = 16L
            simulatedHardwareState = DeviceHardwareState(
                thermalState = DeviceThermalState.SEVERE,
                batteryPct = 50,
                isCharging = false
            )
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime)

        adapter.openSession(
            turns("hello"),
            localPlatform().copy(accelerator = LocalAccelerators.GPU, maxTokens = 512, topK = 40)
        ).streamRound(emptyList(), emptyList()).toList()

        // Production admission rejects unsafe work; the adapter preserves quality settings.
        assertEquals(8192, runtime.loadEngineCalls.single().maxTokens)
        assertEquals(40, runtime.createConversationCalls.single().sampler.topK)
    }

    @Test
    fun `thermal state does not silently discard history through a smaller budget`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            deviceRamGb = 16L
            simulatedHardwareState = DeviceHardwareState(
                thermalState = DeviceThermalState.SEVERE,
                batteryPct = 50,
                isCharging = false
            )
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime)
        // Admission and thermal policy must not silently shrink the requested history budget.
        val platform = localPlatform().copy(accelerator = LocalAccelerators.GPU, maxTokens = 512)

        val anchor = completedTurn("Anchor turn prompt setup", "Anchor reply")
        // Create 20 historical turns with 300 chars each (~6000 chars total, which exceeds 1024 tokens / ~4096 chars)
        val intermediateTurns = (1..20).map { i ->
            completedTurn("Intermediate user question turn $i ".repeat(10), "Intermediate answer $i with reply words filling context room 1234567890 1234567890")
        }
        val current = pendingTurn("Current user message")

        adapter.openSession(
            listOf(anchor) + intermediateTurns + current,
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        val config = runtime.createConversationCalls.single()
        // Anchor must be preserved
        assertEquals("Anchor turn prompt setup", config.initialMessages[0].text)
        assertEquals("Anchor reply", config.initialMessages[1].text)
        // All history that fits the explicitly requested context remains available.
        assertEquals((intermediateTurns.size * 2) + 2, config.initialMessages.size)
    }

    @Test
    fun `high RAM device still respects NPU context clamp`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            deviceRamGb = 16L
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(
            runtime,
            catalog = FakeModelCatalogRepository(
                listOf(
                    CatalogEntry(
                        id = "gemma3-1b-it",
                        supportedAccelerators = listOf("gpu", "cpu", "npu"),
                        socToModelFiles = mapOf(
                            "SM8750" to SocVariant(modelFile = "npu.litertlm", contextSize = 1280)
                        )
                    )
                )
            ),
            deviceSocModel = "SM8750"
        )

        adapter.openSession(
            turns("hello"),
            localPlatform().copy(accelerator = LocalAccelerators.NPU, maxTokens = 320)
        ).streamRound(emptyList(), emptyList()).toList()

        assertEquals(1280, runtime.loadEngineCalls.single().maxTokens)
    }

    @Test
    fun `excessive turns beyond context budget are compacted preserving anchor Turn 0`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(runtime, catalog = FakeModelCatalogRepository(listOf(CatalogEntry(id = "gemma3-1b-it", maxContextTokens = 256))))
        val platform = localPlatform().copy(maxTokens = null) // Small compiled context, independent of output limit

        val anchor = completedTurn("Anchor prompt setup instructions", "Anchor reply confirmation")
        val middle1 = completedTurn("Intermediate step 1 with lots and lots of text that overflows", "Intermediate reply 1")
        val middle2 = completedTurn("Intermediate step 2 with lots and lots of text that overflows", "Intermediate reply 2")
        val recent = completedTurn("Recent step question", "Recent step reply")
        val current = pendingTurn("Current prompt")

        adapter.openSession(
            listOf(anchor, middle1, middle2, recent, current),
            platform
        ).streamRound(emptyList(), emptyList()).toList()

        val config = runtime.createConversationCalls.single()
        // Anchor (first user & model message) must be preserved
        assertEquals("Anchor prompt setup instructions", config.initialMessages[0].text)
        assertEquals("Anchor reply confirmation", config.initialMessages[1].text)
        // Required generation/template headroom takes priority over optional recent history.
        assertEquals(2, config.initialMessages.size)
    }

    @Test
    fun `generation completion emits telemetry notice with live metrics`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.TextDelta("Hello world"),
                    LocalRuntimeEvent.Metrics(
                        LocalInferenceMetrics(
                            timeToFirstTokenMs = 120L,
                            totalDurationMs = 500L,
                            totalChunks = 5,
                            totalCharacters = 44,
                            estimatedTokens = 11,
                            tokensPerSecond = 22.0
                        )
                    ),
                    LocalRuntimeEvent.Done
                )
            )
        }
        val adapter = adapter(runtime)

        val events = adapter.openSession(turns("hi"), localPlatform()).streamRound(emptyList(), emptyList()).toList()

        val notice = events.filterIsInstance<ProviderEvent.Notice>().singleOrNull { it.message.startsWith("Local:") }
        assertTrue(notice != null)
        assertTrue(notice!!.message.contains("Local: ~22.0 tok/s end-to-end · First callback 120ms · ~11 tokens"))
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `telemetry notice appends throttling badge under severe thermal pressure`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            simulatedHardwareState = DeviceHardwareState(
                thermalState = DeviceThermalState.SEVERE,
                batteryPct = 40,
                isCharging = false
            )
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.TextDelta("Hello world"),
                    LocalRuntimeEvent.Metrics(
                        LocalInferenceMetrics(
                            timeToFirstTokenMs = 250L,
                            totalDurationMs = 1000L,
                            totalChunks = 4,
                            totalCharacters = 32,
                            estimatedTokens = 8,
                            tokensPerSecond = 8.0
                        )
                    ),
                    LocalRuntimeEvent.Done
                )
            )
        }
        val adapter = adapter(runtime)

        val events = adapter.openSession(turns("hi"), localPlatform()).streamRound(emptyList(), emptyList()).toList()

        val notice = events.filterIsInstance<ProviderEvent.Notice>().singleOrNull { it.message.startsWith("Local:") }
        assertTrue(notice != null)
        assertTrue(notice!!.message.contains("Local: ~8.0 tok/s end-to-end · First callback 250ms · ~8 tokens · ⚡ Throttled"))
    }

    @Test
    fun `cpu fallback failure retains the original GPU cause and secondary CPU failure`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            failLoadEngineIf = {
                IllegalStateException("Failed to create engine … CreateSharedMemoryManager unimplemented")
            }
        }
        val adapter = adapter(runtime)

        val events = adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()

        val failed = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertTrue(failed.message.startsWith(LiteRtLmAdapter.DEFAULT_ENGINE_LOAD_FAILED))
        assertTrue(failed.message.contains("GPU:"))
        assertTrue(failed.message.contains("CPU fallback:"))
        assertTrue(failed.message.contains("CreateSharedMemoryManager"))
        assertTrue(failed.message.indexOf("GPU:") < failed.message.indexOf("CPU fallback:"))
    }

    @Test
    fun `legacy GPU profile resolves downloaded edition with its own capabilities and context`() = runBlocking {
        val runtime = FakeLocalRuntime()
        val models = FakeLocalModelRepository(
            initialModels = listOf(installedModel("gemma3-1b-it", "gemma_SM8750.litertlm")),
            downloadedPaths = mapOf(
                "gemma3-1b-it" to "/models/gemma_SM8750.litertlm",
                "gemma3-1b-it-litert" to "/models/gemma-gpu.litertlm"
            )
        )
        val catalog = FakeModelCatalogRepository(
            listOf(
                CatalogEntry(id = "gemma3-1b-it", maxContextTokens = 1024, capabilities = CatalogCapabilities(vision = true)),
                CatalogEntry(id = "gemma3-1b-it-litert", maxContextTokens = 2048)
            )
        )
        val events = adapter(runtime, models, catalog).openSession(turns("hello"), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        val spec = runtime.loadEngineCalls.single()
        assertEquals("/models/gemma-gpu.litertlm", spec.modelPath)
        assertEquals(2048, spec.maxTokens)
        assertFalse(spec.isVisionEnabled)
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `legacy GPU profile still works after original NPU download is removed`() = runBlocking {
        val runtime = FakeLocalRuntime()
        val models = FakeLocalModelRepository(downloadedPaths = mapOf("gemma3-1b-it-litert" to "/models/gemma.litertlm"))
        val events = adapter(runtime, models).openSession(turns("hello"), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals("/models/gemma.litertlm", runtime.loadEngineCalls.single().modelPath)
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun `known broken downloaded MiniCpm export fails before allocating native memory`() = runBlocking {
        val runtime = FakeLocalRuntime()
        val file = "MiniCPM5-1B-qualcomm-sm8750.litertlm"
        val model = "minicpm"
        val models = FakeLocalModelRepository(
            initialModels = listOf(installedModel(model, file)),
            downloadedPaths = mapOf(model to "/models/$file")
        )
        val catalog = FakeModelCatalogRepository(
            listOf(
                CatalogEntry(
                    id = model,
                    downloadUrl = "https://huggingface.co/Tdamre/MiniCPM5-1B-litert-lm/resolve/revision/$file",
                    supportedAccelerators = listOf("npu"),
                    socToModelFiles = mapOf("SM8750" to SocVariant(modelFile = file))
                )
            )
        )
        val events = adapter(runtime, models, catalog, deviceSocModel = "SM8750")
            .openSession(turns("hello"), localPlatform(model = model).copy(accelerator = "npu"))
            .streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("QNN memory allocation"))
        assertTrue(runtime.loadEngineCalls.isEmpty())
    }

    @Test
    fun `NPU package startup guidance is preserved without an incompatible CPU retry`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            failLoadEngineIf = { LocalNpuPackageException(IllegalStateException("native failure")) }
        }
        val events = adapter(runtime).openSession(turns("hello"), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, runtime.loadEngineCalls.size)
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("GPU / CPU edition"))
    }

    @Test
    fun `failed generation closes its native session before returning`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.Error("context exhausted")))
        }
        val events = adapter(runtime).openSession(turns("hello"), localPlatform())
            .streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertFalse(events.any { it is ProviderEvent.Completed })
        assertFalse(runtime.hasOpenConversation())
        assertEquals(1, runtime.closeConversationCalls)
    }

    @Test
    fun `three assist sessions close their own conversation and retain warm engine`() = runBlocking {
        val native = FakeLocalRuntime().apply { scriptedEvents = List(3) { listOf(LocalRuntimeEvent.TextDelta("[0]"), LocalRuntimeEvent.Done) } }
        val runtime = LocalEngineHolder(native) { 1000L }
        runtime.loadEngine(dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec("/models/gemma.litertlm", "gpu", 4096))
        val adapter = adapter(runtime)
        repeat(3) {
            val events = adapter.openSession(turns("select records"), localPlatform(), constraints = RequestConstraints(allowTools = false, requestRole = "assist")).streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.last() is ProviderEvent.Completed)
            assertFalse(native.hasOpenConversation())
        }
        assertEquals(3, native.closeConversationCalls)
        assertEquals(1, native.loadEngineCalls.size)
    }

    @Test
    fun `assist without an admitted warm engine returns failure without loading`() = runBlocking {
        val native = FakeLocalRuntime()
        val events = adapter(LocalEngineHolder(native) { 1000L }).openSession(turns("select"), localPlatform(), constraints = RequestConstraints(allowTools = false, requestRole = "assist")).streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertTrue(native.loadEngineCalls.isEmpty())
        assertTrue(native.sendMessageCalls.isEmpty())
    }

    @Test
    fun `assist shares the warm engine after interactive generation has stopped`() = runBlocking {
        val native = FakeLocalRuntime().apply { scriptedEvents = List(2) { listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done) } }
        val adapter = adapter(LocalEngineHolder(native) { 1000L })
        adapter.openSession(turns("chat"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        val events = adapter.openSession(turns("assist"), localPlatform(), constraints = RequestConstraints(allowTools = false, requestRole = "assist")).streamRound(emptyList(), emptyList()).toList()
        assertFalse(events.any { it is ProviderEvent.Failed })
        assertEquals(1, native.loadEngineCalls.size)
        assertEquals(listOf("chat", "assist"), native.sendMessageCalls)
    }

    @Test
    fun `native counts survive output limit and missing speed through provider boundary`() = runBlocking {
        val metrics = LocalInferenceMetrics(segmentId = "segment", native = dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics(240, 37, 0.0, 0.0), finishReason = "length")
        val native = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("partial"), LocalRuntimeEvent.Metrics(metrics), LocalRuntimeEvent.Metrics(metrics), LocalRuntimeEvent.Error("output limit")))
        }
        val events = adapter(native).openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        val usage = events.filterIsInstance<ProviderEvent.Usage>().last()
        assertEquals(240, usage.inputTokens)
        assertEquals(37, usage.outputTokens)
        assertEquals("runtime_reported", usage.outputSource)
        assertEquals("length", usage.finishReason)
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    private fun installedModel(id: String, file: String) = LocalModel(
        catalogEntryId = id,
        commitHash = "revision",
        fileName = file,
        relativeDirectory = "$id/revision",
        totalBytes = 1_000_000
    )

    @Test
    fun `holding the fake mutex emits waiting notice before the run`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("ok"), LocalRuntimeEvent.Done))
        }
        runtime.generationMutex.lock()
        val adapter = adapter(runtime)
        val events = mutableListOf<ProviderEvent>()

        val job = launch {
            adapter.openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).collect {
                events += it
            }
        }
        withTimeout(5_000) {
            while (events.none { it is ProviderEvent.Notice && it.message == LiteRtLmAdapter.DEFAULT_WAITING_FOR_ENGINE }) {
                yield()
            }
        }

        runtime.generationMutex.unlock()
        job.join()

        assertEquals(
            listOf(
                ProviderEvent.Notice(LiteRtLmAdapter.DEFAULT_WAITING_FOR_ENGINE),
                ProviderEvent.TextDelta("ok"),
                ProviderEvent.Completed
            ),
            events
        )
    }

    @Test
    fun `cancelling a queued run does not cancel the active local response`() = runBlocking {
        val pause = CompletableDeferred<Unit>()
        val native = FakeLocalRuntime().apply {
            pauseAfterFirst = pause
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("active"), LocalRuntimeEvent.Done))
        }
        val adapter = adapter(LocalEngineHolder(native) { 1000L })
        val firstEvents = mutableListOf<ProviderEvent>()
        val queuedEvents = mutableListOf<ProviderEvent>()
        val active = launch {
            adapter.openSession(turns("first"), localPlatform()).streamRound(emptyList(), emptyList()).collect { firstEvents += it }
        }
        withTimeout(5000) { while (firstEvents.none { it is ProviderEvent.TextDelta }) yield() }
        val queued = launch {
            adapter.openSession(turns("second"), localPlatform(uid = "queued")).streamRound(emptyList(), emptyList()).collect { queuedEvents += it }
        }
        withTimeout(5000) { while (queuedEvents.none { it is ProviderEvent.Notice && it.message == LiteRtLmAdapter.DEFAULT_WAITING_FOR_ENGINE }) yield() }
        queued.cancelAndJoin()
        assertEquals(0, native.cancelActiveCalls)
        pause.complete(Unit)
        active.join()
        assertTrue(firstEvents.last() is ProviderEvent.Completed)
        assertEquals(listOf("first"), native.sendMessageCalls)
    }

    @Test
    fun `disabled runtime fallback is not bypassed by adapter CPU retry`() = runBlocking {
        val native = FakeLocalRuntime().apply {
            failLoadEngineIf = { dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeFallbackDisabledException(IllegalStateException("QNN failed")) }
        }
        val events = adapter(native).openSession(turns("hello"), localPlatform()).streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, native.loadEngineCalls.size)
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("fallback is disabled"))
    }

    private fun adapter(
        runtime: LocalRuntime,
        models: FakeLocalModelRepository = FakeLocalModelRepository(
            downloadedPaths = mapOf(
                "gemma3-1b-it" to "/models/gemma.litertlm",
                "gemma-3n-e2b-it" to "/models/gemma3n.litertlm"
            )
        ),
        catalog: ModelCatalogRepository = FakeModelCatalogRepository(),
        loadingModelNotice: String = "",
        deviceSocModel: String = "",
        loadImageBytes: suspend (ChatAttachment) -> ByteArray? = { attachment ->
            attachment.preparedFilePath.ifBlank { attachment.localFilePath }.toByteArray()
        }
    ) = LiteRtLmAdapter(
        localRuntime = runtime,
        localModelRepository = models,
        ignoredAttachmentsNotice = LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS,
        modelNotDownloadedError = LiteRtLmAdapter.DEFAULT_MODEL_NOT_DOWNLOADED,
        tooManyImagesNotice = LiteRtLmAdapter.DEFAULT_TOO_MANY_IMAGES,
        loadingModelNotice = loadingModelNotice,
        modelCatalogRepository = catalog,
        deviceSocModel = deviceSocModel,
        loadImageBytes = loadImageBytes
    )

    private fun turns(text: String) = listOf(pendingTurn(text))

    private fun pendingTurn(text: String, attachments: List<ChatAttachment> = emptyList()) = ConversationTurn(
        userMessage = MessageV2(content = text, platformType = null, attachments = attachments),
        assistantMessage = null,
        isCurrentTurn = true
    )

    private fun completedTurn(
        user: String,
        assistant: String,
        attachments: List<ChatAttachment> = emptyList()
    ) = ConversationTurn(
        userMessage = MessageV2(content = user, platformType = null, attachments = attachments),
        assistantMessage = MessageV2(content = assistant, platformType = "local"),
        isCurrentTurn = false
    )

    private fun imageAttachment(
        path: String = "/tmp/photo.png",
        name: String = "photo.png"
    ) = ChatAttachment(
        localFilePath = path,
        preparedFilePath = path,
        displayName = name,
        mimeType = "image/png",
        sizeBytes = 12
    )

    private fun pdfAttachment() = ChatAttachment(
        localFilePath = "/tmp/doc.pdf",
        preparedFilePath = "/tmp/doc.pdf",
        displayName = "doc.pdf",
        mimeType = "application/pdf",
        sizeBytes = 100
    )

    private fun visionCatalog() = FakeModelCatalogRepository(
        listOf(CatalogEntry(id = "gemma-3n-e2b-it", capabilities = CatalogCapabilities(vision = true)))
    )

    private fun toolsCatalog() = FakeModelCatalogRepository(
        listOf(CatalogEntry(id = "gemma3-1b-it", capabilities = CatalogCapabilities(tools = true)))
    )

    private fun lookupTool(
        name: String = "lookup",
        execute: suspend (String, JsonObject) -> AgentToolResult = { callId, _ ->
            AgentToolResult(callId, ToolResultContent.Text("result-ok"), isError = false)
        }
    ): AgentTool = object : AgentTool {
        override val definition = AgentToolDefinition(
            name = name,
            description = "Look things up",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                    }
                )
            }
        )

        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = execute(callId, arguments)
    }

    private fun visionPlatform() = localPlatform(model = "gemma-3n-e2b-it")

    private fun assertImageBytes(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedBytes, actualBytes) ->
            assertTrue(expectedBytes.contentEquals(actualBytes))
        }
    }

    private fun localPlatform(
        uid: String = "local",
        model: String = "gemma3-1b-it"
    ) = PlatformV2(
        uid = uid,
        name = "Local",
        compatibleType = ClientType.LITERT_LM,
        apiUrl = "",
        model = model,
        temperature = 0.8f,
        topP = 0.9f,
        topK = 32,
        // Keep the ordinary fixture within the 4096-token model's reply reserve;
        // tests above exercise unset, excessive and request-constrained budgets.
        maxTokens = 1024,
        accelerator = "gpu",
        systemPrompt = "Be concise"
    )
}
