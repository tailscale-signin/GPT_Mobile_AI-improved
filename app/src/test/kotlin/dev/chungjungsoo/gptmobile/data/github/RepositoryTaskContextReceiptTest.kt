package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryTaskContextReceiptTest {
    private val sha = "a".repeat(40)
    private fun receipt() = Json.parseToJsonElement("""{"schema":"repository.task_context.v1","source_sha":"$sha","source_current":true,"variant":"TASK","bytes":2100,"token_count":null,"selected_ranges":[{"path":"src/Voice.kt","blob_sha":"${"b".repeat(40)}","start_line":30,"end_line":48}],"documents":[],"test_candidates":[{"path":"test/VoiceTest.kt","status":"NOT_RUN"}],"coverage":{"loaded_files":2,"eligible_files":8000,"exhaustive":false},"cache":{"cached_files":1,"cache_hit":false}}""") as JsonObject

    @Test fun selectedRangesRetainExactSourceAndUnknownTokenStatus() {
        val result = requireNotNull(RepositoryTaskContextReceipt.from(receipt(), sha))
        assertTrue(result.current)
        assertEquals(listOf("src/Voice.kt:30–48 · ${"b".repeat(12)}"), result.sources)
        assertTrue(result.summary.any { "token count unavailable" in it })
        assertTrue(result.summary.any { "selected coverage" in it })
        assertTrue(result.summary.any { "does not mean the tests ran or passed" in it })
    }

    @Test fun phonePathCandidatesRetainTheirUnexecutedCount() {
        val paths = JsonArray(listOf(JsonPrimitive("test/VoiceTest.kt"), JsonPrimitive("../private.txt")))
        val result = requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(receipt() + ("test_candidates" to paths)), sha))
        assertTrue(result.summary.any { "1 test candidates" in it })
    }

    @Test fun numericEstimatesAndUnverifiedStatusesCannotClaimMeasuredTokens() {
        for (status in listOf(null, "NOT_MEASURED", "CONSERVATIVE_UTF8_BOUND")) {
            val fields = receipt() + ("token_count" to JsonPrimitive(123)) +
                if (status == null) emptyMap() else mapOf("token_count_status" to JsonPrimitive(status))
            val result = requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(fields), sha))
            assertTrue(result.summary.any { "token count unavailable" in it })
            assertFalse(result.summary.any { "measured tokens" in it })
        }
        val measured = receipt() + mapOf(
            "token_count" to JsonPrimitive(123),
            "token_count_status" to JsonPrimitive("MEASURED_SELECTED_TOKENIZER"),
            "token_scope" to JsonPrimitive("CONTEXT_WITH_USER_MESSAGE_FRAMING")
        )
        assertTrue(requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(measured), sha)).summary.any { "123 measured tokens including user message framing" in it })
        for (invalid in listOf(JsonPrimitive("123"), JsonPrimitive(-1))) {
            assertTrue(requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(measured + ("token_count" to invalid)), sha)).summary.any { "token count unavailable" in it })
        }
    }

    @Test fun staleCommitCannotDisplayRetainedPathsAsCurrentContext() {
        val result = requireNotNull(RepositoryTaskContextReceipt.from(receipt(), "c".repeat(40)))
        assertFalse(result.current)
        assertTrue(result.sources.isEmpty())
        assertEquals(1, result.summary.size)
        val stale = JsonObject(receipt() + ("source_current" to JsonPrimitive(false)))
        assertFalse(requireNotNull(RepositoryTaskContextReceipt.from(stale, sha)).current)
    }

    @Test fun semanticChoiceShowsActualRankingAndPolicyFallback() {
        val semantic = receipt() + ("variant" to JsonPrimitive("SEMANTIC"))
        for (applied in listOf(mapOf("semantic_applied" to JsonPrimitive(true)), mapOf("retrieval" to JsonPrimitive("LOCAL_SEMANTIC_AND_LEXICAL")))) {
            assertTrue(requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(semantic + applied), sha)).summary.any { "Local similarity and lexical ranking applied" in it })
        }
        val sessionOnly = semantic + mapOf("semantic_applied" to JsonPrimitive(false), "semantic_fallback" to JsonPrimitive("SESSION_ONLY_MEMORY_POLICY"))
        assertTrue(requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(sessionOnly), sha)).summary.any { "skipped for session-only memory" in it })
        assertTrue(requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(semantic), sha)).summary.any { "unavailable or deferred" in it })
        assertFalse(requireNotNull(RepositoryTaskContextReceipt.from(JsonObject(semantic + ("semantic_applied" to JsonPrimitive("true"))), sha)).summary.any { "Local similarity and lexical ranking applied" in it })
    }

    @Test fun unsupportedReceiptCannotBecomeSourceEvidence() {
        assertNull(RepositoryTaskContextReceipt.from(JsonObject(receipt() + ("schema" to JsonPrimitive("unknown"))), sha))
        assertNull(RepositoryTaskContextReceipt.from(JsonObject(receipt() + ("source_sha" to JsonPrimitive("main"))), sha))
    }
}
