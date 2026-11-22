package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDispatchAccountingTest {
    @Test fun issueContinuationDiagnosticsRequireTheCompleteTypedShapeAndCannotReplaceAccounting() = runTest {
        for (state in GitHubInspection.InvalidIssueContinuation.State.entries) {
            val failure = GitHubInspection.InvalidIssueContinuation(state)
            val fields = buildJsonObject {
                put("issue_continuation_rejected", true)
                put("issue_continuation_state", state.name)
                put("reason_code", "TOOL_ARGUMENT_CONSTRAINT_REJECTED")
                put("missing_field_count", if (failure.missing) 1 else 0)
                put("invalid_field_count", if (failure.missing) 0 else 1)
                put(if (failure.missing) "missing_fields" else "invalid_fields", JsonArray(listOf(JsonPrimitive("after"))))
                put("invalid_field_classes", JsonArray(listOf(JsonPrimitive(if (failure.missing) "MISSING_REQUIRED" else "COMPOSITE_CONSTRAINT"))))
                put("validation_field_names", JsonArray(failure.fields.map(::JsonPrimitive)))
            }
            val candidate = AgentToolResult(
                "issue-page",
                ToolResultContent.Text("Rejected continuation"),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false,
                executionReceipt = JsonObject(
                    fields + buildJsonObject {
                        put("phase", "ACCEPTED")
                        put("http_status", 200)
                        put("read_requests", 99)
                        put("accepted_mutations", 99)
                        put("requires_reconciliation", true)
                    }
                )
            )
            val receipt = requireNotNull(GitHubDispatchAccounting(record = {}).finish(candidate, false).executionReceipt)
            fields.forEach { (key, value) -> assertEquals(key, value, receipt[key]) }
            assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), receipt["phase"])
            assertEquals(JsonPrimitive(0), receipt["read_requests"])
            assertEquals(JsonPrimitive(0), receipt["accepted_mutations"])
            assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
            assertEquals(null, receipt["http_status"])
            val malformed = listOf(
                fields + ("issue_continuation_rejected" to JsonPrimitive("true")),
                fields + ("issue_continuation_state" to JsonPrimitive("invented")),
                fields + ("missing_field_count" to JsonPrimitive(if (failure.missing) "1" else "0")),
                fields + ("validation_field_names" to JsonArray(listOf(JsonPrimitive("private-value")))),
                fields + ((if (failure.missing) "invalid_fields" else "missing_fields") to JsonArray(listOf(JsonPrimitive("after"))))
            )
            for (invalid in malformed) {
                val rejected = GitHubDispatchAccounting(record = {}).finish(candidate.copy(executionReceipt = JsonObject(invalid)), false)
                assertEquals(null, rejected.executionReceipt?.get("issue_continuation_state"))
                assertEquals(null, rejected.executionReceipt?.get("validation_field_names"))
            }
            for (mutation in listOf(false, true)) {
                val dispatched = GitHubDispatchAccounting(record = {})
                withContext(dispatched) {
                    val id = GitHubDispatchAccounting.started(mutation)
                    GitHubDispatchAccounting.responded(mutation, if (mutation) 201 else 200, id)
                }
                val observed = dispatched.finish(candidate, mutation)
                assertEquals(true, observed.dispatched)
                assertEquals(JsonPrimitive(if (mutation) "PARTIAL_ACCEPTED" else "READ_FAILED"), observed.executionReceipt?.get("phase"))
                assertEquals(null, observed.executionReceipt?.get("issue_continuation_state"))
            }
        }
    }

    @Test fun cooldownObservationsNeverReplaceEarlierAcceptedOrUnknownMutationAccounting() = runTest {
        val cooldown = GitHubCooldownActive(GitHubCooldownActive.Resource.CORE, GitHubCooldownActive.Basis.RESOURCE_RESET, 30_000, 1_800_000_030_000, 1_800_000_000_000, 0)
        for (accepted in listOf(false, true)) {
            val accounting = GitHubDispatchAccounting(record = {})
            withContext(accounting) {
                val id = GitHubDispatchAccounting.started(true)
                if (accepted) GitHubDispatchAccounting.responded(true, 201, id)
            }
            val result = accounting.finish(
                AgentToolResult(
                    "compound",
                    ToolResultContent.Text("local cooldown"),
                    true,
                    errorCode = "GITHUB_COOLDOWN_ACTIVE",
                    dispatched = false,
                    executionReceipt = kotlinx.serialization.json.JsonObject(
                        cooldown.failureFields() + kotlinx.serialization.json.buildJsonObject {
                            put("retry_after_ms", JsonPrimitive(30_000))
                            put("failed_stage", JsonPrimitive("tool_precheck"))
                            put("requires_reconciliation", JsonPrimitive(false))
                            put("dispatched", JsonPrimitive(false))
                            put("recovery_action", JsonPrimitive("RETRY_REQUEST"))
                        }
                    )
                ),
                true
            )
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals(true, result.dispatched)
            assertEquals(if (accepted) "GITHUB_COOLDOWN_ACTIVE" else "RECONCILIATION_REQUIRED", result.errorCode)
            assertEquals(JsonPrimitive(if (accepted) "PARTIAL_ACCEPTED" else "OUTCOME_UNKNOWN"), receipt["phase"])
            assertEquals(JsonPrimitive(true), receipt["requires_reconciliation"])
            assertEquals(JsonPrimitive(if (accepted) 1 else 0), receipt["accepted_mutations"])
            assertEquals(JsonPrimitive(if (accepted) 0 else 1), receipt["unknown_mutations"])
            assertEquals(null, receipt["failed_stage"])
            assertEquals(null, receipt["recovery_action"])
            cooldown.failureFields().forEach { (key, value) -> assertEquals(key, value, receipt[key]) }
            assertEquals(JsonPrimitive(30_000), receipt["retry_after_ms"])
        }
    }

    @Test fun exceptionEvidenceCannotOverwriteMeasuredDispatchOrReconciliationFacts() = runTest {
        val failure = IllegalStateException("private token", UnsupportedOperationException("private cause"))
        val evidence = dev.chungjungsoo.gptmobile.data.agent.ToolExceptionEvidence.fields(failure)
        assertEquals(setOf("cause_types", "exception_fingerprint"), evidence.keys)
        for (observedRead in listOf(false, true)) {
            val accounting = GitHubDispatchAccounting(record = {})
            if (observedRead) {
                withContext(accounting) {
                    val id = GitHubDispatchAccounting.started(false)
                    GitHubDispatchAccounting.responded(false, 200, id)
                }
            }
            val candidate = AgentToolResult(
                "call",
                ToolResultContent.Text("private operation detail"),
                true,
                errorCode = "GITHUB_OPERATION_FAILED",
                dispatched = false,
                executionReceipt = kotlinx.serialization.json.JsonObject(
                    evidence + kotlinx.serialization.json.buildJsonObject {
                        put("schema", JsonPrimitive("invented"))
                        put("phase", JsonPrimitive("OUTCOME_UNKNOWN"))
                        put("dispatched", JsonPrimitive(true))
                        put("read_requests", JsonPrimitive(99))
                        put("accepted_mutations", JsonPrimitive(99))
                        put("requires_reconciliation", JsonPrimitive(true))
                        put("retryable", JsonPrimitive(true))
                        put("remote_acceptance", JsonPrimitive("YES"))
                    }
                )
            )
            val result = accounting.finish(candidate, false)
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals("GITHUB_OPERATION_FAILED", result.errorCode)
            assertEquals(observedRead, result.dispatched)
            assertEquals(JsonPrimitive("github.dispatch.v1"), receipt["schema"])
            assertEquals(JsonPrimitive(if (observedRead) "READ_FAILED" else "PREFLIGHT_REJECTED"), receipt["phase"])
            assertEquals(JsonPrimitive(if (observedRead) 1 else 0), receipt["read_requests"])
            assertEquals(JsonPrimitive(0), receipt["accepted_mutations"])
            assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
            assertEquals(null, receipt["remote_acceptance"])
            assertEquals(null, receipt["retryable"])
            assertEquals(evidence["cause_types"], receipt["cause_types"])
            assertEquals(evidence["exception_fingerprint"], receipt["exception_fingerprint"])
        }
    }

    @Test fun transportDiagnosticsSurviveAccountingWithoutChangingDispatchTruth() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        withContext(accounting) { GitHubDispatchAccounting.started(false) }
        val result = accounting.finish(
            AgentToolResult(
                "read",
                ToolResultContent.Text("Connection failed"),
                true,
                errorCode = "GITHUB_TRANSIENT_NETWORK",
                dispatched = false,
                executionReceipt = kotlinx.serialization.json.buildJsonObject {
                    put("github_transport_kind", JsonPrimitive("DNS"))
                    put("github_transport_wait_ms", JsonPrimitive(30000))
                }
            ),
            false
        )
        assertEquals(true, result.dispatched)
        assertEquals(JsonPrimitive("READ_FAILED"), result.executionReceipt?.get("phase"))
        assertEquals(JsonPrimitive("DNS"), result.executionReceipt?.get("github_transport_kind"))
        assertEquals(JsonPrimitive(30000), result.executionReceipt?.get("github_transport_wait_ms"))
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
    }

    @Test fun optionalFailedReadKeepsItsEndpointAndStatusWhenParentSucceeds() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        withContext(accounting) {
            val id = GitHubDispatchAccounting.started(false, "/repos/private/secret/branches/main/protection?token=private")
            GitHubDispatchAccounting.responded(false, 403, id)
        }
        val receipt = accounting.finish(AgentToolResult("read", ToolResultContent.Text("Other reads succeeded"), false), false).executionReceipt!!
        val failure = (receipt["failed_requests"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("BRANCH_PROTECTION"), failure["endpoint_class"])
        assertEquals(JsonPrimitive(403), failure["http_status"])
        assertEquals(JsonPrimitive(true), receipt["parent_result_successful"])
        org.junit.Assert.assertFalse(receipt.toString().contains("secret"))
        org.junit.Assert.assertFalse(receipt.toString().contains("token"))
    }

    @Test fun timeoutDoesNotProveAWriteWasRejected() = runTest {
        for (status in listOf(408, 422)) {
            val accounting = GitHubDispatchAccounting()
            withContext(accounting) {
                GitHubDispatchAccounting.started(true)
                GitHubDispatchAccounting.responded(true, status)
            }
            val result = accounting.finish(AgentToolResult("write", ToolResultContent.Text("Response $status"), true, errorCode = "GITHUB_HTTP_ERROR"), true)
            assertEquals(JsonPrimitive(if (status == 408) "OUTCOME_UNKNOWN" else "REJECTED"), result.executionReceipt?.get("phase"))
            assertEquals(JsonPrimitive(if (status == 408) 1 else 0), result.executionReceipt?.get("unknown_mutations"))
            assertEquals(JsonPrimitive(status == 408), result.executionReceipt?.get("requires_reconciliation"))
            assertEquals(if (status == 408) "RECONCILIATION_REQUIRED" else "GITHUB_HTTP_ERROR", result.errorCode)
        }
    }
}
