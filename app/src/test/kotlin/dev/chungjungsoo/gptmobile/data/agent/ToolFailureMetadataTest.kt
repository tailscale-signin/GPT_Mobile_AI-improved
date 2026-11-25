package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailureMetadataTest {
    @Test fun typedMetadataPrivacyDenialKeepsItsCauseWithoutErasingEarlierDispatch() {
        val denied = java.io.IOException("private wrapper", dev.chungjungsoo.gptmobile.data.network.PrivateMetadataDenied())
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", ToolFailureMetadata.classify(denied))
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", ToolFailureMetadata.authorityCode(denied))
        val precheck = requireNotNull(ToolFailureMetadata.authorityPrecheck("local", "mcp", denied))
        assertFalse(precheck.dispatched)
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", precheck.errorCode)
        val earlier = ToolResultEnvelope.error("earlier", "DISPATCH_OUTCOME_UNKNOWN", "Earlier operation uncertain", dispatched = true)
        val retained = requireNotNull(ToolFailureMetadata.withAuthorityFailure(earlier, denied, "tool_execution"))
        assertTrue(retained.dispatched)
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", retained.errorCode)
        assertEquals("RECONCILIATION_REQUIRED", ToolFailureMetadata.authorityFailure(retained, hasUnknownOutcome = true).errorCode)
        assertNull(ToolFailureMetadata.authorityCode(java.io.IOException("PRIVACY_SERVICE_UNVERIFIED")))
    }

    @Test fun observedGitHubPreflightProjectsItsStageAndExactCallWithoutPrivateReceiptValues() {
        val original = githubPreflight().let {
            it.copy(
                executionReceipt = JsonObject(
                    it.executionReceipt.orEmpty() + buildJsonObject {
                        put(
                            "resource_context",
                            buildJsonObject {
                                put("repo", "PRIVATE_REPOSITORY")
                                put("query", "PRIVATE_QUERY")
                            }
                        )
                    }
                )
            )
        }
        val result = ToolFailureMetadata.receipt(original, "github_context__github_api")
        val receipt = requireNotNull(result.executionReceipt)
        original.executionReceipt.orEmpty().forEach { (field, value) -> assertEquals(field, value, receipt[field]) }
        assertEquals(original.content, result.content)
        assertEquals(original.errorCode, result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive("github.dispatch.v1"), receipt["schema"])
        assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), receipt["phase"])
        assertEquals(JsonPrimitive("tool_precheck"), receipt["failed_stage"])

        val context = policyIncident(ReportFeature.TOOLS, "TOOL_RESULT", requireNotNull(result.errorCode), receipt)
        assertEquals("tool_precheck", context.stage)
        assertEquals(false, context.dispatched)
        assertEquals(false, context.requiresReconciliation)
        assertEquals(IssueReportContext.identifierHash("call", original.callId), context.callIdHash)
        assertTrue(IssueReportIncidentCapsule.identity(context, result.errorCode) != null)
        val report = policyReport(context, requireNotNull(result.errorCode))
        for (privateValue in listOf(original.callId, "private-run", "PRIVATE_REJECTION_BODY", "PRIVATE_REPOSITORY", "PRIVATE_QUERY")) {
            assertFalse(privateValue, report.contains(privateValue))
        }
        assertEquals(receipt, ToolFailureMetadata.receipt(result, "github_context__github_api").executionReceipt)
    }

    @Test fun githubPreflightMappingPreservesExplicitStagesAuthorityAndReplayControls() {
        for (stageField in listOf("failed_stage", "failedStage", "failurePhase", "stage", "authority_failed_stage")) {
            val original = githubPreflight("GITHUB_CIRCUIT_OPEN").let {
                it.copy(
                    executionReceipt = JsonObject(
                        it.executionReceipt.orEmpty() + buildJsonObject {
                            put(stageField, "delivery")
                            put("authority_invalidation_code", "POLICY_STALE")
                            put("resume_required", true)
                            put("replay_safe", true)
                            put("retryable", true)
                            put("retry_not_before_ms", 1234L)
                            put("cause_code", "POLICY_STALE")
                        }
                    )
                )
            }
            val result = ToolFailureMetadata.receipt(original, "github_ci", diagnosticCode = "GITHUB_CIRCUIT_OPEN")
            original.executionReceipt.orEmpty().forEach { (field, value) -> assertEquals("$stageField/$field", value, result.executionReceipt?.get(field)) }
            assertEquals(if (stageField == "failed_stage") JsonPrimitive("delivery") else null, result.executionReceipt?.get("failed_stage"))
            assertEquals("POLICY_STALE", ToolFailureMetadata.authorityCode(result))
            assertTrue(ToolFailureMetadata.retryable(result))
        }
    }

    @Test fun githubPreflightMappingRequiresObservedNonDispatchWithoutContradictoryOutcomeEvidence() {
        val original = githubPreflight()
        val receipt = requireNotNull(original.executionReceipt)
        val contradictions = listOf(
            buildJsonObject { put("requires_reconciliation", true) },
            buildJsonObject { put("requires_reconciliation", JsonNull) },
            buildJsonObject { put("dispatched", true) },
            buildJsonObject { put("dispatched", JsonNull) },
            buildJsonObject { put("remote_acceptance", "YES") },
            buildJsonObject { put("remote_acceptance", "UNKNOWN") },
            buildJsonObject { put("execution_state", "UNKNOWN_OUTCOME") },
            buildJsonObject { put("dispatch_state", "DISPATCHED") },
            buildJsonObject { put("state", "UNKNOWN_OUTCOME") },
            buildJsonObject { put("outcome", "unknown") },
            buildJsonObject { put("command_outcome_unknown", true) },
            buildJsonObject { put("read_requests", 1) },
            buildJsonObject { put("read_response_observations", 1) },
            buildJsonObject { put("mutation_requests", 1) },
            buildJsonObject { put("accepted_mutations", 1) },
            buildJsonObject { put("unknown_mutations", 1) },
            buildJsonObject { put("unknown_mutations", JsonNull) },
            buildJsonObject { put("error_code", "DISPATCH_OUTCOME_UNKNOWN") },
            buildJsonObject { put("cause_code", "RECONCILIATION_REQUIRED") }
        ).map { original.copy(executionReceipt = JsonObject(receipt + it)) } +
            listOf(
                original.copy(dispatched = true),
                original.copy(executionReceipt = JsonObject(receipt - "requires_reconciliation")),
                original.copy(executionReceipt = JsonObject(receipt - "read_requests"))
            ) + ToolResultEnvelope.unknownOutcomeCodes.map { original.copy(errorCode = it) }
        for (candidate in contradictions) {
            val result = ToolFailureMetadata.receipt(candidate, "github_ci")
            assertNull(candidate.toString(), result.executionReceipt?.get("failed_stage"))
            candidate.executionReceipt.orEmpty().forEach { (field, value) -> assertEquals(field, value, result.executionReceipt?.get(field)) }
        }
    }

    @Test fun nonDispatchAloneOtherSchemasOtherPhasesAndSuccessDoNotGainAGitHubPreflightStage() {
        val original = githubPreflight()
        val receipt = requireNotNull(original.executionReceipt)
        val candidates = listOf(
            original.copy(executionReceipt = null),
            original.copy(executionReceipt = JsonObject(receipt - "schema")),
            original.copy(executionReceipt = JsonObject(receipt + ("schema" to JsonPrimitive("arbitrary.receipt.v1")))),
            original.copy(executionReceipt = JsonObject(receipt + ("phase" to JsonPrimitive("READ_FAILED")))),
            original.copy(executionReceipt = JsonObject(receipt + ("phase" to JsonPrimitive("OUTCOME_UNKNOWN")))),
            original.copy(isError = false, errorCode = null)
        )
        for (candidate in candidates) {
            val result = ToolFailureMetadata.receipt(candidate, "github_context")
            assertNull(result.executionReceipt?.get("failed_stage"))
            assertEquals(candidate.isError, result.isError)
            assertEquals(candidate.dispatched, result.dispatched)
            assertEquals(candidate.content, result.content)
        }
    }

    private fun githubPreflight(code: String = "INVALID_ARGUMENTS"): AgentToolResult = GitHubDispatchAccounting(record = {}).finish(
        AgentToolResult("private-preflight-call", ToolResultContent.Text("PRIVATE_REJECTION_BODY"), true, errorCode = code, dispatched = false),
        mutation = false
    )

    @Test fun searchTransportUncertaintyRemainsUnknownWhileReadRetryIsSafe() {
        val receipt = buildJsonObject {
            put("schema", "gptmobile.search-operation.v1")
            put("callId", "search-1")
            put("state", "UNKNOWN_OUTCOME")
            put("requestSent", JsonNull)
            put("bodyReceived", false)
        }
        val result = ToolFailureMetadata.receipt(AgentToolResult("search-1", ToolResultContent.Text("Response timed out"), true, errorCode = "TIMEOUT", executionReceipt = receipt), "web_search", replaySafe = true)
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(result))
        assertTrue(ToolFailureMetadata.retryable(result))
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(ToolResultEnvelope.encode("web_search", result)).jsonObject
        assertEquals(JsonPrimitive("unknown"), envelope.getValue("execution").jsonObject["outcome"])
        assertEquals(JsonPrimitive(false), envelope.getValue("error").jsonObject["reconciliationRequired"])
    }

    @Test fun aDifferentCallOrArbitraryReceiptCannotChangeOutcomeCertainty() {
        val receipt = buildJsonObject {
            put("schema", "gptmobile.search-operation.v1")
            put("callId", "another-call")
            put("state", "PRECHECK_REJECTED")
            put("requestSent", false)
        }
        val result = AgentToolResult("write", ToolResultContent.Text("Failed"), true, errorCode = "DISPATCH_OUTCOME_UNKNOWN", executionReceipt = receipt)
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(result))
        assertFalse(ToolFailureMetadata.retryable(result))
    }

    @Test fun failedResultsAlwaysHaveANormalizedCodeIncludingLegacyNone() {
        for (code in listOf(null, "", "none", " NONE ", "unknown")) {
            val result = ToolFailureMetadata.normalize(AgentToolResult("call", ToolResultContent.Text("UnknownHostException: source.example"), true, errorCode = code))
            assertEquals("DNS_RESOLUTION_FAILED", result.errorCode)
        }
    }

    @Test fun gatewayAmbiguityIsNeverReclassifiedAsASafeTransportRetry() {
        val code = ToolFailureMetadata.classify(java.io.IOException("GATEWAY_OUTCOME_UNKNOWN: response timed out"))
        val result = ToolFailureMetadata.receipt(AgentToolResult("call", ToolResultContent.Text("Gateway outcome unknown"), true, errorCode = code), "gateway", replaySafe = true)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", code)
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(result))
        assertFalse(ToolFailureMetadata.retryable(result))
    }

    @Test fun dnsPreflightRemainsEligibleForAControlledReadRetryAfterConnectivityReturns() {
        val result = ToolFailureMetadata.receipt(AgentToolResult("search", ToolResultContent.Text("DNS preflight rejected"), true, errorCode = "DNS_FAILED", dispatched = false), "web_search", replaySafe = true)
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(result))
        assertTrue(ToolFailureMetadata.retryable(result))
    }

    @Test fun githubCircuitCooldownIsADurableReplaySafePrecheck() {
        val result = ToolFailureMetadata.receipt(
            AgentToolResult(
                "github-read",
                ToolResultContent.Text("GitHub circuit is cooling down"),
                true,
                errorCode = "GITHUB_CIRCUIT_OPEN",
                dispatched = false,
                executionReceipt = buildJsonObject {
                    put("replay_safe", true)
                    put("retry_not_before_ms", 1_234L)
                }
            ),
            "github_ci"
        )
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(result))
        assertTrue(ToolFailureMetadata.retryable(result))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("replay_safe"))
        assertEquals(JsonPrimitive(1_234L), result.executionReceipt?.get("retry_not_before_ms"))
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
    }

    @Test fun paginationInvalidationCannotStopExecutionAuthority() {
        val stale = AgentToolResult("page", ToolResultContent.Text("Restart inventory"), true, errorCode = "STALE_INVENTORY", dispatched = false)
        assertEquals(null, ToolFailureMetadata.authorityCode(stale))
        assertEquals(null, ToolFailureMetadata.authorityCode(dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("STALE_INVENTORY", "Local page changed")))
        for (code in listOf("POLICY_STALE", "DISPATCH_POLICY_CHANGED")) {
            assertEquals(code, ToolFailureMetadata.authorityCode(stale.copy(errorCode = code)))
        }
    }

    @Test fun changedEngineeringGatewayKeepsItsDirectionThroughBlockedCallsAndReports() {
        val policy = dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("githubProfileOverride.engineeringGatewayProfileUid"), "REPLACED")
        val call = requireNotNull(ToolFailureMetadata.authorityPrecheck("private-call", "github_ci", policy))
        val queued = ToolFailureMetadata.authorityBlocked("queued", "github_read", call)
        val failure = ToolFailureMetadata.authorityFailure(queued, hasUnknownOutcome = false)
        val context = policyIncident(ReportFeature.TOOLS, "TOOL_RESULT", requireNotNull(call.errorCode), call.executionReceipt.orEmpty())
        assertEquals(JsonPrimitive("REPLACED"), requireNotNull(context.diagnostics).values["dispatch_gateway_selection_change"])
        assertEquals(JsonPrimitive("REPLACED"), failure.failureDetails?.get("dispatch_gateway_selection_change"))
        assertTrue(failure.message.contains("engineering Gateway selection changed"))
        assertFalse(queued.dispatched)
        assertEquals("RECONCILIATION_REQUIRED", ToolFailureMetadata.authorityFailure(queued, hasUnknownOutcome = true).errorCode)
    }

    @Test fun selectingAGatewayDuringDeliveryRequiresResumingFromRetainedEvidence() {
        val change = dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("githubProfileOverride", "githubProfileOverride.engineeringGatewayProfileUid"), "SELECTED")
        val result = AgentToolResult("completed", ToolResultContent.Text("Retained read"), false)
        val blocked = requireNotNull(ToolFailureMetadata.withAuthorityFailure(result, change, "delivery"))
        val failure = ToolFailureMetadata.authorityFailure(blocked, hasUnknownOutcome = false)
        assertEquals("CONFIGURATION_INVALIDATED", failure.taskOutcome)
        assertEquals(JsonPrimitive("delivery"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("resume_required"))
        assertTrue(failure.message.contains("authorize it for the remaining work"))
        assertEquals("RECONCILIATION_REQUIRED", ToolFailureMetadata.authorityFailure(blocked, hasUnknownOutcome = true).errorCode)
    }

    @Test fun policyDriftRetainsChangedFieldsWithoutReplacingSuccessfulReceipts() {
        val observed = AgentToolResult("completed", ToolResultContent.Text("written"), false, executionReceipt = buildJsonObject { put("remote_receipt", "observed-write") })
        val policy = dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("tool_connections", "allowed_tools"))
        val retained = requireNotNull(ToolFailureMetadata.withAuthorityFailure(observed, policy, "after_tool_execution"))
        assertFalse(retained.isError)
        assertTrue(retained.dispatched)
        assertEquals(observed.content, retained.content)
        assertEquals(JsonPrimitive("observed-write"), retained.executionReceipt?.get("remote_receipt"))
        assertEquals(kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("tool_connections"), JsonPrimitive("allowed_tools"))), retained.executionReceipt?.get("changed_policy_fields"))
        assertEquals("DISPATCH_POLICY_CHANGED", ToolFailureMetadata.authorityCode(retained))
    }

    @Test fun requiredToolFailureRetainsTypedStageAndDispatchMetadataThroughWrappedErrors() {
        val failure = RequiredToolReadinessFailure("ACQUISITION_TIMED_OUT", "github_context__github_api", "Retry acquisition.")
        assertEquals("REQUIRED_TOOL_ACQUISITION_TIMED_OUT", ToolFailureMetadata.classify(IllegalStateException("Outer readiness failure", failure)))
        assertEquals(JsonPrimitive("tool_readiness"), failure.failureDetails["failed_stage"])
        assertEquals(JsonPrimitive(false), failure.failureDetails["dispatched"])
        assertEquals(JsonPrimitive(true), failure.failureDetails["retryable"])
        assertEquals(JsonPrimitive("READINESS_INCOMPLETE"), failure.failureDetails["disposition"])
    }

    @Test fun promotedToolFailureKeepsItsExactCausalIdentityAndOriginalFailureCode() {
        val result = AgentToolResult("opaque-tool-call", ToolResultContent.Text("retained unknown outcome"), true, errorCode = "DISPATCH_OUTCOME_UNKNOWN", executionReceipt = buildJsonObject { put("authority_invalidation_code", "DISPATCH_POLICY_CHANGED") })
        val failure = ToolFailureMetadata.authorityFailure(result, hasUnknownOutcome = true)
        assertEquals("RECONCILIATION_REQUIRED", failure.errorCode)
        assertEquals(JsonPrimitive(result.callId), failure.failureDetails?.get("causal_id"))
        assertEquals(JsonPrimitive("TOOL_CALL"), failure.failureDetails?.get("causal_provenance"))
        assertEquals(JsonPrimitive("DISPATCH_OUTCOME_UNKNOWN"), failure.failureDetails?.get("causal_failure_code"))
    }

    @Test fun policyPrecheckProjectionRetainsChangedFieldsAndExplicitPromotionIdentity() {
        val changed =
            dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(
                listOf("tools", "profile.model", "PRIVATE_FIELD_VALUE")
            )
        val result = requireNotNull(ToolFailureMetadata.authorityPrecheck("private-call", "github_ci", changed))
        val failure = ToolFailureMetadata.authorityFailure(result, hasUnknownOutcome = false)
        val tool =
            policyIncident(
                ReportFeature.TOOLS,
                "TOOL_RESULT",
                requireNotNull(result.errorCode),
                result.executionReceipt.orEmpty() + mapOf("call_id" to JsonPrimitive(result.callId), "tool" to JsonPrimitive("github_ci"))
            )
        val chat = policyIncident(ReportFeature.CHAT, "CHAT_FAILURE", requireNotNull(failure.errorCode), failure.failureDetails.orEmpty())

        assertFalse(result.dispatched)
        assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
        assertEquals("tool_precheck", chat.stage)
        assertEquals(false, chat.dispatched)
        assertEquals(
            IssueReportIncidentCapsule.identity(tool, result.errorCode),
            IssueReportIncidentCapsule.identity(chat, failure.errorCode)
        )
        assertTrue(IssueReportIncidentCapsule.identity(chat, failure.errorCode) != null)
        for (context in listOf(tool, chat)) {
            val values = requireNotNull(context.diagnostics).values
            assertEquals(JsonArray(listOf(JsonPrimitive("profile.model"), JsonPrimitive("tools"))), values["changed_policy_fields"])
            assertEquals(JsonPrimitive(3), values["changed_policy_field_count"])
            assertEquals(JsonPrimitive(1), values["changed_policy_unknown_count"])
            assertEquals(JsonPrimitive("PARTIAL"), values["changed_policy_observation"])
            assertFalse(policyReport(context).contains("PRIVATE_FIELD_VALUE"))
            assertFalse(policyReport(context).contains("private-call"))
            assertFalse(policyReport(context).contains("private-run"))
        }
    }

    @Test fun deliveryPolicyProjectionPreservesTheCompletedDomainReceipt() {
        val original =
            AgentToolResult(
                "private-completed-call",
                ToolResultContent.Text(
                    "PRIVATE_SUCCESS_BODY"
                ),
                false,
                executionReceipt =
                buildJsonObject {
                    put("remote_receipt", "PRIVATE_RECEIPT")
                }
            )
        val changed =
            dev.chungjungsoo.gptmobile.data.context
                .DispatchPolicyChangedException(listOf("profile.token"))
        val result = requireNotNull(ToolFailureMetadata.withAuthorityFailure(original, changed, "delivery"))
        val failure = ToolFailureMetadata.authorityFailure(result, hasUnknownOutcome = false)
        val context =
            policyIncident(ReportFeature.CHAT, "CHAT_FAILURE", requireNotNull(failure.errorCode), failure.failureDetails.orEmpty())

        assertEquals(original.content, result.content)
        assertEquals(original.errorCode, result.errorCode)
        assertFalse(result.isError)
        assertTrue(result.dispatched)
        assertEquals(original.executionReceipt?.get("remote_receipt"), result.executionReceipt?.get("remote_receipt"))
        assertEquals("delivery", context.stage)
        assertEquals(true, context.dispatched)
        assertEquals(false, context.requiresReconciliation)
        val values = requireNotNull(context.diagnostics).values
        assertEquals(JsonArray(listOf(JsonPrimitive("profile.token"))), values["changed_policy_fields"])
        assertEquals(JsonPrimitive("OBSERVED"), values["changed_policy_observation"])
        assertFalse(policyReport(context).contains("PRIVATE_"))
        assertNull(original.executionReceipt?.get("authority_invalidation_code"))
    }

    @Test fun policyProjectionCannotDowngradeAnUnknownMutationOutcome() {
        val original =
            AgentToolResult(
                "private-uncertain-call",
                ToolResultContent.Text(
                    "PRIVATE_MUTATION_BODY"
                ),
                true,
                errorCode = "DISPATCH_OUTCOME_UNKNOWN",
                executionReceipt =
                buildJsonObject {
                    put("requires_reconciliation", true)
                }
            )
        val changed =
            dev.chungjungsoo.gptmobile.data.context
                .DispatchPolicyChangedException(listOf("engineering"))
        val result = requireNotNull(ToolFailureMetadata.withAuthorityFailure(original, changed, "delivery"))
        val failure = ToolFailureMetadata.authorityFailure(result, hasUnknownOutcome = true)
        val context =
            policyIncident(ReportFeature.CHAT, "CHAT_FAILURE", requireNotNull(failure.errorCode), failure.failureDetails.orEmpty())

        assertEquals("DISPATCH_OUTCOME_UNKNOWN", result.errorCode)
        assertTrue(result.dispatched)
        assertEquals("RECONCILIATION_REQUIRED", failure.errorCode)
        assertEquals("PARTIAL", failure.taskOutcome)
        assertEquals(true, context.requiresReconciliation)
        assertNull(context.dispatched)
        assertNull(context.retryable)
        val values = requireNotNull(context.diagnostics).values
        assertEquals(JsonPrimitive("DISPATCH_OUTCOME_UNKNOWN"), values["causal_failure_code"])
        assertEquals(JsonArray(listOf(JsonPrimitive("engineering"))), values["changed_policy_fields"])
        assertNull(values["request_replay_allowed"])
        assertFalse(policyReport(context).contains("PRIVATE_MUTATION_BODY"))
    }

    private fun policyIncident(
        feature: ReportFeature,
        source: String,
        code: String,
        fields: Map<String, Any?>
    ): IssueReportContext =
        IssueReportContext.fromFields(
            source,
            IssueReportIncidentCapsule.fields(feature, source, code, fields + ("run_id" to "private-run")),
            1_800_000_000_000L
        )

    private fun policyReport(context: IssueReportContext, code: String = "DISPATCH_POLICY_CHANGED"): String =
        IssueReport(
            "11111111-1111-4111-8111-111111111111",
            "debug",
            "1.0",
            1,
            "abcdef0",
            "16",
            36,
            "Fixture",
            feature = "chat",
            errorCode = code,
            context = context
        ).approvedPayload()
}
