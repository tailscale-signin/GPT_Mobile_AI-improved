package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentRunTerminalDiagnostics
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
}
