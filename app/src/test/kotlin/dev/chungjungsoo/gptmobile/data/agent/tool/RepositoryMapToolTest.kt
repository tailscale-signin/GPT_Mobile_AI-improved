package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
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

    @Test fun phonePrivacyRejectionBeforeFirstSendPreservesPolicyAndNonDispatchEvidence() = runTest {
        var fallbackCalls = 0
        val tool = RepositoryGraphTool("repository_graph", nativeStatus = {
            fallbackCalls++
            error("Privacy rejection must not dispatch a fallback")
        }) {
            val request = GitHubDispatchAccounting.started(false, "/repos/owner/repo")
            GitHubDispatchAccounting.locallyRejected(request)
            throw GatewayGraphOperationException(IOException("Transport boundary", UnverifiedServiceTransport()), "", "On this phone", "REVIEW_PRIVACY_MODE", false)
        }

        val result = tool.execute("phone-private-status", buildJsonObject { put("action", "status") })
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        val receipt = requireNotNull(result.executionReceipt)
        assertTrue(result.isError)
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), body["state"])
        assertEquals(JsonPrimitive("LOCAL_POLICY_OR_CONFIGURATION"), body["failure_category"])
        assertEquals(JsonPrimitive("REVIEW_PRIVACY_MODE"), body["recovery_kind"])
        assertEquals(JsonPrimitive("REVIEW_PRIVACY_MODE"), body["repair_action"])
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), receipt["phase"])
        assertEquals(JsonPrimitive("tool_precheck"), receipt["failed_stage"])
        assertEquals(JsonPrimitive("tool_precheck"), receipt["authority_failed_stage"])
        assertEquals(JsonPrimitive("PRIVACY_SERVICE_UNVERIFIED"), receipt["authority_invalidation_code"])
        assertEquals(JsonPrimitive(0), receipt["read_requests"])
        assertEquals(JsonPrimitive(1), receipt["local_request_rejections"])
        assertEquals(JsonPrimitive(false), receipt["dispatched"])
        assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
        assertEquals(JsonPrimitive(false), receipt["index_outcome_unknown"])
        assertFalse("http_status" in receipt)
        assertFalse("native_fallback" in body)
        assertFalse("recheck_after_recovery" in body)
        assertEquals(0, fallbackCalls)
    }

    @Test fun phonePrivacyRejectionDuringIndexRetainsEarlierReadWithoutRemoteReconciliation() = runTest {
        var fallbackCalls = 0
        val tool = RepositoryGraphTool("repository_graph", nativeStatus = {
            fallbackCalls++
            error("Privacy rejection must not dispatch a fallback")
        }) {
            val earlier = GitHubDispatchAccounting.started(false, "/repos/owner/repo")
            GitHubDispatchAccounting.responded(false, 200, earlier, "first-response")
            val blocked = GitHubDispatchAccounting.started(false, "/repos/owner/repo/git/blobs/${"a".repeat(40)}")
            GitHubDispatchAccounting.locallyRejected(blocked)
            throw GatewayGraphOperationException(UnverifiedServiceTransport(), "", "On this phone", "REVIEW_PRIVACY_MODE", false)
        }

        val result = tool.execute("phone-private-index", buildJsonObject { put("action", "index") })
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        val receipt = requireNotNull(result.executionReceipt)
        assertTrue(result.isError)
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", result.errorCode)
        assertTrue(result.dispatched)
        assertEquals(JsonPrimitive("POLICY_BLOCKED"), body["state"])
        assertEquals(JsonPrimitive("REVIEW_PRIVACY_MODE"), body["recovery_kind"])
        assertEquals(JsonPrimitive("FAILED"), receipt["phase"])
        assertEquals(JsonPrimitive("tool_execution"), receipt["failed_stage"])
        assertEquals(JsonPrimitive("tool_execution"), receipt["authority_failed_stage"])
        assertEquals(JsonPrimitive("PRIVACY_SERVICE_UNVERIFIED"), receipt["authority_invalidation_code"])
        assertEquals(JsonPrimitive(1), receipt["read_requests"])
        assertEquals(JsonPrimitive(1), receipt["read_response_observations"])
        assertEquals(JsonPrimitive(1), receipt["local_request_rejections"])
        assertEquals(JsonPrimitive(200), receipt["http_status"])
        assertEquals(JsonPrimitive(true), receipt["dispatched"])
        assertEquals(JsonPrimitive(0), receipt["mutation_requests"])
        assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
        assertEquals(JsonPrimitive(false), receipt["index_outcome_unknown"])
        assertEquals(JsonPrimitive(false), receipt["current_index_outcome_unknown"])
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
