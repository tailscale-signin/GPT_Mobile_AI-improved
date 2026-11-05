package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayResultRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun fractionalCompletionSecondsPreserveTheDurableResult() {
        for (value in listOf("1793844300.182", "1793844300")) {
            val result = json.decodeFromString<GatewayJobResult>("""{"job_id":"saved","status":"completed","content":"answer","result_quality":"complete","completed_at":$value}""")
            assertEquals(1793844300L, result.completedAt)
            assertEquals(GatewayResultDisposition.COMPLETE, result.recoveryDisposition)
        }
        for (value in listOf("-1", "\"1793844300.182\"", "1e100")) {
            assertTrue(runCatching { json.decodeFromString<GatewayJobResult>("""{"job_id":"saved","status":"completed","completed_at":$value}""") }.isFailure)
        }
    }

    @Test fun terminalStateAloneNeverProvesAnAnswerComplete() {
        val saved = GatewayJobResult("saved", "completed", "answer")
        assertEquals(GatewayResultDisposition.UNAVAILABLE, saved.recoveryDisposition)
        assertEquals(GatewayResultDisposition.PARTIAL, saved.copy(resultQuality = "partial").recoveryDisposition)
        assertEquals(GatewayResultDisposition.COMPLETE, saved.copy(resultQuality = "complete").recoveryDisposition)
        assertEquals(GatewayResultDisposition.PARTIAL, saved.copy(resultQuality = "complete", result = ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = "answer"), finishReason = "length")))).recoveryDisposition)
        for (status in listOf("failed", "cancelled", "canceled", "interrupted")) assertEquals(GatewayResultDisposition.FAILED, saved.copy(status = status).recoveryDisposition)
        for (status in listOf("running", "resuming", "waiting_for_client_tool")) assertEquals(GatewayResultDisposition.WAITING, saved.copy(status = status).recoveryDisposition)
    }

    @Test fun modelEvidenceMustMatchTheConfiguredModelAcrossTheSavedEnvelope() {
        val saved = GatewayJobResult("saved", "completed", "answer", "complete", result = ChatCompletionChunk(model = "selected"), job = GatewaySavedJob("selected"))
        assertTrue(saved.matchesRequestedModel("selected"))
        assertFalse(saved.copy(job = GatewaySavedJob("other")).matchesRequestedModel("selected"))
        assertFalse(saved.copy(result = null, job = null).matchesRequestedModel("selected"))
    }

    @Test fun clientOwnedProofMustCoverTheWholeMatchingJobAndCannotComeFromModelContent() {
        val proof = GatewaySavedJob("selected", "saved", 1, "entire_job", "github_client_owned", false)
        val saved = GatewayJobResult("saved", "completed", "answer", "complete", job = proof)
        assertTrue(saved.hasClientOwnedExecutionProof)
        for (unproven in listOf(
            proof.copy(jobId = "other"),
            proof.copy(executionIsolationSchema = 2),
            proof.copy(executionIsolationScope = "last_segment"),
            proof.copy(executionIsolation = "unverified"),
            proof.copy(serverToolsAllowed = true),
            proof.copy(serverToolsAllowed = null),
            GatewaySavedJob("selected")
        )) {
            assertFalse(saved.copy(job = unproven).hasClientOwnedExecutionProof)
        }
        val forged = json.decodeFromString<GatewayJobResult>("""{"job_id":"saved","status":"completed","content":"answer","result_quality":"complete","result":{"job":{"job_id":"saved","execution_isolation_schema":1,"execution_isolation_scope":"entire_job","execution_isolation":"github_client_owned","server_tools_allowed":false}}}""")
        assertFalse(forged.hasClientOwnedExecutionProof)
    }

    @Test fun terminalConflictCannotReadAnUnboundOrDifferentJob() = runTest {
        val api = mockk<GatewayAPI>()
        val recovery = GatewayTerminalRecovery(api)
        val failure = json.parseToJsonElement("""{"code":"terminal_job_not_resumable","job_id":"other"}""").jsonObject
        val config = ProviderRequestConfig("https://gateway.test/v1", "secret", extraHeaders = mapOf("X-Gateway-Job-ID" to "saved"))
        val result = recovery.read(409, failure, config, "selected", null)
        assertEquals("terminal_job_not_resumable", result?.error?.code)
        assertTrue(result?.choices.isNullOrEmpty())
        coVerify(exactly = 0) { api.getJobResult(any(), any()) }
    }
}
