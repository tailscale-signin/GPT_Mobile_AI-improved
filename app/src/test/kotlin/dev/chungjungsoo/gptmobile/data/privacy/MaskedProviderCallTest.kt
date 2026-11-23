package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.toolRoundSystemPrompt
import dev.chungjungsoo.gptmobile.data.context.CapturedProviderAuthority
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskedProviderCallTest {
    @Test
    fun shortNameSurvivesActualMaskingFinalSystemTransformAndLocalRestoration() = runBlocking {
        val fixture = Fixture()
        fixture.response = { "Hello " + fixture.outgoingTurns.single().userMessage.content.removePrefix("My name is ") }
        val events = fixture.collect(fixture.prepare(listOf(ConversationTurn(MessageV2(content = "My name is An.", platformType = null), null, true))))
        assertEquals("Hello An.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, fixture.dispatches)
        assertEquals(ProviderEvent.Completed, events.last())
        assertFalse(fixture.outgoingTurns.single().userMessage.content.contains("An"))
        assertEquals(JsonPrimitive(1), fixture.finalWireReceipt?.get("validated_app_owned_public_suffixes"))
    }

    @Test
    fun allOutgoingHistoryAndSystemTextAreMaskedBeforeLaterIntroductionsAreSent() = runBlocking {
        val fixture = Fixture()
        val original = sampleTurns()
        val session = fixture.prepare(original)
        val outgoing = fixture.outgoingTurns.flatMap { listOfNotNull(it.userMessage.content, it.assistantMessage?.content) } + fixture.outgoingProfile.systemPrompt.orEmpty()
        outgoing.forEach { text ->
            assertFalse(text.contains("Alice", ignoreCase = true) || text.contains("Sam") || text.contains("private-code-4927"))
        }
        assertFalse(fixture.outgoingProfile.batchMode)
        assertNull(fixture.outgoingProfile.batchApiUrl)
        assertTrue(fixture.savedInputs.isEmpty())
        val events = fixture.collect(session)
        assertEquals(1, fixture.dispatches)
        assertEquals("Alice and Sam can visit a museum.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertSame(ProviderEvent.Completed, events.last())
        assertEquals(listOf(1, 2, 3), fixture.savedInputs.map { it.first.id })
        assertEquals(listOf("Alice"), fixture.savedInputs[0].second.protectedValues(original[0].userMessage.content))
        assertEquals(listOf("Sam"), fixture.savedInputs[1].second.protectedValues(original[0].assistantMessage!!.content))
        assertEquals(listOf("Alice", "Sam"), fixture.savedInputs[2].second.protectedValues(original[1].userMessage.content))
        fixture.savedInputs.forEach { (message, receipt) -> assertTrue(receipt.validFor(message.content)) }
        val answer = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        val receipt = fixture.protection.displayReceipt(answer)
        assertTrue(receipt.validFor(answer))
        assertEquals(listOf("Alice", "Sam"), receipt.protectedValues(answer))
    }

    @Test
    fun providerMessagesExcludeLocalThoughtsTimelinesRevisionsAndCombinedSources() = runBlocking {
        val fixture = Fixture()
        val original = sampleTurns()
        fixture.prepare(original)
        val outgoing = fixture.outgoingTurns.flatMap { listOfNotNull(it.userMessage, it.assistantMessage) }
        outgoing.forEach { message ->
            assertEquals("", message.thoughts)
            assertTrue(message.timeline.isEmpty())
            assertTrue(message.revisions.isEmpty())
            assertTrue(message.combinedSources.isEmpty())
            assertNull(message.currentRunId)
            assertFalse(message.toString().contains("LOCAL_ONLY"))
        }
        assertEquals(listOf(1, 2, 3), outgoing.map { it.id })
        assertEquals(listOf(27, 27, 27), outgoing.map { it.chatId })
        assertEquals(listOf(null, "private-profile", null), outgoing.map { it.platformType })
        // The local receipt callback receives the complete original record, not the wire copy.
        fixture.protection.flushInputs()
        assertSame(original[0].assistantMessage, fixture.savedInputs[1].first)
        assertTrue(fixture.savedInputs[1].first.thoughts.contains("LOCAL_ONLY"))
    }

    @Test
    fun changingEnabledStateCategoriesOrRevisionBeforeCollectionBlocksDispatch() = runBlocking {
        val changes: List<(PrivacyModeState) -> PrivacyModeState> = listOf(
            { it.copy(enabled = false) },
            { it.copy(categories = it.categories + PrivacyCategory.CONTACTS) },
            { it.copy(revision = it.revision + 1) }
        )
        changes.forEach { change ->
            val fixture = Fixture()
            val session = fixture.prepare(sampleTurns())
            fixture.state = change(fixture.state)
            val failure = runCatching { fixture.collect(session) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(0, fixture.dispatches)
            assertTrue(fixture.savedInputs.isEmpty())
        }
    }

    @Test
    fun settingsChangedWhileInputEvidenceIsBeingPersistedBlockDispatch() = runBlocking {
        val fixture = Fixture()
        val session = fixture.prepare(sampleTurns())
        fixture.afterInput = {
            yield()
            fixture.state = fixture.state.copy(revision = fixture.state.revision + 1)
        }
        val failure = runCatching { fixture.collect(session) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, fixture.dispatches)
    }

    @Test
    fun privacyChangeAtDelayedPhysicalBoundaryBlocksThePreparedRequest() = runBlocking {
        val fixture = Fixture()
        val session = fixture.prepare(sampleTurns())
        fixture.beforePhysicalBoundary = {
            yield()
            fixture.state = fixture.state.copy(categories = fixture.state.categories + PrivacyCategory.CONTACTS)
        }
        val failure = runCatching { fixture.collect(session) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, fixture.dispatches)
        assertTrue(fixture.savedInputs.isNotEmpty())
    }

    @Test
    fun composedPrivacyGuardPreservesCapturedAuthorityAndCallsOwnerValidation() = runBlocking {
        val captured = mockk<CapturedProviderAuthority>()
        var ownerChecks = 0
        val owner = RequestAuthorizationGuard("source-permissions-r7", captured) { ownerChecks++ }
        val fixture = Fixture(owner)
        val events = fixture.collect(fixture.prepare(sampleTurns()))
        assertEquals(events.size + 1, ownerChecks)
        assertEquals(1, fixture.dispatches)
        assertSame(captured, fixture.observedAuthority?.authority)
        assertEquals("source-permissions-r7:privacy-4", fixture.observedAuthority?.revision)
    }

    @Test
    fun ownerPermissionRevocationAtTheBoundaryRemainsEnforced() = runBlocking {
        var ownerRevoked = false
        var ownerChecks = 0
        val owner = RequestAuthorizationGuard("source-permissions-r7") {
            ownerChecks++
            check(!ownerRevoked) { "Retained source permission was revoked." }
        }
        val fixture = Fixture(owner)
        val session = fixture.prepare(sampleTurns())
        fixture.beforePhysicalBoundary = {
            yield()
            ownerRevoked = true
        }
        val failure = runCatching { fixture.collect(session) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, ownerChecks)
        assertEquals(0, fixture.dispatches)
    }

    @Test
    fun foreignRequestMarkersStayLiteralAndNeverGainProtectionEvidence() = runBlocking {
        val foreign = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Casey")).protect("Casey")
        val fixture = Fixture()
        fixture.response = { "Foreign marker $foreign stays literal." }
        val events = fixture.collect(fixture.prepare(sampleTurns()))
        val answer = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Foreign marker $foreign stays literal.", answer)
        assertTrue(fixture.protection.displayReceipt(answer).ranges.isEmpty())
    }

    @Test
    fun inputEvidenceFailureStopsBeforePhysicalDispatch() = runBlocking {
        val fixture = Fixture()
        val session = fixture.prepare(sampleTurns())
        fixture.afterInput = { throw IOException("Local receipt storage unavailable.") }
        assertTrue(runCatching { fixture.collect(session) }.exceptionOrNull() is IOException)
        assertEquals(0, fixture.dispatches)
    }

    @Test
    fun externalToolsAndAttachmentsAreRejectedBeforeProviderDispatch() = runBlocking {
        val fixture = Fixture()
        val session = fixture.prepare(sampleTurns())
        val tool = AgentToolDefinition("lookup", "Example external tool", buildJsonObject {})
        assertTrue(runCatching { fixture.collect(session, listOf(tool)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, fixture.dispatches)
        val withAttachment = sampleTurns().map { turn ->
            turn.copy(userMessage = turn.userMessage.copy(attachments = listOf(mockk<ChatAttachment>())))
        }
        val unopened = Fixture()
        assertTrue(runCatching { unopened.prepare(withAttachment) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, unopened.opens)
        assertEquals(0, unopened.dispatches)
    }

    @Test
    fun restoredProseArrivesBeforeTheProviderFinishesAndTerminalMetricsAreAlreadyAvailable() = runBlocking {
        val fixture = Fixture()
        val firstVisible = CompletableDeferred<String>()
        fixture.afterFirstDelta = { withTimeout(2_000) { firstVisible.await() } }
        val events = fixture.collect(fixture.prepare(sampleTurns()), onEvent = { event ->
            if (event is ProviderEvent.TextDelta) {
                assertFalse(event.text.contains("[PRIVATE_"))
                firstVisible.complete(event.text)
            }
            if (event is ProviderEvent.Completed) {
                val evidence = requireNotNull(fixture.dispatchProof?.streamSnapshot())
                assertEquals("true", evidence["completed"].toString())
                assertTrue(evidence.containsKey("first_upstream_answer_ms"))
                assertTrue(evidence.containsKey("first_restored_answer_ms"))
            }
        })
        assertTrue(firstVisible.isCompleted)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().size >= 2)
        assertEquals("Alice and Sam can visit a museum.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun changedOwnedMarkerFailsWithoutPublishingAnOriginalOrReplayingTheRequest() = runBlocking {
        val fixture = Fixture()
        fixture.response = { fixture.outgoingTurns.first().userMessage.content.substringBefore(" likes").replace("_PERSON_", "_DATE_") }
        val events = fixture.collect(fixture.prepare(sampleTurns()))
        assertTrue(events.none { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("PRIVACY_OUTPUT_PROTOCOL_INVALID", failure.errorCode)
        assertEquals(1, fixture.dispatches)
        assertFalse(failure.toString().contains("Alice") || failure.toString().contains("[PRIVATE_"))
    }

    @Test
    fun draftPlaceholderForASuppliedPersonIsReportedAsUnverifiedWithoutInventingTheValue() = runBlocking {
        val fixture = Fixture()
        fixture.response = { "Regards, [Your full name]." }
        val turns = sampleTurns().toMutableList()
        val last = turns.last()
        turns[turns.lastIndex] = last.copy(userMessage = last.userMessage.copy(content = last.userMessage.content + " Write a complete letter."))
        val events = fixture.collect(fixture.prepare(turns))
        assertTrue(events.filterIsInstance<ProviderEvent.Notice>().any { it.message.contains("not been verified") })
        assertEquals("Regards, [Your full name].", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, fixture.dispatches)
    }

    private fun sampleTurns(): List<ConversationTurn> {
        val earlier = MessageV2(content = "Alice likes museums.", platformType = null, id = 1, chatId = 27)
        val answer = MessageV2(
            content = "Sam likes music.",
            platformType = "private-profile",
            id = 2,
            chatId = 27,
            thoughts = "LOCAL_ONLY Alice thoughts",
            currentRunId = "local-run-id",
            timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.THINKING, "LOCAL_ONLY Sam timeline")),
            revisions = listOf(AssistantRevision(content = "LOCAL_ONLY Alice revision", createdAt = 1L)),
            combinedSources = listOf(CombinedModelResponse(platformUid = "source", platformName = "source", content = "LOCAL_ONLY Sam source"))
        )
        val introduction = MessageV2(
            content = "My girlfriend Alice and I, (Sam) are dating.",
            platformType = null,
            id = 3,
            chatId = 27
        )
        return listOf(ConversationTurn(earlier, answer, false), ConversationTurn(introduction, null, true))
    }

    private class Fixture(owner: RequestAuthorizationGuard? = null) {
        var state = PrivacyModeState(enabled = true, categories = setOf(PrivacyCategory.IDENTITIES, PrivacyCategory.CUSTOM), revision = 4)
        private val store = mockk<PrivacyModeStore>()
        val savedInputs = mutableListOf<Pair<MessageV2, PrivacyProtectionReceipt>>()
        var afterInput: suspend () -> Unit = {}
        var beforePhysicalBoundary: suspend () -> Unit = {}
        var afterFirstDelta: suspend () -> Unit = {}
        val protection = PrivateProtectionContext { message, receipt ->
            savedInputs += message to receipt
            afterInput()
        }
        private val executionContext: CoroutineContext = ConversationPrivacyContext(27, true) + if (owner == null) protection else protection + owner
        lateinit var outgoingTurns: List<ConversationTurn>
        lateinit var outgoingProfile: PlatformV2
        var opens = 0
        var dispatches = 0
        var observedAuthority: RequestAuthorizationGuard? = null
        var dispatchProof: PrivateDispatchContext? = null
        var finalWireReceipt: JsonObject? = null
        var response: () -> String = {
            val alice = outgoingTurns[0].userMessage.content.removeSuffix(" likes museums.")
            val sam = outgoingTurns[0].assistantMessage!!.content.removeSuffix(" likes music.")
            "$alice and $sam can visit a museum."
        }

        init {
            every { store.effectiveSnapshot(true) } answers { state }
            coEvery { store.privateTerms() } returns listOf("private-code-4927")
        }

        suspend fun prepare(turns: List<ConversationTurn>): AgentProviderSession = withContext(executionContext) {
            val profile = PlatformV2(
                uid = "private-profile",
                name = "Test private profile",
                model = "test-model",
                systemPrompt = "Alice and Sam prefer privacy. Use private-code-4927.",
                batchMode = true,
                batchApiUrl = "https://invalid.example/batches"
            )
            maskedProviderSession(store, profile, turns) { maskedTurns, maskedProfile ->
                opens++
                outgoingTurns = maskedTurns
                outgoingProfile = maskedProfile
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                        assertTrue(tools.isEmpty() && exchanges.isEmpty())
                        assertTrue(currentCoroutineContext()[MaskedProviderCall.Key] != null)
                        beforePhysicalBoundary()
                        val guard = requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard])
                        dispatchProof = currentCoroutineContext()[PrivateDispatchContext]
                        observedAuthority = guard
                        guard.validate()
                        // The fixture models the provider's actual no-tools system transform,
                        // then invokes the real final-wire validator before its dispatch port.
                        val messages = buildList {
                            fun message(role: String, text: String) = add(JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive(text))))
                            message("system", requireNotNull(toolRoundSystemPrompt(outgoingProfile.systemPrompt, false)))
                            outgoingTurns.forEach { turn ->
                                message("user", turn.userMessage.content)
                                turn.assistantMessage?.let { message("assistant", it.content) }
                            }
                        }
                        finalWireReceipt = requireNotNull(dispatchProof).validate(JsonObject(mapOf("messages" to JsonArray(messages))).toString(), guard.revision)
                        dispatches++
                        val answer = response()
                        val split = answer.length / 2
                        emit(ProviderEvent.TextDelta(answer.substring(0, split)))
                        afterFirstDelta()
                        emit(ProviderEvent.TextDelta(answer.substring(split)))
                        emit(ProviderEvent.Completed)
                    }
                }
            }
        }

        suspend fun collect(
            session: AgentProviderSession,
            tools: List<AgentToolDefinition> = emptyList(),
            onEvent: (ProviderEvent) -> Unit = {}
        ): List<ProviderEvent> = withContext(executionContext) { session.streamRound(tools, emptyList()).onEach(onEvent).toList() }
    }
}
