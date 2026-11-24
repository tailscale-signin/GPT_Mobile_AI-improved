package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLegacySearchReadTest {
    private val commit = "a".repeat(40)
    private val tools = listOf("github_read", "github_search").map { GitHubOperationTool.definition(it) }
    private val status = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$commit"}}""")
    private val source = buildJsonObject {
        put("commit_sha", commit)
        put("has_more", true)
        put("complete", false)
        put(
            "items",
            JsonArray(
                (0..4).map { index ->
                    buildJsonObject {
                        put("path", "docs/README-$index.md")
                        put("sha", (index + 1).toString().repeat(40))
                    }
                }
            )
        )
    }
    private val arguments = json("""{"action":"search","query":"README","mode":"paths","commit_sha":"$commit"}""")

    @Test fun legacyEmptyPlanningRowsCannotEraseSurvivingProjectedOrExcerptPaths() {
        val projected = GitHubEvidenceProjection.result(source, 240)
        for (result in listOf(
            projected,
            buildJsonObject {
                put("observationTruncated", true)
                put("excerpt", projected)
            }
        )) {
            val restored = legacy(result)
            assertEquals(JsonPrimitive("docs/README-0.md"), GitHubInventoryObservation.searchPath(listOf(status, restored)))
            val next = GitHubSelfTestReads.available(tools, listOf(status, restored))
            assertEquals(3, next.count { it.arguments["action"] in dependentActions })
            assertFalse(next.any { it.callId.startsWith("search-source-") })
            assertPartialCoverage(restored)
        }
    }

    @Test fun completelyLostLegacySelectorsReacquireOneAuthorizedPinnedSearchThenEnableDependencies() {
        val restored = legacy(
            buildJsonObject {
                put("observationTruncated", true)
                put("excerpt", GitHubEvidenceProjection.result(source, 80))
            }
        )
        assertEquals(null, GitHubInventoryObservation.searchPath(listOf(status, restored)))
        val next = GitHubSelfTestReads.available(tools, listOf(status, restored))
        assertFalse(next.any { it.arguments["action"] in dependentActions })
        val recovery = next.single { it.callId.startsWith("search-source-") }
        assertEquals("github_search", recovery.name)
        assertEquals(JsonPrimitive(commit), recovery.arguments["commit_sha"])
        assertEquals(JsonPrimitive("README"), recovery.arguments["query"])
        assertTrue(GitHubSelfTestReads.valid(tools.single { it.name == recovery.name }, recovery.arguments))
        val failed = JsonObject(restored + mapOf("call_id" to JsonPrimitive(recovery.callId + "-1"), "arguments" to recovery.arguments, "success" to JsonPrimitive(false), "error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED")))
        assertFalse(GitHubSelfTestReads.available(tools, listOf(status, restored, failed)).any { it.callId.startsWith("search-source-") })
        val laterDenied = JsonObject(failed + ("call_id" to JsonPrimitive("provider-recheck")))
        assertFalse(GitHubSelfTestReads.available(tools, listOf(status, restored, laterDenied)).any { it.callId.startsWith("search-source-") })
        val fetched = JsonObject(restored + mapOf("call_id" to JsonPrimitive(recovery.callId), "arguments" to recovery.arguments, "result" to source, "inventory_observation" to requireNotNull(GitHubInventoryObservation.capture(source))))
        val resumed = GitHubSelfTestReads.available(tools, listOf(status, restored, Json.parseToJsonElement(fetched.toString()).jsonObject))
        assertEquals(3, resumed.count { it.arguments["action"] in dependentActions })
        assertFalse(resumed.any { it.callId.startsWith("search-source-") })
        assertPartialCoverage(restored)
    }

    @Test fun foreignOrWrongRevisionEvidenceAndUnavailableToolsCannotAuthorizeReacquisition() {
        val restored = legacy(
            buildJsonObject {
                put("observationTruncated", true)
                put("excerpt", JsonObject(emptyMap()))
            }
        )
        val other = JsonPrimitive("b".repeat(40))
        for (changed in listOf(
            JsonObject(restored + ("repository" to JsonPrimitive("foreign/repo"))),
            JsonObject(restored + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(restored + ("repository_role" to JsonPrimitive("READ_SOURCE"))),
            JsonObject(restored + ("arguments" to JsonObject(arguments + ("commit_sha" to other)))),
            JsonObject(restored + ("inventory_observation" to JsonObject((restored["inventory_observation"] as JsonObject) + ("commit_sha" to other)))),
            JsonObject(restored + ("result" to buildJsonObject { put("commit_sha", other) })),
            JsonObject(restored + ("source_head_sha" to other))
        )) {
            assertEquals(null, GitHubInventoryObservation.searchPath(listOf(status, changed)))
            val next = GitHubSelfTestReads.available(tools, listOf(status, changed))
            assertFalse(next.any { it.callId.startsWith("search-source-") || it.arguments["action"] in dependentActions })
        }
        assertTrue(GitHubSelfTestReads.available(emptyList(), listOf(status, restored)).isEmpty())
        assertFalse(GitHubSelfTestReads.available(tools.map { it.copy(modelVisible = false) }, listOf(status, restored)).any { it.callId.startsWith("search-source-") })
    }

    private fun legacy(result: JsonElement): JsonObject {
        val captured = requireNotNull(GitHubInventoryObservation.capture(source, result))
        val legacy = JsonObject(captured + ("planning_records" to JsonArray(List(5) { JsonObject(emptyMap()) })))
        return Json.parseToJsonElement(
            buildJsonObject {
                put("binding", "task")
                put("repository", "owner/repo")
                put("trusted", true)
                put("success", true)
                put("tool", "github_search")
                put("arguments", arguments)
                put("result", result)
                put("inventory_observation", legacy)
            }.toString()
        ).jsonObject
    }

    private fun assertPartialCoverage(receipt: JsonObject) {
        val captured = GitHubInventoryObservation.of(receipt)
        assertEquals(JsonPrimitive(5), captured?.get("fetched"))
        assertEquals(JsonPrimitive(false), captured?.get("complete"))
        assertEquals(JsonPrimitive(true), captured?.get("has_more"))
        assertEquals(JsonNull, captured?.get("consumed"))
        assertEquals(JsonPrimitive("NOT_RECORDED"), captured?.get("consumption_status"))
    }

    private val dependentActions = setOf("ranges", "search_repository", "search_code").map(::JsonPrimitive).toSet()
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
