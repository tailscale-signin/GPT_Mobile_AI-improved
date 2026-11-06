package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationAcceptanceTest {
    private val head = "a".repeat(40)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun receipt(tool: String, arguments: String, result: String, time: Long = 1000, origin: String = "LIVE") = buildJsonObject {
        put("tool", tool)
        put("arguments", json(arguments))
        put("result", json(result))
        put("binding", "task")
        put("repository", "o/r")
        put("trusted", true)
        put("success", true)
        put("dispatched", true)
        put("observed_at_ms", time)
        put("evidence_origin", origin)
    }
    private val linked = json("""{"operation_receipts":["write"],"verification_receipts":["read"]}""")
    private fun state(task: String, receipts: Map<String, JsonObject>, acceptance: JsonObject = linked) = GitHubOperationAcceptance.evaluate(task, acceptance, receipts, now = 1002)?.state

    @Test fun ordinaryIssueCreationNeedsMatchingLiveReadbackInsteadOfAnImplementationCommit() {
        val write = receipt("github_issue", """{"action":"create","title":"Exact title","body":"Description"}""", """{"issue":{"number":7,"title":"Exact title","body":"Description","state":"open"},"upstream_completed":true}""")
        val read = receipt("github_issue", """{"action":"context","issue_number":7,"verify_live":true}""", """{"issue":{"number":7,"title":"Exact title","body":"Description","state":"open"}}""", 1001)
        val records = linkedMapOf("write" to write, "read" to read)
        assertEquals(GitHubAcceptanceLedger.State.PASS, state("Create an issue", records))
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, state("Create an issue", mapOf("write" to write)))
        val answer = buildJsonObject { put("acceptance", buildJsonObject { put(GitHubRequestCompletion.id("Create an issue"), linked) }) }
        val report = GitHubRequestCompletion.evaluate("Create an issue", listOf(json("""{"status":"VERIFIED"}""")), answer, records, now = 1002)
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        val ledger = GitHubAcceptanceLedger.attach(report).getValue("acceptance_ledger").jsonObject
        val criterion = (ledger["requirements"] as JsonArray).single().jsonObject
        assertEquals(setOf(JsonPrimitive("write"), JsonPrimitive("read")), (criterion["receipt_ids"] as JsonArray).toSet())
    }

    @Test fun cachedMockedWrongResourceAndEarlierReadsCannotQualifyTheOperation() {
        val write = receipt("github_issue", """{"action":"create","title":"Title"}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""")
        for (read in listOf(
            receipt("github_issue", """{"action":"context","issue_number":7}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""", origin = "CACHED"),
            receipt("github_issue", """{"action":"context","issue_number":7}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""", origin = "MOCKED"),
            receipt("github_issue", """{"action":"context","issue_number":8}""", """{"issue":{"number":8,"title":"Title","state":"open"}}"""),
            receipt("github_issue", """{"action":"context","issue_number":7}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""", time = 999)
        )) {
            assertFalse(state("Create an issue", mapOf("write" to write, "read" to read)) == GitHubAcceptanceLedger.State.PASS)
        }
    }

    @Test fun newerContradictingStateInvalidatesTheSelectedEarlierSuccess() {
        val write = receipt("github_issue", """{"action":"update","issue_number":7,"state":"closed","expected_updated_at":"before"}""", """{"issue":{"number":7,"state":"closed"}}""")
        val read = receipt("github_issue", """{"action":"context","issue_number":7}""", """{"issue":{"number":7,"state":"closed"}}""", 1001)
        val later = receipt("github_issue", """{"action":"context","issue_number":7}""", """{"issue":{"number":7,"state":"open"}}""", 1002)
        assertEquals(GitHubAcceptanceLedger.State.PASS, state("Close the issue", mapOf("write" to write, "read" to read)))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Close the issue", linkedMapOf("write" to write, "read" to read, "later" to later)))
    }

    @Test fun prMetadataAcceptanceChecksExpectedHeadAndActualRequestedState() {
        val write = receipt("github_pr", """{"action":"update","pull_number":7,"expected_head_sha":"$head","state":"closed"}""", """{"number":7,"head":{"sha":"$head"},"state":"closed"}""")
        val read = receipt("github_pr", """{"action":"headers","pull_number":7,"verify_live":true}""", """{"number":7,"head_sha":"$head","state":"closed"}""", 1001)
        assertEquals(GitHubAcceptanceLedger.State.PASS, state("Close the pull request", mapOf("write" to write, "read" to read)))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Close pull request #8", mapOf("write" to write, "read" to read)))
        val wrongHead = JsonObject(read + ("result" to json("""{"number":7,"head_sha":"${"b".repeat(40)}","state":"closed"}""")))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Close the pull request", mapOf("write" to write, "read" to wrongHead)))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Reopen the pull request", mapOf("write" to write, "read" to read)))
    }

    @Test fun branchFinalStateCannotStandInForRequestedCodeBuildTestsOrMerge() {
        val write = receipt("github_commit", """{"action":"branch","branch":"topic","expected_head_sha":"$head"}""", """{"branch":"topic","head_sha":"$head","base_sha":"$head","remote_state_verified":true,"receipt":{"ref":"refs/heads/topic","object":{"sha":"$head"}}}""")
        assertEquals(GitHubAcceptanceLedger.State.PASS, state("Create a branch topic", mapOf("write" to write)))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Create a branch different", mapOf("write" to write)))
        for (request in listOf("Create a branch and fix the bug", "Create a branch, run tests", "Create a branch then merge the PR", "Build the feature", "Test the app", "Merge the PR")) {
            assertFalse(request, GitHubOperationAcceptance.supports(request))
            val answer = buildJsonObject { put("acceptance", buildJsonObject { put(GitHubRequestCompletion.id(request), linked) }) }
            assertEquals(request, JsonPrimitive(false), GitHubRequestCompletion.evaluate(request, listOf(json("""{"status":"VERIFIED"}""")), answer, mapOf("write" to write))["completion_verified"])
        }
        val cached = JsonObject(write + ("evidence_origin" to JsonPrimitive("CACHED")))
        assertFalse(state("Create a branch topic", mapOf("write" to cached)) == GitHubAcceptanceLedger.State.PASS)
    }

    @Test fun uncertainWriteCannotPassEvenWithALiveReadbackAndASuccessFlag() {
        val write = receipt("github_issue", """{"action":"create","title":"Title"}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""")
        val uncertain = JsonObject(write + ("execution" to json("""{"phase":"OUTCOME_UNKNOWN","unknown_mutations":1}""")))
        val read = receipt("github_issue", """{"action":"context","issue_number":7}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""", 1001)
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, state("Create an issue", mapOf("write" to uncertain, "read" to read)))
        assertTrue(GitHubOperationAcceptance.supports("Update the issue titled \"Title\""))
    }

    @Test fun oldMutableReadbackCannotBeReusedAsCurrentTaskAcceptance() {
        val write = receipt("github_issue", """{"action":"create","title":"Title"}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""")
        val read = receipt("github_issue", """{"action":"context","issue_number":7}""", """{"issue":{"number":7,"title":"Title","state":"open"}}""", 1001)
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, GitHubOperationAcceptance.evaluate("Create an issue", linked, mapOf("write" to write, "read" to read), now = 200_000)?.state)
    }

    @Test fun aStaleBranchProofCanResumeWithAFreshExactReadWithoutReplayingCreation() {
        val write = receipt("github_commit", """{"action":"branch","branch":"topic","expected_head_sha":"$head"}""", """{"branch":"topic","head_sha":"$head","base_sha":"$head","remote_state_verified":true,"receipt":{"ref":"refs/heads/topic","object":{"sha":"$head"}}}""")
        val read = receipt("github_context", """{"action":"branch","branch":"topic","verify_live":true}""", """{"branch":{"name":"topic","commit":{"sha":"$head"}}}""", 200_000)
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, GitHubOperationAcceptance.evaluate("Create branch topic", linked, mapOf("write" to write), now = 200_000)?.state)
        assertEquals(GitHubAcceptanceLedger.State.PASS, GitHubOperationAcceptance.evaluate("Create branch topic", linked, linkedMapOf("write" to write, "read" to read), now = 200_000)?.state)
    }

    @Test fun unconsumedEngineeringOrMetadataRequirementsCannotUseTheSimpleOperationPath() {
        for (request in listOf(
            "Create branch foo and ensure all checks pass",
            "Create branch foo before running tests",
            "Create branch foo with required CI passing",
            "Create branch foo and verify the build",
            "Create branch foo once tests pass",
            "Create branch foo until required checks succeed",
            "Create branch foo; require test evidence",
            "Update issue #7 with title Hello",
            "Close PR #7 after verifying the release",
            "Create an issue titled \"Bug\" with body Secret",
            "Update issue #7",
            "Create branch topic in o/r then merge"
        )) {
            assertFalse(request, GitHubOperationAcceptance.supports(request))
        }
        assertTrue(GitHubOperationAcceptance.supports("Create branch topic from main in o/r"))
        assertTrue(GitHubOperationAcceptance.supports("Create issue titled \"Run tests and ensure checks pass\" in o/r"))
    }

    @Test fun acceptedBranchCreationWithAMissingImmediateReadbackCanFinishFromIndependentLiveProof() {
        val write = receipt("github_commit", """{"action":"branch","branch":"topic","expected_head_sha":"$head"}""", """{"branch":"topic","head_sha":"$head","base_sha":"$head","created":true,"receipt_verified":true,"remote_state_verified":false,"context_refresh_required":true,"receipt":{"ref":"refs/heads/topic","object":{"sha":"$head"}}}""")
        val read = receipt("github_context", """{"action":"branch","branch":"topic","verify_live":true}""", """{"branch":{"name":"topic","commit":{"sha":"$head"}}}""", 1001)
        val records = linkedMapOf("write" to write, "read" to read)
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Create branch topic", mapOf("write" to write)))
        assertEquals(GitHubAcceptanceLedger.State.PASS, state("Create branch topic", records))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Create branch topic", records, json("""{"operation_receipts":["write"]}""")))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Create branch topic", records + ("read" to JsonObject(read + ("evidence_origin" to JsonPrimitive("CACHED"))))))
        val wrong = JsonObject(read + ("result" to json("""{"branch":{"name":"topic","commit":{"sha":"${"b".repeat(40)}"}}}""")))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, state("Create branch topic", records + ("read" to wrong)))
    }
}
