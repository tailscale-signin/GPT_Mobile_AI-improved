package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubExplicitBranchWriteTest {
    @Test fun explicitWorkingBranchIsIndependentOfSelectionAndStillRequiresItsExactLease() = runTest {
        val head = "a".repeat(40)
        val branch = "codex-self-test/" + "d".repeat(20)
        for (mode in listOf("valid", "missing-branch", "missing-lease", "wrong-lease", "default-branch")) {
            var mutations = 0
            var refUpdates = 0
            val client = HttpClient(
                MockEngine { request ->
                    if (request.method != HttpMethod.Get) mutations++
                    val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
                    val path = request.url.encodedPath
                    val response = when {
                        path == "/repos/o/r" -> """{"default_branch":"main"}"""
                        request.method == HttpMethod.Get && path.contains("/git/ref/heads/") -> """{"ref":"refs/heads/$branch","object":{"sha":"$head"}}"""
                        request.method == HttpMethod.Get && path.contains("/git/commits/") -> """{"sha":"$head","tree":{"sha":"${"1".repeat(40)}"},"parents":[],"message":"base"}"""
                        request.method == HttpMethod.Get && path.contains("/git/trees/") -> """{"sha":"${"1".repeat(40)}","truncated":false,"tree":[]}"""
                        request.method == HttpMethod.Post && path.endsWith("/git/trees") -> """{"sha":"${"2".repeat(40)}"}"""
                        request.method == HttpMethod.Post && path.endsWith("/git/commits") -> {
                            assertEquals(JsonPrimitive(head), body?.get("parents")?.jsonArray?.single())
                            """{"sha":"${"b".repeat(40)}"}"""
                        }
                        request.method == HttpMethod.Patch && path.contains("/git/refs/heads/") -> {
                            refUpdates++
                            assertTrue(path.endsWith("codex-self-test%2F${"d".repeat(20)}") || path.endsWith(branch))
                            assertEquals(JsonPrimitive(false), body?.get("force"))
                            "{}"
                        }
                        else -> error("Unexpected request ${request.method} $path")
                    }
                    respond(response)
                }
            )
            try {
                val backend = GitHubTool("token", client, repositoryContext = GitHubRepositoryContext("o", "r", "main"), persistentState = false)
                val tool = GitHubOperationTool("github_write", backend, "explicit-target-$mode")
                val args = buildJsonObject {
                    put("action", "commit")
                    if (mode != "missing-branch") put("branch", if (mode == "default-branch") "main" else branch)
                    if (mode != "missing-lease") put("expected_head_sha", if (mode == "wrong-lease") "e".repeat(40) else head)
                    put("message", "Test explicit working branch")
                    put(
                        "files",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("path", "fixture.txt")
                                    put("content", "nonempty fixture\n")
                                }
                            )
                        )
                    )
                }
                val result = tool.execute(mode, args)
                if (mode == "valid") {
                    assertFalse(result.toString(), result.isError)
                    assertEquals(3, mutations)
                    assertEquals(1, refUpdates)
                } else {
                    assertTrue(mode, result.isError)
                    assertEquals(mode, 0, mutations)
                    assertEquals(mode, 0, refUpdates)
                    if (mode == "wrong-lease") {
                        assertEquals("GITHUB_HEAD_CONFLICT", result.errorCode)
                        assertFalse(result.dispatched)
                        val envelope = ToolResultEnvelope.element(result.content).jsonObject
                        assertEquals(JsonPrimitive("github.operation.v1"), envelope["schema"])
                        assertEquals(JsonPrimitive("github_write"), envelope["operation"])
                        val details = envelope.getValue("data").jsonObject
                        assertEquals(JsonPrimitive("e".repeat(40)), details["expected_head_sha"])
                        assertEquals(JsonPrimitive(head), details["observed_head_sha"])
                        assertEquals(JsonPrimitive(false), details["remote_mutation_performed"])
                        assertEquals(JsonPrimitive("CURRENT_INVOCATION"), details["observation_scope"])
                        assertFalse(details.containsKey("http_status"))
                    }
                }
                assertEquals("main", backend.planningContext().repository?.ref)
            } finally {
                client.close()
            }
        }
    }
}
