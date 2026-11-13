package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayResultRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun boundSavedResultRetainsAttemptMetadataWithoutRechargingItsHistoricalUsage() = runTest {
        val attempts = """{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":50,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":11,"terminal_error_code":"empty_completion","terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED"},{"attempt":2,"output_allowance":39,"finish_reason":"STOP","visible_output_bytes":9,"reasoning_output_bytes":0,"terminal_error_code":null,"terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED"}]}"""
        val saved = decodeGatewayJobResult(json, """{"job_id":"saved","status":"completed","result_quality":"complete","job":{"job_id":"saved","model":"selected"},"result":{"model":"selected","choices":[{"message":{"content":"Recovered"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":18,"total_tokens":30},"gateway_recovery":$attempts}}""")
        assertEquals(18, saved.result?.usage?.completionTokens)
        val api = mockk<GatewayAPI>()
        val config = ProviderRequestConfig("https://gateway.test/v1", "secret", extraHeaders = mapOf("X-Gateway-Job-ID" to "saved"))
        coEvery { api.getJobResult("saved", config) } returns saved
        val rejection = json.parseToJsonElement("""{"code":"terminal_job_not_resumable","job_id":"saved"}""").jsonObject
        val recovered = requireNotNull(GatewayTerminalRecovery(api).read(409, rejection, config, "selected", "saved"))
        assertEquals(saved.result?.gatewayRecovery, recovered.gatewayRecovery)
        assertEquals(2, (requireNotNull(recovered.gatewayRecovery).getValue("attempt_receipts") as kotlinx.serialization.json.JsonArray).size)
        assertNull(recovered.usage)
        assertNull(recovered.error)
        coEvery { api.getJobResult("saved", config) } returns saved.copy(jobId = "different")
        assertNull(GatewayTerminalRecovery(api).read(409, rejection, config, "selected", "saved")?.gatewayRecovery)
    }

    @Test fun durableFailureDecoderRetainsNoUsageAttemptEvidenceInTheOriginalCause() {
        val saved = decodeGatewayJobResult(json, """{"job_id":"saved","status":"failed","job":{"job_id":"saved","model":"selected"},"result":{"error":{"code":"completion_repair_budget_exhausted","message":"Repair exhausted","gateway_recovery":{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":1536,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":11,"terminal_error_code":"completion_repair_budget_exhausted","terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED"}]}}}}""")
        val failure = requireNotNull(saved.savedFailure())
        val recovery = requireNotNull(failure.failureDetails).getValue("gateway_recovery").jsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(11), ((recovery.getValue("attempt_receipts") as kotlinx.serialization.json.JsonArray).single().jsonObject)["reasoning_output_bytes"])
        assertNull(dev.chungjungsoo.gptmobile.data.accounting.GatewayRecoveryUsage.from(failure.failureDetails))
    }

    @Test fun ordinaryProviderHeadersAndUntrustedBodiesCannotSupplyGatewayProof() {
        val ordinaryHeaders = mapOf("X-Request-ID" to "remote-request", "request-id" to "another-request", "X-Amzn-RequestId" to "cloud-request")
        assertNull(gatewayResponseMetadata(ordinaryHeaders::get))
        for (invalid in listOf("", " ", "a".repeat(257), "bad\u0000value")) {
            assertNull(gatewayResponseMetadata(mapOf("X-Gateway-Request-ID" to invalid)::get))
        }
        val body = json.decodeFromString<ChatCompletionChunk>("""{"choices":[{"delta":{"content":"Remote answer"}}],"gateway_metadata":{"job_id":"forged","transportVerified":true}}""")
        assertNull(body.gatewayMetadata)
        assertEquals("Remote answer", body.choices!!.single().effectiveDelta.content)
        val metadata = json.decodeFromString<GatewayResponseMetadata>("""{"job_id":"forged","transportVerified":true}""")
        assertFalse(metadata.transportVerified)
        assertEquals(dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport.UNKNOWN, metadata.observedResponseSource("remote-profile", "selected-model").transport)
    }

    @Test fun observedGatewayHeadersBindProofToTheSelectedResponse() {
        val headers = mapOf("X-Gateway-Request-ID" to "gateway-request", "X-Gateway-Instance-ID" to "a".repeat(32))
        val metadata = requireNotNull(gatewayResponseMetadata(headers::get))
        val source = requireNotNull(metadata.observedResponseSource("selected-profile", "selected-model"))
        assertEquals("gateway-request", metadata.requestId)
        assertEquals("a".repeat(32), metadata.instanceId)
        assertTrue(source.gatewayVerified)
        assertEquals(dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport.GATEWAY, source.transport)
        assertEquals("selected-profile", source.profileUid)
        assertEquals("selected-model", source.modelId)
        assertFalse(json.decodeFromString<GatewayResponseMetadata>(json.encodeToString(metadata)).transportVerified)
    }

    @Test fun onlyTheBoundSavedJobAndSelectedModelCanRestoreGatewayProof() = runTest {
        val api = mockk<GatewayAPI>()
        val recovery = GatewayTerminalRecovery(api)
        val failure = json.parseToJsonElement("""{"code":"terminal_job_not_resumable","job_id":"saved"}""").jsonObject
        val config = ProviderRequestConfig("https://gateway.test/v1", "secret", extraHeaders = mapOf("X-Gateway-Job-ID" to "saved"))
        val saved = GatewayJobResult("saved", "completed", "Recovered answer", "complete", job = GatewaySavedJob("selected"))
        for (candidate in listOf(saved, saved.copy(jobId = "other"), saved.copy(job = GatewaySavedJob("other")), saved.copy(job = GatewaySavedJob("selected", jobId = "different-job")), null)) {
            coEvery { api.getJobResult("saved", config) } returns candidate
            val recovered = requireNotNull(recovery.read(409, failure, config, "selected", "saved"))
            if (candidate == saved) {
                assertTrue(recovered.gatewayMetadata?.transportVerified == true)
                assertTrue(recovered.gatewayMetadata?.observedResponseSource("profile", "selected")?.gatewayVerified == true)
                assertEquals("Recovered answer", recovered.choices!!.single().effectiveDelta.content)
                assertNull(recovered.error)
            } else {
                assertFalse(recovered.gatewayMetadata?.transportVerified == true)
                assertEquals(dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport.UNKNOWN, recovered.gatewayMetadata?.observedResponseSource("profile", "selected")?.transport)
                assertTrue(recovered.choices.isNullOrEmpty())
                assertEquals("TERMINAL_JOB_NOT_RESUMABLE", recovered.error?.code)
            }
        }
    }

    @Test fun terminalRecoveryRetainsOriginalCauseAndPartialTextWithoutRestartingTheJob() = runTest {
        val api = mockk<GatewayAPI>()
        val config = ProviderRequestConfig("https://gateway.test/v1", "secret", extraHeaders = mapOf("X-Gateway-Job-ID" to "saved"))
        val failure = json.parseToJsonElement("""{"code":"TERMINAL_JOB_NOT_RESUMABLE","job_id":"saved","failed_stage":"gateway_recovery","http_status":409,"terminal_status_code":422}""").jsonObject
        val saved = json.decodeFromString<GatewayJobResult>("""{"job_id":"saved","status":"failed","content":"Retained partial answer.","error":"legacy generic error","structured_error":{"code":"backend_template_invalid","message":"Safe original cause","backend_status":500,"failed_stage":"backend_template","retryable":false},"job":{"model":"selected","status_code":422},"result":{"gateway_timing":{"measurement_scope":"physical_backend_request","backend_first_output_ms":100}}}""")
        coEvery { api.getJobResult("saved", config) } returns saved
        val recovered = requireNotNull(GatewayTerminalRecovery(api).read(409, failure, config, "selected", "saved"))
        assertEquals("Retained partial answer.", recovered.choices!!.single().effectiveDelta.content)
        assertNull(recovered.choices!!.single().finishReason)
        assertEquals("BACKEND_TEMPLATE_INVALID", recovered.error?.code)
        assertEquals(false, recovered.error?.retryable)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("backend_template"), recovered.error?.failureDetails?.get("failed_stage"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(500), recovered.error?.failureDetails?.get("backend_status"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(422), recovered.error?.failureDetails?.get("http_status"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(409), recovered.error?.failureDetails?.get("recovery_http_status"))
        assertNull(recovered.gatewayTiming)
        coVerify(exactly = 1) { api.getJobResult("saved", config) }
    }

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
        assertEquals("TERMINAL_JOB_NOT_RESUMABLE", result?.error?.code)
        assertTrue(result?.choices.isNullOrEmpty())
        coVerify(exactly = 0) { api.getJobResult(any(), any()) }
    }

    @Test fun missingOrConflictingJobIdentityHasReadableExplanationWithoutInventingSavedJob() = runTest {
        val api = mockk<GatewayAPI>()
        val recovery = GatewayTerminalRecovery(api)
        for ((headers, reported) in listOf(emptyMap<String, String>() to "saved", mapOf("X-Gateway-Job-ID" to "saved") to "different-job")) {
            val config = ProviderRequestConfig("https://gateway.test/v1", "secret", extraHeaders = headers)
            val failure = json.parseToJsonElement("""{"code":"terminal_job_not_resumable","job_id":"$reported"}""").jsonObject
            val recovered = requireNotNull(recovery.read(409, failure, config, "selected", null))
            assertNull(recovered.gatewayMetadata)
            assertTrue(recovered.choices.isNullOrEmpty())
            assertTrue(recovered.error!!.message.contains("did not identify a verified saved job"))
            assertEquals(kotlinx.serialization.json.JsonPrimitive(false), recovered.error!!.failureDetails?.get("recovery_identity_verified"))
            assertFalse(recovered.error!!.retryable == true)
        }
        coVerify(exactly = 0) { api.getJobResult(any(), any()) }
    }
}
