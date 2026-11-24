package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadRoundProgressTest {
    private val commit = "a".repeat(40)
    private val searchId = "b".repeat(64)

    @Test fun repeatedBranchPageDoesNotResetTheUnproductiveSourceReadCounter() {
        fun branches(id: Int, name: String = "main"): AgentToolExchange {
            val call = ProviderEvent.ToolCall("branch-$id", "github_context", Json.parseToJsonElement("""{"action":"branches","page":1}""") as JsonObject)
            val result = Json.parseToJsonElement("""{"branches":[{"name":"$name","commit":{"sha":"head"}}],"has_more":true}""")
            return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(result), false)))
        }
        val guard = GitHubReadRoundProgress()
        assertFalse(guard.replan(branches(1), 1))
        assertFalse(guard.replan(range(1, 1, 20), 1))
        assertFalse(guard.replan(branches(2), 1))
        assertFalse(guard.replan(range(2, 1, 20), 1))
        assertTrue(guard.replan(branches(3), 1))
        assertFalse(guard.replan(branches(4, "feature/new"), 1))
    }

    private fun exchange(id: Int, fields: String, tool: String = "github_read"): AgentToolExchange {
        val call = ProviderEvent.ToolCall("call-$id", tool, JsonObject(emptyMap()))
        val result = Json.parseToJsonElement("""{"commit_sha":"revision",$fields}""")
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(result), false)))
    }

    private fun range(id: Int, start: Int, end: Int) =
        exchange(id, """"items":[{"path":"src/A.kt","sha":"blob","start_line":$start,"end_line":$end,"range_complete":true}]""")

    @Test fun overlappingLineRequestsCannotDisguiseThreeUnproductiveRounds() {
        val guard = GitHubReadRoundProgress()
        assertFalse(guard.replan(range(1, 1, 20), 1))
        assertFalse(guard.replan(range(2, 20, 40), 1))
        assertFalse(guard.replan(range(3, 10, 35), 1))
        assertFalse(guard.replan(range(3, 10, 35), 1))
        assertFalse(guard.replan(range(4, 2, 8), 1))
        assertTrue(guard.replan(range(5, 5, 25), 1))
        assertFalse(guard.replan(range(6, 41, 60), 1))
    }

    @Test fun emptySearchPagesRequestOneReplanAndPreserveTheLatestExactFrontier() {
        val guard = GitHubReadRoundProgress()
        val replannedAt = mutableListOf<Int>()
        val receipts = linkedMapOf<String, JsonObject>()
        repeat(33) { index ->
            val page = searchPage(index, index * 4, (index + 1) * 4)
            if (guard.replan(page, 100)) replannedAt += index
            receipts["page-$index"] = buildJsonObject {
                put("trusted", true)
                put("success", true)
                put("tool", page.calls.single().name)
                put("arguments", page.calls.single().arguments)
                put("result", (page.results.single().content as ToolResultContent.Json).value)
            }
        }
        assertEquals(listOf(2), replannedAt)
        val retained = GitHubExecutionContinuation.searchFrontiers(receipts)
        assertEquals(JsonPrimitive(1), retained["observed_search_count"])
        val latest = (retained["searches"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("page-32"), latest["receipt_id"])
        val frontier = latest["frontier"] as JsonObject
        assertEquals(JsonPrimitive(132), frontier["interval_end"])
        assertEquals(JsonPrimitive(commit), frontier["commit_sha"])
        assertEquals(JsonPrimitive(searchId), frontier["search_id"])
        assertEquals(JsonPrimitive(false), frontier["complete"])
        assertEquals(JsonPrimitive(false), frontier["matches_complete"])
        assertEquals(JsonPrimitive(true), frontier["has_more"])
        val lastResult = receipts.getValue("page-32")["result"] as JsonObject
        assertEquals(lastResult["continuation_call"], frontier["continuation_call"])
        val instruction = GitHubExecutionContinuation.replanInstruction("Audit every source file", "Continue")
        assertTrue(instruction.contains("exact issued continuation"))
        assertTrue(instruction.contains("remaining budget allows it"))
    }

    @Test fun newSourcePathsAndNewMatchedLinesKeepUsefulSearchesMoving() {
        val guard = GitHubReadRoundProgress()
        repeat(10) { index ->
            val items = """[{"path":"src/File$index.kt","sha":"blob-$index"}]"""
            assertFalse(guard.replan(searchPage(index, index * 4, (index + 1) * 4, items), 50_000))
        }
        repeat(10) { index ->
            val items = """[{"path":"src/File0.kt","sha":"blob-0","matches":[{"line":${index + 1},"text":"needle"}]}]"""
            assertFalse(guard.replan(searchPage(index + 10, (index + 10) * 4, (index + 11) * 4, items), 50_000))
        }
    }

    @Test fun aNewSourceWindowRestartsTheUsefulProgressBudget() {
        val guard = GitHubReadRoundProgress()
        assertFalse(guard.replan(range(1, 1, 20), 1))
        assertFalse(guard.replan(searchPage(2, 0, 4), 20_000))
        assertFalse(guard.replan(searchPage(3, 4, 8), 20_000))
        assertFalse(guard.replan(range(4, 21, 40), 50_000))
        assertFalse(guard.replan(searchPage(5, 8, 12), 20_000))
        assertFalse(guard.replan(searchPage(6, 12, 16), 20_000))
        assertTrue(guard.replan(searchPage(7, 16, 20), 20_000))
    }

    @Test fun emptySearchWorkIsBoundedByElapsedTimeWithoutCountingReplayedCallsTwice() {
        val guard = GitHubReadRoundProgress()
        val first = searchPage(1, 0, 4)
        assertFalse(guard.replan(first, 40_000))
        assertFalse(guard.replan(first, 40_000))
        assertTrue(guard.replan(searchPage(2, 4, 8), 20_000))
        assertFalse(guard.replan(searchPage(3, 8, 12), Long.MAX_VALUE))
        assertTrue(GitHubReadRoundProgress().replan(first, Long.MAX_VALUE))
    }

    @Test fun changingAnEmptyQueryDoesNotCreateUsefulEvidence() {
        val guard = GitHubReadRoundProgress()
        repeat(3) { index ->
            val page = searchPage(index, 0, 4, query = "needle-$index")
            assertEquals(index == 2, guard.replan(page, 100))
        }
    }

    @Test fun waitingForCiDoesNotConsumeTheSourceReplanBudget() {
        val guard = GitHubReadRoundProgress()
        assertFalse(guard.replan(searchPage(1, 0, 4), 100))
        assertFalse(guard.replan(searchPage(2, 4, 8), 100))
        assertFalse(guard.replan(exchange(3, """"status":"waiting"""", "github_ci"), 120_000))
        assertFalse(guard.replan(searchPage(4, 8, 12), 100))
        assertFalse(guard.replan(searchPage(5, 12, 16), 100))
        assertTrue(guard.replan(searchPage(6, 16, 20), 100))
    }

    @Test fun metadataPresentationChangesDoNotRestartTheUsefulSourceBudget() {
        val guard = GitHubReadRoundProgress()
        repeat(4) { index ->
            val call = ProviderEvent.ToolCall(
                "resolve-$index",
                if (index % 2 == 0) "github_context__selected" else "github_context_inspect__selected",
                buildJsonObject {
                    put("action", if (index % 2 == 0) "resolve" else "status")
                    put("verify_live", index % 2 == 0)
                    put("response_detail", if (index % 2 == 0) "full" else "compact")
                }
            )
            val data = buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
                put("ref", "main")
                put("commit_sha", commit)
                put("tree_sha", "b".repeat(40))
            }
            val exchange = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(data), false)))
            assertEquals(index == 3, guard.replan(exchange, 1))
        }
    }

    @Test fun equivalentFailedReadsTriggerOneReplanWithoutReplayingWritesOrBreakingAChangedSelector() {
        fun failure(id: String, path: String, tool: String = "github_read"): AgentToolExchange {
            val call = ProviderEvent.ToolCall(
                id,
                tool,
                buildJsonObject {
                    put("action", "ranges")
                    put("path", path)
                }
            )
            return AgentToolExchange(listOf(call), listOf(AgentToolResult(id, ToolResultContent.Text("gone"), true, errorCode = "GITHUB_NOT_FOUND_OR_INACCESSIBLE")))
        }
        val active = GitHubReadRoundProgress()
        assertFalse(active.replan(failure("one", "A.kt"), 1))
        assertFalse(active.replan(failure("two", "B.kt"), 1))
        assertFalse(active.replan(failure("two", "B.kt"), 1))
        assertTrue(active.replan(failure("three", "B.kt"), 1))
        assertFalse(active.replan(failure("four", "B.kt"), 1))
        val legacy = GitHubReadRoundProgress(detectFailedReads = false)
        val writes = GitHubReadRoundProgress()
        repeat(4) {
            assertFalse(legacy.replan(failure(it.toString(), "A.kt"), 1))
            assertFalse(writes.replan(failure(it.toString(), "A.kt", "github_commit"), 1))
        }
    }

    private fun searchPage(id: Int, start: Int, end: Int, items: String = "[]", query: String = "needle"): AgentToolExchange {
        val pageSearchId = if (query == "needle") searchId else GitHubRepositoryStateStore.digest(query)
        val rows = Json.parseToJsonElement(items) as JsonArray
        val arguments = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            put("query", query)
            put("mode", "literal")
            put("commit_sha", commit)
            put("search_id", pageSearchId)
            put("index_cursor", start)
        }
        val call = ProviderEvent.ToolCall("search-$id", "github_search", arguments)
        val result = buildJsonObject {
            put("commit_sha", commit)
            put("search_id", pageSearchId)
            put("interval_start", start)
            put("interval_end", end)
            put("total_candidate_files", 400)
            put("items", rows)
            put("returned", rows.size)
            put("complete", false)
            put("matches_complete", false)
            put("coverage_from_start", start == 0)
            put("has_more", true)
            put("stop_reason", "download_budget")
            put(
                "continuation_call",
                buildJsonObject {
                    put("tool", call.name)
                    put("arguments", JsonObject(arguments + ("index_cursor" to JsonPrimitive(end))))
                }
            )
        }
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(result), false)))
    }
}
