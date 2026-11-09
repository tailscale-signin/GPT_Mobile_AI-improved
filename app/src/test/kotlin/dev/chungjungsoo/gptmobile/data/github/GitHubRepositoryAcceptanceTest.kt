package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubRepositoryAcceptanceTest {
    private val now = 1_000_000L
    private val acceptance = buildJsonObject { put("operation_receipts", JsonArray(listOf(JsonPrimitive("created")))) }
    private fun receipt(action: String = "create", state: String = "VERIFIED", destination: String = "me/project", extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("tool", "github_repository")
        put("trusted", true)
        put("verification_eligible", true)
        put("success", true)
        put("evidence_origin", "LIVE")
        put("observed_at_ms", now)
        put("repository", destination)
        put(
            "arguments",
            buildJsonObject {
                put("action", action)
                put("destination_owner", destination.substringBefore('/'))
                put("destination_repo", destination.substringAfter('/'))
                put("owner_type", "user")
                put("visibility", "private")
                put("source_owner", "upstream")
                put("source_repo", "project")
                put("copy_mode", "snapshot")
            }
        )
        put(
            "result",
            JsonObject(
                buildJsonObject {
                    put("operation", "${action}_repository")
                    put("status", state)
                    put("repository_id", 42)
                    put("destination_repository", destination)
                    put("source_repository", "upstream/project")
                    put("visibility", "private")
                    put("remote_state_verified", state == "VERIFIED")
                } + extra
            )
        )
    }

    private fun assessment(task: String, receipt: JsonObject) = requireNotNull(GitHubRepositoryAcceptance.evaluate(task, acceptance, mapOf("created" to receipt), now))

    @Test fun exactCreationProofPassesButAcceptedOrStaleReceiptsDoNot() {
        val task = "Create a private repository named project under me"
        assertEquals(GitHubAcceptanceLedger.State.PASS, assessment(task, receipt()).state)
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, assessment(task, receipt(state = "PENDING")).state)
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, assessment(task, JsonObject(receipt() + ("observed_at_ms" to JsonPrimitive(1)))).state)
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, assessment(task, receipt(destination = "other/project")).state)
    }

    @Test fun importCannotSubstituteDestinationOrDiscardRequestedHistory() {
        val snapshot = buildJsonObject {
            put("source_tree_sha", "a".repeat(40))
            put("destination_tree_sha", "a".repeat(40))
            put("history_preserved", false)
        }
        val proof = receipt("import", extra = snapshot)
        assertEquals(GitHubAcceptanceLedger.State.PASS, assessment("Import repository upstream/project into me/project", proof).state)
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, assessment("Import repository upstream/project into other/project", proof).state)
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, assessment("Import repository upstream/project with full history", proof).state)
    }

    @Test fun aRepositoryReceiptCannotCompleteAdditionalImplementationOrConfiguration() {
        for (task in listOf("Create a private repository named project and implement a web app", "Create a private repository named project with branch protection", "Create a private repository named project and add tests")) {
            val answer = buildJsonObject { put("acceptance", buildJsonObject { put(GitHubRequestCompletion.id(task), acceptance) }) }
            val result = GitHubRequestCompletion.evaluate(task, listOf(buildJsonObject { put("status", "VERIFIED") }), answer, mapOf("created" to receipt()), now)
            assertFalse(task, result["completion_verified"] == JsonPrimitive(true))
        }
    }

    @Test fun cloningNeedsVerifiedCheckedOutSourceRevision() {
        val proof = receipt(
            "clone",
            extra = buildJsonObject {
                put("workspace_id", "repoop_${"a".repeat(32)}")
                put("checked_out_sha", "b".repeat(40))
                put("source_commit_sha", "c".repeat(40))
            }
        )
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, assessment("Clone upstream/project to the gateway", proof).state)
    }
}
