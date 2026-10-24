package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWriterRetrySnapshotTest {
    @Test fun retryPreservesResearchQuestionsUnknownMetadataAndImmutableCorpus() {
        val original = CombinedWriterRetryFixture.snapshot()
        val retry = CombinedWriterRetrySnapshot.prepare(original, CombinedWriterRetryFixture.writer)
        val before = Json.parseToJsonElement(original.prompt).jsonObject
        val after = Json.parseToJsonElement(retry.prompt).jsonObject
        val allowed = setOf("target_revision_id", "requested_policy_digest", "effective_plan_digest")
        assertEquals(before.filterKeys { it !in allowed }, after.filterKeys { it !in allowed })
        assertEquals(original.sources, retry.sources)
        assertEquals(original.evidence, retry.evidence)
        assertEquals(original.history, retry.history)
        assertEquals(original.acceptedTask, retry.acceptedTask)
        assertNotEquals(original.revisionId, retry.revisionId)
        assertNotEquals(original.dispatchIdentity, retry.dispatchIdentity)
        assertNotEquals(original.combined!!.rootBudgetId, retry.combined!!.rootBudgetId)
        assertEquals(listOf("finalization"), retry.combined.plan!!.workItems.map { it.id })
        CombinedWriterRetrySnapshot.validate(original, retry)
    }

    @Test fun aResealedRetryCannotReuseItsRootRevisionOrDispatchOrReplaceItsSources() {
        val original = CombinedWriterRetryFixture.snapshot()
        val retry = CombinedWriterRetrySnapshot.prepare(original, CombinedWriterRetryFixture.writer)
        val illegal = listOf(
            retry.copy(combined = retry.combined!!.copy(rootBudgetId = original.combined!!.rootBudgetId)),
            retry.copy(revisionId = original.revisionId),
            retry.copy(dispatchIdentity = original.dispatchIdentity),
            retry.copy(sources = retry.sources.map { it.copy(content = "An unrelated replacement contribution.") }),
            retry.copy(evidence = retry.evidence.copy(records = emptyList())),
            retry.copy(acknowledgedFailureAttempts = setOf("a different failure epoch"))
        )
        illegal.forEach { candidate ->
            val failure = runCatching { CombinedWriterRetrySnapshot.validate(original, candidate.copy(inputDigest = "").seal()) }.exceptionOrNull()
            assertTrue("Mutation was accepted: $candidate", failure is IllegalArgumentException)
        }
    }

    @Test fun replacingResearchObligationsIsRejectedEvenWhenANewPlanWasBuilt() {
        val original = CombinedWriterRetryFixture.snapshot()
        val retry = CombinedWriterRetrySnapshot.prepare(original, CombinedWriterRetryFixture.writer)
        val changed = retry.prompt.replace("Retain the accepted research question", "CURRENT_REQUEST")
        val changedPlan = CombinedWriterRequestPlan.freeze(
            CombinedWriterRetryFixture.writer,
            original.userMessage.content,
            changed,
            combinedWriterSystemPrompt(original.systemPrompt, original.deliveryProtocol, original.userMessage.content),
            combinedWriterHistory(original.history, original.replies, original.profileUid),
            original.deliveryProtocol,
            8192
        )
        val candidate = retry.copy(prompt = changed, writerRequestPlan = changedPlan, inputDigest = "").seal()
        assertEquals("COMBINED_RETRY_PROMPT_CHANGED", runCatching { CombinedWriterRetrySnapshot.validate(original, candidate) }.exceptionOrNull()?.message)
    }
}

internal object CombinedWriterRetryFixture {
    val writer = PlatformV2(uid = "p", name = "Writer", compatibleType = ClientType.CUSTOM, apiUrl = "https://writer.example/v1", model = "writer-test", maxTokens = 16_384)

    fun snapshot(): CombinedInputSnapshot {
        val user = MessageV2(id = 2, chatId = 1, platformType = null, content = "Explain the accepted research findings")
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p"), mapOf("p" to writer.model), user.content, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "initial-root", CombinedRunRole.SYNTHESIS, "p", chatId = 1, acceptedTask = AcceptedTaskRef.from(user))
        val prompt = """{"target_revision_id":"initial-revision","requested_policy_digest":"${requested.requestedPolicyDigest}","effective_plan_digest":"${context.plan!!.effectivePlanDigest}","question_obligations":[{"question":"Retain the accepted research question","status":"SUPPORTED"}],"future_contract":{"preserve":"unknown fields"},"contributions":[{"profile":"p","response":"The study reported 12 participants, with uncertain applicability."}]}"""
        val plan = CombinedWriterRequestPlan.freeze(writer, user.content, prompt, combinedWriterSystemPrompt(null, "PASSAGES_V3", user.content), emptyList(), "PASSAGES_V3", 8192)
        return CombinedInputSnapshot(
            version = 6, userMessage = user, history = emptyList(), replies = emptyList(),
            sources = listOf(CombinedModelResponse("p", "Writer", content = "The study reported 12 participants, with uncertain applicability.")),
            prompt = prompt, profileUid = writer.uid, model = writer.model, systemPrompt = "", configKey = "frozen-config",
            combined = context, acceptedTools = ChatMcpToolConfig(combined = context), acceptedTask = AcceptedTaskRef.from(user),
            evidence = CombinedEvidenceLedger(records = listOf(CombinedEvidenceRecord("source-1", "https://example.org/study", passage = "12 participants", retrieval = "READABLE_PASSAGE"))),
            deliveryProtocol = "PASSAGES_V3", snapshotId = "initial-snapshot", revisionId = "initial-revision", dispatchIdentity = "initial-dispatch", writerRequestPlan = plan
        ).seal()
    }
}
