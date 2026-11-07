package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    @Test fun opaquePendingIdsArePreservedVerbatim() {
        val id = "opaque call/東京:0"
        val opaque = json.decodeFromString<GatewayJobResult>(json.encodeToString(saved).replace("original-call", id))
        val binding = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", opaque)
        binding.requireResults(listOf(Triple(id, "read_file", arguments)), listOf(id))
        assertTrue(runCatching { binding.requireResults(listOf(Triple(id, "read_file", arguments), Triple(id, "read_file", arguments)), listOf(id)) }.isFailure)
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
