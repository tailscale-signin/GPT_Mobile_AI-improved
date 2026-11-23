package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ToolArgumentNormalizationTest {
    @Test fun rejectedRevisionRemainsFailedAfterRepositoryBinding() {
        val arguments = buildJsonObject {
            put("owner", "selected")
            put("repo", "repository")
            put("commit_sha", "c".repeat(40))
            put("evidence_id", "retained-source")
        }
        val rejected = AgentToolResult("read", ToolResultContent.Text("Revision choice required"), true, errorCode = "EVIDENCE_REVISION_MISMATCH", dispatched = false)
        val observed = ToolArgumentNormalization.retain(rejected, arguments, ToolArgumentNormalization.Kind.REPOSITORY_BINDING)
        assertEquals(rejected.errorCode, observed.errorCode)
        assertEquals(rejected.content, observed.content)
        assertFalse(observed.dispatched)
        assertFalse(observed.executionReceipt.orEmpty().containsKey("corrected_arguments"))
        assertEquals(arguments, observed.executionReceipt?.get("canonical_arguments"))
        val normalization = requireNotNull(observed.executionReceipt?.get("argument_normalization")).jsonObject
        assertEquals(JsonPrimitive(false), normalization["semantic_repair"])
        assertEquals(JsonPrimitive("FAILED"), normalization["outcome"])
    }

    @Test fun outerBindingCannotReplaceTheInnerExecutorsCorrectedOrCanonicalArguments() {
        for (key in listOf("corrected_arguments", "canonical_arguments")) {
            val inner = AgentToolResult(
                "read",
                ToolResultContent.Text("Retained inner outcome"),
                false,
                executionReceipt = buildJsonObject {
                    put(key, buildJsonObject { put("commit_sha", "a".repeat(40)) })
                }
            )
            assertSame(inner, ToolArgumentNormalization.retain(inner, buildJsonObject { put("commit_sha", "c".repeat(40)) }, ToolArgumentNormalization.Kind.SCHEMA_CANONICALIZATION))
        }
    }
}
