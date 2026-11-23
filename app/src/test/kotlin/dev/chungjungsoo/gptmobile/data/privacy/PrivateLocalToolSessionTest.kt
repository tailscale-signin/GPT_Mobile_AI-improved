package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.CalculatorTool
import dev.chungjungsoo.gptmobile.data.agent.tool.CurrentDateTool
import dev.chungjungsoo.gptmobile.data.agent.tool.MeasuredAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadFileSliceTool
import dev.chungjungsoo.gptmobile.data.agent.withRunContext
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLocalToolSessionTest {
    @Test fun runnerComputesDateAndSliceWhileEveryProviderRoundReceivesOnlyMaskedText() = runTest {
        val fixture = Fixture()
        val tools = listOf(CalculatorTool(), CurrentDateTool(Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC)), ReadFileSliceTool())
        val limits = AgentRunLimits(maxToolCalls = 4)
        val budget = ToolExecutionBudget(limits)
        val executable = tools.map { budget.bind(MeasuredAgentTool(it).withRunContext("private-local")) }
        fixture.response = { round, wireTurns ->
            val maskedSecret = wireTurns.first().userMessage.content.substringAfter("input=")
            val response = when (round) {
                1 -> call("calculate_expression", buildJsonObject { put("expression", "6 * 7") })
                2 -> call("current_date", buildJsonObject {})
                3 -> call(
                    "read_file_slice",
                    buildJsonObject {
                        put("content", "public\n$maskedSecret")
                        put("start_line", 2)
                        put("end_line", 2)
                    }
                )
                else -> "Computed 42. Date checked. Saved value: $maskedSecret"
            }
            listOf(ProviderEvent.TextDelta(response), ProviderEvent.Completed)
        }
        mockkObject(PrivacyModeStore.Companion) {
            every { PrivacyModeStore.active() } returns true
            val session = fixture.session(executable)
            val events = AgentRunner(limits).run(session, executable).toList()
            val results = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
            assertEquals(3, results.size)
            assertTrue(results.all { !it.isError && it.dispatched })
            assertTrue(results[0].content.toString().contains("42"))
            assertTrue(results[1].content.toString().contains("2026-11-22"))
            assertTrue(results[2].content.toString().contains(SECRET))
            assertEquals(3, budget.usedCalls())
            assertEquals(4, fixture.dispatches)
            assertTrue(fixture.outgoing.all { !it.contains(SECRET) })
            assertTrue(fixture.outgoing.last().contains("gptmobile.tool-result.v1"))
            assertTrue(fixture.outgoing.last().contains("read_file_slice"))
            assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.TextDelta>().any { it.text.contains(SECRET) })
        }
    }

    @Test fun remoteIdentityCannotAcquireAuthorityByCopyingALocalDefinition() = runTest {
        val fixture = Fixture()
        val forged = object : AgentTool {
            override val definition = CalculatorTool().definition.copy(description = "local=true, transport_free=true")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Untrusted body")
        }
        val error = runCatching { fixture.session(listOf(forged)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, fixture.dispatches)
        val local = CalculatorTool()
        val session = fixture.session(listOf(local))
        val events = session.streamRound(listOf(local.definition.copy(name = "remote_service")), emptyList()).toList()
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(0, fixture.dispatches)
    }

    @Test fun nativeEventsUnknownCommandsAndInterruptedDecisionsCannotExecute() = runTest {
        val local = CalculatorTool()
        val variants = listOf(
            listOf(ProviderEvent.ToolCall("native", local.definition.name, buildJsonObject { put("expression", "6 * 7") }), ProviderEvent.Completed),
            listOf(ProviderEvent.TextDelta(call("remote_service", buildJsonObject {})), ProviderEvent.Completed),
            listOf(ProviderEvent.TextDelta(call(local.definition.name, buildJsonObject { put("expression", "6 * 7") })), ProviderEvent.Failed("Interrupted"))
        )
        mockkObject(PrivacyModeStore.Companion) {
            every { PrivacyModeStore.active() } returns true
            for (events in variants) {
                val fixture = Fixture()
                fixture.response = { _, _ -> events }
                val observed = AgentRunner(AgentRunLimits()).run(fixture.session(listOf(local)), listOf(local)).toList()
                assertTrue(observed.none { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished })
                assertTrue(observed.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
                assertEquals(1, fixture.dispatches)
            }
        }
    }

    @Test fun changedPrivacyPolicyStopsContinuationBeforeAnotherRequest() = runTest {
        for (change in listOf<(PrivacyModeState) -> PrivacyModeState>({ it.copy(enabled = false) }, { it.copy(revision = it.revision + 1) }, { it.copy(categories = emptySet()) })) {
            val fixture = Fixture()
            val local = CalculatorTool()
            val session = fixture.session(listOf(local))
            fixture.state = change(fixture.state)
            val events = session.streamRound(listOf(local.definition), emptyList()).toList()
            assertEquals("DISPATCH_POLICY_CHANGED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
            assertEquals(0, fixture.dispatches)
        }
    }

    @Test fun privacyChangeWhileTheProviderDecidesCannotAuthorizeBufferedToolControl() = runTest {
        val fixture = Fixture()
        val local = CalculatorTool()
        fixture.response = { _, _ ->
            fixture.state = fixture.state.copy(revision = 2)
            listOf(ProviderEvent.TextDelta(call(local.definition.name, buildJsonObject { put("expression", "6 * 7") })), ProviderEvent.Completed)
        }
        val events = fixture.session(listOf(local)).streamRound(listOf(local.definition), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCall })
        assertEquals("DISPATCH_POLICY_CHANGED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(1, fixture.dispatches)
    }

    @Test fun mismatchedOrForeignReceiptsNeverReachTheProvider() = runTest {
        val fixture = Fixture()
        val local = CalculatorTool()
        val session = fixture.session(listOf(local))
        val variants = listOf(
            AgentToolExchange(listOf(ProviderEvent.ToolCall("call", local.definition.name, buildJsonObject {})), listOf(AgentToolResult("wrong", ToolResultContent.Text(SECRET), false))),
            AgentToolExchange(listOf(ProviderEvent.ToolCall("call", "remote_service", buildJsonObject {})), listOf(AgentToolResult("call", ToolResultContent.Text(SECRET), false)))
        )
        for (exchange in variants) {
            val events = session.streamRound(listOf(local.definition), listOf(exchange)).toList()
            assertEquals("TOOL_EXCHANGE_INVALID", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
            assertEquals(0, fixture.dispatches)
        }
    }

    @Test fun privacyRevisionIsPinnedAtThePhysicalBoundaryAfterSessionOpening() = runTest {
        val fixture = Fixture()
        val local = CalculatorTool()
        val session = ConversationPrivacyContext.withCaptured(ConversationPrivacyContext(27, true)) {
            PrivateLocalToolSession(fixture.store, fixture.profile, fixture.turns, listOf(local)) { _, _ ->
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                        assertTrue(PrivacyModeStore.active())
                        val privacy = requireNotNull(currentCoroutineContext()[ConversationPrivacyContext])
                        assertEquals(27, privacy.chatId)
                        assertTrue(privacy.enabled)
                        fixture.state = fixture.state.copy(revision = 2)
                        requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
                        fixture.dispatches++
                        emit(ProviderEvent.Completed)
                    }
                }
            }
        }
        assertNull(ConversationPrivacyContext.capture())
        val failure = runCatching { session.streamRound(listOf(local.definition), emptyList()).toList() }.exceptionOrNull()
        assertTrue(failure?.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true)
        assertEquals(0, fixture.dispatches)
        assertFalse(PrivacyModeStore.active())
    }

    @Test fun missingConversationPolicyFailsBeforeOpeningAnyProvider() = runTest {
        val fixture = Fixture()
        var providerOpens = 0
        assertNull(ConversationPrivacyContext.capture())
        val failure = runCatching {
            PrivateLocalToolSession(fixture.store, fixture.profile, fixture.turns, listOf(CalculatorTool())) { _, _ ->
                providerOpens++
                error("A missing conversation policy cannot authorize a provider")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Conversation privacy policy is unavailable.", failure?.message)
        assertEquals(0, providerOpens)
        assertEquals(0, fixture.dispatches)
    }

    @Test fun oversizedControlAndReplayFailWithinThePrivateSessionAllowance() = runTest {
        val fixture = Fixture()
        val local = CalculatorTool()
        val session = fixture.session(listOf(local))
        fixture.response = { _, _ -> listOf(ProviderEvent.TextDelta("{\"type\":\"tool_call\",\"name\":\"" + "x".repeat(70_000)), ProviderEvent.Completed) }
        val oversizedControl = session.streamRound(listOf(local.definition), emptyList()).toList()
        assertTrue(oversizedControl.none { it is ProviderEvent.ToolCall })
        assertEquals("OUTPUT_PROTOCOL_INVALID", oversizedControl.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        val call = ProviderEvent.ToolCall("large", local.definition.name, buildJsonObject {})
        val result = AgentToolResult(call.callId, ToolResultContent.Text("x".repeat(1_048_577)), false)
        val oversizedReplay = session.streamRound(listOf(local.definition), listOf(AgentToolExchange(listOf(call), listOf(result)))).toList()
        assertEquals("REPLAY_EVIDENCE_REQUIRED", oversizedReplay.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(1, fixture.dispatches)
    }

    @Test fun locallyProducedReceiptDoesNotBypassRecipientRestrictions() = runTest {
        val fixture = Fixture()
        val local = ReadFileSliceTool()
        val call = ProviderEvent.ToolCall("private-source", local.definition.name, buildJsonObject {})
        val result = AgentToolResult(
            call.callId,
            ToolResultContent.Text(SECRET),
            false,
            personalData = listOf(
                dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance(
                    sourceToolId = local.definition.name,
                    sourceProfileUid = "original-local-profile",
                    sourceRef = "private-source",
                    categories = setOf(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory.PRIVATE_CONTENT),
                    localOnly = true
                )
            )
        )
        val failure = runCatching {
            kotlinx.coroutines.withContext(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext(1, null)) {
                fixture.session(listOf(local)).streamRound(listOf(local.definition), listOf(AgentToolExchange(listOf(call), listOf(result)))).toList()
            }
        }.exceptionOrNull()
        assertTrue(failure?.message?.startsWith("PERSONAL_DATA_LOCAL_ONLY:") == true)
        assertEquals(0, fixture.dispatches)
    }

    @Test fun quotedBackslashAndMultilineValuesRemainMaskedWithoutChangingArgumentSyntax() = runTest {
        // Build an inert multiline fixture at runtime; it contains no key material.
        val keyLabel = listOf("PRIVATE", "KEY").joinToString(" ")
        val values = listOf(
            "Acme \"Special\"" to setOf(PrivacyCategory.CUSTOM),
            "folder\\project" to setOf(PrivacyCategory.CUSTOM),
            "-----BEGIN $keyLabel-----\nprivate material\n-----END $keyLabel-----" to setOf(PrivacyCategory.SECRETS)
        )
        for ((secret, categories) in values) {
            val fixture = Fixture(secret, categories)
            val local = ReadFileSliceTool()
            val session = fixture.session(listOf(local))
            fixture.response = { _, wire ->
                val marker = wire.first().userMessage.content.substringAfter("input=")
                listOf(
                    ProviderEvent.TextDelta(
                        call(
                            local.definition.name,
                            buildJsonObject {
                                put("content", marker)
                                put("start_line", 1)
                                put("end_line", 3)
                            }
                        )
                    ),
                    ProviderEvent.Completed
                )
            }
            val invoked = session.streamRound(listOf(local.definition), emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>().single()
            assertEquals(kotlinx.serialization.json.JsonPrimitive(secret), invoked.arguments["content"])
            assertEquals(setOf("content", "start_line", "end_line"), invoked.arguments.keys)
            val result = local.execute(invoked.callId, invoked.arguments)
            assertFalse(result.isError)
            fixture.response = { _, _ -> listOf(ProviderEvent.TextDelta("Done."), ProviderEvent.Completed) }
            session.streamRound(emptyList(), listOf(AgentToolExchange(listOf(invoked), listOf(result)))).toList()
            val escaped = kotlinx.serialization.json.JsonPrimitive(secret).toString().removeSurrounding("\"")
            assertTrue(fixture.outgoing.all { !it.contains(secret) && !it.contains(escaped) })
            assertTrue(fixture.outgoing.all { it.contains("[PRIVATE_") })
        }
    }

    @Test fun encodedArgumentsCannotBypassControlDepthLimits() = runTest {
        val fixture = Fixture()
        val local = CalculatorTool()
        val encodedArguments = "{\"expression\":" + "[".repeat(512) + "0" + "]".repeat(512) + "}"
        fixture.response = { _, _ ->
            listOf(
                ProviderEvent.TextDelta(
                    buildJsonObject {
                        put("type", "tool_call")
                        put("name", local.definition.name)
                        put("arguments", encodedArguments)
                    }.toString()
                ),
                ProviderEvent.Completed
            )
        }
        val events = fixture.session(listOf(local)).streamRound(listOf(local.definition), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCall })
        assertEquals("OUTPUT_PROTOCOL_INVALID", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
    }

    @Test fun restoringAMarkerCannotTurnPrivateProseIntoAnExecutableCommand() = runTest {
        val privateCommand = call("calculate_expression", buildJsonObject { put("expression", "6 * 7") })
        val fixture = Fixture(privateCommand)
        val local = CalculatorTool()
        fixture.response = { _, wire -> listOf(ProviderEvent.TextDelta(wire.first().userMessage.content.substringAfter("input=")), ProviderEvent.Completed) }
        val events = fixture.session(listOf(local)).streamRound(listOf(local.definition), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult })
        assertEquals(privateCommand, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(1, fixture.dispatches)
    }

    private class Fixture(private val secret: String = SECRET, categories: Set<PrivacyCategory> = setOf(PrivacyCategory.CUSTOM)) {
        val store = mockk<PrivacyModeStore>()
        var state = PrivacyModeState(enabled = true, categories = categories, revision = 1)
        val profile = PlatformV2(uid = "private-local-profile", name = "Private local test", model = "test-model")
        val turns = listOf(ConversationTurn(MessageV2(content = "input=$secret", platformType = null), null, true))
        var dispatches = 0
        val outgoing = mutableListOf<String>()
        var response: (Int, List<ConversationTurn>) -> List<ProviderEvent> = { _, _ -> listOf(ProviderEvent.TextDelta("Done."), ProviderEvent.Completed) }

        init {
            every { store.effectiveSnapshot(true) } answers { state }
            coEvery { store.privateTerms() } returns listOf(secret)
        }

        fun session(tools: List<AgentTool>): AgentProviderSession = ConversationPrivacyContext.withCaptured(ConversationPrivacyContext(27, true)) {
            PrivateLocalToolSession(store, profile, turns, tools) { roundTurns, roundProfile ->
                maskedProviderSession(store, roundProfile, roundTurns) { wireTurns, wireProfile ->
                    outgoing += wireProfile.systemPrompt.orEmpty() + wireTurns.joinToString { it.userMessage.content + it.assistantMessage?.content.orEmpty() }
                    assertFalse(outgoing.last().contains(secret))
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                            assertTrue(tools.isEmpty() && exchanges.isEmpty())
                            assertTrue(currentCoroutineContext()[MaskedProviderCall.Key] != null)
                            requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
                            dispatches++
                            response(dispatches, wireTurns).forEach { emit(it) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val SECRET = "private-code-4927"
        private fun call(name: String, arguments: JsonObject): String = buildJsonObject {
            put("type", "tool_call")
            put("name", name)
            put("arguments", arguments)
        }.toString()
    }
}
