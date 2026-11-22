package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryMapToolTest {
    @Test fun defaultToolRouteIsPhoneAndRetainsPartialEvidence() = runTest {
        val evidence = buildJsonObject {
            put("compiler_resolution", false)
            put("graph_complete", false)
            put("map_source", "phone")
        }
        var observed: JsonObject? = null
        val tool = RepositoryGraphTool("repository_graph") { arguments ->
            observed = arguments
            evidence
        }
        val result = tool.execute("phone-map", buildJsonObject { put("action", "architecture") })
        assertFalse(result.isError)
        assertEquals(JsonPrimitive("phone"), observed?.get("map_source"))
        assertEquals(evidence, (result.content as ToolResultContent.Json).value)
    }

    @Test fun phoneTransportErrorNamesGitHubRecoveryWithoutDispatchingAGatewayOrDuplicateRead() = runTest {
        var fallbackCalls = 0
        val tool = RepositoryGraphTool("repository_graph", nativeStatus = {
            fallbackCalls++
            error("Phone failures must not dispatch an unrelated status probe")
        }) { throw IOException("GitHub unavailable") }
        val result = tool.execute("phone-status", buildJsonObject { put("action", "status") })
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        assertTrue(result.isError)
        assertEquals(JsonPrimitive("phone"), body["map_source"])
        assertEquals(JsonPrimitive("RESTORE_PHONE_REPOSITORY_ACCESS"), body["recovery_kind"])
        assertTrue((body["next_action"] as JsonPrimitive).content.contains("Gateway is optional"))
        assertFalse("native_fallback" in body)
        assertEquals(0, fallbackCalls)
    }

    @Test fun modelCannotRedirectMapSourceOrRevisionThroughOptions() = runTest {
        var calls = 0
        val tool = RepositoryGraphTool("repository_graph") {
            calls++
            buildJsonObject { }
        }
        for (key in listOf("map_source", "commit_sha", "repository", "owner", "repo", "branch", "ref", "action")) {
            val result = tool.execute(
                "invalid-$key",
                buildJsonObject {
                    put("action", "status")
                    put("options", buildJsonObject { put(key, "gateway") })
                }
            )
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertEquals(0, calls)
    }
}
