package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiReadProgressTest {
    private val head = "a".repeat(40)
    private fun logs(id: String, start: Int, end: Int, attempt: Int = 1, connection: String = "selected"): AgentToolExchange {
        val call = ProviderEvent.ToolCall(
            id,
            "github_ci__$connection",
            buildJsonObject {
                put("action", "logs")
                put("owner", "owner")
                put("repo", "repo")
                put("job_id", 29)
                put("run_id", 17)
                put("run_attempt", attempt)
                put("expected_head_sha", head)
                put("start_line", start)
                put("end_line", end)
            }
        )
        val data = buildJsonObject {
            put("head_sha", head)
            put("job_id", 29)
            put("run_id", 17)
            put("run_attempt", attempt)
            put("job_status", "completed")
            put("log_complete", true)
            put("truncated", false)
            put("has_more", false)
            put(
                "excerpts",
                JsonArray(
                    (start..end).map { line ->
                        buildJsonObject {
                            put("line", line)
                            put("start_offset", 0)
                            put("text", "Captured log line $line")
                        }
                    }
                )
            )
        }
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(id, ToolResultContent.Json(data), false, dispatched = true)))
    }

    @Test fun overlappingLogWindowsAdvanceOnlyTheUnseenCoverage() {
        val progress = GitHubCiReadProgress()
        fun observed(exchange: AgentToolExchange) = progress.observe(exchange.calls.single(), exchange.results.single())
        assertEquals(true, observed(logs("first", 3790, 3880)))
        assertEquals(true, observed(logs("wider", 3700, 3900)))
        assertEquals(false, observed(logs("inside", 3825, 3860)))
        assertEquals(true, observed(logs("tail", 3861, 3960)))
        assertEquals(false, observed(logs("again", 3800, 3860)))
        assertEquals(true, observed(logs("rerun", 3800, 3860, attempt = 2)))
        assertEquals(true, observed(logs("other-connection", 3800, 3860, connection = "another")))
    }

    @Test fun aCoveredPinnedLogReadReusesItsSourceWithoutSpendingARemoteCall() {
        val source = logs("source", 3700, 3900)
        val call = logs("covered", 3825, 3860).calls.single()
        val reused = requireNotNull(GitHubCiReadProgress.reused(call, listOf(source)))
        assertTrue(reused.sharedResult)
        assertFalse(reused.dispatched)
        assertFalse(reused.isError)
        assertEquals(JsonPrimitive("source"), reused.executionReceipt?.get("source_observation_call_id"))
        val body = (reused.content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive("OBSERVATION_REUSED"), body["status"])
        assertEquals(JsonPrimitive(3825), body["covered_start_line"])
        val plan = planToolBatch(listOf(call), 0, setOf(call.name), emptyMap(), emptySet(), 100000, reusableReadIds = setOf(call.callId)) { it.name }
        assertTrue(plan.executable.isEmpty())
        assertEquals(setOf(call.callId), plan.reusedReadIds)
        assertTrue(plan.acceptedByName.isEmpty())
    }

    @Test fun newerAttemptsLiveRefreshDifferentConnectionsAndUncoveredRangesCannotBeReused() {
        val source = logs("source", 3700, 3900)
        val call = logs("candidate", 3800, 3860).calls.single()
        assertNull(GitHubCiReadProgress.reused(call.copy(arguments = JsonObject(call.arguments - "run_attempt")), listOf(source)))
        assertNull(GitHubCiReadProgress.reused(call.copy(arguments = JsonObject(call.arguments + ("verify_live" to JsonPrimitive(true)))), listOf(source)))
        assertNull(GitHubCiReadProgress.reused(logs("new", 3800, 3860, 2).calls.single(), listOf(source)))
        assertNull(GitHubCiReadProgress.reused(logs("other", 3800, 3860, connection = "other").calls.single(), listOf(source)))
        assertNull(GitHubCiReadProgress.reused(logs("unread", 3861, 3960).calls.single(), listOf(source)))
        val truncated = source.results.single().let { result ->
            result.copy(content = ToolResultContent.Json(JsonObject(((result.content as ToolResultContent.Json).value as JsonObject) + ("truncated" to JsonPrimitive(true)))))
        }
        assertNull(GitHubCiReadProgress.reused(call, listOf(source.copy(results = listOf(truncated)))))
    }

    @Test fun selectedWorkspaceCiReadGetsItsInitialObservationBeforeTheRepeatLimit() {
        val progress = GitHubInvestigationProgress("Diagnose CI failures")
        val args = buildJsonObject { put("action", "workflows") }
        val data = ToolResultContent.Json(buildJsonObject { put("total_count", 57) })
        fun exchange(id: String) = AgentToolExchange(
            listOf(ProviderEvent.ToolCall(id, "github_ci", args)),
            listOf(AgentToolResult(id, data, false))
        )
        assertNull(progress.observe(exchange("first")))
        repeat(3) { assertNull(progress.observe(exchange("repeat-$it"))) }
        assertNotNull(progress.observe(exchange("fourth-repeat")))
    }

    @Test fun displayChangesCannotResetCanonicalReadLimitsOrSourceProgress() {
        val exchange = logs("source", 3700, 3900)
        val call = exchange.calls.single()
        assertEquals(toolRepeatKey(call), toolRepeatKey(call.copy(arguments = JsonObject(call.arguments + ("response_detail" to JsonPrimitive("compact"))))))
        val progress = GitHubInvestigationProgress("Diagnose CI failures")
        assertNull(progress.observe(exchange))
        repeat(3) { assertNull(progress.observe(logs("repeat-$it", 3800, 3860))) }
        assertNotNull(progress.observe(logs("last", 3825, 3860)))
    }

    @Test fun aPartialLongLogLineDoesNotCountAsCovered() {
        val source = logs("source", 1, 3)
        val result = source.results.single()
        val data = (result.content as ToolResultContent.Json).value as JsonObject
        val partial = result.copy(
            content = ToolResultContent.Json(
                JsonObject(
                    data + mapOf(
                        "has_more" to JsonPrimitive(true),
                        "continuation" to buildJsonObject {
                            put("start_line", 3)
                            put("start_offset", 200)
                        }
                    )
                )
            )
        )
        val retained = source.copy(results = listOf(partial))
        assertNotNull(GitHubCiReadProgress.reused(logs("covered", 1, 2).calls.single(), listOf(retained)))
        assertNull(GitHubCiReadProgress.reused(logs("partial", 3, 3).calls.single(), listOf(retained)))
    }

    @Test fun jobPageGeometryCannotReuseADifferentOrIncompleteInventory() {
        fun jobs(id: String, page: Int, perPage: Int): ProviderEvent.ToolCall = ProviderEvent.ToolCall(
            id,
            "github_ci__selected",
            buildJsonObject {
                put("action", "jobs")
                put("owner", "owner")
                put("repo", "repo")
                put("run_id", 17)
                put("expected_head_sha", head)
                put("run_attempt", 1)
                put("page", page)
                put("per_page", perPage)
            }
        )
        fun source(page: Int, total: Int): AgentToolExchange {
            val call = jobs("source", page, 20)
            return AgentToolExchange(
                listOf(call),
                listOf(
                    AgentToolResult(
                        call.callId,
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("head_sha", head)
                                put("run_id", 17)
                                put("run_attempt", 1)
                                put("page", page)
                                put("returned", 1)
                                put("total_count", total)
                                put("has_more", false)
                                put("complete", true)
                                put(
                                    "jobs",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("id", 29)
                                                put("status", "completed")
                                            }
                                        )
                                    )
                                )
                            }
                        ),
                        false,
                        dispatched = true
                    )
                )
            )
        }
        assertNull(GitHubCiReadProgress.reused(jobs("changed-page-size", 2, 50), listOf(source(2, 21))))
        assertNull(GitHubCiReadProgress.reused(jobs("incomplete", 1, 50), listOf(source(1, 2))))
        assertNotNull(GitHubCiReadProgress.reused(jobs("complete", 1, 50), listOf(source(1, 1))))
    }

    @Test fun newOffsetsOnALongLineRemainProgressWithoutFullLineCoverage() {
        val source = logs("long-line", 3, 3)
        val call = source.calls.single()
        val original = source.results.single()
        val data = (original.content as ToolResultContent.Json).value as JsonObject
        fun part(offset: Int) = original.copy(
            content = ToolResultContent.Json(
                JsonObject(
                    data + mapOf(
                        "excerpts" to JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("line", 3)
                                    put("start_offset", offset)
                                    put("text", "log text")
                                }
                            )
                        ),
                        "has_more" to JsonPrimitive(true),
                        "continuation" to buildJsonObject {
                            put("start_line", 3)
                            put("start_offset", offset + 8)
                        }
                    )
                )
            )
        )
        val progress = GitHubCiReadProgress()
        assertEquals(true, progress.observe(call, part(0)))
        assertEquals(false, progress.observe(call, part(0)))
        assertEquals(true, progress.observe(call, part(8)))
        assertEquals(false, progress.observe(call, part(8)))
        assertNull(GitHubCiReadProgress.reused(call, listOf(source.copy(results = listOf(part(0))))))
    }
}
