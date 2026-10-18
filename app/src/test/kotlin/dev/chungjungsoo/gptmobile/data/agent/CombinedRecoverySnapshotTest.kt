package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CombinedRecoverySnapshotTest {
    private val platform = PlatformV2(uid = "primary", name = "Primary", model = "test", enabled = true)
    private val source = MessageV2(id = 2, chatId = 1, content = "Full task", platformType = null)
    private val snapshot = CombinedInputSnapshot(
        userMessage = source, history = emptyList(), replies = emptyList(),
        sources = listOf(CombinedModelResponse("primary", "Primary", content = "Original contribution")),
        prompt = "Complete frozen prompt", profileUid = platform.uid, model = platform.model,
        systemPrompt = "Original instructions", configKey = benchmarkConfigKey(platform)
    )
    private val raw = Json.encodeToString(snapshot)
    private val run = AgentRun("combined-synthesis:old", 1, 2, 3, "primary", "CUSTOM", "test", "INTERRUPTED", ownershipFence = 4, synthesisKey = researchHash(raw), synthesisSnapshot = raw)
    private val answer = MessageV2(id = 3, chatId = 1, content = "Preserved chapter 🙂", platformType = "primary", currentRunId = run.runId, ownershipFence = 4)
    private val segments = AnswerSegmentChain.append(emptyList(), run.runId, 4, answer.content)

    @Test fun restartRestoresOriginalSourcesAndExactPrefixWithoutPromotingTheAnswerIntoEvidence() {
        val recovered = CombinedRecoverySnapshot.prepare(run, answer, "primary", platform, segments)
        assertEquals(snapshot.sources, recovered.sources)
        assertEquals(snapshot.prompt, recovered.prompt)
        assertEquals(snapshot.history, recovered.history)
        assertEquals(answer.content, recovered.committedPrefix)
        assertEquals(run.runId, recovered.resumeFromRunId)
        assertEquals("Original contribution", recovered.sources.single().content)
    }

    @Test fun corruptStaleOrIncompatibleRecoveryCannotReplaceTheSavedAnswer() {
        assertThrows(IllegalArgumentException::class.java) { CombinedRecoverySnapshot.prepare(run, answer.copy(ownershipFence = 5), "primary", platform, segments) }
        assertThrows(IllegalArgumentException::class.java) { CombinedRecoverySnapshot.prepare(run, answer, "different-primary", platform, segments) }
        assertThrows(IllegalArgumentException::class.java) { CombinedRecoverySnapshot.prepare(run, answer, "primary", platform.copy(model = "new-model"), segments) }
        assertThrows(IllegalArgumentException::class.java) { CombinedRecoverySnapshot.prepare(run.copy(synthesisSnapshot = raw + " "), answer, "primary", platform, segments) }
        assertThrows(IllegalArgumentException::class.java) { CombinedRecoverySnapshot.prepare(run, answer.copy(content = "lost prefix"), "primary", platform, segments) }
    }

    @Test fun explicitRetryOfCompletedAnswerStartsANewRevisionWithTheSameFrozenEvidence() {
        val recovered = CombinedRecoverySnapshot.prepare(run.copy(status = "COMPLETED"), answer, "primary", platform, segments)
        assertEquals("", recovered.committedPrefix)
        assertEquals(snapshot.sources, recovered.sources)
    }

    private fun modern(): CombinedInputSnapshot {
        val accepted = CombinedRequestedPolicy(settings = ConversationCombinedSettings(collectionBias = 75, effort = 25), participantUids = listOf("pinned", "primary"), requestedModels = mapOf("primary" to "test"), preferredOwnerUid = "pinned", userPrompt = source.content)
        val requested = accepted.copy(requestedPolicyDigest = combinedDigest(Json.encodeToString(accepted)))
        val context = CombinedExecutionContext(requested = requested, rootBudgetId = "accepted-root", role = CombinedRunRole.SYNTHESIS)
        return snapshot.copy(deliveryProtocol = "PASSAGES_V2", combined = context, acceptedTools = ChatMcpToolConfig(enabledToolIds = setOf("read_url"), allowAllByDefault = false, combined = context), answerContract = CombinedAnswerPolicy.from(source.content))
    }

    @Test fun modernRecoveryUsesActualFallbackOwnerAndPreservesAcceptedPolicyAndTools() {
        val saved = modern()
        val encoded = Json.encodeToString(saved)
        val recovered = CombinedRecoverySnapshot.prepare(run.copy(synthesisKey = researchHash(encoded), synthesisSnapshot = encoded), answer, "pinned", platform, segments)
        assertEquals("", recovered.committedPrefix)
        assertEquals(saved.combined, recovered.combined)
        assertEquals(saved.acceptedTools, recovered.acceptedTools)
        assertEquals(saved.sources, recovered.sources)
        assertEquals(saved.answerContract, recovered.answerContract)
    }

    @Test fun structuredReplacementStillChecksTheArchivedPrefixAndFrozenPolicyDigest() {
        val saved = modern()
        val encoded = Json.encodeToString(saved)
        val owner = run.copy(synthesisKey = researchHash(encoded), synthesisSnapshot = encoded)
        assertThrows(IllegalArgumentException::class.java) { CombinedRecoverySnapshot.prepare(owner, answer.copy(content = "Changed"), "pinned", platform, segments) }
        val changed = saved.copy(combined = saved.combined!!.copy(requested = saved.combined.requested.copy(settings = ConversationCombinedSettings(effort = 100))))
        val changedRaw = Json.encodeToString(changed)
        assertThrows(IllegalArgumentException::class.java) { CombinedRecoverySnapshot.prepare(owner.copy(synthesisSnapshot = changedRaw, synthesisKey = researchHash(changedRaw)), answer, "pinned", platform, segments) }
    }

    @Test fun legacyInventoryCanResumeWithoutInventingModernSemanticClaims() {
        val legacy = snapshot.copy(version = 3, claimInventory = CombinedClaimInventory.freeze(snapshot.sources).copy(version = 1, claims = emptyList()))
        val encoded = Json.encodeToString(legacy)
        val recovered = CombinedRecoverySnapshot.prepare(run.copy(synthesisKey = researchHash(encoded), synthesisSnapshot = encoded), answer, "primary", platform, segments)
        assertEquals(1, recovered.claimInventory!!.version)
        assertEquals(emptyList<CombinedClaimRecord>(), recovered.claimInventory.claims)
    }
}
