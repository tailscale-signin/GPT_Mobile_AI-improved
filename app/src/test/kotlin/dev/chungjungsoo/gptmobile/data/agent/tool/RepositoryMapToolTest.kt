package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected
import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryMapToolTest {
    @Test fun automaticGatewaySelectionPreservesExplicitPhoneAndTaskContextPreparation() = runTest {
        var selections = 0
        val sources = mutableListOf<JsonPrimitive>()
        val tool = RepositoryGraphTool("repository_graph", defaultSource = {
            selections++
            "gateway"
        }) { request ->
            sources += request.getValue("map_source") as JsonPrimitive
            buildJsonObject { put("map_source", request.getValue("map_source")) }
        }
        tool.execute("automatic", buildJsonObject { put("action", "status") })
        tool.execute(
            "explicit-phone",
            buildJsonObject {
                put("action", "status")
                put("map_source", "phone")
            }
        )
        tool.execute("task-context", buildJsonObject { put("action", "task_context_bundle") })
        assertEquals(listOf("gateway", "phone", "phone").map(::JsonPrimitive), sources)
        assertEquals(1, selections)
    }

    @Test fun statusChecksTheCopiedCommitAgainstTheSelectedMapBeforeRemovingIt() = runTest {
        val sha = "ab".repeat(20)
        val scope = GitHubConversationContext("github", "owner", "repo", "main", sha, "profile")
        val queries = mutableListOf<JsonObject>()
        val tool = RepositoryGraphTool("repository_graph__github_api", repositoryScope = scope) { payload ->
            queries += payload
            buildJsonObject { put("map_source", payload.getValue("map_source")) }
        }
        fun request(commit: String, source: String = "phone", action: String = "status", extra: Boolean = false) = buildJsonObject {
            put("action", action)
            put("map_source", source)
            put(
                "options",
                buildJsonObject {
                    put("commit_sha", commit)
                    if (extra) put("repo", "other")
                }
            )
        }
        for (source in listOf("phone", "gateway")) {
            assertFalse(tool.execute("matching-$source", request(sha.uppercase(), source)).isError)
            assertEquals(JsonPrimitive(source), queries.last()["map_source"])
            assertFalse("commit_sha" in queries.last())
        }
        for (invalid in listOf(request("cd".repeat(20)), request("main"), request(sha, action = "index"), request(sha, extra = true))) {
            val rejected = tool.execute("refused", invalid)
            assertTrue(rejected.isError)
            assertFalse(rejected.dispatched)
        }
        val mismatch = tool.execute("different-commit", request("cd".repeat(20)))
        assertEquals("GITHUB_VALIDATION_FAILED", mismatch.errorCode)
        assertEquals(JsonPrimitive("EVIDENCE_REVISION_MISMATCH"), mismatch.executionReceipt?.get("reason_code"))
        assertEquals(2, queries.size)
    }

    @Test fun localPhoneCooldownKeepsWaitEvidenceWithoutDispatchOrSourceFallback() = runTest {
        for (nested in listOf(false, true)) {
            val cooldown = cooldown()
            val failure = if (nested) GitHubRequestRejected(0, "Local admission").apply { initCause(cooldown) } else cooldown
            var fallbackCalls = 0
            val tool = RepositoryGraphTool("repository_graph", nativeStatus = {
                fallbackCalls++
                error("A phone cooldown cannot dispatch another source")
            }) { arguments ->
                assertEquals(JsonPrimitive("phone"), arguments["map_source"])
                throw wrapped(failure)
            }
            val result = tool.execute("phone-cooldown", status())
            val body = (result.content as ToolResultContent.Json).value as JsonObject
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            assertEquals("GITHUB_COOLDOWN_ACTIVE", result.errorCode)
            assertEquals(JsonPrimitive("WAITING"), body["state"])
            assertEquals(JsonPrimitive("WAIT_FOR_REPORTED_RATE_LIMIT"), body["recovery_kind"])
            assertEquals(JsonPrimitive(60_000), body["retry_after_ms"])
            assertEquals(JsonPrimitive(120_000), body["github_cooldown_deadline_ms"])
            assertEquals(JsonPrimitive(0), result.executionReceipt?.get("read_requests"))
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("dispatched"))
            assertEquals(JsonPrimitive("GITHUB_COOLDOWN_ACTIVE"), result.executionReceipt?.get("reason_code"))
            assertNull(result.executionReceipt?.get("http_status"))
            assertNull(body["http_status"])
            assertEquals(0, fallbackCalls)
        }
    }

    @Test fun phoneCooldownAfterAnObservedReadRetainsTheEarlierDispatch() = runTest {
        val tool = RepositoryGraphTool("repository_graph") {
            val request = GitHubDispatchAccounting.started(false, "/repos/owner/repo")
            GitHubDispatchAccounting.responded(false, 200, request)
            throw wrapped(cooldown())
        }
        val result = tool.execute("phone-partial-cooldown", status())
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        assertTrue(result.dispatched)
        assertEquals("GITHUB_COOLDOWN_ACTIVE", result.errorCode)
        assertEquals(JsonPrimitive("WAITING"), body["state"])
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("read_requests"))
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("read_response_observations"))
        assertEquals(JsonPrimitive(200), result.executionReceipt?.get("http_status"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("dispatched"))
        assertEquals(JsonPrimitive(60_000), result.executionReceipt?.get("retry_after_ms"))
        assertNull("The current locally blocked request has no HTTP response", body["http_status"])
    }

    @Test fun phoneHttpRejectionsRetainCanonicalIdentityAndObservedDispatch() = runTest {
        for ((httpStatus, code) in listOf(401 to "AUTH_REQUIRED", 403 to "GITHUB_ENDPOINT_PERMISSION_REQUIRED", 404 to "GITHUB_NOT_FOUND_OR_INACCESSIBLE", 429 to "GITHUB_SECONDARY_RATE_LIMITED")) {
            val tool = RepositoryGraphTool("repository_graph", nativeStatus = { error("A native rejection cannot switch sources") }) {
                val request = GitHubDispatchAccounting.started(false, "/repos/owner/repo")
                GitHubDispatchAccounting.responded(false, httpStatus, request)
                throw wrapped(GitHubRequestRejected(httpStatus, "$code · private upstream response"))
            }
            val result = tool.execute("phone-http-$httpStatus", status())
            val body = (result.content as ToolResultContent.Json).value as JsonObject
            assertTrue(result.dispatched)
            assertEquals(code, result.errorCode)
            assertEquals(JsonPrimitive(httpStatus), body["http_status"])
            assertEquals(JsonPrimitive(httpStatus), result.executionReceipt?.get("http_status"))
            assertEquals(JsonPrimitive(1), result.executionReceipt?.get("read_http_failures"))
            assertFalse(body.toString().contains("private upstream"))
            assertFalse(body.containsKey("native_fallback"))
        }
    }

    @Test fun phoneRateLimitKeepsOnlyBoundedFieldsFromTheSameHttpResponse() = runTest {
        val code = "GITHUB_PRIMARY_RATE_LIMITED"
        val evidence = buildJsonObject {
            put("code", code)
            put("http_status", 403)
            put("github_cooldown_resource", "CORE")
            put("github_cooldown_basis", "RESOURCE_RESET")
            put("github_cooldown_delay_ms", 60_000)
            put("github_cooldown_deadline_ms", 120_000)
            put("github_cooldown_remaining_requests", 0)
            put("retry_after_ms", 60_000)
            put("private_source_note", "private account information")
        }
        suspend fun rejected(fields: JsonObject): JsonObject {
            val tool = RepositoryGraphTool("repository_graph") { throw wrapped(GitHubRequestRejected(403, "$code · rejected", fields)) }
            return (tool.execute("phone-rate", status()).content as ToolResultContent.Json).value as JsonObject
        }
        val bounded = rejected(evidence)
        assertEquals(JsonPrimitive("WAIT_FOR_REPORTED_RATE_LIMIT"), bounded["recovery_kind"])
        assertEquals(JsonPrimitive(60_000), bounded["retry_after_ms"])
        assertFalse(bounded.toString().contains("private account"))
        assertFalse(bounded.containsKey("private_source_note"))
        val mismatched = rejected(JsonObject(evidence + ("http_status" to JsonPrimitive(429))))
        assertNull(mismatched["retry_after_ms"])
        assertNull(mismatched["github_cooldown_deadline_ms"])
        val invalid = rejected(
            JsonObject(
                evidence + mapOf(
                    "github_cooldown_resource" to JsonPrimitive("private resource"),
                    "github_cooldown_delay_ms" to JsonPrimitive(-1),
                    "github_cooldown_remaining_requests" to JsonPrimitive(16_000_001),
                    "retry_after_ms" to JsonPrimitive(86_400_001)
                )
            )
        )
        for (key in listOf("github_cooldown_resource", "github_cooldown_delay_ms", "github_cooldown_remaining_requests", "retry_after_ms")) assertNull(key, invalid[key])
    }

    @Test fun typedPhoneHttpResponseWithoutNativeCallbacksStillProvesDispatch() = runTest {
        val tool = RepositoryGraphTool("repository_graph") { throw wrapped(GitHubRequestRejected(401, "AUTH_REQUIRED · rejected")) }
        val result = tool.execute("phone-response", status())
        assertTrue(result.dispatched)
        assertEquals("AUTH_REQUIRED", result.errorCode)
        assertEquals(JsonPrimitive(401), result.executionReceipt?.get("http_status"))
        assertNull("Unavailable per-request callbacks must not claim zero actual requests", result.executionReceipt?.get("read_requests"))
    }

    @Test fun phoneCancellationPropagatesWithoutChangingSource() = runTest {
        val cancelled = CancellationException("cancel phone map")
        var fallbackCalls = 0
        val queries = mutableListOf<JsonObject>()
        val tool = RepositoryGraphTool("repository_graph", nativeStatus = {
            fallbackCalls++
            error("Cancellation cannot dispatch fallback")
        }) { arguments ->
            queries += arguments
            throw cancelled
        }
        val propagated = runCatching { tool.execute("phone-cancelled", status()) }.exceptionOrNull()
        assertTrue(propagated is CancellationException)
        assertEquals(cancelled.message, propagated?.message)
        // Coroutine stack-trace recovery may copy the exception while retaining its original cause.
        assertTrue("The original cancellation must remain in its cause chain", generateSequence(propagated) { it.cause }.take(16).any { it === cancelled })
        assertEquals(1, queries.size)
        assertEquals(JsonPrimitive("phone"), queries.single()["map_source"])
        assertEquals(JsonPrimitive("status"), queries.single()["action"])
        assertEquals(0, fallbackCalls)
    }

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

    @Test fun phoneLocalSuccessDoesNotInventDispatchOrAdoptGatewayCheckpoints() = runTest {
        val tool = RepositoryGraphTool("repository_graph") {
            buildJsonObject {
                put("map_source", "phone")
                put(
                    "index_checkpoint",
                    buildJsonObject {
                        put("index_outcome_unknown", true)
                        put("graph_index_attempt_hash", "a".repeat(64))
                    }
                )
            }
        }
        val result = tool.execute("phone-local-index", buildJsonObject { put("action", "index") })
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        val receipt = requireNotNull(result.executionReceipt)
        assertFalse(result.isError)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive("COMPLETED"), receipt["phase"])
        assertEquals(JsonPrimitive("phone"), receipt["map_source"])
        assertEquals(JsonPrimitive(0), receipt["read_requests"])
        assertEquals(JsonPrimitive(0), receipt["mutation_requests"])
        for (key in listOf("requires_reconciliation", "index_outcome_unknown", "current_index_outcome_unknown")) assertEquals(key, JsonPrimitive(false), receipt[key])
        assertNull(receipt["graph_index_attempt_hash"])
        assertNull(body["repair_action"])
        assertNull(body["recovery_call"])
    }

    @Test fun phoneIndexCooldownPreservesEarlierReadsWithoutRemoteMutationUncertainty() = runTest {
        val tool = RepositoryGraphTool("repository_graph") {
            val earlier = GitHubDispatchAccounting.started(false, "/repos/owner/repo")
            GitHubDispatchAccounting.responded(false, 200, earlier)
            throw GatewayGraphOperationException(cooldown(), "", "On this phone", null, true, currentIndexOutcomeUnknown = true)
        }
        val result = tool.execute("phone-index-cooldown", buildJsonObject { put("action", "index") })
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        val receipt = requireNotNull(result.executionReceipt)
        assertTrue(result.isError)
        assertTrue(result.dispatched)
        assertEquals("GITHUB_COOLDOWN_ACTIVE", result.errorCode)
        assertEquals(JsonPrimitive("WAITING"), body["state"])
        assertEquals(JsonPrimitive("WAIT_FOR_REPORTED_RATE_LIMIT"), body["recovery_kind"])
        assertEquals(JsonPrimitive("FAILED"), receipt["phase"])
        assertEquals(JsonPrimitive(1), receipt["read_requests"])
        assertEquals(JsonPrimitive(0), receipt["mutation_requests"])
        assertEquals(JsonPrimitive(60_000), receipt["retry_after_ms"])
        for (key in listOf("requires_reconciliation", "index_outcome_unknown", "current_index_outcome_unknown")) assertEquals(key, JsonPrimitive(false), receipt[key])
        assertNull(body["repair_action"])
        assertNull(body["recovery_call"])
    }

    @Test fun wrappedPrivacyRejectionDoesNotTriggerGatewayNativeFallback() = runTest {
        var fallbackCalls = 0
        val tool = RepositoryGraphTool("repository_graph", nativeStatus = {
            fallbackCalls++
            error("A local privacy denial cannot use a transport fallback")
        }) { throw IOException("Transport boundary", UnverifiedServiceTransport()) }
        val result = tool.execute(
            "gateway-private-status",
            buildJsonObject {
                put("action", "status")
                put("map_source", "gateway")
            }
        )
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        assertTrue(result.isError)
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", result.errorCode)
        assertEquals(JsonPrimitive("POLICY_BLOCKED"), body["state"])
        assertEquals(JsonPrimitive("REVIEW_PRIVACY_MODE"), body["recovery_kind"])
        assertNull(body["native_fallback"])
        assertNull(body["recheck_after_recovery"])
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

    private fun status() = buildJsonObject { put("action", "status") }
    private fun cooldown() = GitHubCooldownActive(GitHubCooldownActive.Resource.CORE, GitHubCooldownActive.Basis.RESOURCE_RETRY_AFTER, 60_000, 120_000)
    private fun wrapped(failure: Exception) = GatewayGraphOperationException(failure, "", "On this phone", null, false, targetRevision = "a".repeat(40))
}
