package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubGraphQlMutationRecoveryTest {
    private val sha = "a".repeat(40)
    private fun arguments() = buildJsonObject {
        put("action", "mark_ready_for_review")
        put("owner", "o")
        put("repo", "r")
        put("pull_number", 7)
        put("expected_head_sha", sha)
    }
    private fun pr(draft: Boolean) = """{"number":7,"node_id":"PR_node","state":"open","draft":$draft,"head":{"sha":"$sha"}}"""
    private val confirmed = """{"data":{"markPullRequestReadyForReview":{"pullRequest":{"number":7,"isDraft":false,"url":"https://github.com/o/r/pull/7"}}}}"""

    @Test fun successfulMutationIsJournaledAndReadBackAndNeverRepostedAfterRestart() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/repos/o/r/pulls/7" -> respond(pr(writes == 0))
                    "/graphql" -> {
                        writes++
                        respond(confirmed)
                    }
                    else -> error("Unexpected ${request.url}")
                }
            }
        )
        try {
            val first = GitHubTool("token", http, stateStore = store).execute("first", arguments())
            assertFalse(first.isError)
            assertEquals(JsonPrimitive(1), first.executionReceipt?.get("accepted_mutations"))
            assertEquals(JsonPrimitive(true), ToolResultEnvelope.element(first.content).jsonObject["remote_state_verified"])
            val resumed = GitHubTool("token", http, stateStore = store).execute("resumed", arguments())
            assertFalse(resumed.isError)
            assertEquals(1, writes)
            assertEquals(JsonPrimitive(true), ToolResultEnvelope.element(resumed.content).jsonObject["remote_state_verified"])
        } finally {
            http.close()
        }
    }

    @Test fun lostMutationResponseIsReconciledByReadOnlyStateBeforeAnyRetry() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/repos/o/r/pulls/7" -> respond(pr(writes == 0))
                    "/graphql" -> {
                        writes++
                        throw IOException("response lost after server applied mutation")
                    }
                    else -> error("Unexpected ${request.url}")
                }
            }
        )
        try {
            val lost = GitHubTool("token", http, stateStore = store).execute("lost", arguments())
            assertTrue(lost.isError)
            assertEquals("RECONCILIATION_REQUIRED", lost.errorCode)
            assertEquals(JsonPrimitive(1), lost.executionReceipt?.get("unknown_mutations"))
            val resumedArguments = JsonObject(arguments() + mapOf("page" to JsonPrimitive(2), "response_detail" to JsonPrimitive("compact")))
            val resumed = GitHubTool("token", http, stateStore = store).execute("resume", resumedArguments)
            assertFalse(resumed.isError)
            val proof = ToolResultEnvelope.element(resumed.content).jsonObject
            assertEquals(JsonPrimitive(true), proof["reconciled"])
            assertEquals(JsonPrimitive("REMOTE_PR_READY_AT_EXPECTED_HEAD"), proof["verification"])
            assertEquals(1, writes)

            val book = GitHubEvidenceBook("task", "o/r", "feature", setOf("github"))
            book.observe(ProviderEvent.ToolCall("lost", "github", arguments()), lost)
            book.observe(ProviderEvent.ToolCall("resume", "github", resumedArguments), resumed)
            val receipts = book.snapshot().first
            val retainedProof = receipts.entries.single { it.value["call_id"] == JsonPrimitive("resume") }
            fun ledger(proofReceipt: JsonObject = retainedProof.value) =
                GitHubExecutionLedger.attach(JsonObject(emptyMap()), receipts + (retainedProof.key to proofReceipt)).getValue("execution_ledger").jsonObject
            assertEquals(JsonPrimitive(0), ledger()["unresolved_remote_outcomes"])
            assertEquals(JsonPrimitive(1), ledger()["reconciled_remote_outcomes"])
            fun changedResult(field: String, value: JsonElement) =
                JsonObject(retainedProof.value + ("result" to JsonObject(proof + (field to value))))
            val execution = proof.getValue("execution").jsonObject
            val invalidProofs = listOf(
                JsonObject(retainedProof.value + ("evidence_origin" to JsonPrimitive("CACHED"))),
                JsonObject(retainedProof.value + ("binding" to JsonPrimitive("another-task"))),
                JsonObject(retainedProof.value + ("repository" to JsonPrimitive("o/other"))),
                JsonObject(retainedProof.value + ("arguments" to JsonObject(resumedArguments + ("pull_number" to JsonPrimitive(8))))),
                JsonObject(retainedProof.value + ("arguments" to JsonObject(resumedArguments + ("expected_head_sha" to JsonPrimitive("b".repeat(40)))))),
                JsonObject(retainedProof.value + ("arguments" to JsonObject(resumedArguments + ("expected_base_sha" to JsonPrimitive("b".repeat(40)))))),
                changedResult("repository", JsonPrimitive("o/other")),
                changedResult("pull_number", JsonPrimitive(8)),
                changedResult("head_sha", JsonPrimitive("b".repeat(40))),
                changedResult("draft", JsonPrimitive(true)),
                changedResult("execution", JsonObject(execution + ("execution_id" to JsonPrimitive("c".repeat(64))))),
                changedResult("execution", JsonObject(execution + ("correlation_id" to JsonPrimitive("unrelated-claim")))),
                changedResult("execution", JsonObject(emptyMap())),
                changedResult("remote_state_verified", JsonPrimitive(false))
            )
            invalidProofs.forEach { invalid -> assertEquals(JsonPrimitive(1), ledger(invalid)["unresolved_remote_outcomes"]) }
        } finally {
            http.close()
        }
    }

    @Test fun http200GraphQlErrorsCannotBeTreatedAsSuccessOrPermissionToReplay() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/graphql") {
                    writes++
                    respond("""{"data":null,"errors":[{"message":"Partial mutation failure"}]}""")
                } else {
                    respond(pr(true))
                }
            }
        )
        try {
            val first = GitHubTool("token", http, stateStore = store).execute("first", arguments())
            assertTrue(first.isError)
            assertEquals("RECONCILIATION_REQUIRED", first.errorCode)
            val second = GitHubTool("token", http, stateStore = store).execute("second", arguments())
            assertTrue(second.isError)
            assertEquals(1, writes)
        } finally {
            http.close()
        }
    }

    @Test fun deterministicHttpDenialIsRecordedWithoutAutomaticRetry() = runTest {
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/graphql") {
                    writes++
                    respond("{}", HttpStatusCode.Forbidden)
                } else {
                    respond(pr(true))
                }
            }
        )
        try {
            val denied = GitHubTool("token", http).execute("denied", arguments())
            assertTrue(denied.isError)
            assertEquals(1, writes)
            assertEquals(JsonPrimitive(1), denied.executionReceipt?.get("rejected_mutations"))
            assertEquals(JsonPrimitive(false), denied.executionReceipt?.get("requires_reconciliation"))
        } finally {
            http.close()
        }
    }

    @Test fun changedWorkspaceAndHeadGuardCannotReleaseAnUncertainGraphQlMutation() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        var currentHead = sha
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/graphql") {
                    writes++
                    throw IOException("response lost")
                } else {
                    respond(pr(true).replace(sha, currentHead))
                }
            }
        )
        try {
            val first = GitHubTool("token", http, stateStore = store).execute("first", arguments())
            assertTrue(first.isError)
            currentHead = "b".repeat(40)
            val changed = JsonObject(arguments() + mapOf("expected_head_sha" to JsonPrimitive(currentHead), "ref" to JsonPrimitive("another-branch")))
            val second = GitHubTool("token", http, stateStore = store).execute("second", changed)
            assertTrue(second.isError)
            assertEquals(1, writes)
        } finally {
            http.close()
        }
    }

    @Test fun readBackCannotConfirmReadyWhenTheHeadChangedDuringMutation() = runTest {
        var writes = 0
        val store = GitHubRepositoryStateStore()
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/graphql") {
                    writes++
                    respond(confirmed)
                } else {
                    respond(if (writes == 0) pr(true) else pr(false).replace(sha, "b".repeat(40)))
                }
            }
        )
        try {
            val result = GitHubTool("token", http, stateStore = store).execute("changed", arguments())
            assertTrue(result.isError)
            assertEquals(1, writes)
            assertEquals(JsonPrimitive(true), result.executionReceipt?.get("requires_reconciliation"))
            val retried = GitHubTool("token", http, stateStore = store).execute("again", arguments())
            assertTrue(retried.isError)
            assertEquals(1, writes)
        } finally {
            http.close()
        }
    }
}
