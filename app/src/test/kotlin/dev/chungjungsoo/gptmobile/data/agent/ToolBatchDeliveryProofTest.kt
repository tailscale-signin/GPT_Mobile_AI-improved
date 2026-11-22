package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ToolBatchDeliveryProofTest {
    private val name = "github_context__selected"
    private val tool = GitHubOperationTool.definition("github_context", name)
    private fun arguments(offset: Int) = buildJsonObject { put("view_cursor", "retained:$offset:1536") }
    private fun recipe(offset: Int) = buildJsonObject {
        put("tool", name)
        put("arguments", arguments(offset))
    }
    private fun call() = ProviderEvent.ToolCall("saved-page", name, arguments(50))
    private fun result() = AgentToolResult(
        call().callId,
        ToolResultContent.Json(
            buildJsonObject {
                put("schema", "github.operation.v1")
                put("operation", "github_context")
                put("fragments", JsonArray(listOf(buildJsonObject { put("value", "source".repeat(800)) })))
                put("fragment_offset", 50)
                put("fragment_end", 123)
                put("total_fragments", 131)
                put("recovery_call", recipe(50))
                put("continuation_call", recipe(123))
            }
        ),
        false
    )
    private fun next(snapshot: ToolBatchRecoverySnapshot): JsonObject {
        val state = GitHubReplayRecovery.State(snapshot.completedExchanges.flatMap { it.calls })
        state.observeDelivered(snapshot.completedExchanges, snapshot.deliveredResultDigests)
        return state.advanceSavedRead(ProviderEvent.ToolCall("saved-result-consume-planning", name, arguments(50)), tool).arguments
    }
    private suspend fun stored(file: File, result: AgentToolResult = result()): ToolBatchCheckpoint {
        val journal = ToolBatchCheckpoint(file, "same-grant")
        journal.enqueue(listOf(call()))
        journal.execute(call()) { result }
        journal.finish()
        return journal
    }

    @Test fun ingressCompactionCannotPromoteSummaryDeliveryToTheOriginalIntervalAfterRestart() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val original = result()
            val journal = stored(file, original)
            assertEquals(original.content, journal.recoverySnapshot().completedExchanges.single().results.single().content)
            val compact = original.copy(content = requireNotNull(GitHubReplayRecovery.compact(original.content, 2000)))
            assertFalse((compact.content as ToolResultContent.Json).value.jsonObject.containsKey("fragments"))
            // collectRound may consume this exact ingress projection, while the
            // checkpoint retains a different, fuller payload under the same ID.
            journal.markConsumed(setOf(original.callId), ToolResultDeliveryProof.capture(listOf(compact)))
            val restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(setOf(original.callId), restarted.consumedCallIds)
            assertTrue(restarted.deliveredResultDigests.isEmpty())
            assertEquals(arguments(50), next(restarted))
            assertUnreadReplay(restarted)
        } finally {
            file.delete()
        }
    }

    @Test fun exactDeliveredProjectionSurvivesRestartAndLaterSummariesCannotEraseItsProof() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val original = result().copy(retainedContent = ToolResultContent.Text("Original source outside the displayed page"))
            val journal = stored(file, original)
            val proof = ToolResultDeliveryProof.capture(listOf(original))
            journal.markConsumed(setOf(original.callId), proof)
            var restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(proof, restarted.deliveredResultDigests)
            assertEquals(arguments(123), next(restarted))
            val compact = original.copy(content = requireNotNull(GitHubReplayRecovery.compact(original.content, 2000)))
            journal.markConsumed(setOf(original.callId), ToolResultDeliveryProof.capture(listOf(compact)))
            restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(proof, restarted.deliveredResultDigests)
            assertEquals(arguments(123), next(restarted))
        } finally {
            file.delete()
        }
    }

    @Test fun legacyConsumptionIdsRemainCompatibleWithoutGrantingIntervalCoverage() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val journal = stored(file)
            journal.markConsumed(setOf(call().callId))
            val legacy = Json.parseToJsonElement(file.readText()).jsonObject
            file.writeText(JsonObject(legacy - "deliveredResultDigests").toString())
            val restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(setOf(call().callId), restarted.consumedCallIds)
            assertTrue(restarted.deliveredResultDigests.isEmpty())
            assertEquals(arguments(50), next(restarted))
            assertUnreadReplay(restarted)
        } finally {
            file.delete()
        }
    }

    @Test fun failedUnknownAndMismatchedPayloadsNeverAcquireDeliveryProof() = runBlocking {
        for (failed in listOf(
            result().copy(isError = true),
            result().copy(errorCode = "GITHUB_OUTCOME_UNKNOWN"),
            result().copy(executionReceipt = buildJsonObject { put("requires_reconciliation", true) })
        )) {
            val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
            try {
                val journal = stored(file, failed)
                assertTrue(ToolResultDeliveryProof.capture(listOf(failed)).isEmpty())
                journal.markConsumed(setOf(failed.callId, "unknown"), mapOf(failed.callId to ToolResultDeliveryProof.digest(failed.content), "unknown" to "a".repeat(64)))
                val snapshot = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
                assertEquals(setOf(failed.callId), snapshot.consumedCallIds)
                assertTrue(snapshot.deliveredResultDigests.isEmpty())
                assertEquals(arguments(50), next(snapshot))
            } finally {
                file.delete()
            }
        }
    }

    @Test fun malformedAndOversizedProofProposalsLeaveTheJournalUnchanged() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val journal = stored(file)
            val before = file.readText()
            for (proof in listOf(
                mapOf(call().callId to "not-a-digest"),
                mapOf("outside-delivery" to "a".repeat(64)),
                (0..ToolResultDeliveryProof.MAX_RESULTS).associate { "id-$it" to "a".repeat(64) }
            )) {
                assertTrue(runCatching { journal.markConsumed(setOf(call().callId), proof) }.isFailure)
                assertEquals(before, file.readText())
            }
        } finally {
            file.delete()
        }
    }

    @Test fun malformedPersistedProofIsRejectedBeforeReconstruction() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val original = result()
            val journal = stored(file, original)
            val digest = ToolResultDeliveryProof.digest(original.content)
            journal.markConsumed(setOf(original.callId), mapOf(original.callId to digest))
            val valid = Json.parseToJsonElement(file.readText()).jsonObject
            val proofs = listOf(
                buildJsonObject { put(original.callId, "not-a-digest") },
                buildJsonObject { put(original.callId, "a".repeat(64)) },
                buildJsonObject { put("unknown", digest) },
                JsonObject((0..ToolResultDeliveryProof.MAX_RESULTS).associate { "id-$it" to JsonPrimitive(digest) })
            )
            for (proof in proofs) {
                file.writeText(JsonObject(valid + ("deliveredResultDigests" to proof)).toString())
                assertTrue(runCatching { ToolBatchCheckpoint(file, "same-grant").recoverySnapshot() }.isFailure)
            }
            file.writeText(JsonObject(valid + ("consumedCallIds" to JsonArray(emptyList()))).toString())
            assertTrue(runCatching { ToolBatchCheckpoint(file, "same-grant").recoverySnapshot() }.isFailure)
            val entries = valid.getValue("entries").jsonObject
            val entry = entries.getValue(original.callId).jsonObject
            val result = entry.getValue("result").jsonObject
            for (changed in listOf(
                "error" to JsonPrimitive(true),
                "code" to JsonPrimitive("GITHUB_OUTCOME_UNKNOWN"),
                "execution" to buildJsonObject { put("requires_reconciliation", true) }
            )) {
                val altered = JsonObject(entry + ("result" to JsonObject(result + changed)))
                file.writeText(JsonObject(valid + ("entries" to JsonObject(entries + (original.callId to altered)))).toString())
                assertTrue(runCatching { ToolBatchCheckpoint(file, "same-grant").recoverySnapshot() }.isFailure)
            }
        } finally {
            file.delete()
        }
    }

    private fun assertUnreadReplay(snapshot: ToolBatchRecoverySnapshot) {
        val consumed = ToolResultDeliveryProof.qualifiedConsumed(snapshot.completedExchanges, snapshot.consumedCallIds, snapshot.deliveredResultDigests)
        assertTrue(consumed.isEmpty())
        val replay = ToolExchangeCompactor.compact(snapshot.completedExchanges, 1024, 512, consumed)
        val body = requireNotNull(GitHubReplayRecovery.envelope(replay.single().results.single().content))
        assertEquals(JsonPrimitive(false), body["previously_delivered"])
        assertEquals(arguments(50), (body["continuation_call"] as JsonObject)["arguments"])
        assertTrue(body["prior_delivery_scope"] != JsonPrimitive("FULL_RESULT"))
    }
}
