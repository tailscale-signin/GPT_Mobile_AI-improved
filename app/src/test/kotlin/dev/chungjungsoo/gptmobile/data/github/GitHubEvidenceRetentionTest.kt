package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEvidenceRetentionTest {
    private val sha = "a".repeat(40)

    @Test fun legacyInventoryMigrationRetainsDispatchCountsWithoutRewritingTheOriginalReceipt() = runBlocking {
        val id = "ev_" + GitHubEvidenceBook.digest("task|legacy").take(24)
        val original = Json.parseToJsonElement("""{"receipt_id":"$id","call_id":"legacy","binding":"task","repository":"owner/repo","tool":"github_ci","trusted":true,"success":true,"arguments":{"action":"runs","commit_sha":"$sha","page":1},"result":{"observationTruncated":true,"excerpt":{"workflow_runs":[{"id":7,"head_sha":"$sha"}]}},"execution":{"schema":"github.dispatch.v1","pagination_progress":{"schema":"github.pagination-progress.v1","remote_page":1,"upstream_rows_returned":30,"reported_total":67}}}""").jsonObject
        val saved = linkedMapOf(id to original)
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_ci"), load = { saved[it] }, save = { key, row -> saved[key] = row }, retainedIds = { listOf(id) })
        book.restore()
        val restored = book.snapshot().first.getValue(id)
        assertEquals(JsonPrimitive(30), restored.getValue("inventory_observation").jsonObject["fetched"])
        assertEquals(JsonPrimitive(false), restored.getValue("inventory_observation").jsonObject["record_keys_complete"])
        assertEquals(original, saved.getValue(id))
        assertEquals(original["result"], restored["result"])
    }

    @Test fun manifestOffsetsLengthsAndChunkHashesMustAgreeBeforeRestorationOrPaging() = runBlocking {
        val id = "ev_" + GitHubEvidenceBook.digest("task|chunked").take(24)
        val original = Json.parseToJsonElement("""{"receipt_id":"$id","call_id":"chunked","binding":"task","repository":"owner/repo","tool":"github_read","trusted":true,"success":true,"arguments":{},"result":{"head_sha":"$sha"}}""").jsonObject
        val text = original.toString()
        val hash = GitHubEvidenceBook.digest(text)
        val key = "original_${id}_${hash.take(24)}_0"
        val reference = buildJsonObject {
            put("id", key)
            put("offset_utf16", 0)
            put("characters", text.length)
            put("sha256", hash)
        }
        val chunk = buildJsonObject {
            put("binding", "task")
            put("receipt_id", id)
            put("index", 0)
            put("offset_utf16", 0)
            put("sha256", hash)
            put("text", text)
        }
        fun manifest(ref: JsonObject) = JsonObject(
            original + buildJsonObject {
                put("original_chunks", JsonArray(listOf(ref)))
                put("original_receipt_sha256", hash)
                put("original_receipt_characters", text.length)
            }
        )
        val saved = linkedMapOf(id to manifest(reference), key to chunk)
        fun book() = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_read"), load = { saved[it] }, retainedIds = { listOf(id) })
        val valid = book()
        valid.restore()
        assertTrue(valid.complete())
        assertNotNull(valid.originalPage(id, 0))
        for (mismatch in listOf("offset_utf16" to JsonPrimitive(1), "characters" to JsonPrimitive(text.length + 1))) {
            saved[id] = manifest(JsonObject(reference + mismatch))
            val damaged = book()
            damaged.restore()
            assertFalse(damaged.complete())
            assertEquals(null, damaged.originalPage(id, 0))
        }
        saved[id] = manifest(reference)
        saved[key] = JsonObject(chunk + ("sha256" to JsonPrimitive("b".repeat(64))))
        val damagedHash = book()
        damagedHash.restore()
        assertFalse(damagedHash.complete())
        assertEquals(null, damagedHash.originalPage(id, 0))
    }

    @Test fun liveReadOnlyReconciliationGetsAnObservationTimeWithoutClaimingAnotherWrite() = runBlocking {
        val book = GitHubEvidenceBook("task", "owner/repo", "work", setOf("github_write"), now = { 1000 })
        suspend fun observe(id: String, execution: JsonObject?, shared: Boolean = false) {
            book.observe(
                ProviderEvent.ToolCall(
                    id,
                    "github_write",
                    buildJsonObject {
                        put("action", "create_branch")
                        put("branch", "work")
                    }
                ),
                AgentToolResult(
                    id,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("head_sha", sha)
                            put("reconciled", true)
                        }
                    ),
                    false,
                    dispatched = false,
                    sharedResult = shared,
                    executionReceipt = execution
                )
            )
        }
        observe(
            "live",
            buildJsonObject {
                put("read_requests", 1)
                put("phase", "OBSERVED")
            }
        )
        observe(
            "cached",
            buildJsonObject {
                put("cached_read_observations", 1)
                put("source_observed_at_ms", 123)
            }
        )
        observe("shared", buildJsonObject { put("read_requests", 1) }, shared = true)
        observe("local", null)
        val rows = book.snapshot().first.values.associateBy { it.getValue("call_id").jsonPrimitive.content }
        assertEquals(JsonPrimitive(1000), rows.getValue("live")["observed_at_ms"])
        assertEquals(JsonPrimitive("LIVE"), rows.getValue("live")["evidence_origin"])
        assertEquals(JsonPrimitive(false), rows.getValue("live")["dispatched"])
        assertEquals(JsonPrimitive(123), rows.getValue("cached")["observed_at_ms"])
        assertEquals(JsonPrimitive(0), rows.getValue("shared")["observed_at_ms"])
        assertEquals(JsonPrimitive(0), rows.getValue("local")["observed_at_ms"])
    }

    @Test fun oversizedReadHistoryFitsReviewBudgetWithoutDiscardingMutationErrorsOrSourceIds() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook(
            "task",
            "owner/repo",
            "work",
            setOf("github_release", "github_commit"),
            load = { saved[it] },
            save = { id, row -> saved[id] = row },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } },
            retentionLimitBytes = 128 * 1024
        )
        val first = book()
        val original = buildJsonObject {
            put("id", 42)
            put("commit_sha", sha)
            put("body", "release note ".repeat(9000))
        }
        repeat(20) { index ->
            val call = ProviderEvent.ToolCall("read-$index", "github_release", buildJsonObject { put("action", "detail") })
            first.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(original), false))
        }
        val mutation = ProviderEvent.ToolCall("commit", "github_commit", buildJsonObject { put("branch", "work") })
        first.observe(mutation, AgentToolResult("commit", ToolResultContent.Json(buildJsonObject { put("commit_sha", sha) }), false))
        val failure = ProviderEvent.ToolCall("failed", "github_release", buildJsonObject { put("action", "status") })
        first.observe(failure, AgentToolResult("failed", ToolResultContent.Json(buildJsonObject { put("error", "denied") }), true, errorCode = "HTTP_403"))
        assertTrue(first.complete())
        assertEquals(22, first.snapshot().first.size)
        assertTrue(saved.values.sumOf { it.toString().encodeToByteArray().size.toLong() } > 128 * 1024)
        assertTrue(first.snapshot().first.values.sumOf { it.toString().encodeToByteArray().size } < 128 * 1024)
        val restored = book()
        restored.restore()
        assertTrue(restored.complete())
        val receipts = restored.snapshot().first.values
        assertEquals(22, receipts.size)
        assertTrue(receipts.any { it["call_id"] == JsonPrimitive("commit") && it.getValue("result").jsonObject["commit_sha"] == JsonPrimitive(sha) })
        assertTrue(receipts.any { it["error_code"] == JsonPrimitive("HTTP_403") && it["success"] == JsonPrimitive(false) })
        val read = receipts.first()
        assertEquals(JsonPrimitive(42), read.getValue("result").jsonObject["id"])
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(original.toString())), read["result_sha256"])
        assertNotNull(restored.originalPage(read.getValue("receipt_id").jsonPrimitive.content, 0))
    }

    @Test fun sourceAboveWorkspaceRecordLimitReassemblesAfterRestartWithIndependentHashes() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook(
            "task",
            "owner/repo",
            "work",
            setOf("github_release"),
            load = { saved[it] },
            save = { id, row ->
                assertTrue("Each existing workspace record remains bounded", row.toString().encodeToByteArray().size <= 1_500_000)
                saved[id] = row
            },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } }
        )
        val original = buildJsonObject {
            put("id", 9_007_199_254_740_993L)
            put("body", "quote \" \\ \n🍁".repeat(100_000))
            put("head_sha", sha)
        }
        val first = book()
        first.observe(ProviderEvent.ToolCall("release", "github_release", buildJsonObject { put("action", "detail") }), AgentToolResult("release", ToolResultContent.Json(original), false))
        val restored = book()
        restored.restore()
        val id = restored.snapshot().first.keys.single()
        val text = StringBuilder()
        var page = 0
        while (true) {
            val chunk = requireNotNull(restored.originalPage(id, page++))
            assertEquals(JsonPrimitive(text.length), chunk["offset_utf16"])
            val body = chunk.getValue("text").jsonPrimitive.content
            assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(body)), chunk["sha256"])
            text.append(body)
            if (chunk["complete"] == JsonPrimitive(true)) {
                assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(text.toString())), chunk["original_receipt_sha256"])
                break
            }
            assertTrue(page < 64)
        }
        assertTrue(page > 1)
        assertEquals(original, Json.parseToJsonElement(text.toString()).jsonObject["result"])
        val sourceKey = saved.keys.single { it.startsWith("original_${id}_") && it.endsWith("_0") }
        saved[sourceKey] = JsonObject(saved.getValue(sourceKey) + ("text" to JsonPrimitive("corrupted")))
        assertEquals(null, restored.originalPage(id, 0))
        assertFalse(restored.snapshot().first.values.single()["review_projection_complete"] == JsonPrimitive(true))
        val damaged = book()
        damaged.restore()
        assertFalse("Missing or changed original chunks cannot become complete evidence", damaged.complete())
    }
}
