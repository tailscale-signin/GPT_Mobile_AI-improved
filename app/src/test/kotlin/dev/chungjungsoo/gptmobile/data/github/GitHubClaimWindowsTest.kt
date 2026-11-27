package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClaimWindowsTest {
    private val metadata = buildJsonObject {
        put("binding", "accepted-task")
        put("context", "receipt-snapshot")
        put("plan", "complete-plan")
        put("authorization", "accepted-policy")
        put("draft", "Saved candidate")
        put("pending", true)
    }

    @Test fun everyCriterionAndVerifiedBatchSurvivesBoundedWindowsAndRestart() = runBlocking {
        for (criteria in listOf(33, 64, 169)) {
            val requirements = (1..criteria).map { "criterion-$it" }.chunked(GitHubClaimBatches.REQUIREMENTS_PER_BATCH).map { it.toSet() }
            val completed = requirements.mapIndexed { index, _ -> if (index < 9) buildJsonObject { put("verified_batch", index) } else null }
            val saved = mutableMapOf<String, JsonObject>()
            val manifest = GitHubClaimWindows.retain(metadata, completed, requirements) { id, window -> saved[id] = window }
            assertEquals(JsonPrimitive("github.claim-extraction.v2"), manifest["schema"])
            assertTrue(saved.values.all { it.getValue("batches").jsonArray.size <= GitHubClaimBatches.MAX_BATCHES })
            assertEquals(criteria, manifest.getValue("batch_requirement_ids").jsonArray.sumOf { it.jsonArray.size })
            val restored = requireNotNull(GitHubClaimWindows.restore(manifest) { saved[it] })
            assertEquals(JsonArray(completed.map { it ?: JsonNull }), restored["batches"])
            assertEquals(JsonPrimitive(minOf(9, completed.size) + 1), restored["next_batch"])
            val final = completed.mapIndexed { index, batch -> batch ?: buildJsonObject { put("verified_batch", index) } }
            val finished = GitHubClaimWindows.retain(JsonObject(metadata + ("pending" to JsonPrimitive(false))), final, requirements) { id, window -> saved[id] = window }
            val restarted = requireNotNull(GitHubClaimWindows.restore(finished) { saved[it] })
            assertEquals(JsonPrimitive(requirements.size + 1), restarted["next_batch"])
            assertEquals(JsonPrimitive(false), restarted["pending"])
            assertEquals(JsonArray(final), restarted["batches"])
        }
    }

    @Test fun interruptionBeforeCursorCommitLeavesPreviousManifestReadable() = runBlocking {
        val requirements = (1..64).map { "criterion-$it" }.chunked(4).map { it.toSet() }
        val saved = mutableMapOf<String, JsonObject>()
        val original = GitHubClaimWindows.retain(metadata, List(requirements.size) { null }, requirements) { id, window -> saved[id] = window }
        var writes = 0
        val interrupted = runCatching {
            GitHubClaimWindows.retain(metadata, List(requirements.size) { buildJsonObject { put("verified_batch", it) } }, requirements) { id, window ->
                saved[id] = window
                if (++writes == 1) error("simulated process loss before manifest commit")
            }
        }
        assertTrue(interrupted.isFailure)
        val restored = requireNotNull(GitHubClaimWindows.restore(original) { saved[it] })
        assertEquals(JsonPrimitive(1), restored["next_batch"])
        assertTrue(restored.getValue("batches").jsonArray.all { it == JsonNull })
    }

    @Test fun corruptedMissingOrReboundWindowsCannotLendCompletedEvidence() = runBlocking {
        val requirements = (1..33).map { "criterion-$it" }.chunked(4).map { it.toSet() }
        val saved = mutableMapOf<String, JsonObject>()
        val manifest = GitHubClaimWindows.retain(metadata, List(requirements.size) { buildJsonObject { put("verified_batch", it) } }, requirements) { id, window -> saved[id] = window }
        assertNull(GitHubClaimWindows.restore(manifest) { null })
        assertNull(GitHubClaimWindows.restore(manifest) { id -> saved[id]?.let { JsonObject(it + ("binding" to JsonPrimitive("other-task"))) } })
        assertNull(GitHubClaimWindows.restore(JsonObject(manifest + ("context" to JsonPrimitive("changed-receipts")))) { saved[it] })
        assertNull(GitHubClaimWindows.restore(JsonObject(manifest + ("next_batch" to JsonPrimitive(1)))) { saved[it] })
        assertNull(GitHubClaimWindows.restore(JsonObject(manifest + ("authorization" to JsonPrimitive("changed-policy")))) { saved[it] })
        assertEquals(requirements.size + 1, (manifest["next_batch"] as JsonPrimitive).intOrNull)
    }
}
