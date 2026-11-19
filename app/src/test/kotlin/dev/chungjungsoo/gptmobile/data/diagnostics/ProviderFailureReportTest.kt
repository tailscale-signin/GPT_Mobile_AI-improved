package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive
import dev.chungjungsoo.gptmobile.data.github.GitHubRateLimitManager
import dev.chungjungsoo.gptmobile.data.github.GitHubRateLimitResponseEvidence
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailureReportTest {
    @Test
    fun toolProtocolFrameReportOnlyRetainsAClosedParserReason() {
        val failure = dev.chungjungsoo.gptmobile.data.agent.provider.toolCall("call", "weather", "{")
            .single() as ProviderEvent.Failed
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        val fields = ProviderFailureReport.fields("run", failure, receipt)
        assertEquals(JsonPrimitive("MALFORMED_JSON"), fields["tool_protocol_frame_kind"])
        val diagnostic = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L).diagnostics)
        assertEquals(JsonPrimitive("MALFORMED_JSON"), diagnostic.values["tool_protocol_frame_kind"])
        assertTrue("TOOL_PROTOCOL_FRAME_DIAGNOSTICS_V1" in diagnostic.requiredServiceFeatures())
        val unrelated = failure.copy(errorCode = "CONTEXT_CAPACITY_EXCEEDED")
        val other = ProviderFailureReport.fields("run", unrelated, ProviderFailureReceipt.from(profile, "primary", unrelated))
        assertFalse("tool_protocol_frame_kind" in other)
    }

    private val profile = PlatformV2(uid = "private-profile", name = "Private selected profile", model = "private-model", compatibleType = ClientType.LLAMA)

    private fun profileSelectionError(registered: Boolean = true) = dev.chungjungsoo.gptmobile.data.context.GitHubProfileSelectionRejected(
        profileMatches = false,
        registeredProfilePresent = registered,
        modelMatches = false.takeIf { registered },
        requestedProfileHash = researchHash(profile.uid),
        requestedModelHash = researchHash(profile.model),
        selectedProfileHash = researchHash("PRIVATE_CONFIGURED_PROFILE"),
        registeredModelHash = researchHash("PRIVATE_REGISTERED_MODEL").takeIf { registered }
    )

    private fun assertSelectionDispatchUnavailable(context: IssueReportContext) {
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertTrue("dispatched" in context.unavailableFields)
        assertTrue("remoteAcceptance" in context.unavailableFields)
        val report = IssueReport(
            "11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
            feature = "chat", errorCode = "GITHUB_PROFILE_LOCKED", context = context
        )
        val payload = report.approvedPayload()
        val serialized = IssueReport.json.parseToJsonElement(payload).jsonObject.getValue("context").jsonObject
        assertFalse(serialized.containsKey("dispatched"))
        assertFalse(serialized.containsKey("remoteAcceptance"))
        val values = requireNotNull(context.diagnostics).values
        assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "execution_dispatch_state", "dispatched", "remote_acceptance", "partial_output_preserved").none { it in values })
        assertFalse(payload.contains("PRIVATE_"))
    }

    private fun assertProfileSelectionProjection(partial: Boolean) {
        for (registered in listOf(false, true)) {
            val error = profileSelectionError(registered)
            val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partial))
            val evidence = requireNotNull(detail.failureDetails)
            assertEquals(false, detail.retryable)
            assertEquals(JsonPrimitive(false), evidence["retryable"])
            assertEquals(JsonPrimitive(false), evidence["request_replay_allowed"])
            val direct = IssueReportContext.fromFields("CHAT_FAILURE", evidence, 1_000L)
            assertSelectionDispatchUnavailable(direct)
            assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "dispatchState", "executionState", "dispatched", "inference_dispatched", "remote_acceptance", "remoteAcceptance", "partial_output_preserved").none { it in evidence })
            assertEquals(false, direct.retryable)
            assertEquals("RETRY_AFTER_CONFIGURATION_CHANGE", direct.recoveryAction)
            val failure = ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = evidence)
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
            val fields = ProviderFailureReport.fields("private-run", failure, receipt)
            assertSelectionDispatchUnavailable(IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L))
            val projected = IssueReportContext.fromFields("CHAT_FAILURE", evidence + fields, 1_000L)
            assertSelectionDispatchUnavailable(projected)
            assertEquals(false, projected.retryable)
            assertEquals("RETRY_AFTER_CONFIGURATION_CHANGE", projected.recoveryAction)
            val capture = requireNotNull(projected.diagnostics)
            assertEquals(JsonPrimitive(false), capture.values["request_replay_allowed"])
            assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
            assertEquals(FailureDomain.APPLICATION_VERIFICATION, receipt.failureDomain)
            assertNull(receipt.dispatched)
            assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(JsonPrimitive(researchHash(profile.uid)), capture.values["profile_hash"])
            assertEquals(JsonPrimitive(researchHash(profile.model)), capture.values["model_hash"])
            for (key in IssueReportDiagnostics.profileSelectionFields) assertEquals(key, error.failureFields()[key], capture.values[key])
            assertTrue("PROFILE_SELECTION_EVIDENCE_V1" in capture.requiredServiceFeatures())
            val unrelated = failure.copy(errorCode = "CONTEXT_CAPACITY_EXCEEDED")
            val otherFields = ProviderFailureReport.fields("private-run", unrelated, ProviderFailureReceipt.from(profile, "primary", unrelated))
            assertTrue(IssueReportDiagnostics.profileSelectionFields.none { it in otherFields })
        }
    }

    @Test fun profileSelectionProjectionWithFreshInputKeepsDispatchUnavailable() = assertProfileSelectionProjection(false)

    @Test fun profileSelectionProjectionWithPartialInputKeepsDispatchUnavailable() = assertProfileSelectionProjection(true)

    @Test fun resumedSelectionFailureWithFreshFenceKeepsEarlierZeroOutput429Evidence() {
        val earlierFence = dev.chungjungsoo.gptmobile.data.context.PreparationReplayFence()
        earlierFence.dispatched()
        val earlier = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf(
                "diagnostic_area" to "PROVIDER",
                "provider_kind" to "LLAMA",
                "failed_stage" to "provider_generation",
                "provider_failure_code" to "PROVIDER_RATE_LIMITED",
                "http_status" to 429,
                "dispatched" to earlierFence.hasAttempted,
                "remote_acceptance" to "NO",
                "output_tokens" to 0
            ),
            1_000L
        )
        val retained = IssueReport.json.encodeToString(IssueReportContext.serializer(), earlier)
        val resumedFence = dev.chungjungsoo.gptmobile.data.context.PreparationReplayFence()
        assertTrue(earlierFence.hasAttempted)
        assertFalse(resumedFence.hasAttempted)
        val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(profileSelectionError(), resumedFence.hasAttempted))
        val direct = IssueReportContext.fromFields("CHAT_FAILURE", requireNotNull(detail.failureDetails), 2_000L)
        assertSelectionDispatchUnavailable(direct)
        val failure = ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = detail.failureDetails)
        val projected = IssueReportContext.fromFields("CHAT_FAILURE", requireNotNull(detail.failureDetails) + ProviderFailureReport.fields("same-run", failure, ProviderFailureReceipt.from(profile, "primary", failure)), 2_000L)
        assertSelectionDispatchUnavailable(projected)
        assertEquals(true, earlier.dispatched)
        assertEquals("NO", earlier.remoteAcceptance)
        assertEquals(429, earlier.httpStatus)
        assertEquals(JsonPrimitive(0), requireNotNull(earlier.diagnostics).values["output_tokens"])
        assertEquals(retained, IssueReport.json.encodeToString(IssueReportContext.serializer(), earlier))
    }

    @Test fun profileSelectionReceiptDoesNotOverrideAnUncertainRemoteOutcome() {
        for (partial in listOf(false, true)) {
            val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(profileSelectionError(), partial))
            val failure = ProviderEvent.Failed(
                "GATEWAY_OUTCOME_UNKNOWN: retained remote operation",
                errorCode = detail.code,
                failureDetails = detail.failureDetails
            )
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
            assertEquals(ProviderFailureStage.GENERATION, receipt.stage)
            assertEquals(FailureDomain.UNKNOWN, receipt.failureDomain)
            assertEquals(true, receipt.dispatched)
            assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
        }
    }

    private fun cooldownError(secondary: Boolean = false, resetEpochSeconds: Long = 130L): GitHubCooldownActive {
        val manager = GitHubRateLimitManager()
        if (secondary) {
            manager.record(headersOf("X-RateLimit-Resource" to listOf("PRIVATE_UNRELATED_RESOURCE"), "X-RateLimit-Remaining" to listOf("17")), nowMillis = 90_000L)
            manager.cooldown(30L, 100_000L)
        } else {
            manager.record(headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(resetEpochSeconds.toString())), nowMillis = 100_000L)
        }
        val error = runCatching { manager.requireAvailable(if (secondary) "search" else "core", 110L) }.exceptionOrNull() as GitHubCooldownActive
        manager.record(headersOf("X-RateLimit-Resource" to listOf("PRIVATE_LATER_RESOURCE"), "X-RateLimit-Remaining" to listOf("9")), nowMillis = 120_000L)
        return error
    }

    private fun assertCooldownDispatchUnavailable(context: IssueReportContext, error: GitHubCooldownActive) {
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertNull(context.httpStatus)
        assertTrue(context.unavailableFields.containsAll(listOf("dispatched", "remoteAcceptance", "httpStatus")))
        val capture = requireNotNull(context.diagnostics)
        for (key in IssueReportDiagnostics.githubCooldownFields) assertEquals(key, error.failureFields()[key], capture.values[key])
        assertTrue("GH_COOLDOWN_V1" in capture.requiredServiceFeatures())
        assertEquals(JsonPrimitive(false), capture.values["request_replay_allowed"])
        assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "execution_dispatch_state", "dispatched", "remote_acceptance", "partial_output_preserved").none { it in capture.values })
        val report = IssueReport(
            "11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
            feature = "chat", errorCode = "GITHUB_COOLDOWN_ACTIVE", context = context
        )
        val payload = report.approvedPayload()
        val serialized = IssueReport.json.parseToJsonElement(payload).jsonObject.getValue("context").jsonObject
        assertTrue(listOf("dispatched", "remoteAcceptance", "httpStatus").none { it in serialized })
        assertFalse(payload.contains("PRIVATE_"))
    }

    @Test fun actualCooldownProjectionRetainsCapturedEvidenceAndUnknownDispatchForBothInputs() {
        for (secondary in listOf(false, true)) {
            val error = cooldownError(secondary)
            assertEquals(20_000L, error.delayMillis)
            assertEquals(130_000L, error.retryNotBeforeMillis)
            assertEquals(if (secondary) null else 100_000L, error.observedAtMillis)
            assertEquals(if (secondary) null else 0L, error.remainingRequests)
            for (captured in listOf(error, GitHubRequestRejected(0, "PRIVATE_WRAPPER").apply { initCause(error) })) {
                for (partial in listOf(false, true)) {
                    val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(captured, partial))
                    val evidence = requireNotNull(detail.failureDetails)
                    assertEquals(false, detail.retryable)
                    assertEquals(JsonPrimitive(false), evidence["retryable"])
                    assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "dispatchState", "executionState", "dispatched", "inference_dispatched", "remote_acceptance", "remoteAcceptance", "partial_output_preserved", "http_status").none { it in evidence })
                    val direct = IssueReportContext.fromFields("CHAT_FAILURE", evidence, 1_000L)
                    assertCooldownDispatchUnavailable(direct, error)
                    assertEquals("repository_admission", direct.stage)
                    assertEquals(false, direct.retryable)
                    assertEquals("RETRY_AFTER_BACKOFF", direct.recoveryAction)
                    assertEquals(20_000L, direct.retryAfterMillis)
                    val failure = ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = evidence)
                    val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
                    assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
                    assertEquals(FailureDomain.APPLICATION_VERIFICATION, receipt.failureDomain)
                    assertNull(receipt.dispatched)
                    assertNull(receipt.httpStatus)
                    assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
                    assertEquals(false, receipt.requestReplayAllowed)
                    val fields = ProviderFailureReport.fields("private-run", failure, receipt)
                    val reportOnly = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
                    assertCooldownDispatchUnavailable(reportOnly, error)
                    assertEquals("repository_admission", reportOnly.stage)
                    assertEquals(false, reportOnly.retryable)
                    assertEquals("RETRY_AFTER_BACKOFF", reportOnly.recoveryAction)
                    assertEquals(20_000L, reportOnly.retryAfterMillis)
                    val projected = IssueReportContext.fromFields("CHAT_FAILURE", evidence + fields, 1_000L)
                    assertCooldownDispatchUnavailable(projected, error)
                    assertEquals(false, projected.retryable)
                    assertEquals("RETRY_AFTER_BACKOFF", projected.recoveryAction)
                    assertEquals(20_000L, projected.retryAfterMillis)
                    val unrelated = failure.copy(errorCode = "CONTEXT_CAPACITY_EXCEEDED")
                    val otherFields = ProviderFailureReport.fields("private-run", unrelated, ProviderFailureReceipt.from(profile, "primary", unrelated))
                    assertTrue(IssueReportDiagnostics.githubCooldownFields.none { it in otherFields })
                }
            }
        }
    }

    @Test fun longCooldownReportKeepsActualDelayWithoutClampingTheOlderRetryField() {
        val error = cooldownError(resetEpochSeconds = Long.MAX_VALUE)
        assertTrue(error.delayMillis > 86_400_000L)
        for (partial in listOf(false, true)) {
            val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partial))
            val evidence = requireNotNull(detail.failureDetails)
            assertFalse(evidence.containsKey("retry_after_ms"))
            val failure = ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = evidence)
            val fields = evidence + ProviderFailureReport.fields("private-run", failure, ProviderFailureReceipt.from(profile, "primary", failure))
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
            assertCooldownDispatchUnavailable(context, error)
            assertNull(context.retryAfterMillis)
            assertTrue("retryAfterMillis" in context.unavailableFields)
            assertEquals(JsonPrimitive(error.delayMillis), context.diagnostics?.values?.get("github_cooldown_delay_ms"))
            assertEquals(JsonPrimitive(error.retryNotBeforeMillis), context.diagnostics?.values?.get("github_cooldown_deadline_ms"))
        }
    }

    @Test fun cooldownReceiptRequiresItsObservedStageAndPreservesUnknownOutcomePrecedence() {
        val error = cooldownError()
        for (partial in listOf(false, true)) {
            val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partial))
            val failure = ProviderEvent.Failed("GATEWAY_OUTCOME_UNKNOWN: retained remote operation", errorCode = detail.code, failureDetails = detail.failureDetails)
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
            assertEquals(ProviderFailureStage.GENERATION, receipt.stage)
            assertEquals(FailureDomain.UNKNOWN, receipt.failureDomain)
            assertEquals(true, receipt.dispatched)
            assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            val withoutStage = failure.copy(message = detail.message.orEmpty(), failureDetails = kotlinx.serialization.json.JsonObject(requireNotNull(detail.failureDetails) - "failed_stage"))
            val unobserved = ProviderFailureReceipt.from(profile, "primary", withoutStage)
            assertEquals(ProviderFailureStage.GENERATION, unobserved.stage)
            assertEquals(FailureDomain.UNKNOWN, unobserved.failureDomain)
            assertNull(unobserved.dispatched)
        }
    }

    private fun rateResponseError(code: String, measured: Boolean): GitHubRequestRejected {
        val primary = code == "GITHUB_PRIMARY_RATE_LIMITED"
        val status = if (primary) 403 else 429
        val headers = when {
            !measured -> headersOf()
            primary -> headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("130"))
            else -> headersOf("X-RateLimit-Resource" to listOf("search"), "X-RateLimit-Remaining" to listOf("7"), "Retry-After" to listOf("20"))
        }
        return GitHubRequestRejected(status, "PRIVATE_HTTP_BODY_AND_TOKEN", requireNotNull(GitHubRateLimitResponseEvidence.fromResponse(status, code, headers, 100_000L)))
    }

    @Test fun actualGitHubRateResponseProjectionRetainsItsOwnHttpAndOptionalCooldownEvidence() {
        for (code in listOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")) {
            for (measured in listOf(false, true)) {
                val error = rateResponseError(code, measured)
                for (partial in listOf(false, true)) {
                    val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partial, nowMillis = 999_000L))
                    val evidence = requireNotNull(detail.failureDetails)
                    assertEquals(code, detail.code)
                    assertEquals(false, detail.retryable)
                    assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "dispatchState", "executionState", "dispatched", "inference_dispatched", "remote_acceptance", "remoteAcceptance", "partial_output_preserved").none { it in evidence })
                    val failure = ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = evidence)
                    val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
                    assertEquals("LLAMA", receipt.provider)
                    assertEquals(ProviderFailureStage.GENERATION, receipt.stage)
                    assertEquals(FailureDomain.TOOL_EXECUTION, receipt.failureDomain)
                    assertEquals("tool_execution", receipt.causeStage)
                    assertEquals(error.status, receipt.httpStatus)
                    assertNull(receipt.dispatched)
                    assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
                    assertEquals(false, receipt.requestReplayAllowed)
                    val fields = ProviderFailureReport.fields("private-run", failure, receipt)
                    for (projected in listOf(evidence, fields, evidence + fields)) {
                        val context = IssueReportContext.fromFields("CHAT_FAILURE", projected + mapOf("provider_kind" to "LLAMA"), 1_000L)
                        val capture = requireNotNull(context.diagnostics)
                        assertEquals("LLAMA", context.provider)
                        assertEquals("tool_execution", context.stage)
                        assertEquals(error.status, context.httpStatus)
                        assertNull(context.dispatched)
                        assertNull(context.remoteAcceptance)
                        assertTrue(context.unavailableFields.containsAll(listOf("dispatched", "remoteAcceptance")))
                        assertEquals(false, context.retryable)
                        assertEquals("RETRY_AFTER_BACKOFF", context.recoveryAction)
                        assertEquals(
                            if (!measured) {
                                null
                            } else if (code == "GITHUB_PRIMARY_RATE_LIMITED") {
                                30_000L
                            } else {
                                20_000L
                            },
                            context.retryAfterMillis
                        )
                        assertEquals(JsonPrimitive(code), capture.values["provider_failure_code"])
                        assertEquals(JsonPrimitive("TOOL_EXECUTION"), capture.values["provider_failure_domain"])
                        assertEquals(JsonPrimitive(false), capture.values["request_replay_allowed"])
                        for (key in IssueReportDiagnostics.githubCooldownFields) assertEquals(key, error.evidence?.get(key), capture.values[key])
                        assertEquals(JsonPrimitive(100_000L), capture.values["github_cooldown_observed_at_ms"])
                        assertTrue("GH_COOLDOWN_V1" in capture.requiredServiceFeatures())
                        val report = IssueReport(
                            "11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
                            feature = "chat", errorCode = code, context = context
                        )
                        val payload = report.approvedPayload()
                        val serialized = IssueReport.json.parseToJsonElement(payload).jsonObject.getValue("context").jsonObject
                        assertFalse(serialized.containsKey("dispatched"))
                        assertFalse(serialized.containsKey("remoteAcceptance"))
                        assertFalse(payload.contains("PRIVATE_"))
                    }
                }
            }
        }
    }

    @Test fun githubRateReceiptDoesNotOverrideUnknownOutcomeOrInferToolDomainFromCodeAlone() {
        for (code in listOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")) {
            val error = rateResponseError(code, measured = true)
            val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, false))
            val failure = ProviderEvent.Failed("GATEWAY_OUTCOME_UNKNOWN: retained remote operation", errorCode = detail.code, failureDetails = detail.failureDetails)
            val uncertain = ProviderFailureReceipt.from(profile, "primary", failure)
            assertEquals(ProviderFailureStage.GENERATION, uncertain.stage)
            assertFalse(uncertain.failureDomain == FailureDomain.TOOL_EXECUTION)
            assertEquals(true, uncertain.dispatched)
            assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, uncertain.remoteAcceptance)
            assertEquals(false, uncertain.requestReplayAllowed)
            for (missing in listOf("failed_stage", "http_status")) {
                val incomplete = failure.copy(message = detail.message.orEmpty(), failureDetails = kotlinx.serialization.json.JsonObject(requireNotNull(detail.failureDetails) - missing))
                assertFalse(ProviderFailureReceipt.from(profile, "primary", incomplete).failureDomain == FailureDomain.TOOL_EXECUTION)
            }
        }
    }

    @Test fun gatewayConflictReportIdentifiesTheBlockingHandoffWithoutClaimingLocalOwnership() {
        val details = requireNotNull(
            dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure(
                """{"error":{"code":"conversation_conflict","failed_stage":"conversation_admission","dispatched":false,"active_job_kind":"generation","active_job_state":"WAITING_FOR_CLIENT_TOOL","conversation_id":"private-conversation","conversation_revision":7,"pending_client_tool_count":2}}""",
                409
            )
        )
        val failure = ProviderEvent.Failed("private prompt", errorCode = "CONVERSATION_CONFLICT", failureDetails = details)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        val fields = ProviderFailureReport.fields("run", failure, receipt)
        val capture = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1000))
        assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
        assertEquals(JsonPrimitive("CONVERSATION_CONFLICT"), capture.values["provider_failure_code"])
        assertEquals(JsonPrimitive("generation"), capture.values["conflict_active_job_kind"])
        assertEquals(JsonPrimitive(7), capture.values["conflict_conversation_revision"])
        assertEquals(JsonPrimitive(2), capture.values["conflict_pending_tool_count"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("conversation", "private-conversation")), capture.values["conflict_conversation_hash"])
        assertTrue("SESSION_RECOVERY_DIAGNOSTICS_V1" in capture.requiredServiceFeatures())
        assertTrue("CONVERSATION_CONFLICT_EVIDENCE_V1" in capture.requiredServiceFeatures())
        assertEquals(JsonPrimitive("UNREPORTED"), capture.values["conflict_active_job_provenance"])
        assertEquals(JsonPrimitive("NOT_REPORTED"), capture.values["conflict_pending_tool_state"])
        assertEquals(JsonPrimitive("ACTIVE_JOB_NOT_REPORTED"), capture.values["conflict_stream_job_relation"])
        assertFalse(capture.toString().contains("private"))
    }

    @Test fun conflictJobRelationUsesOnlyVerifiedStreamMetadataAndKeepsPendingIdentityHashed() {
        val failure = ProviderEvent.Failed(
            "private prompt",
            errorCode = "CONVERSATION_CONFLICT",
            failureDetails = buildJsonObject {
                put("active_job_id", "private-active-job")
                put("active_job_provenance", "CONVERSATION_ACTIVE_JOB")
                put("pending_client_tool_state", "IDENTIFIED")
                put("pending_client_tool_count", 2)
                put("pending_client_tool_ids_hash", "a".repeat(64))
                put("client_tool_handoff_sequence", 3)
            }
        )
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        for ((metadata, relation) in listOf(
            null to "NO_VERIFIED_STREAM_JOB",
            dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata(jobId = "private-active-job") to "NO_VERIFIED_STREAM_JOB",
            dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata(jobId = "private-active-job", transportVerified = true) to "MATCHES_VERIFIED_STREAM_JOB",
            dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata(jobId = "private-other-job", transportVerified = true) to "DIFFERS_VERIFIED_STREAM_JOB"
        )) {
            val fields = ProviderFailureReport.fields("run", failure, receipt, metadata = metadata)
            val capture = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1000))
            assertEquals(JsonPrimitive(relation), capture.values["conflict_stream_job_relation"])
            assertEquals(JsonPrimitive("a".repeat(64)), capture.values["conflict_pending_tool_ids_hash"])
            assertEquals(JsonPrimitive(3), capture.values["conflict_handoff_sequence"])
            assertEquals(JsonPrimitive("IDENTIFIED"), capture.values["conflict_pending_tool_state"])
            assertEquals(JsonPrimitive("CONVERSATION_ACTIVE_JOB"), capture.values["conflict_active_job_provenance"])
            assertFalse(capture.toString().contains("private"))
        }
    }

    @Test fun capacityComponentAndProvenanceEvidenceSurvivesTheFailureProjection() {
        val evidence = buildJsonObject {
            put("admission_system_tokens", 2000)
            put("admission_history_tokens", 2500)
            put("admission_current_request_tokens", 500)
            put("admission_tool_result_tokens", 400)
            put("admission_response_schema_tokens", 100)
            put("admission_envelope_tokens", 547)
            put("admission_route_context_tokens", 5632)
            put("admission_configured_context_tokens", 8192)
            put("admission_catalog_context_tokens", 32768)
            put("admission_input_ceiling_tokens", 5632)
            put("admission_output_ceiling_tokens", 4096)
            put("admission_capacity_age_ms", 1000)
            put("admission_capacity_provenance", "VERIFIED_ROUTE")
        }
        val failure = ProviderEvent.Failed("private prompt", errorCode = "CONTEXT_CAPACITY_EXCEEDED", failureDetails = evidence)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        val fields = ProviderFailureReport.fields("run", failure, receipt, configured = ProviderEvent.RequestConfigured(configuredProfileOutputTokens = 0))
        val capture = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1000))
        evidence.forEach { (key, value) -> assertEquals(key, value, capture.values[key]) }
        assertEquals(JsonPrimitive(0), capture.values["configured_output_limit"])
        assertFalse("effective_output_limit" in capture.values)
        assertTrue("CAPACITY_ADMISSION_EVIDENCE_V1" in capture.requiredServiceFeatures())
        assertTrue(capture.isValid())
        assertFalse(capture.toString().contains("private"))
    }

    @Test fun upstream429ReportsHeaderOrBackoffAndCountWithoutInferringScheduledRecovery() {
        for ((source, expected) in listOf("RETRY_AFTER" to "PROVIDER_HEADER", "APP_BACKOFF" to "LOCAL_BACKOFF", "untrusted" to null)) {
            val failure = ProviderEvent.Failed(
                "rate limited",
                errorCode = "PROVIDER_RATE_LIMITED",
                failureDetails = buildJsonObject {
                    put("retry_source", source)
                    put("consecutive_rejections", 2)
                }
            )
            val fields = ProviderFailureReport.fields("run", failure, ProviderFailureReceipt.from(profile, "primary", failure))
            assertEquals(expected?.let(::JsonPrimitive), fields["retry_delay_source"])
            assertEquals(JsonPrimitive(2), fields["provider_consecutive_rejections"])
            assertNull(fields["recovery_observation"])
        }
    }

    @Test fun cooldownReportDistinguishesWaitEligibilityFromCheckpointAvailability() {
        val limit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision(
            provider = "LLAMA",
            profileUid = profile.uid,
            model = profile.model,
            retryAt = 2_000L,
            scope = "model",
            category = "rate_limit",
            knownNotAccepted = true,
            consecutiveRejections = 3
        )
        val failure = ProviderEvent.Failed("private", errorCode = "PROVIDER_RATE_LIMITED", providerLimit = limit)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure, null)
        val fields = ProviderFailureReport.fields("run", failure, receipt)
        assertEquals(JsonPrimitive(true), fields["provider_wait_eligible"])
        assertEquals(JsonPrimitive(false), fields["provider_checkpoint_present"])
        assertEquals(JsonPrimitive(3), fields["provider_consecutive_rejections"])
        val foreign = ProviderFailureReport.fields("run", failure.copy(providerLimit = limit.copy(profileUid = "other")), receipt)
        assertNull(foreign["provider_wait_eligible"])
    }

    @Test fun actualStreamExportsCompletionBudgetCauseCapAndOwnedUsageWithoutAnotherRequest() = runBlocking {
        val failure = ProviderEvent.Failed(
            "private provider detail",
            errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED",
            failureDetails = buildJsonObject {
                put("failed_stage", "provider_generation")
                put("http_status", 200)
                put("job_id", "private-job")
                put("gateway_recovery", Json.parseToJsonElement("""{"schema_version":1,"attempts":2,"completed_attempts":1,"failed_attempt":2,"attempt_receipts":[{"attempt":1,"finish_reason":"LENGTH","status":"COMPLETED","backend_response_observed":true},{"attempt":2,"output_allowance":40,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":512,"terminal_error_code":"completion_repair_budget_exhausted","terminal_status_code":200,"status":"FAILED"}]}"""))
            }
        )
        val events = listOf(
            ProviderEvent.RequestConfigured(configuredProfileOutputTokens = 1024, effectiveOutputTokens = 256, serializedOutputTokens = 128),
            ProviderEvent.Usage(inputTokens = 30, outputTokens = 80, reasoningTokens = 70, outputSource = "gateway_reported"),
            ProviderEvent.Usage(finishReason = "length"),
            failure
        )
        val source = Session(events)
        val observed = withContext(RequestDispatchContext(emptySet(), "owned-run", "owned-invocation")) {
            withProviderFailureReceipts(source, "owned-run", profile, "primary").streamRound(emptyList(), emptyList()).toList()
        }.filterIsInstance<ProviderEvent.Failed>().single()
        val context = IssueReportContext.fromFields("CHAT_FAILURE", observed.failureDetails.orEmpty(), 1_000_000)
        val diagnostic = requireNotNull(context.diagnostics)
        val values = diagnostic.values
        assertEquals(1, source.calls)
        assertEquals(failure.errorCode, observed.errorCode)
        assertEquals(JsonPrimitive(researchHash(profile.model)), values["model_hash"])
        assertEquals(JsonPrimitive(1024), values["configured_output_limit"])
        assertEquals(JsonPrimitive(128), values["effective_output_limit"])
        assertEquals(JsonPrimitive(30), values["input_tokens"])
        assertEquals(JsonPrimitive(80), values["output_tokens"])
        assertEquals(JsonPrimitive(70), values["reasoning_tokens"])
        assertEquals(JsonPrimitive("gateway_reported"), values["usage_source"])
        assertEquals(JsonPrimitive("LENGTH"), values["finish_reason"])
        assertEquals(JsonPrimitive(2), values["backend_attempts"])
        assertEquals(JsonPrimitive(2), values["backend_failed_attempt"])
        assertEquals(JsonPrimitive(40), values["backend_output_limit"])
        assertEquals(JsonPrimitive("LAST_BACKEND_ATTEMPT"), values["output_byte_scope"])
        assertEquals(JsonPrimitive(512), values["reasoning_output_bytes"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("backend-error-code", "completion_repair_budget_exhausted")), values["backend_terminal_code_hash"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("invocation", "owned-invocation")), values["invocation_id_hash"])
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).contains("private"))
        assertTrue(diagnostic.isValid())
    }

    @Test fun unrelatedUsageCannotSupplyTokensAndDeliveryCannotRelabelTheSourceProfile() = runBlocking {
        val source = Session(
            listOf(
                ProviderEvent.RequestConfigured(configuredProfileOutputTokens = 256, effectiveOutputTokens = 128),
                ProviderEvent.Usage(outputTokens = 9876, reasoningTokens = 9000, invocationId = "other-invocation"),
                ProviderEvent.Failed("failed", errorCode = "CONNECTION_ABORTED", invocationId = "owned-invocation")
            )
        )
        val inner = withProviderFailureReceipts(source, "owned-run", profile, "primary")
        val outer = withProviderFailureReceipts(inner, "owned-run", profile.copy(uid = "other-profile", model = "other-model"), "github-delivery")
        val failure = withContext(RequestDispatchContext(emptySet(), "owned-run", "owned-invocation")) {
            outer.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
        }
        val fields = requireNotNull(failure.failureDetails)
        assertNull(fields["output_tokens"])
        assertNull(fields["reasoning_tokens"])
        assertEquals(JsonPrimitive(researchHash(profile.model)), fields["model_hash"])
        assertEquals(JsonPrimitive(128), fields["effective_output_limit"])
        assertEquals(1, source.calls)
    }

    @Test fun localBudgetRefusalKeepsEarlierHttpAndUsageOffItsReceipt() = runBlocking {
        val timing = dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming(httpStatus = 502, serverRequestId = "previous-request")
        val local = ProviderEvent.Failed(
            "Stopped at local budget",
            errorCode = "BUDGET_RESTRICTED",
            failureDetails = buildJsonObject {
                put("provider_local_budget_block", true)
                put("failed_stage", "provider_admission")
                put("request_replay_allowed", false)
            }
        )
        val source = Session(listOf(ProviderEvent.Usage(providerResponseTiming = timing, outputTokens = 32), local))
        val events = withContext(RequestDispatchContext(emptySet(), "run", "same-invocation")) {
            withProviderFailureReceipts(source, "run", profile, "primary").streamRound(emptyList(), emptyList()).toList()
        }
        assertEquals(32, events.filterIsInstance<ProviderEvent.Usage>().single().outputTokens)
        val fields = requireNotNull(events.filterIsInstance<ProviderEvent.Failed>().single().failureDetails)
        assertEquals(JsonPrimitive("BUDGET_RESTRICTED"), fields["provider_failure_code"])
        assertEquals(JsonPrimitive("APPLICATION"), fields["failure_origin"])
        assertEquals(JsonPrimitive("ADMISSION"), fields["provider_failure_stage"])
        assertEquals(JsonPrimitive("APPLICATION_VERIFICATION"), fields["provider_failure_domain"])
        assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
        for (key in listOf("http_status", "server_request_id", "generation_id", "output_tokens")) assertNull(key, fields[key])
    }

    @Test fun successfulHttpResponseDoesNotClassifyAnUnknownLogicalFailureAsHttpError() {
        val failure = ProviderEvent.Failed("incomplete answer", failureDetails = buildJsonObject { put("http_status", 200) })
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        assertEquals(200, receipt.httpStatus)
        assertEquals("UNCLASSIFIED_PROVIDER_FAILURE", receipt.errorCode)
    }

    @Test fun eachRoundAndMissingOwnerKeepUnknownMeasurementsUnknown() = runBlocking {
        val source = Session(listOf(ProviderEvent.Failed("no usage", errorCode = "PROVIDER_INVOCATION_FAILED")))
        val wrapped = withProviderFailureReceipts(source, "run", profile, "primary")
        repeat(2) {
            val failure = wrapped.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
            val fields = requireNotNull(failure.failureDetails)
            assertNull(fields["configured_output_limit"])
            assertNull(fields["output_tokens"])
            assertNull(fields["visible_output_bytes"])
            assertNull(fields["invocation_id_hash"])
        }
        val approval = ProviderEvent.Failed("approval pending", errorCode = "WAITING_FOR_APPROVAL")
        val result = withProviderFailureReceipts(Session(listOf(approval)), "run", profile, "github-delivery")
            .streamRound(emptyList(), emptyList()).toList().single()
        assertEquals(approval, result)
    }

    @Test fun owningPreflightFailureRetainsHeaderIdentityAcceptanceAndUsageBeforeDelivery() = runBlocking {
        val timing = dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming(httpStatus = 200, serverRequestId = "request-observed", responseHeadersMs = 45)
        val source = Session(
            listOf(
                ProviderEvent.GatewayMetadataCaptured(dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata(requestId = "request-observed", transportVerified = true)),
                ProviderEvent.Usage(providerResponseTiming = timing),
                ProviderEvent.Usage(outputTokens = 2048, outputSource = "gateway_reported", finishReason = "length"),
                ProviderEvent.Failed("No visible verdict", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", failureDetails = buildJsonObject { put("failed_stage", "provider_generation") })
            )
        )
        val failure = withContext(RequestDispatchContext(emptySet(), "run", "owned-probe")) {
            withProviderFailureReceipts(source, "run", profile, "github-review-preflight-output-retry").streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
        }
        assertEquals("owned-probe", failure.invocationId)
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("YES"), failure.failureDetails?.get("remote_acceptance"))
        assertEquals(JsonPrimitive("request-observed"), failure.failureDetails?.get("server_request_id"))
        assertEquals(JsonPrimitive(200), failure.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive(2048), failure.failureDetails?.get("output_tokens"))
        assertEquals(JsonPrimitive("REVIEW_COMPATIBILITY"), failure.failureDetails?.get("provider_failure_stage"))
        assertFalse(failure.transportComplete)
        assertNull(failure.failureDetails?.get("generation_completed"))
        val context = IssueReportContext.fromFields("CHAT_FAILURE", failure.failureDetails.orEmpty(), 1000)
        assertEquals(true, context.dispatched)
        assertEquals("YES", context.remoteAcceptance)
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("server-request", "request-observed")), context.diagnostics?.values?.get("server_request_id_hash"))
        assertFalse(context.toString().contains("request-observed"))
    }

    @Test fun foreignOrUnverifiedMetadataCannotSupplyFailureAcceptanceOrIdentity() = runBlocking {
        for (mode in listOf("foreign", "unverified", "delivery")) {
            val source = Session(
                listOf(
                    ProviderEvent.GatewayMetadataCaptured(dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata(requestId = "unrelated-request", transportVerified = mode != "unverified")),
                    ProviderEvent.Usage(providerResponseTiming = dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming(httpStatus = 200, serverRequestId = "unrelated-request"), invocationId = "different-invocation"),
                    ProviderEvent.Failed("Failed", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = if (mode == "foreign") "foreign-invocation" else "owned-invocation")
                )
            )
            val failure = withContext(RequestDispatchContext(emptySet(), "run", "owned-invocation")) {
                withProviderFailureReceipts(source, "run", profile, if (mode == "delivery") "github-delivery" else "primary").streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
            }
            assertNull(failure.failureDetails?.get("server_request_id"))
            assertNull(failure.failureDetails?.get("http_status"))
            assertNull(failure.failureDetails?.get("dispatched"))
            assertNull(failure.failureDetails?.get("remote_acceptance"))
        }
    }

    @Test fun explicitUnknownOutcomeIsNotConvertedToRejectedOrCompletedByLaterHeaders() = runBlocking {
        val original = ProviderEvent.Failed(
            "Uncertain earlier attempt",
            errorCode = "GATEWAY_OUTCOME_UNKNOWN",
            failureDetails = buildJsonObject {
                put("requires_reconciliation", true)
                put("remote_acceptance", "UNKNOWN")
                put("request_replay_allowed", false)
            }
        )
        val failure = withContext(RequestDispatchContext(emptySet(), "run", "owned-invocation")) {
            withProviderFailureReceipts(Session(listOf(ProviderEvent.Usage(providerResponseTiming = dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming(httpStatus = 409)), original)), "run", profile, "primary")
                .streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
        }
        assertEquals(JsonPrimitive("UNKNOWN"), failure.failureDetails?.get("remote_acceptance"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
        assertNull(failure.failureDetails?.get("generation_completed"))
        assertFalse(failure.transportComplete)
    }

    @Test fun finishOnlyUsageDoesNotSuppressFailureAttachedMeasuredTokens() {
        val failure = ProviderEvent.Failed(
            "Probe exhausted",
            errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED",
            invocationId = "probe",
            failureDetails = buildJsonObject {
                put("gateway_recovery", Json.parseToJsonElement("""{"usage_origin":"PROVIDER_REPORTED","usage_estimated":false,"attempts":1,"attempt_usage":[{"prompt_tokens":509,"completion_tokens":2048}]}"""))
            }
        )
        val receipt = ProviderFailureReceipt.from(profile, "github-review-preflight", failure, ProviderEvent.Usage(finishReason = "length", invocationId = "probe"))
        assertEquals(2048, receipt.outputTokens)
        assertEquals("gateway_reported", receipt.outputTokenSource)
        assertEquals(ProviderFinishCategory.LENGTH, receipt.finishReason)
        assertEquals("probe", receipt.usageInvocationId)
    }

    private class Session(private val events: List<ProviderEvent>) : AgentProviderSession {
        var calls = 0
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
            calls++
            return events.asFlow()
        }
    }

    @Test fun pendingGatewayResultsReportSafeHandoffIdentityWithoutReplayingCalls() {
        val failure = ProviderEvent.Failed(
            "private gateway handoff details",
            errorCode = "CLIENT_TOOL_RESULTS_REQUIRED",
            failureDetails = buildJsonObject {
                put("active_job_kind", "generation")
                put("active_job_provenance", "CONVERSATION_ACTIVE_JOB")
                put("active_job_id", "private-job-123")
                put("conversation_id", "private-conversation")
                put("conversation_revision", 7)
                put("pending_client_tool_count", 2)
                put("pending_client_tool_state", "IDENTIFIED")
                put("pending_client_tool_ids_hash", "a".repeat(64))
                put("client_tool_handoff_sequence", 3)
            }
        )
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        assertEquals("CLIENT_TOOL_RESULTS_REQUIRED", receipt.errorCode)
        assertTrue(receipt.valid())
        val fields = ProviderFailureReport.fields("run", failure, receipt)
        val diagnostics = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000L))
        assertEquals(JsonPrimitive("CLIENT_TOOL_RESULTS_REQUIRED"), diagnostics.values["provider_failure_code"])
        assertEquals(JsonPrimitive(2), diagnostics.values["conflict_pending_tool_count"])
        assertEquals(JsonPrimitive(3), diagnostics.values["conflict_handoff_sequence"])
        assertEquals(JsonPrimitive("IDENTIFIED"), diagnostics.values["conflict_pending_tool_state"])
        assertEquals(JsonPrimitive("a".repeat(64)), diagnostics.values["conflict_pending_tool_ids_hash"])
        assertEquals(JsonPrimitive("NO_VERIFIED_STREAM_JOB"), diagnostics.values["conflict_stream_job_relation"])
        assertTrue("SESSION_RECOVERY_DIAGNOSTICS_V1" in diagnostics.requiredServiceFeatures())
        assertTrue("CONVERSATION_CONFLICT_EVIDENCE_V1" in diagnostics.requiredServiceFeatures())
        assertFalse(diagnostics.toString().contains("private-job"))
        assertFalse(diagnostics.toString().contains("private gateway"))
        assertTrue(diagnostics.isValid())
    }

    @Test fun capacityFailuresRemainTypedAndCarryOwningBudget() {
        for (code in listOf("CAPACITY_UNVERIFIED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CONTEXT_CAPACITY_EXCEEDED")) {
            val failure = ProviderEvent.Failed(
                "private prompt",
                errorCode = code,
                failureDetails = buildJsonObject {
                    put("admission_prompt_tokens", 9639)
                    put("admission_safety_tokens", 2409)
                    put("admission_output_tokens", 4096)
                    put("admission_capacity_tokens", 5632)
                    put("admission_dominant_component", "tool_schemas")
                    put("admission_dominant_tokens", 4414)
                    put("admission_measurement", "UTF8_ESTIMATE")
                }
            )
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure, null)
            val fields = ProviderFailureReport.fields("run", failure, receipt)
            val capture = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1000))
            assertEquals(code, IssueReportCodes.select(failure.errorCode, "CHAT_FAILED"))
            assertEquals(JsonPrimitive(code), capture.values["provider_failure_code"])
            assertEquals(JsonPrimitive(5632), capture.values["admission_capacity_tokens"])
            assertEquals(JsonPrimitive("tool_schemas"), capture.values["admission_dominant_component"])
            assertTrue("ADMISSION_DIAGNOSTICS_V1" in capture.requiredServiceFeatures())
            assertFalse(capture.toString().contains("private prompt"))
        }
    }
}
