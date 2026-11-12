package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIntegrationPreparationTest {
    private val task = "Test all authorized GitHub integration tools and verify cleanup."
    private val sha = "a".repeat(40)
    private val fixture = "codex-self-test/" + GitHubEvidenceBook.digest("tasksource").take(20)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun tool(name: String, vararg actions: String) = AgentToolDefinition(
        name,
        name,
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray(actions.map(::JsonPrimitive))) }) })
        }
    )
    private val tools = listOf(tool("github_context", "status", "resolve", "branch_dependencies"), tool("github_commit", "branch", "delete_branch"), tool("github_issue", "list", "create"))
    private fun receipt(id: String, name: String, args: String, result: String) = buildJsonObject {
        put("receipt_id", id)
        put("call_id", id)
        put("binding", "task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", true)
        put("dispatched", true)
        put("evidence_origin", "LIVE")
        put("tool", name)
        put("arguments", json(args))
        put("result", json(result))
    }
    private fun source() = receipt("source", "github_context", """{"action":"status"}""", """{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}""")
    private fun active() = linkedMapOf(
        "source" to source(),
        "creation" to receipt(
            "creation",
            "github_commit",
            """{"action":"branch","branch":"$fixture","from_branch":"main","expected_head_sha":"$sha"}""",
            """{"branch":"$fixture","created":true,"receipt_verified":true,"head_sha":"$sha"}"""
        )
    )

    @Test fun manualApprovalAndUnsupportedResourceLifecyclesExposeExactPrerequisitesWithoutMutations() {
        val preflight = GitHubTestChecklist.fixtureApprovalPreflight(GitHubDecisionMode.ASK, "task", "owner/repo", "main", JsonObject(emptyMap()))
        val checkpoint = GitHubTestChecklist.checkpoint(task, mapOf("source" to source()), tools, 64, preflight)
        val preparation = checkpoint.getValue("preparation").jsonObject
        assertEquals(JsonPrimitive(false), preparation["automatic_permission_changes"])
        assertEquals(JsonPrimitive(false), preparation["owned_fixture_creation_admissible"])
        assertNull(preparation["next_owned_fixture_call"])
        val blockers = preparation.getValue("blockers").jsonArray.map { it.jsonObject }
        val approval = blockers.single { it["code"] == JsonPrimitive("FIXTURE_APPROVAL_MODE_REQUIRED") }
        assertTrue(approval.getValue("capability_ids").jsonArray.contains(JsonPrimitive("github_commit:branch")))
        val owned = blockers.single { it["code"] == JsonPrimitive("OWNED_FIXTURE_REQUIRED") }
        assertTrue(owned.getValue("required_inputs").jsonArray.any { it.toString().contains("cleanup") })
        assertTrue(owned.getValue("supported_preparation_ids").jsonArray.isEmpty())
    }

    @Test fun existingFixtureReservesCleanupBeforeOptionalReadsAndSurvivesCheckpointSerialization() {
        val receipts = active()
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        assertFalse(GitHubChecklistBudget.acquisitionAllowed(receipts, 6))
        assertTrue(GitHubChecklistBudget.acquisitionAllowed(receipts, 7))
        val next = requireNotNull(GitHubTestChecklist.nextCall(task, tools, receipts, 6))
        assertEquals("github_context", next.name)
        assertEquals(JsonPrimitive("resolve"), next.arguments["action"])
        val stored = GitHubTestChecklist.checkpoint(task, receipts, tools, 6).toString()
        val restored = json(stored)
        assertEquals(JsonPrimitive(4), restored.getValue("stage_budget").jsonObject["cleanup_reserve"])
        assertEquals(JsonPrimitive(false), restored.getValue("stage_budget").jsonObject["acquisition_allowed"])
        assertEquals(JsonPrimitive("CLEANUP_RESERVE_ACTIVE"), restored.getValue("preparation").jsonObject["status"])
        assertEquals(JsonPrimitive("github_context"), restored.getValue("preparation").jsonObject.getValue("next_owned_fixture_call").jsonObject["tool"])
        assertEquals(2, receipts.size)
    }

    @Test fun cachedLiveVerificationCannotSpendAnActiveFixturesCleanupReserve() {
        val branches = AgentToolDefinition(
            "github_context",
            "Read branches",
            json("""{"type":"object","properties":{"action":{"enum":["branches"]},"verify_live":{"type":"boolean"}},"required":["action"],"additionalProperties":false}""")
        )
        val receipts = active()
        receipts["cached"] = JsonObject(
            receipt("cached", "github_context", """{"action":"branches"}""", """{"has_more":false}""") + mapOf(
                "dispatched" to JsonPrimitive(false),
                "evidence_origin" to JsonPrimitive("CACHED")
            )
        )
        val retained = receipts.toMap()
        val available = listOf(branches)
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        assertNull(GitHubSelfTestFixtures.next(task, available, receipts, allowCreate = false, cleanupOnly = true))
        assertNull(GitHubTestChecklist.nextCall(task, available, receipts, remainingToolCalls = 4))

        val verification = requireNotNull(GitHubTestChecklist.nextCall(task, available, receipts, remainingToolCalls = 5))
        assertEquals("github_context", verification.name)
        assertEquals(json("""{"action":"branches","verify_live":true}"""), verification.arguments)
        assertEquals(retained, receipts)
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, available, remainingToolCalls = 4)
        assertEquals(JsonPrimitive(4), checkpoint.getValue("stage_budget").jsonObject["cleanup_reserve"])
    }

    @Test fun cleanupReserveIsReleasedOnlyAfterExactOwnedAbsenceIsVerified() {
        val receipts = active()
        val deletion = receipt("cleanup", "github_commit", """{"action":"delete_branch","branch":"$fixture","expected_head_sha":"$sha"}""", """{"deleted":true,"absence_verified":true,"branch":"$fixture","observed_head_sha":"$sha"}""")
        receipts["cleanup"] = JsonObject(deletion + ("result" to JsonObject(deletion.getValue("result").jsonObject + ("absence_verified" to JsonPrimitive(false)))))
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        receipts["cleanup"] = deletion
        assertEquals(0, GitHubChecklistBudget.cleanupReserve(receipts))
        assertTrue(GitHubChecklistBudget.acquisitionAllowed(receipts, 6))
    }
}
