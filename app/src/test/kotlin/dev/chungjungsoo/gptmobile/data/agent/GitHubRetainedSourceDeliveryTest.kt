package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
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

class GitHubRetainedSourceDeliveryTest {
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

    @Test fun anUnreadRetainedLogReopensItsIssuedLocalPageAfterTheSourceLeavesReplay() {
        val source = logs("source", 1, 200)
        val originalCall = source.calls.single()
        val views = GitHubResultViews()
        val presented = views.present("selected", "github_ci", originalCall.name, source.results.single(), originalCall.arguments)
        assertEquals(JsonPrimitive(true), presented.executionReceipt?.get("retained_original_complete"))
        val history = listOf(source.copy(results = listOf(presented)))
        val call = logs("covered", 95, 100).calls.single()
        val reused = requireNotNull(GitHubCiReadProgress.reused(call, history))
        val body = (reused.content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(false), body["source_projection_delivered"])
        assertEquals(presented.executionReceipt?.get("saved_view_source_sha256"), body["source_result_sha256"])
        assertEquals(((presented.content as ToolResultContent.Json).value as JsonObject)["recovery_call"], body["continuation_call"])
        assertFalse(reused.dispatched)
        assertTrue(reused.sharedResult)

        // A replay of the small wrapper does not prove delivery of the source.
        val repeatedCall = call.copy(callId = "repeat")
        val wrapperOnly = listOf(AgentToolExchange(listOf(call), listOf(reused)))
        val repeated = requireNotNull(GitHubReplayRecovery.repeated(repeatedCall, wrapperOnly, "Use saved source", ToolResultDeliveryProof.capture(listOf(reused))))
        val repeatedBody = (repeated.content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(false), repeatedBody["source_projection_delivered"])
        for (field in listOf("head_sha", "run_attempt", "covered_start_line", "covered_end_line", "source_result_sha256")) {
            assertEquals(field, body[field], repeatedBody[field])
        }
        val definition = GitHubOperationTool.definition("github_ci").copy(name = call.name)
        val next = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(AgentToolExchange(listOf(repeatedCall), listOf(repeated))), listOf(definition), 3000))
        val cursor = (next.arguments["view_cursor"] as JsonPrimitive).content
        assertEquals(0, requireNotNull(GitHubSavedReadProgress.cursor(cursor)).offset)

        // The durable view restores the exact source without issuing another log download.
        val snapshot = requireNotNull(views.checkpoint("selected", presented)).second
        val restored = GitHubResultViews()
        assertTrue(restored.restore("selected", "github_ci", call.name, cursor, snapshot))
        val read = restored.read("selected", "github_ci", call.name, cursor, next.callId)
        assertFalse(read.dispatched)
        assertFalse(read.isError)
        assertEquals(presented.executionReceipt?.get("saved_view_source_sha256"), read.executionReceipt?.get("saved_view_source_sha256"))
        assertEquals("SAVED_VIEW_UNAVAILABLE", restored.read("other-owner", "github_ci", call.name, cursor, "other").errorCode)
    }

    @Test fun aDeliveredFirstLogPageDoesNotStandForTheUnreadRemainder() {
        val source = logs("source", 1, 200)
        val sourceCall = source.calls.single()
        val views = GitHubResultViews()
        val presented = views.present("selected", "github_ci", sourceCall.name, source.results.single(), sourceCall.arguments)
        val sourceExchange = source.copy(results = listOf(presented))
        val delivered = ToolResultDeliveryProof.capture(listOf(presented))
        val state = GitHubReplayRecovery.State(sourceExchange.calls, sourceExchange.results)
        state.observeDelivered(listOf(sourceExchange), delivered)
        val repeatedCall = sourceCall.copy(callId = "repeat")
        val repeated = requireNotNull(GitHubCiReadProgress.reused(repeatedCall, listOf(sourceExchange), delivered))
        val body = (repeated.content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(true), body["source_projection_delivered"])
        val next = requireNotNull(
            GitHubReplayRecovery.nextSavedRead(
                listOf(sourceExchange, AgentToolExchange(listOf(repeatedCall), listOf(repeated))),
                listOf(GitHubOperationTool.definition("github_ci").copy(name = sourceCall.name)),
                3000,
                state
            )
        )
        val firstBody = (presented.content as ToolResultContent.Json).value as JsonObject
        val firstUnread = (firstBody["fragment_end"] as JsonPrimitive).content.toInt()
        val cursor = (next.arguments["view_cursor"] as JsonPrimitive).content
        assertTrue(firstUnread > 0)
        assertEquals(firstUnread, requireNotNull(GitHubSavedReadProgress.cursor(cursor)).offset)
        val page = views.read("selected", "github_ci", sourceCall.name, cursor, next.callId)
        assertFalse(page.isError)
        assertFalse(page.dispatched)
    }

    @Test fun sourceDeliveryRequiresTheExactUncompactedProjectionDigest() {
        val source = logs("source", 1, 3)
        val call = logs("covered", 1, 3).calls.single()
        val delivered = ToolResultDeliveryProof.capture(source.results)
        fun proof(exchange: AgentToolExchange, digests: Map<String, String>): JsonPrimitive? =
            ((requireNotNull(GitHubCiReadProgress.reused(call, listOf(exchange), digests)).content as ToolResultContent.Json).value as JsonObject)["source_projection_delivered"] as? JsonPrimitive
        assertEquals(JsonPrimitive(false), proof(source, emptyMap()))
        assertEquals(JsonPrimitive(false), proof(source, mapOf("source" to "b".repeat(64))))
        assertEquals(JsonPrimitive(true), proof(source, delivered))
        val projected = source.results.single().copy(
            content = ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("operation", "github_ci")
                    put("replay_compacted", true)
                    put("previously_delivered", false)
                }
            ),
            retainedContent = source.results.single().content
        )
        assertEquals(JsonPrimitive(false), proof(source.copy(results = listOf(projected)), ToolResultDeliveryProof.capture(listOf(projected))))
    }

    @Test fun retainedNonSourceFragmentsRemainReadableWhenTheirProjectionWasNotDelivered() {
        val views = GitHubResultViews()
        val name = "github_release"
        val request = buildJsonObject {
            put("action", "detail")
            put("tag_name", "v1")
        }
        val original = views.present(
            "task",
            name,
            name,
            AgentToolResult("source", ToolResultContent.Json(buildJsonObject { put("body", "Retained changelog ".repeat(2000)) }), false),
            request
        )
        assertEquals(JsonPrimitive(true), original.executionReceipt?.get("retained_original_complete"))
        val compacted = original.copy(content = requireNotNull(GitHubReplayRecovery.compact(original.content, 1600)))
        val history = listOf(AgentToolExchange(listOf(ProviderEvent.ToolCall("source", name, request)), listOf(compacted)))
        val next = requireNotNull(GitHubReplayRecovery.nextSavedRead(history, listOf(GitHubOperationTool.definition(name)), 3000))
        val cursor = (next.arguments["view_cursor"] as JsonPrimitive).content
        assertEquals(0, requireNotNull(GitHubSavedReadProgress.cursor(cursor)).offset)
        val read = views.read("task", name, name, cursor, next.callId)
        assertFalse(read.dispatched)
        assertFalse(read.isError)
        assertFalse((read.content as ToolResultContent.Json).value.jsonObject.containsKey("replay_compacted"))
    }

    @Test fun largeDiagnosticSummariesCannotDisplaceTheExactUnreadLogCursor() {
        val source = logs("source", 1, 200)
        val call = source.calls.single()
        val captured = source.results.single()
        val data = (captured.content as ToolResultContent.Json).value as JsonObject
        val diagnosticData = JsonObject(
            data + (
                "diagnostic_excerpts" to JsonArray(
                    (1..4).map { line ->
                        buildJsonObject {
                            put("line", line)
                            put("text", "Compiler type mismatch: " + "expected.value.".repeat(24))
                        }
                    }
                )
                )
        )
        val views = GitHubResultViews()
        val presented = views.present("selected", "github_ci", call.name, captured.copy(content = ToolResultContent.Json(diagnosticData)), call.arguments)
        assertTrue(requireNotNull(presented.executionReceipt?.get("semantic_summary")).toString().length > 1536)
        val repeatedCall = call.copy(callId = "repeat")
        val repeated = requireNotNull(GitHubCiReadProgress.reused(repeatedCall, listOf(source.copy(results = listOf(presented)))))
        val compacted = repeated.copy(content = requireNotNull(GitHubReplayRecovery.compact(repeated.content, 1536)))
        val body = (compacted.content as ToolResultContent.Json).value as JsonObject
        assertTrue(body.toString().length <= 1536)
        assertEquals(JsonPrimitive(false), body["previously_delivered"])
        assertEquals(JsonPrimitive(false), body["source_projection_delivered"])
        assertEquals(presented.executionReceipt?.get("saved_view_source_sha256"), body["source_result_sha256"])
        assertEquals(((presented.content as ToolResultContent.Json).value as JsonObject)["recovery_call"], body["continuation_call"])
        val next = requireNotNull(
            GitHubReplayRecovery.nextSavedRead(
                listOf(AgentToolExchange(listOf(repeatedCall), listOf(compacted))),
                listOf(GitHubOperationTool.definition("github_ci").copy(name = call.name)),
                3000
            )
        )
        val cursor = (next.arguments["view_cursor"] as JsonPrimitive).content
        val read = views.read("selected", "github_ci", call.name, cursor, next.callId)
        assertFalse(read.isError)
        assertFalse(read.dispatched)
        assertEquals(presented.executionReceipt?.get("saved_view_source_sha256"), read.executionReceipt?.get("saved_view_source_sha256"))
    }
}
