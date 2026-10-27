package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWriterRequestPlanTest {
    private val profile = PlatformV2(uid = "writer-plan-test", compatibleType = ClientType.CUSTOM, name = "Writer", model = "writer-test", apiUrl = "https://writer.example/v1", enabled = true, maxTokens = 16_384)

    @After fun clearObservations() = CombinedWriterObservations.clearForTest()

    private fun plan(context: Int? = null, prompt: String = "Complete frozen contributions.", userLimit: Int? = null) = CombinedWriterRequestPlan.freeze(
        profile, "Explain the result in 500 words", prompt, "Write the final answer.", listOf("Relevant prior question", "Relevant prior answer"), "PASSAGES_V3", 8192, userLimit, context
    )

    @Test fun incrementalPlanBindsTheFullCorpusAndEachScopedInspection() {
        val prompt = "Complete source body ".repeat(5000)
        val system = "Write the final answer."
        val plan = CombinedWriterRequestPlan.freeze(profile, "Explain in 500 words", prompt, system, emptyList(), "PASSAGES_V3", 8192, userContextLimit = 12000, incrementalSynthesis = true)
        assertEquals(3, plan.version)
        assertEquals("FULL_CORPUS_INSPECTION_REQUIRED", plan.capacityState)
        val guard = CombinedWriterDispatchGuard(plan)
        guard.validatePreparedRequest(profile.copy(systemPrompt = system), listOf(ConversationTurn(MessageV2(platformType = null, content = prompt), null, true)))
        val turns = listOf(ConversationTurn(MessageV2(platformType = null, content = "Inspect bounded frozen span"), null, true))
        val inspection = guard.corpusRequest(profile.copy(systemPrompt = system), turns, 512, false)
        inspection.validatePreparedRequest(profile.copy(systemPrompt = system), turns)
        assertTrue(runCatching { inspection.validatePreparedRequest(profile.copy(systemPrompt = system), listOf(ConversationTurn(MessageV2(platformType = null, content = "Changed corpus"), null, true))) }.isFailure)
        assertTrue(runCatching { guard.corpusRequest(profile.copy(model = "different"), turns, 512, false) }.isFailure)
        assertTrue(runCatching { guard.corpusRequest(profile, turns, 512, true) }.isFailure)
    }

    @Test fun unknownCapacityIsExplicitAndDoesNotClaimTheInputFits() {
        val planned = plan()
        assertEquals("CAPACITY_UNVERIFIED", planned.capacityState)
        assertEquals(null, planned.contextTokens)
        assertTrue(planned.effectiveOutputTokens >= planned.requiredOutputTokens)
        assertTrue(planned.uncertaintyTokens > 0)
    }

    @Test fun finalEnvelopeAndHistoryAreIncludedInCapacityAdmission() {
        val failure = runCatching { plan(context = 12_000, prompt = "Full contribution ".repeat(6000)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("complete frozen input"))
    }

    @Test fun explicitUserOutputLimitIsNeverRaisedToHideAnIneligibleWriter() {
        val failure = runCatching { plan(userLimit = 2048) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("accepted limit is 2048"))
    }

    @Test fun learnedReasoningAndTruncationIncreaseThePlanBeforeItsSingleDispatch() {
        val before = CombinedWriterPolicy.requiredOutput("Explain the result in 500 words", profile)
        CombinedWriterObservations.recordUsage(CombinedWriterObservations.key(profile), 7000, "length", 8192)
        val after = plan()
        assertTrue(after.requiredOutputTokens > before)
        assertTrue(after.effectiveOutputTokens > 8192)
    }

    @Test fun serializerCapReductionCannotConsumeAPhysicalAttempt() = runTest {
        val planned = plan()
        val receipt = receipt(4096)
        val guard = CombinedWriterDispatchGuard(planned).also { it.validatePreparedRequest(profile.copy(systemPrompt = "Write the final answer."), preparedTurns()) }
        var calls = 0
        val failure = runCatching {
            withContext(guard + PhysicalAttemptBoundary { calls++ }) {
                PhysicalAttemptBoundary.dispatch(receipt)
            }
        }.exceptionOrNull()
        assertEquals(0, calls)
        assertTrue(failure!!.message.orEmpty().startsWith("COMBINED_OUTPUT_ALLOWANCE_CHANGED"))
    }

    @Test fun matchingWireAdmissionDispatchesOnceWithTheFrozenAllowance() = runTest {
        val planned = plan()
        val guard = CombinedWriterDispatchGuard(planned).also { it.validatePreparedRequest(profile.copy(systemPrompt = "Write the final answer."), preparedTurns()) }
        var calls = 0
        withContext(guard + PhysicalAttemptBoundary { calls++ }) {
            assertTrue(PhysicalAttemptBoundary.dispatch(receipt(planned.effectiveOutputTokens)).isNotBlank())
        }
        assertEquals(1, calls)
    }

    @Test fun aMatchingWireReceiptCannotBypassPreparedEnvelopeValidation() = runTest {
        val planned = plan()
        var calls = 0
        val failure = runCatching {
            withContext(CombinedWriterDispatchGuard(planned) + PhysicalAttemptBoundary { calls++ }) {
                PhysicalAttemptBoundary.dispatch(receipt(planned.effectiveOutputTokens))
            }
        }.exceptionOrNull()
        assertEquals(0, calls)
        assertEquals("COMBINED_WRITER_PREPARED_ENVELOPE_UNAVAILABLE", failure?.message)
    }

    @Test fun systemHistoryPromptAndRouteDriftAllStopBeforePhysicalDispatch() = runTest {
        val planned = plan()
        val preparedProfile = profile.copy(systemPrompt = "Write the final answer.")
        val cases = listOf(
            Triple(preparedProfile.copy(systemPrompt = "Changed instruction"), preparedTurns(), "COMBINED_WRITER_PLAN_SYSTEM_CHANGED"),
            Triple(preparedProfile.copy(apiUrl = "https://different.example/v1"), preparedTurns(), "COMBINED_WRITER_PLAN_ROUTE_CHANGED"),
            Triple(preparedProfile.copy(openRouterRouting = "new-backend"), preparedTurns(), "COMBINED_WRITER_PLAN_ROUTE_CHANGED"),
            Triple(preparedProfile, preparedTurns().drop(1), "COMBINED_WRITER_PLAN_HISTORY_CHANGED"),
            Triple(preparedProfile, preparedTurns().map { if (it.isCurrentTurn) it.copy(userMessage = it.userMessage.copy(content = "Changed contributions")) else it }, "COMBINED_WRITER_PLAN_PROMPT_CHANGED"),
            Triple(preparedProfile, preparedTurns().map { if (!it.isCurrentTurn) it.copy(assistantMessage = it.assistantMessage!!.copy(content = "Changed saved answer")) else it }, "COMBINED_WRITER_PLAN_HISTORY_CHANGED")
        )
        cases.forEach { (target, turns, expected) ->
            val guard = CombinedWriterDispatchGuard(planned)
            var calls = 0
            val failure = runCatching {
                withContext(guard + PhysicalAttemptBoundary { calls++ }) {
                    guard.validatePreparedRequest(target, turns)
                    PhysicalAttemptBoundary.dispatch(receipt(planned.effectiveOutputTokens))
                }
            }.exceptionOrNull()
            assertEquals(expected, failure?.message)
            assertEquals(0, calls)
        }
    }

    @Test fun sealedSnapshotCannotChangeItsSystemOrSelectedHistoryUnderAnOldPlan() {
        val user = MessageV2(id = 3, chatId = 1, platformType = null, content = "Hi")
        val history = listOf(MessageV2(id = 1, chatId = 1, platformType = null, content = "Relevant prior question"))
        val replies = listOf(listOf(MessageV2(id = 2, chatId = 1, platformType = profile.uid, content = "Relevant prior answer")))
        val prompt = "Frozen greeting input"
        val plan = CombinedWriterRequestPlan.freeze(profile, user.content, prompt, combinedWriterSystemPrompt(null, "PASSAGES_V3", user.content), combinedWriterHistory(history, replies, profile.uid), "PASSAGES_V3", 8192)
        val snapshot = CombinedInputSnapshot(version = 6, userMessage = user, history = history, replies = replies, sources = emptyList(), prompt = prompt, profileUid = profile.uid, model = profile.model, systemPrompt = "", configKey = "config", deliveryProtocol = "PASSAGES_V3", snapshotId = "snapshot", writerRequestPlan = plan).seal()
        snapshot.bindAcceptedStage()
        assertEquals("COMBINED_WRITER_PLAN_SYSTEM_CHANGED", runCatching { snapshot.copy(systemPrompt = "New instruction", inputDigest = "").seal().bindAcceptedStage() }.exceptionOrNull()?.message)
        assertEquals("COMBINED_WRITER_PLAN_HISTORY_CHANGED", runCatching { snapshot.copy(replies = listOf(listOf(replies.single().single().copy(content = "Revised answer"))), inputDigest = "").seal().bindAcceptedStage() }.exceptionOrNull()?.message)
    }

    @Test fun capturedNativeAttachmentsCannotBeRemovedOrReplacedAfterThePlan() {
        val attachment = dev.chungjungsoo.gptmobile.data.model.ChatAttachment("/accepted/image.png", "", "image.png", "image/png", 24)
        val user = MessageV2(id = 2, chatId = 1, platformType = null, content = "Explain this image", attachments = listOf(attachment))
        val context = CombinedWriterContext.capture(emptyList(), emptyList(), user, profile)
        val prompt = "Complete frozen contributions."
        val plan = CombinedWriterRequestPlan.freeze(profile, user.content, prompt, "Write the final answer.", context.historyText(), "PASSAGES_V3", 8192, writerContext = context)
        val prepared = profile.copy(systemPrompt = "Write the final answer.")
        plan.validatePreparedRequest(prepared, context.turns(prompt))
        val missing = context.turns(prompt).map { it.copy(userMessage = it.userMessage.copy(attachments = emptyList())) }
        assertEquals("COMBINED_WRITER_PLAN_CONTEXT_CHANGED", runCatching { plan.validatePreparedRequest(prepared, missing) }.exceptionOrNull()?.message)
        val changed = context.turns(prompt).map { it.copy(userMessage = it.userMessage.copy(attachments = listOf(attachment.copy(localFilePath = "/different/image.png")))) }
        assertEquals("COMBINED_WRITER_PLAN_CONTEXT_CHANGED", runCatching { plan.validatePreparedRequest(prepared, changed) }.exceptionOrNull()?.message)
    }

    private fun preparedTurns() = listOf(
        ConversationTurn(MessageV2(platformType = null, content = "Relevant prior question"), MessageV2(platformType = profile.uid, content = "Relevant prior answer"), false),
        ConversationTurn(MessageV2(platformType = null, content = "Complete frozen contributions."), null, true)
    )

    @Test fun explicitWritingRetryDoesNotReallocateContributorWork() {
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), listOf("p", "h"), mapOf("p" to "m", "h" to "m"), "Explain the result", singlePass = true)
        val original = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "old-root", CombinedRunRole.SYNTHESIS, "p")
        val retry = original.forExplicitWriterRetry()
        assertFalse(retry.rootBudgetId == original.rootBudgetId)
        assertEquals(listOf("finalization"), retry.plan!!.workItems.map { it.id })
        assertTrue(retry.plan!!.baselineByOwner.isEmpty())
        assertEquals(original.plan!!.hardLimits, retry.plan!!.hardLimits)
        assertEquals(original.requested, retry.requested)
    }

    @Test fun acceptedAppendAddsOnlyTheCommittedCursorToTheCompleteFrozenEnvelope() {
        val request = "Explain the result in 10000 words"
        val user = MessageV2(id = 2, chatId = 1, platformType = null, content = request)
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(continueLongAnswers = true), listOf(profile.uid), mapOf(profile.uid to profile.model), request, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.SYNTHESIS, profile.uid, chatId = 1)
        val prompt = "All complete frozen contributions and evidence."
        val system = combinedWriterSystemPrompt(null, "PASSAGES_V3", request, appendContinuations = true)
        val writer = profile.copy(systemPrompt = system)
        val plan = CombinedWriterRequestPlan.freeze(profile, request, prompt, system, emptyList(), "PASSAGES_V3", 8192, appendContinuations = true)
        val frozen = CombinedInputSnapshot(version = 6, userMessage = user, history = emptyList(), replies = emptyList(), sources = emptyList(), prompt = prompt, profileUid = profile.uid, model = profile.model, systemPrompt = "", configKey = "config", deliveryProtocol = "PASSAGES_V3", snapshotId = "snapshot", revisionId = "revision", combined = context, writerRequestPlan = plan).seal()
        val initial = listOf(ConversationTurn(user.copy(content = prompt), null, true))
        val protocol = CombinedRecordProtocol(CombinedRecordCheckpoint(snapshotDigest = frozen.inputDigest, revisionId = "revision"), emptySet(), emptySet(), emptySet(), 10000)
        protocol.accept("""{"type":"passage","seq":1,"id":"P1","text":"Already committed paragraph.","claims":[],"contributors":[],"evidence":[]}""")
        protocol.markAttemptFinished("OUTPUT_LIMIT")
        val cursor = protocol.continuationContext()
        val checkpoint = protocol.checkpoint()
        val continued = dev.chungjungsoo.gptmobile.data.queue.appendFollowUpContext(initial, "\n\nResume the frozen structured answer using this application-owned cursor: $cursor", checkpoint.records.joinToString("\n") { it.wire })
        val guard = CombinedWriterDispatchGuard(plan)
        guard.authorizeContinuation(frozen, checkpoint, writer, initial, continued, cursor)
        guard.validatePreparedRequest(writer, continued)
        guard.validateDispatch(receipt(plan.effectiveOutputTokens))
        assertTrue(continued.single().userMessage.content.startsWith(prompt))
        assertEquals(plan, guard.plan)
        assertTrue(runCatching { guard.validateDispatch(receipt(4096)) }.isFailure)
        assertEquals("COMBINED_WRITER_PLAN_IDENTITY_CHANGED", runCatching { guard.authorizeContinuation(frozen, checkpoint, writer.copy(uid = "other"), initial, continued, cursor) }.exceptionOrNull()?.message)
        val changed = continued.map { it.copy(userMessage = it.userMessage.copy(content = it.userMessage.content.replace("All complete frozen contributions", "A shortened source summary"))) }
        assertEquals("COMBINED_APPEND_CORPUS_CHANGED", runCatching { guard.authorizeContinuation(frozen, checkpoint, writer, initial, changed, cursor) }.exceptionOrNull()?.message)
        assertEquals("COMBINED_APPEND_CURSOR_INVALID", runCatching { guard.authorizeContinuation(frozen, checkpoint, writer, initial, continued, cursor.replace("\"next_sequence\":2", "\"next_sequence\":3")) }.exceptionOrNull()?.message)
        assertEquals("COMBINED_APPEND_CURSOR_UNAVAILABLE", runCatching { guard.authorizeContinuation(frozen, checkpoint.copy(appendReady = false, lastAttemptOutcome = "IN_FLIGHT"), writer, initial, continued, cursor) }.exceptionOrNull()?.message)
        val bounded = combinedRecordContinuationInput(initial, checkpoint, cursor, system, dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings(contextTokens = 32768), plan.effectiveOutputTokens)
        guard.authorizeContinuation(frozen, checkpoint, writer, initial, bounded.turns, cursor, bounded.recordContextTokens)
        guard.validatePreparedRequest(writer, bounded.turns)
        assertEquals(
            "COMBINED_APPEND_CURSOR_INVALID",
            runCatching {
                guard.authorizeContinuation(frozen, checkpoint, writer, initial, bounded.turns, cursor.replace(combinedRecordDigest(checkpoint), "incorrect-digest"), bounded.recordContextTokens)
            }.exceptionOrNull()?.message
        )
        val changedTail = bounded.turns.map { it.copy(userMessage = it.userMessage.copy(content = it.userMessage.content.replace("Already committed paragraph.", "Changed committed content."))) }
        assertEquals("COMBINED_APPEND_CORPUS_CHANGED", runCatching { guard.authorizeContinuation(frozen, checkpoint, writer, initial, changedTail, cursor, bounded.recordContextTokens) }.exceptionOrNull()?.message)
        // Previously saved append plans used the strict instruction. Their digest
        // remains valid; only newly frozen v2 plans adopt packet-aware instructions.
        val legacySystem = combinedWriterSystemPrompt(null, "PASSAGES_V3", request)
        val legacyPlan = CombinedWriterRequestPlan.freeze(profile, request, prompt, legacySystem, emptyList(), "PASSAGES_V3", 8192, appendContinuations = true).copy(version = 1)
        val legacySnapshot = frozen.copy(writerRequestPlan = legacyPlan, inputDigest = "").seal()
        legacyPlan.validate(legacySnapshot)
        legacyPlan.validatePreparedRequest(profile.copy(systemPrompt = legacySystem), initial)
        assertEquals("COMBINED_WRITER_PLAN_SYSTEM_CHANGED", runCatching { legacyPlan.validatePreparedRequest(writer, initial) }.exceptionOrNull()?.message)
    }

    private fun receipt(output: Int) = RequestAdmission.prepare(
        "{\"messages\":[]}",
        "synthesis",
        CapacitySnapshot(RuntimeFingerprint(profile.uid, "route", "credential", profile.model, profile.model, "runtime", "template", "allocation"), 32_768, provenance = CapacityProvenance.DECLARED),
        RequestAdmission.estimate("{\"messages\":[]}"),
        output.toLong(),
        "permission"
    ).receipt
}
