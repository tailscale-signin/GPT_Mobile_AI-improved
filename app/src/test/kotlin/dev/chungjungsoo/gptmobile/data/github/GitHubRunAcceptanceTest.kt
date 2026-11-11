package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentRunTerminalDiagnostics
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRunAcceptanceTest {
    private val response = "The requested state was verified."

    private fun report(unresolved: Int = 0, passed: Boolean = true) = buildJsonObject {
        put("binding", "a".repeat(64))
        put("completion_verified", true)
        put("task_outcome", "COMPLETED")
        put("task_state", "COMPLETED")
        put("response_delivered", true)
        put("response_sha256", GitHubEvidenceBook.digest(response))
        put("evidence_retention_complete", true)
        put(
            "acceptance_ledger",
            buildJsonObject {
                put("completion_verified", passed)
                put(
                    "requirements",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("required", true)
                                put("state", if (passed) "PASS" else "NOT_TESTED")
                            }
                        )
                    )
                )
            }
        )
        put(
            "execution_ledger",
            buildJsonObject {
                put("requested_task", "VERIFIED_COMPLETE")
                put("unresolved_remote_outcomes", unresolved)
                put("receipt_count", 2)
            }
        )
    }

    private fun assess(report: JsonObject = report(), text: String = response): GitHubRunAcceptance.Assessment = requireNotNull(
        GitHubRunAcceptance.assess(GitHubRunAcceptance.capture(report, "run", 7, 1, 2), "run", 7, 1, 2, text)
    )

    @Test fun deliveredAcceptanceReachesTerminalDiagnosticsWithExactEvidenceIdentity() {
        val acceptance = assess()
        assertEquals("VERIFIED", acceptance.verification)
        assertEquals(GitHubRunAcceptance.key("run", 7), acceptance.evidenceId)
        assertEquals(1, acceptance.requiredCriteria)
        assertEquals(1, acceptance.verifiedCriteria)
        assertEquals("VERIFIED", AgentRunTerminalDiagnostics.from(AgentRunStatus.COMPLETED, null, acceptance).taskVerification)
    }

    @Test fun aTaskScopedVerdictCannotVerifyAnotherRunFenceOrMessage() {
        val checkpoint = GitHubRunAcceptance.capture(report(), "run", 7, 1, 2)
        assertNull(GitHubRunAcceptance.assess(checkpoint, "other", 7, 1, 2, response))
        assertNull(GitHubRunAcceptance.assess(checkpoint, "run", 8, 1, 2, response))
        assertNull(GitHubRunAcceptance.assess(checkpoint, "run", 7, 9, 2, response))
        assertNull(GitHubRunAcceptance.assess(checkpoint, "run", 7, 1, 3, response))
    }

    @Test fun missingCriteriaRetentionOrDeliveryProofCannotPass() {
        assertEquals("PARTIAL", assess(report(passed = false)).verification)
        for (field in listOf("response_delivered", "evidence_retention_complete")) {
            assertEquals(field, "PARTIAL", assess(JsonObject(report() + (field to JsonPrimitive(false)))).verification)
        }
        assertEquals("PARTIAL", assess(JsonObject(report() - "execution_ledger")).verification)
        assertEquals("PARTIAL", assess(text = "The response was truncated").verification)
    }

    @Test fun explicitUnknownMutationCannotBecomeVerifiedFromFlowCompletion() {
        val acceptance = assess(report(unresolved = 1))
        val terminal = AgentRunTerminalDiagnostics.from(AgentRunStatus.COMPLETED, null, acceptance)
        assertEquals("UNKNOWN_OUTCOME", terminal.executionState)
        assertEquals("UNKNOWN_OUTCOME", terminal.taskVerification)
    }

    @Test fun measuredPartialVerificationDoesNotInventUnknownRemoteDispatch() {
        val acceptance = assess(report(passed = false))
        val failure = ProfileFailureRecord.fromProvider("Task incomplete", "GITHUB_TASK_INCOMPLETE")
        val terminal = AgentRunTerminalDiagnostics.from(AgentRunStatus.PARTIAL, failure, acceptance)
        assertEquals("PARTIAL", terminal.taskVerification)
        assertEquals("OBSERVED", terminal.executionState)
        assertEquals("verification", terminal.stage)
        assertEquals("NOT_RECORDED", AgentRunTerminalDiagnostics.from(AgentRunStatus.PARTIAL, failure).executionState)
    }

    @Test fun aMissingOrInvalidPublicationReceiptRejectsTheTerminalCompletionBeforeCommit() {
        GitHubRunAcceptance.requireCompletion(completed = true, acceptanceExpected = false, acceptance = null)
        GitHubRunAcceptance.requireCompletion(completed = false, acceptanceExpected = true, acceptance = null)
        GitHubRunAcceptance.requireCompletion(completed = true, acceptanceExpected = true, acceptance = assess())
        for (acceptance in listOf(null, assess(text = "Truncated after publication"), assess(report(unresolved = 1)))) {
            assertThrows(IllegalStateException::class.java) { GitHubRunAcceptance.requireCompletion(completed = true, acceptanceExpected = true, acceptance = acceptance) }
        }
    }

    @Test fun processRestartCannotTurnAMissingOrUnreadableDurableReceiptIntoUnmeasuredChat() = runTest {
        for (payload in listOf(null, GitHubRunAcceptance.capture(report(), "another-run", 7, 1, 2))) {
            val read = GitHubRunAcceptance.readForCommit("run", 7, 1, 2, response, expectedInMemory = false, recordExists = { true }, load = { payload })
            assertTrue(read.expected)
            assertNull(read.acceptance)
            assertThrows(IllegalStateException::class.java) { GitHubRunAcceptance.requireCompletion(true, read.expected, read.acceptance) }
        }
        val chat = GitHubRunAcceptance.readForCommit("chat", 7, 1, 2, response, expectedInMemory = false, recordExists = { false }, load = { null })
        GitHubRunAcceptance.requireCompletion(true, chat.expected, chat.acceptance)
        val failure = runCatching {
            GitHubRunAcceptance.readForCommit("run", 7, 1, 2, response, expectedInMemory = false, recordExists = { throw java.io.IOException("Storage unavailable") }, load = { null })
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }

    @Test fun pendingApprovalRemainsDistinctAndRetainsItsIndependentStopFields() {
        val pending = JsonObject(
            report() + mapOf(
                "task_outcome" to JsonPrimitive("WAITING_FOR_APPROVAL"),
                "task_state" to JsonPrimitive("WAITING_FOR_APPROVAL"),
                "stop_code" to JsonPrimitive("WAITING_FOR_APPROVAL"),
                "approval_state" to JsonPrimitive("WAITING_FOR_APPROVAL"),
                "blocking_condition" to JsonPrimitive("WAITING_FOR_APPROVAL"),
                "execution_ledger" to buildJsonObject {
                    put("requested_task", "WAITING_FOR_APPROVAL")
                    put("unresolved_remote_outcomes", 0)
                    put("receipt_count", 2)
                    put("pending_approvals", JsonArray(listOf(buildJsonObject {
                        put("engineering_task_id", "task-123")
                        put("card_id", "github-approval-task-123")
                        put("state", "WAITING_FOR_APPROVAL")
                        put("branch", "codex-self-test/fixture")
                    })))
                }
            )
        )
        val captured = GitHubRunAcceptance.capture(pending, "run", 7, 1, 2)
        val acceptance = requireNotNull(GitHubRunAcceptance.assess(captured, "run", 7, 1, 2, response))
        assertEquals("WAITING_FOR_APPROVAL", acceptance.verification)
        assertEquals("WAITING_FOR_APPROVAL", acceptance.taskState)
        assertEquals("WAITING_FOR_APPROVAL", acceptance.stopCode)
        assertEquals("WAITING_FOR_APPROVAL", acceptance.approvalState)
        assertEquals("WAITING_FOR_APPROVAL", acceptance.taskOutcome)
        assertTrue(GitHubRunAcceptance.diagnosticLines(acceptance).first().contains("WAITING_FOR_APPROVAL"))
        val failure = assertThrows(IllegalStateException::class.java) {
            GitHubRunAcceptance.requireCompletion(true, true, acceptance)
        }
        assertTrue(failure.message.orEmpty().contains("WAITING_FOR_APPROVAL"))
    }

    @Test fun allAcceptancePagesSurviveRunCaptureAssessmentAndExport() {
        val requirements = JsonArray((1..109).map { index ->
            buildJsonObject {
                put("id", "criterion-$index")
                put("requirement", "Verify criterion $index")
                put("status", "UNTESTED")
                put("acceptance_state", "NOT_TESTED")
            }
        })
        val completeLedger = GitHubAcceptanceLedger.attach(
            buildJsonObject {
                put("requirements", requirements)
                put("capabilities", JsonArray(emptyList()))
            }
        ).getValue("acceptance_ledger").jsonObject
        val source = JsonObject(report(passed = false) + ("acceptance_ledger" to completeLedger))
        val acceptance = requireNotNull(
            GitHubRunAcceptance.assess(
                GitHubRunAcceptance.capture(source, "run", 7, 1, 2),
                "run", 7, 1, 2, response
            )
        )
        assertEquals(109, (acceptance.diagnostics?.get("criterion_count") as? JsonPrimitive)?.intOrNull)
        assertEquals(2, acceptance.diagnosticPages.size)
        assertTrue(acceptance.diagnosticsComplete)
        val lines = GitHubRunAcceptance.diagnosticLines(acceptance, "run")
        assertTrue(lines.first().contains("\"total\":109"))
        assertTrue(lines.first().contains("\"omitted\":0"))
        assertEquals(109, lines.count { it.contains("github.acceptance-criterion.v1") })
    }

    @Test fun incompleteDiagnosticPagesProducePartialEvidenceInsteadOfAcceptance() {
        val requirements = JsonArray((1..109).map { index ->
            buildJsonObject {
                put("id", "criterion-$index")
                put("requirement", "Verify criterion $index")
                put("status", "UNTESTED")
                put("acceptance_state", "NOT_TESTED")
            }
        })
        val ledger = GitHubAcceptanceLedger.snapshot(requirements = requirements)
        val partialAcceptance = buildJsonObject {
            put("completion_verified", true)
            put("requirements", requirements)
            put("diagnostic_projection", GitHubAcceptanceDiagnostics.snapshot(ledger))
        }
        val source = JsonObject(report(passed = false) + ("acceptance_ledger" to partialAcceptance))
        val captured = GitHubRunAcceptance.capture(source, "run", 7, 1, 2)
        val acceptance = requireNotNull(GitHubRunAcceptance.assess(captured, "run", 7, 1, 2, response))
        assertEquals("PARTIAL_EVIDENCE", acceptance.verification)
        assertEquals(false, acceptance.diagnosticsComplete)
        assertTrue(GitHubRunAcceptance.diagnosticLines(acceptance).first().contains("\"omitted\":45"))
        val error = assertThrows(IllegalStateException::class.java) {
            GitHubRunAcceptance.requireCompletion(true, true, acceptance)
        }
        assertTrue(error.message.orEmpty().contains("PARTIAL_EVIDENCE"))
    }
}
