package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProviderToolCheckpointTest {
    private val task = "a".repeat(64)
    private val scope = "2:17:profile"
    private val original = "$scope:configured:first:documents:retained-sources"
    private val semantics = ToolHandoffReceipt.semanticsDigest(false, false, false, false, null)

    private fun checkpoint(key: String = original, binding: String = task) = buildJsonObject {
        put("state", "WAITING_PROVIDER_LIMIT")
        put("resume_allowed", true)
        put(
            "context",
            buildJsonObject {
                put("task_binding", binding)
                put("tool_checkpoint_binding", key)
            }
        )
    }

    private fun expected(value: JsonObject) = GitHubProviderCheckpoint(task, MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray()).joinToString("") { "%02x".format(it) })

    @Test fun resumedCoroutineUsesAcceptedReceiptJournalInsteadOfItsNewParentGuardIdentity() {
        val saved = checkpoint()
        val journals = mapOf(original to listOf("context-receipt", "search-receipt"))
        val restored = GitHubProviderToolCheckpoint.binding(scope, "configured:new:provider-wait:parent", expected(saved), saved)
        assertEquals(listOf("context-receipt", "search-receipt"), journals[restored])
        assertEquals(original, restored)
    }

    @Test fun changedCheckpointCannotRestoreAnOlderReceiptJournal() {
        val saved = checkpoint()
        val changed = JsonObject(saved + ("draft" to JsonPrimitive("A newer owner changed this boundary")))
        val error = runCatching { GitHubProviderToolCheckpoint.binding(scope, "new", expected(saved), changed) }.exceptionOrNull()
        assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", error?.message)
        assertTrue(error is ProviderRecoveryFailure)
        val details = (error as ProviderRecoveryFailure).event().failureDetails
        assertEquals(JsonPrimitive(false), details?.get("dispatched"))
        assertEquals(JsonPrimitive(expected(saved).digest), details?.get("checkpoint_digest"))
        assertEquals(JsonPrimitive("DIGEST_CHANGED"), details?.get("checkpoint_mismatch_reason"))
        assertEquals(JsonPrimitive(expected(changed).digest), details?.get("observed_checkpoint_digest"))
        assertEquals(JsonPrimitive("WAITING_PROVIDER_LIMIT"), details?.get("observed_checkpoint_state"))
        assertTrue(details.toString().contains("A newer owner").not())
    }

    @Test fun mismatchDiagnosisDistinguishesMissingStateAndPermissionWithoutRelaxingTheDigestFence() {
        val saved = checkpoint()
        val expected = expected(saved)
        val cases = listOf(
            null to "MISSING_CHECKPOINT",
            JsonObject(saved + ("state" to JsonPrimitive("TOOL_RESULTS_READY"))) to "STATE_CHANGED",
            JsonObject(saved + ("resume_allowed" to JsonPrimitive(false))) to "RESUME_DISALLOWED"
        )
        for ((observed, reason) in cases) {
            val failure = runCatching { GitHubProviderToolCheckpoint.binding(scope, "current", expected, observed) }.exceptionOrNull() as ProviderRecoveryFailure
            assertEquals(reason, (failure.correlation["checkpoint_mismatch_reason"] as JsonPrimitive).content)
            assertEquals(JsonPrimitive(false), failure.event().failureDetails?.get("request_replay_allowed"))
            assertEquals(JsonPrimitive(false), failure.event().failureDetails?.get("resume_allowed"))
        }
        assertEquals(original, GitHubProviderToolCheckpoint.binding(scope, "current", expected, saved))
    }

    @Test fun anotherTaskProfileOrTurnCannotBeSubstitutedEvenWithAMatchingDigest() {
        listOf(checkpoint(key = "2:18:profile:configured:first"), checkpoint(key = "2:17:other:configured:first"), checkpoint(binding = "b".repeat(64))).forEach { saved ->
            assertTrue(runCatching { GitHubProviderToolCheckpoint.binding(scope, "new", expected(saved), saved) }.isFailure)
        }
    }

    @Test fun ordinaryRequestDoesNotAdoptRetainedWaitWithoutItsTypedAuthority() {
        assertEquals("$scope:current", GitHubProviderToolCheckpoint.binding(scope, "current", null, checkpoint()))
    }

    @Test fun recoveredHandoffRequiresEveryOriginalReceiptAndRejectsUnknownOrUnrelatedResults() {
        val prior = ToolHandoffReceipt("prior", "a".repeat(64), "b".repeat(64), "c".repeat(64), true, null, false, resultSemanticsSha256 = semantics)
        val pending = prior.copy(callId = "pending", invocationFingerprint = "d".repeat(64))
        val saved = buildJsonObject {
            put("state", "TOOL_HANDOFF")
            put("pending_calls", JsonArray(listOf(JsonPrimitive(pending.invocationFingerprint))))
            put("completed_results", JsonArray(listOf(prior.json())))
        }
        assertEquals(GitHubProviderReplayEvidence.journalDigest(listOf(prior, pending)), GitHubProviderReplayEvidence.settledHandoffDigest(saved, listOf(pending, prior)))
        for (receipts in listOf(
            listOf(prior),
            listOf(pending),
            listOf(prior, pending.copy(requiresReconciliation = true)),
            listOf(prior.copy(sourceResultSha256 = "e".repeat(64)), pending),
            listOf(prior, pending.copy(callId = prior.callId)),
            listOf(prior, pending.copy(sourceResultSha256 = null)),
            listOf(prior, pending, pending.copy(callId = "extra"))
        )) {
            assertNull(GitHubProviderReplayEvidence.settledHandoffDigest(saved, receipts))
        }
    }

    @Test fun emptyCompleteHandoffValidatesItsOldBoundaryBeforeAdoptingNewResults() {
        val settled = ToolHandoffReceipt("new-call", "a".repeat(64), "b".repeat(64), "c".repeat(64), true, null, false, "TOOL_BATCH_CHECKPOINT", resultSemanticsSha256 = semantics)
        for (version in listOf(1, 2)) {
            val saved = buildJsonObject {
                put("state", "TOOL_HANDOFF")
                put("pending_calls", JsonArray(listOf(JsonPrimitive(settled.invocationFingerprint))))
                put("completed_results", JsonArray(emptyList()))
                put("tool_journal_complete", true)
                put("tool_journal_receipt_count", 0)
                put("tool_journal_digest", if (version == 1) dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("[]") else requireNotNull(GitHubProviderReplayEvidence.journalDigest(emptyList())))
                if (version == 2) put("tool_journal_digest_version", version)
            }
            assertEquals(GitHubProviderReplayEvidence.journalDigest(listOf(settled)), GitHubProviderReplayEvidence.settledHandoffDigest(saved, listOf(settled)))
            val updated = GitHubToolHandoff.reconcile(saved, listOf(settled), emptySet())
            assertEquals(JsonPrimitive("TOOL_RESULTS_READY"), updated["state"])
            assertTrue(GitHubProviderReplayEvidence.journalBindingKeys.none { it in updated })
            assertEquals(JsonPrimitive("PERSISTED_JOURNAL_PROJECTION_UTF8"), ((updated["completed_results"] as JsonArray).single() as JsonObject)["projection_hash_scope"])
            for (invalid in listOf(
                JsonObject(saved + ("tool_journal_receipt_count" to JsonPrimitive(1))),
                JsonObject(saved + ("tool_journal_digest" to JsonPrimitive("f".repeat(64)))),
                JsonObject(saved + ("tool_journal_digest_version" to JsonPrimitive(3))),
                JsonObject(saved - "tool_journal_complete")
            )) {
                assertNull(GitHubProviderReplayEvidence.settledHandoffDigest(invalid, listOf(settled)))
                assertEquals(invalid, GitHubToolHandoff.reconcile(invalid, listOf(settled), emptySet()))
            }
        }
    }

    @Test fun replayRetentionPreservesTheProtectedStoredProjectionUntilTheNextCompleteWait() {
        val durable = ToolHandoffReceipt("retained", "a".repeat(64), "b".repeat(64), "c".repeat(64), true, null, false, "TOOL_BATCH_CHECKPOINT", resultSemanticsSha256 = semantics)
        val compacted = durable.copy(projectionSha256 = "d".repeat(64), receiptSource = "REPLAY_PROJECTION", journalProjectionSha256 = "e".repeat(64))
        val retained = compacted.retaining(durable.json())
        assertEquals(JsonPrimitive(compacted.projectionSha256), retained["projection_sha256"])
        assertEquals(JsonPrimitive("REPLAY_PROJECTION_PAYLOAD_UTF8"), retained["projection_hash_scope"])
        assertEquals(JsonPrimitive(durable.projectionSha256), retained["journal_projection_sha256"])
        assertEquals(JsonPrimitive("PERSISTED_JOURNAL_PROJECTION_UTF8"), retained["journal_projection_scope"])
        val saved = buildJsonObject { put("completed_results", JsonArray(listOf(retained))) }
        assertEquals(GitHubProviderReplayEvidence.journalDigest(listOf(durable)), GitHubProviderReplayEvidence.reconciledJournalDigest(saved, listOf(durable)))
        assertNull(GitHubProviderReplayEvidence.reconciledJournalDigest(saved, listOf(durable.copy(projectionSha256 = "e".repeat(64)))))
    }

    @Test fun legacyWaitStillRequiresTheExactCheckpointAndUsesItsCurrentJournalKey() {
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
        }
        assertEquals("$scope:current", GitHubProviderToolCheckpoint.binding(scope, "current", expected(saved), saved))
        assertTrue(runCatching { GitHubProviderToolCheckpoint.binding(scope, "current", expected(saved), null) }.isFailure)
    }
}
