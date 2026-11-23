package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.accounting.InvocationDao
import dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger
import dev.chungjungsoo.gptmobile.data.accounting.InvocationRoundAccounting
import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.context.CapturedProviderAuthority
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.ModelRequestPreparation
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.WorkerRequestBudget
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyComparisonProviderBindingTest {
    @Test
    fun ordinaryLegIsLazyAndUsesOnlyTheClosedFixtureWithBoundedSettings() = runBlocking {
        val fixture = Fixture()
        val prepared = fixture.open(false)
        assertEquals(0, fixture.opened)
        assertNull(prepared.policy)
        val caller = ConversationPrivacyContext.currentOrNull()
        val events = prepared.session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, fixture.opened)
        assertEquals(listOf(ProviderEvent.TextDelta(PrivacyComparisonCase.TRAVEL.prompt), ProviderEvent.Completed), events)
        val sent = requireNotNull(fixture.sentProfile)
        assertEquals(fixture.profile.uid, sent.uid)
        assertEquals(fixture.profile.model, sent.model)
        assertEquals(fixture.profile.apiUrl, sent.apiUrl)
        assertEquals(512, sent.maxTokens)
        assertEquals(0f, sent.temperature)
        assertTrue(sent.disableAllTools && sent.disableLocalTools && sent.disableRemoteTools)
        assertFalse(sent.batchMode)
        assertFalse(sent.systemPrompt.orEmpty().contains(SAVED_INSTRUCTION))
        assertFalse(sent.systemPrompt.orEmpty().contains(SAVED_TERM))
        assertEquals(PrivacyComparisonCase.TRAVEL.prompt, fixture.sentTurns.single().userMessage.content)
        assertNull(fixture.sentTurns.single().assistantMessage)
        assertTrue(fixture.sentTurns.single().userMessage.attachments.isEmpty())
        assertEquals(512, fixture.constraints?.maxOutputTokens)
        assertFalse(requireNotNull(fixture.constraints).allowTools)
        assertFalse(requireNotNull(fixture.constraints).allowGatewayLocalTools)
        assertEquals(1, fixture.requestLimit)
        assertEquals("ORDINARY_TEXT", prepared.measurements().routeKind)
        assertNull(prepared.measurements().inputReceipt)
        assertNull(prepared.measurements().maskingNanos)
        assertNull(prepared.measurements().restorationNanos)
        assertNotNull(prepared.measurements().firstUpstreamAnswerMillis)
        coVerify(exactly = 0) { fixture.store.privateTerms() }
        assertSame(caller, ConversationPrivacyContext.currentOrNull())
    }

    @Test
    fun privateLegUsesActualMaskProofAndRestorationWithoutLoadingSavedTerms() = runBlocking {
        val fixture = Fixture()
        val dispatch = RequestDispatchContext(emptySet())
        val prepared = fixture.open(true)
        val events = withContext(dispatch) { prepared.session.streamRound(emptyList(), emptyList()).toList() }
        val sent = fixture.sentTurns.single().userMessage.content
        assertFalse(sent.contains("Mira Voss"))
        assertFalse(sent.contains("Elian Reed"))
        assertTrue(sent.contains("[PRIVATE_"))
        assertEquals(PrivacyComparisonCase.TRAVEL.prompt, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
        val metrics = prepared.measurements()
        val receipt = requireNotNull(metrics.inputReceipt)
        assertTrue(receipt.confirmed && receipt.validFor(PrivacyComparisonCase.TRAVEL.prompt))
        assertTrue(receipt.ranges.isNotEmpty())
        assertTrue(requireNotNull(metrics.maskingNanos) >= 0)
        assertTrue(requireNotNull(metrics.restorationNanos) >= 0)
        assertNotNull(metrics.firstUpstreamAnswerMillis)
        assertNotNull(metrics.firstRestoredAnswerMillis)
        assertEquals("KNOWN_VALUES_ABSENT", metrics.wireOutcome)
        assertEquals("MASKED_TEXT", metrics.routeKind)
        assertNull(metrics.comparisonLimitation)
        assertEquals(fixture.state, prepared.policy)
        coVerify(exactly = 0) { fixture.store.privateTerms() }
    }

    @Test
    fun theOrdinaryMaskEntryStillLoadsAndProtectsSavedCustomTerms() = runBlocking {
        val fixture = Fixture()
        val original = MessageV2(content = "Repeat " + SAVED_TERM, platformType = null)
        withContext(ConversationPrivacyContext(8, true)) {
            val session = maskedProviderSession(fixture.store, fixture.profile, listOf(ConversationTurn(original, null, true))) { turns, target ->
                assertFalse(turns.single().userMessage.content.contains(SAVED_TERM))
                fixture.provider(turns, target, RequestConstraints())
            }
            val events = session.streamRound(emptyList(), emptyList()).toList()
            assertEquals(original.content, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        }
        coVerify(exactly = 1) { fixture.store.privateTerms() }
    }

    @Test
    fun closedTermsWitnessRejectsSubstitutedPromptHistoryAndSystemInstruction() = runBlocking {
        val fixture = Fixture()
        expectBlocked(PrivacyComparisonFailure.UNSUPPORTED_PROFILE) { fixture.binding.open(fixture.profile, "not-a-fixture", true, "case", 60_000) }
        val input = PrivateSyntheticInput.create("travel", fixture.profile)
        val replaced = input.turns.single().copy(userMessage = input.turns.single().userMessage.copy(content = SAVED_TERM))
        expectBlocked(PrivacyComparisonFailure.UNSUPPORTED_PROFILE) { input.privateTerms(input.providerProfile, listOf(replaced)) }
        val history = input.turns.single().copy(assistantMessage = MessageV2(content = SAVED_TERM, platformType = null))
        expectBlocked(PrivacyComparisonFailure.UNSUPPORTED_PROFILE) { input.privateTerms(input.providerProfile, listOf(history)) }
        expectBlocked(PrivacyComparisonFailure.UNSUPPORTED_PROFILE) { input.privateTerms(input.providerProfile.copy(systemPrompt = SAVED_INSTRUCTION), input.turns) }
        assertEquals(emptyList<String>(), input.privateTerms(input.providerProfile, input.turns))
        assertEquals(0, fixture.opened)
        coVerify(exactly = 0) { fixture.store.privateTerms() }
    }

    @Test
    fun frozenProfileOrPolicyChangesBeforeCollectionPreventBothProviderArms() = runBlocking {
        for (protection in listOf(false, true)) {
            val changedProfile = Fixture()
            val profileSession = changedProfile.open(protection)
            changedProfile.currentProfile = false
            expectBlocked(PrivacyComparisonFailure.PROFILE_CHANGED) { profileSession.session.streamRound(emptyList(), emptyList()).toList() }
            assertEquals(0, changedProfile.opened)
            val changedPolicy = Fixture()
            val policySession = changedPolicy.open(protection)
            changedPolicy.state = changedPolicy.state.copy(revision = 12, protectionRevision = 12)
            expectBlocked(PrivacyComparisonFailure.POLICY_CHANGED) { policySession.session.streamRound(emptyList(), emptyList()).toList() }
            assertEquals(0, changedPolicy.opened)
        }
    }

    @Test
    fun configuredActivationAndPermissionRevocationPreventProviderDispatch() = runBlocking {
        for (protection in listOf(false, true)) {
            var activationRequired = true
            var permissionRevision = 1
            val guard = RequestAuthorizationGuard("configured-fixture") {
                check(!activationRequired && permissionRevision == 1)
            }
            val fixture = Fixture(providerAuthority = guard)
            expectBlocked(PrivacyComparisonFailure.POLICY_CHANGED) { fixture.open(protection) }
            assertEquals(0, fixture.opened)
            activationRequired = false
            val prepared = fixture.open(protection)
            permissionRevision = 2
            expectBlocked(PrivacyComparisonFailure.POLICY_CHANGED) { prepared.session.streamRound(emptyList(), emptyList()).toList() }
            assertEquals(0, fixture.opened)

            // Revocation during a suspending fresh-profile read must be checked
            // again, even though the profile bytes themselves still match.
            permissionRevision = 1
            val suspendedRead = fixture.open(protection)
            fixture.profileRead = {
                permissionRevision = 2
                yield()
            }
            expectBlocked(PrivacyComparisonFailure.POLICY_CHANGED) { suspendedRead.session.streamRound(emptyList(), emptyList()).toList() }
            assertEquals(0, fixture.opened)
        }
    }

    @Test
    fun theFreshSavedRowRejectsAProfileStillPresentInTheLegacyCache() = runBlocking {
        val fixture = Fixture()
        val settings = mockk<SettingRepository>()
        val current = MutableStateFlow<PlatformV2?>(fixture.profile)
        coEvery { settings.fetchPlatformV2s() } returns listOf(fixture.profile)
        every { settings.observePlatformV2ByUid(fixture.profile.uid) } returns current
        val binding = PrivacyComparisonProviderBinding(
            fixture.store,
            { frozen -> privacyComparisonProfileIsCurrent(settings, frozen) },
            fixture::provider
        )
        val prepared = binding.open(fixture.profile, "travel", true, "fresh-row-fixture", 60_000)
        current.value = fixture.profile.copy(model = "edited-fictional-model")
        assertEquals(listOf(fixture.profile), settings.fetchPlatformV2s())
        expectBlocked(PrivacyComparisonFailure.PROFILE_CHANGED) { prepared.session.streamRound(emptyList(), emptyList()).toList() }
        assertEquals(0, fixture.opened)
        current.value = null
        expectBlocked(PrivacyComparisonFailure.PROFILE_CHANGED) { binding.open(fixture.profile, "travel", false, "removed-row-fixture", 60_000) }
        assertEquals(0, fixture.opened)
    }

    @Test
    fun configuredAuthorityIdentityWinsWhileInheritedValidationStillRuns() = runBlocking {
        val configured = CapturedProviderAuthority("synthetic-profile", "fictional-model", "registered", "policy", "permission-a", "route")
        val inherited = configured.copy(permissionRevision = "permission-b")
        var configuredChecks = 0
        var inheritedChecks = 0
        val configuredGuard = RequestAuthorizationGuard("configured-fixture", configured) { configuredChecks++ }
        val fixture = Fixture(providerAuthority = configuredGuard)
        val prepared = withContext(RequestAuthorizationGuard("inherited-fixture", inherited) { inheritedChecks++ }) {
            fixture.open(false).also { it.session.streamRound(emptyList(), emptyList()).toList() }
        }
        assertTrue(configuredChecks > 0 && inheritedChecks > 0)
        assertSame(configured, fixture.outboundAuthority)
        assertTrue(requireNotNull(fixture.outboundRevision).contains(configuredGuard.revision))
        assertEquals(researchHash(configuredGuard.revision + "|permission-a"), prepared.authorityRevision)
        assertFalse(requireNotNull(prepared.authorityRevision).contains("permission-a"))
        val changed = Fixture(providerAuthority = RequestAuthorizationGuard(configuredGuard.revision, inherited) {}).open(false)
        assertFalse(prepared.authorityRevision == changed.authorityRevision)
    }

    @Test
    fun fixedProviderCeilingsCannotExceedTheFictionalComparisonAllowance() = runBlocking {
        val fixed = FreeAiProvider.POLLINATIONS
        for (protection in listOf(false, true)) {
            val fixture = Fixture(ClientType.FREE, fixed.apiUrl, fixed.model)
            expectBlocked(PrivacyComparisonFailure.UNSUPPORTED_PROFILE) { fixture.open(protection) }
            assertEquals(0, fixture.opened)
            coVerify(exactly = 0) { fixture.store.privateTerms() }
            coVerify(exactly = 0) { fixture.verifier.verify(any()) }
        }
    }

    @Test
    fun ordinaryTerminalCollectionFinishesTheRealLedgerWithoutCancellingSuccessfulLegs() = runBlocking {
        val saved = mutableListOf<ModelInvocation>()
        val dao = mockk<InvocationDao>(relaxed = true)
        every { dao.recent() } returns flowOf(emptyList())
        coEvery { dao.save(any()) } answers { saved += firstArg<ModelInvocation>() }
        val database = mockk<ChatDatabaseV2>()
        every { database.invocationDao() } returns dao
        val ledger = InvocationLedger(database)
        val fixture = Fixture(ledger = ledger)
        fixture.response = { _, _ ->
            emit(ProviderEvent.TextDelta("READY"))
            emit(ProviderEvent.Completed)
        }
        mockkObject(AppLogRecorder)
        try {
            every { AppLogRecorder.record(any(), any(), any()) } returns Unit
            val result = PrivacyComparisonRunner({ caseId, protection, requestId, deadlineMs ->
                fixture.binding.open(fixture.profile, caseId, protection, requestId, deadlineMs)
            }).run("terminal-accounting")
            assertEquals(PrivacyComparisonStatus.COMPLETE, result.status)
            assertEquals(8, result.legs.size)
            assertEquals(10, fixture.opened)
            assertEquals(10, saved.size)
            assertTrue(saved.all { it.status == "COMPLETED" })
            assertTrue(saved.none { it.status == "CANCELED" })
            assertTrue(ledger.active.value.isEmpty())
            // This fixture verifies terminal accounting, not the answer rubric.
            assertTrue(result.legs.any { !it.taskCriteriaPassed })
        } finally {
            unmockkObject(AppLogRecorder)
        }
    }

    @Test
    fun ordinaryArmCannotDowngradeAnInheritedPrivateConversation() = runBlocking {
        val fixture = Fixture()
        withContext(ConversationPrivacyContext(71, true)) {
            var rejected = false
            try {
                fixture.open(false)
            } catch (_: UnverifiedServiceTransport) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(PrivacyModeStore.active())
        }
        assertEquals(0, fixture.opened)
    }

    @Test
    fun aPreparedComparisonLegCannotBeCollectedAgain() = runBlocking {
        val fixture = Fixture()
        val prepared = fixture.open(true)
        prepared.session.streamRound(emptyList(), emptyList()).toList()
        var rejected = false
        try {
            prepared.session.streamRound(emptyList(), emptyList()).toList()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(1, fixture.opened)
    }

    @Test
    fun receiverRechecksTheFrozenProfileAfterTheContextHandoffWaits() = runBlocking {
        val fixture = Fixture()
        val pending = CompletableDeferred<Unit>()
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val events = mutableListOf<ProviderEvent>()
        var stopped = false
        fixture.response = { _, _ ->
            try {
                emit(ProviderEvent.TextDelta("safe prefix"))
                pending.complete(Unit)
                emit(ProviderEvent.TextDelta("pending synthetic answer"))
                emit(ProviderEvent.Completed)
            } finally {
                stopped = true
            }
        }
        val prepared = fixture.open(false)
        val job = launch {
            prepared.session.streamRound(emptyList(), emptyList()).collect { event ->
                events += event
                if (events.size == 1) {
                    paused.complete(Unit)
                    resume.await()
                }
            }
        }
        try {
            withTimeout(2_000) {
                paused.await()
                pending.await()
            }
            fixture.currentProfile = false
            resume.complete(Unit)
            withTimeout(2_000) { job.join() }
            assertEquals(listOf(ProviderEvent.TextDelta("safe prefix")), events.filterIsInstance<ProviderEvent.TextDelta>())
            val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("generation_completed"))
            assertTrue(events.none { it == ProviderEvent.Completed })
            assertTrue(stopped)
            assertEquals(1, fixture.opened)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun completedGenerationRemainsAccountedWhenTheComparisonReceiverDeniesDelivery() = runBlocking {
        assertDeniedAccounting(ProviderEvent.Completed, expectedCompletion = true)
    }

    @Test
    fun configuredReceiverDenialPreservesCurrentGenerationAccounting() = runBlocking {
        assertDeniedAccounting(ProviderEvent.Completed, expectedCompletion = true, revokeProvider = true)
    }

    @Test
    fun forgedFailureCannotSupplyCompletionToTheComparisonReceiverAccounting() = runBlocking {
        val forged = ProviderEvent.Failed(
            SAVED_TERM,
            errorCode = "DISPATCH_POLICY_CHANGED",
            transportComplete = true,
            failureDetails = buildJsonObject {
                put("generation_completed", true)
                put("private_value", SAVED_TERM)
            }
        )
        assertDeniedAccounting(forged, expectedCompletion = false)
    }

    private suspend fun assertDeniedAccounting(pendingEvent: ProviderEvent, expectedCompletion: Boolean, revokeProvider: Boolean = false) = coroutineScope {
        var providerAllowed = true
        val authority = if (revokeProvider) RequestAuthorizationGuard("configured-receiver-fixture") { check(providerAllowed) } else null
        val fixture = Fixture(providerAuthority = authority)
        val pending = CompletableDeferred<Unit>()
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val events = mutableListOf<ProviderEvent>()
        var stopped = false
        fixture.response = { _, _ ->
            try {
                emit(ProviderEvent.TextDelta("safe prefix"))
                pending.complete(Unit)
                emit(pendingEvent)
            } finally {
                stopped = true
            }
        }
        val dao = mockk<InvocationDao>(relaxed = true)
        var saved: ModelInvocation? = null
        coEvery { dao.save(any()) } answers { saved = firstArg() }
        val record = ModelInvocation("comparison-accounting", "run", "turn", "OPENAI", "fictional-model", "benchmark", 128, 512)
        val live = MutableStateFlow<Map<String, ModelInvocation>>(emptyMap())
        val round = InvocationRoundAccounting(record, null, RequestDispatchContext(emptySet()), null, dao, true, live, null, "turn")
        val receipts = mutableListOf<String>()
        val caller = ConversationPrivacyContext.currentOrNull()
        mockkObject(AppLogRecorder)
        try {
            every { AppLogRecorder.record("Model", match { it.startsWith("PROVIDER_RECEIPT") }, any()) } answers {
                receipts += secondArg<String>()
                Unit
            }
            val prepared = fixture.open(false)
            val job = launch {
                prepared.session.streamRound(emptyList(), emptyList()).collect { event ->
                    round.observe(event)
                    events += event
                    if (events.size == 1) {
                        paused.complete(Unit)
                        resume.await()
                    }
                }
            }
            try {
                withTimeout(2_000) {
                    paused.await()
                    pending.await()
                }
                if (revokeProvider) providerAllowed = false else fixture.currentProfile = false
                resume.complete(Unit)
                withTimeout(2_000) { job.join() }
                assertTrue(stopped)
                assertEquals(1, fixture.opened)
                assertEquals(listOf(ProviderEvent.TextDelta("safe prefix")), events.filterIsInstance<ProviderEvent.TextDelta>())
                assertTrue(events.none { it == ProviderEvent.Completed || it === pendingEvent && pendingEvent is ProviderEvent.Failed })
                val denied = events.filterIsInstance<ProviderEvent.Failed>().single()
                assertFalse(denied.transportComplete)
                assertEquals("PARTIAL", denied.taskOutcome)
                assertEquals(JsonPrimitive(expectedCompletion), denied.failureDetails?.get("generation_completed"))
                assertEquals(setOf("failed_stage", "generation_completed", "request_replay_allowed", "logical_continuation_allowed"), requireNotNull(denied.failureDetails).keys)
                assertEquals("FAILED", live.value.getValue(record.id).status)
                round.finish()
                val persisted = requireNotNull(saved)
                assertEquals("FAILED", persisted.status)
                assertTrue(live.value.isEmpty())
                coVerify(exactly = 1) { dao.save(any()) }
                val evidence = Json.parseToJsonElement(requireNotNull(persisted.usageEvidenceJson)).jsonObject
                val failure = Json.decodeFromJsonElement(ProfileFailureRecord.serializer(), evidence.getValue("failure"))
                assertEquals(expectedCompletion, failure.generationCompleted)
                assertEquals(false, failure.requestReplayAllowed)
                assertFalse(failure.retryAllowed)
                assertEquals("POLICY_STALE", failure.category)
                val receipt = Json.parseToJsonElement(receipts.single().substringAfter("receipt=")).jsonObject
                assertEquals(JsonPrimitive(expectedCompletion), receipt["generationCompleted"])
                assertEquals(JsonPrimitive(false), receipt["requestReplayAllowed"])
                assertEquals(JsonPrimitive("FAILED"), receipt["roundOutcome"])
                assertEquals(JsonPrimitive("safe prefix".toByteArray().size), receipt["visibleBytes"])
                assertNull(persisted.observedInputTokens)
                assertNull(persisted.observedOutputTokens)
                assertFalse(evidence.toString().contains(SAVED_TERM))
                assertFalse(receipts.single().contains(SAVED_TERM))
                assertSame(caller, ConversationPrivacyContext.currentOrNull())
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            unmockkObject(AppLogRecorder)
        }
    }

    @Test
    fun cancellingAnIncompletePrivateMarkerStopsTheProviderAndRestoresCallerScope() = runBlocking {
        val fixture = Fixture()
        val waiting = CompletableDeferred<Unit>()
        var stopped = false
        fixture.response = { turns, _ ->
            val marker = requireNotNull(Regex("\\[PRIVATE_[^]]+]").find(turns.single().userMessage.content)).value
            try {
                emit(ProviderEvent.TextDelta(marker.dropLast(1)))
                waiting.complete(Unit)
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val caller = ConversationPrivacyContext(42, false)
        withContext(caller) {
            val prepared = fixture.open(true)
            val events = mutableListOf<ProviderEvent>()
            val job = launch { prepared.session.streamRound(emptyList(), emptyList()).collect { events += it } }
            try {
                withTimeout(2_000) { waiting.await() }
            } finally {
                job.cancelAndJoin()
            }
            assertTrue(stopped)
            assertTrue(events.isEmpty())
            assertSame(caller, ConversationPrivacyContext.currentOrNull())
            assertSame(caller, currentCoroutineContext()[ConversationPrivacyContext])
            assertEquals(1, fixture.opened)
        }
    }

    @Test
    fun strictArmKeepsItsActualSerializerAndDeclaresTheComparisonLimit() = runBlocking {
        val fixture = Fixture()
        val preparation = mockk<ModelRequestPreparation>()
        every { fixture.verifier.preparation } returns preparation
        coEvery { fixture.verifier.verify(any()) } returns PrivacyProviderVerifier.Result(
            PrivacyProfileEligibility.Decision(true, "Fictional verified route"),
            VerifiedPrivateRoute("https://example.invalid/v1/chat/completions", ClientType.OPENAI)
        )
        var wire: JsonObject? = null
        coEvery { preparation.prepareQueued(any(), any(), any(), any()) } coAnswers {
            val payload = firstArg<String>()
            wire = Json.parseToJsonElement(payload).jsonObject
            val config = secondArg<ProviderRequestConfig>()
            requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
            requireNotNull(currentCoroutineContext()[PrivateDispatchContext]).validate(payload, config.permissionRevision)
            // Stop at the production preparation boundary; this test has no HTTP server.
            throw IllegalStateException("Synthetic preparation stopped before transport")
        }
        val prepared = fixture.open(true)
        val events = prepared.session.streamRound(emptyList(), emptyList()).toList()
        assertNotNull(wire)
        assertFalse(requireNotNull(wire).toString().contains("Mira Voss"))
        assertFalse(requireNotNull(wire).containsKey("temperature"))
        assertFalse(requireNotNull(wire).containsKey("top_p"))
        assertEquals("STRICT_PRIVATE_TEXT", prepared.measurements().routeKind)
        assertEquals("STRICT_ROUTE_SETTINGS_UNVERIFIED", prepared.measurements().comparisonLimitation)
        assertEquals(0, fixture.opened)
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertTrue(events.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        coVerify(exactly = 0) { fixture.store.privateTerms() }
    }

    @Test
    fun cancellingSuspendedConfiguredAuthorityDuringOpeningPreservesCancellationWithoutDispatch() = runBlocking {
        assertSuspendedConfiguredAuthorityCancellation(duringCollection = false)
    }

    @Test
    fun cancellingSuspendedConfiguredAuthorityDuringCollectionPreservesCancellationWithoutDispatch() = runBlocking {
        assertSuspendedConfiguredAuthorityCancellation(duringCollection = true)
    }

    private suspend fun assertSuspendedConfiguredAuthorityCancellation(duringCollection: Boolean) = coroutineScope {
        for (protection in listOf(false, true)) {
            val previousConversation = ConversationPrivacyContext.currentOrNull()
            val previousAuthority = currentCoroutineContext()[RequestAuthorizationGuard]
            val caller = ConversationPrivacyContext(42, false)
            val inherited = RequestAuthorizationGuard("synthetic-cancellation-caller") {}
            withContext(caller + inherited) {
                var suspendValidation = !duringCollection
                val validatorEntered = CompletableDeferred<Job>()
                val validatorExited = CompletableDeferred<Unit>()
                val callerExited = CompletableDeferred<Unit>()
                val original = AuthorityCancellation(duringCollection, protection)
                var validatorCancellation: CancellationException? = null
                var callerCancellation: CancellationException? = null
                var laterLegAttempts = 0
                val fixture = Fixture(
                    providerAuthority = RequestAuthorizationGuard("synthetic-suspended-provider-authority") {
                        if (suspendValidation) {
                            validatorEntered.complete(requireNotNull(currentCoroutineContext()[Job]))
                            try {
                                awaitCancellation()
                            } catch (cancelled: CancellationException) {
                                validatorCancellation = cancelled
                                throw cancelled
                            } finally {
                                validatorExited.complete(Unit)
                            }
                        }
                    }
                )
                // Collection must pass the real opening checks before its first
                // producer validation is suspended, still before any provider open.
                val prepared = if (duringCollection) fixture.open(protection) else null
                suspendValidation = true
                val events = mutableListOf<ProviderEvent>()
                val job = launch {
                    try {
                        val first = prepared ?: fixture.open(protection)
                        first.session.streamRound(emptyList(), emptyList()).toList(events)
                        laterLegAttempts++
                        fixture.open(!protection).session.streamRound(emptyList(), emptyList()).toList(events)
                    } catch (cancelled: CancellationException) {
                        callerCancellation = cancelled
                        throw cancelled
                    } finally {
                        callerExited.complete(Unit)
                    }
                }
                try {
                    val validatingJob = withTimeout(2_000) { validatorEntered.await() }
                    assertFalse(validatorExited.isCompleted)
                    assertEquals(0, fixture.opened)
                    job.cancel(original)
                    withTimeout(2_000) {
                        job.join()
                        validatingJob.join()
                        validatorExited.await()
                        callerExited.await()
                    }
                    assertSame(original, validatorCancellation)
                    assertSame(original, callerCancellation)
                    assertTrue(job.isCancelled)
                    assertTrue(validatingJob.isCompleted)
                    assertEquals(0, fixture.opened)
                    assertEquals(0, laterLegAttempts)
                    assertTrue(events.isEmpty())
                    assertNull(fixture.sentProfile)
                    assertTrue(fixture.sentTurns.isEmpty())
                    assertNull(fixture.constraints)
                    assertSame(caller, ConversationPrivacyContext.currentOrNull())
                    assertSame(caller, currentCoroutineContext()[ConversationPrivacyContext])
                    assertSame(inherited, currentCoroutineContext()[RequestAuthorizationGuard])
                } finally {
                    job.cancelAndJoin()
                }
            }
            assertSame(previousConversation, ConversationPrivacyContext.currentOrNull())
            assertSame(previousAuthority, currentCoroutineContext()[RequestAuthorizationGuard])
        }
    }

    // A distinct constructor keeps the sentinel identifiable across coroutine
    // debug stack recovery as well as the real producer context handoff.
    private class AuthorityCancellation(duringCollection: Boolean, protection: Boolean) : CancellationException("Synthetic authority cancellation: collecting=$duringCollection; protection=$protection")

    private suspend fun expectBlocked(code: PrivacyComparisonFailure, block: suspend () -> Unit) {
        var failure: PrivacyComparisonFailure? = null
        try {
            block()
        } catch (blocked: PrivacyComparisonBlockedException) {
            failure = blocked.code
        }
        assertEquals(code, failure)
    }

    private class Fixture(
        type: ClientType = ClientType.OPENAI,
        apiUrl: String = "https://example.invalid/v1",
        model: String = "fictional-model",
        ledger: InvocationLedger? = null,
        providerAuthority: RequestAuthorizationGuard? = null
    ) {
        val store = mockk<PrivacyModeStore>()
        val verifier = mockk<PrivacyProviderVerifier>()
        val profile = PlatformV2(
            uid = "synthetic-profile", name = "Fictional comparison", compatibleType = type,
            apiUrl = apiUrl, model = model, systemPrompt = SAVED_INSTRUCTION,
            temperature = 0.9f, maxTokens = 4096, batchMode = true, batchApiUrl = "https://example.invalid/batch"
        )
        var state = PrivacyModeState(true, PrivacyCategory.entries.toSet(), 11)
        var currentProfile = true
        var profileRead: suspend () -> Unit = {}
        var opened = 0
        var sentProfile: PlatformV2? = null
        var sentTurns = emptyList<ConversationTurn>()
        var constraints: RequestConstraints? = null
        var requestLimit: Int? = null
        var outboundAuthority: CapturedProviderAuthority? = null
        var outboundRevision: String? = null
        var response: suspend FlowCollector<ProviderEvent>.(List<ConversationTurn>, PlatformV2) -> Unit = { turns, _ ->
            emit(ProviderEvent.TextDelta(turns.single().userMessage.content))
            emit(ProviderEvent.Completed)
        }
        val binding = PrivacyComparisonProviderBinding(store, {
            profileRead()
            currentProfile && it == profile
        }, ::provider, ledger, providerAuthority)

        init {
            every { store.effectiveSnapshot(any()) } answers { state.copy(enabled = firstArg()) }
            every { store.verifier } returns verifier
            coEvery { store.privateTerms() } returns listOf(SAVED_TERM)
            coEvery { verifier.verify(any()) } returns PrivacyProviderVerifier.Result(PrivacyProfileEligibility.Decision(false, "Fictional masked fallback"), null)
        }

        suspend fun open(protection: Boolean): PrivacyComparisonSession = binding.open(profile, "travel", protection, "fixture-leg", 60_000)

        suspend fun provider(turns: List<ConversationTurn>, target: PlatformV2, constraints: RequestConstraints): AgentProviderSession {
            opened++
            sentTurns = turns
            sentProfile = target
            this.constraints = constraints
            requestLimit = currentCoroutineContext()[WorkerRequestBudget]?.preflight()?.requestLimit
            outboundAuthority = currentCoroutineContext()[RequestAuthorizationGuard]?.authority
            outboundRevision = currentCoroutineContext()[RequestAuthorizationGuard]?.revision
            return object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    assertTrue(tools.isEmpty() && exchanges.isEmpty())
                    val context = currentCoroutineContext()
                    context[PrivateDispatchContext]?.let { proof ->
                        val body = buildJsonObject {
                            put("model", target.model)
                            put(
                                "messages",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("role", "system")
                                            put("content", target.systemPrompt.orEmpty())
                                        }
                                    )
                                    turns.forEach { turn ->
                                        add(
                                            buildJsonObject {
                                                put("role", "user")
                                                put("content", turn.userMessage.content)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                        val guard = requireNotNull(context[RequestAuthorizationGuard])
                        guard.validate()
                        val receipt = proof.validate(body.toString(), guard.revision)
                        context[RequestDispatchContext]?.privacyValidated(receipt, proof)
                    }
                    response(turns, target)
                }
            }
        }
    }

    companion object {
        private const val SAVED_INSTRUCTION = "Saved personal instructions must never enter this fictional comparison."
        private const val SAVED_TERM = "SavedPersonalCanary-48291"
    }
}
