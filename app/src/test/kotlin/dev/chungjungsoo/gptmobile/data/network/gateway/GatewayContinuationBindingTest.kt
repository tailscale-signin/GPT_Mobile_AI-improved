package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayContinuationBindingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val arguments = json.parseToJsonElement("""{"path":"README.md"}""") as JsonObject
    private val saved = json.decodeFromString<GatewayJobResult>("""{"job_id":"saved","status":"waiting_for_client_tool","job":{"job_id":"saved","model":"selected","pending_client_tool_call_ids":["original-call"]},"result":{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"original-call","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]},"finish_reason":"tool_calls"}]}}""")

    @Test fun waitingHandoffBindsOriginalJobAndRequiresExactPendingResults() = runTest {
        var reads = 0
        var authorityChecks = 0
        val binding = requireNotNull(
            GatewayContinuationBinding.read("profile", "https://gateway.test/v1", "selected", "saved", { authorityChecks++ }, {
                reads++
                saved
            })
        )
        assertEquals("saved", binding.jobId)
        assertEquals(1, reads)
        assertEquals(2, authorityChecks)
        binding.requireRoute("profile", "https://gateway.test/v1/", "selected")
        binding.requireResults(listOf(Triple("original-call", "read_file", arguments)), listOf("original-call"))
        for (ids in listOf(emptyList(), listOf("another"), listOf("original-call", "original-call"))) {
            assertTrue(runCatching { binding.requireResults(listOf(Triple("original-call", "read_file", arguments)), ids) }.exceptionOrNull()?.message.orEmpty().contains("CLIENT_TOOL_RESULTS_REQUIRED"))
        }
        assertTrue(runCatching { binding.requireResults(listOf(Triple("original-call", "write_file", arguments)), listOf("original-call")) }.exceptionOrNull()?.message.orEmpty().contains("CLIENT_TOOL_RESULT_IDENTITY_MISMATCH"))
    }

    @Test fun routedCallReturnsOriginalProviderIdentityWithoutChangingExecution() {
        val binding = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", saved)
        val routed = ProviderEvent.ToolCall(
            "original-call",
            "read_file__authorized",
            buildJsonObject {
                put("path", "README.md")
                put("response_detail", "compact")
            },
            originalProviderName = "read_file",
            originalProviderArguments = arguments
        )
        binding.requireResults(listOf(exchange(routed)))
        assertEquals("read_file__authorized", routed.name)
        assertEquals("read_file", routed.providerIdentity().name)
        assertEquals(arguments, routed.providerIdentity().arguments)
        assertTrue(runCatching { binding.requireResults(listOf(exchange(routed.copy(originalProviderArguments = buildJsonObject { put("path", "changed") })))) }.isFailure)
        assertTrue(runCatching { routed.copy(originalProviderName = null).providerIdentity() }.isFailure)
    }

    @Test fun opaquePendingIdsArePreservedVerbatim() {
        val id = "opaque call/東京:0"
        val opaque = json.decodeFromString<GatewayJobResult>(json.encodeToString(saved).replace("original-call", id))
        val binding = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", opaque)
        binding.requireResults(listOf(Triple(id, "read_file", arguments)), listOf(id))
        assertTrue(runCatching { binding.requireResults(listOf(Triple(id, "read_file", arguments), Triple(id, "read_file", arguments)), listOf(id)) }.isFailure)
    }

    private fun exchange(call: ProviderEvent.ToolCall) = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("retained result"), false)))

    private fun local(cursor: String = "saved-view:0:2048"): AgentToolExchange {
        val name = "github_read__account"
        val id = "saved-result-consume-" + GitHubEvidenceBook.digest("$name:$cursor").take(24)
        return exchange(ProviderEvent.ToolCall(id, name, buildJsonObject { put("view_cursor", cursor) }))
    }

    @Test fun exactPendingBatchSurvivesOnlyCompleteCursorBoundLocalSuffixes() {
        val binding = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", saved)
        val pending = exchange(ProviderEvent.ToolCall("original-call", "read_file", arguments))
        binding.requireResults(listOf(pending, local(), local("saved-view:1:2048")))
        val failedLocal = local().let { it.copy(results = it.results.map { result -> result.copy(isError = true, errorCode = "SAVED_VIEW_UNAVAILABLE") }) }
        binding.requireResults(listOf(pending, failedLocal))
        val extra = Triple("extra", "read_file", arguments)
        assertTrue(runCatching { binding.requireResults(listOf(Triple("original-call", "read_file", arguments), extra), listOf("original-call", "extra")) }.isFailure)
        assertTrue(runCatching { binding.requireResults(listOf(Triple("original-call", "read_file", arguments)), listOf("original-call", "extra")) }.isFailure)
    }

    @Test fun laterRealBatchesAndExplicitBoundariesSupersedeHistoricalMatchingIds() {
        val binding = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", saved)
        val pending = exchange(ProviderEvent.ToolCall("original-call", "read_file", arguments))
        for (later in listOf(
            exchange(ProviderEvent.ToolCall("another-call", "read_file", arguments)),
            exchange(ProviderEvent.ToolCall("original-call", "write_file", arguments)),
            exchange(ProviderEvent.ToolCall("original-call", "read_file", JsonObject(arguments + ("path" to JsonPrimitive("other.md"))))),
            AgentToolExchange(emptyList(), emptyList())
        )) {
            assertTrue(runCatching { binding.requireResults(listOf(pending, later, local())) }.isFailure)
        }
    }

    @Test fun forgedIncompleteOrDetachedLocalSuffixesCannotMaskTheLatestBatch() {
        val binding = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", saved)
        val pending = exchange(ProviderEvent.ToolCall("original-call", "read_file", arguments))
        val valid = local()
        val call = valid.calls.single()
        val invalid = listOf(
            exchange(call.copy(callId = "saved-result-consume-unbound")),
            exchange(call.copy(arguments = JsonObject(call.arguments + ("owner" to JsonPrimitive("owner"))))),
            exchange(call.copy(arguments = buildJsonObject { put("view_cursor", "saved-view:1:2048") })),
            exchange(call.copy(name = "shell")),
            valid.copy(results = emptyList()),
            valid.copy(results = valid.results + valid.results),
            valid.copy(results = listOf(valid.results.single().copy(callId = "detached"))),
            local("not-a-cursor"),
            local("bad\u00a0view:0:2048"),
            local("bad\u0085view:0:2048"),
            local("bad\u001cview:0:2048")
        )
        invalid.forEach { assertTrue(runCatching { binding.requireResults(listOf(pending, it)) }.isFailure) }
        assertTrue(runCatching { binding.requireResults(listOf(local())) }.isFailure)
    }

    @Test fun changedAuthorityBlocksBeforeReadOrBeforeBinding() = runTest {
        for (revokeAt in listOf(1, 2)) {
            var checks = 0
            var reads = 0
            val failure = runCatching {
                GatewayContinuationBinding.read("profile", "https://gateway.test/v1", "selected", "saved", {
                    checks++
                    check(checks != revokeAt) { "DISPATCH_POLICY_CHANGED" }
                }, {
                    reads++
                    saved
                })
            }.exceptionOrNull()
            assertEquals("DISPATCH_POLICY_CHANGED", failure?.message)
            assertEquals(revokeAt - 1, reads)
        }
    }

    @Test fun terminalRunningMismatchedAndMalformedJobsCannotAcquireContinuationBinding() = runTest {
        for (status in listOf("completed", "failed", "cancelled", "interrupted")) {
            assertNull(GatewayContinuationBinding.read("profile", "https://gateway.test/v1", "selected", "saved", {}, { saved.copy(status = status) }))
        }
        assertTrue(runCatching { GatewayContinuationBinding.read("profile", "https://gateway.test/v1", "selected", "saved", {}, { saved.copy(status = "running") }) }.exceptionOrNull()?.message.orEmpty().contains("GATEWAY_JOB_IN_PROGRESS"))
        for (invalid in listOf(saved.copy(jobId = "other"), saved.copy(job = saved.job!!.copy(model = "other")), saved.copy(job = saved.job!!.copy(pendingClientToolCallIds = listOf("other"))), saved.copy(job = saved.job!!.copy(pendingClientToolCallIds = emptyList())))) {
            assertTrue(runCatching { GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", invalid) }.isFailure)
        }
        val binding = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", saved)
        assertTrue(runCatching { binding.requireRoute("other", "https://gateway.test/v1", "selected") }.isFailure)
        assertTrue(runCatching { binding.requireRoute("profile", "https://other.test/v1", "selected") }.isFailure)
        assertTrue(runCatching { binding.requireRoute("profile", "https://gateway.test/v1", "other") }.isFailure)
    }
}
