package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIssueObligationCompletionTest {
    private val binding = "issue-obligation"
    private val repository = "owner/repo"
    private val now = 10_000L

    @Test fun completeLiveEmptyIntakeFinishesOnlyIssueWorkWithoutInventingAMutation() {
        for (task in listOf(
            "Fix all open issues",
            "Please fix every open issue.",
            "Fix and close all open issues",
            "Examine all open issues, fix them all, and then close them.",
            "Close all open issues",
            "Review all issues and close them."
        )) {
            val receipts = mapOf("inventory" to inventory())
            val context = context(task, receipts)
            val result = evaluate(task, context, receipts)
            assertTrue(task, context.groups.isEmpty())
            assertEquals(task, JsonPrimitive(true), result["answer_verified"])
            assertEquals(task, JsonPrimitive(true), result["completion_verified"])
            assertTrue(task, rows(result).all { it["acceptance_state"] == JsonPrimitive("PASS") })
            assertFalse(task, result.toString().contains("implementation_receipts"))
        }
    }

    @Test fun emptyIntakeDoesNotWaiveUnrelatedCriteriaConditionsOrAnotherRepository() {
        for (task in listOf(
            "Fix all open issues and update README.md",
            "Fix all open issues\nUpdate README.md",
            "Close all open issues and run the integration tests",
            "Fix all open issues when CI passes",
            "Fix all closed issues",
            "Fix all open issues in owner/other",
            "Fix all open issues and create a branch",
            "Fix all open issues; 'also deploy the app'"
        )) {
            val receipts = mapOf("inventory" to inventory())
            val result = evaluate(task, context(task, receipts), receipts)
            assertEquals(task, JsonPrimitive(false), result["completion_verified"])
            assertTrue(task, rows(result).any { it["acceptance_state"] != JsonPrimitive("PASS") })
        }
    }

    @Test fun emptyIntakeRechecksItsExactLiveEvidenceAndCurrentAuthorization() {
        val task = "Fix all open issues"
        val original = inventory()
        val context = context(task, mapOf("inventory" to original))
        val replacements = listOf(
            JsonObject(original + ("trusted" to JsonPrimitive(false))),
            JsonObject(original + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(original + ("binding" to JsonPrimitive("another-task"))),
            JsonObject(original + ("repository" to JsonPrimitive("owner/other"))),
            JsonObject(original + ("repository_role" to JsonPrimitive("READ_SOURCE"))),
            JsonObject(original + ("evidence_origin" to JsonPrimitive("CACHED"))),
            changeResult(original, "has_more", JsonPrimitive(true)),
            changeResult(original, "returned", JsonPrimitive(1)),
            inventory(listOf(1))
        )
        for ((index, replacement) in replacements.withIndex()) {
            assertEquals("replacement $index", JsonPrimitive(false), evaluate(task, context, mapOf("inventory" to replacement))["completion_verified"])
        }
        assertEquals(JsonPrimitive(false), evaluate(task, context, emptyMap())["completion_verified"])
        val forged = context.copy(cohort = JsonObject(context.cohort + ("work_kind" to JsonPrimitive("CLOSURE_ONLY"))))
        assertEquals(JsonPrimitive(false), evaluate(task, forged, mapOf("inventory" to original))["completion_verified"])
    }

    @Test fun emptyIntakeNeedsACheckedClaimLinkedToTheCompleteInventory() {
        val task = "Fix all open issues"
        val receipts = mapOf("inventory" to inventory(), "unrelated" to receipt("github_read", args("ranges"), JsonObject(emptyMap())))
        val context = context(task, receipts)
        assertEquals(JsonPrimitive(false), evaluate(task, context, receipts, listOf(claim("unrelated")))["completion_verified"])
        assertEquals(JsonPrimitive(false), evaluate(task, context, receipts, listOf(JsonObject(claim("inventory") + ("status" to JsonPrimitive("UNSUPPORTED")))))["completion_verified"])
        assertEquals(JsonPrimitive(false), evaluate(task, context, receipts, emptyList())["completion_verified"])
        val legacy = context.copy(cohort = JsonObject(context.cohort - "work_kind"))
        assertEquals(JsonPrimitive(true), evaluate(task, legacy, receipts)["completion_verified"])
    }

    @Test fun pendingDecisionsAndApprovalsStillBlockAnOtherwiseCompleteEmptyIntake() {
        val task = "Fix all open issues"
        val observations = mapOf("inventory" to inventory())
        val context = context(task, observations)
        for (blocker in listOf(decision(), approval())) {
            val result = evaluate(task, context, observations + ("pending" to blocker))
            assertEquals(JsonPrimitive(false), result["completion_verified"])
            assertEquals(JsonPrimitive("UNRESOLVED_USER_DECISION"), rows(result).first()["acceptance_mismatch"])
        }
    }

    @Test fun closureOnlyCohortCompletesFromGuardedOperationsAndFreshReadbacks() {
        for (task in listOf("Close all open issues", "Review all issues and close them")) {
            val receipts = closedIssues()
            val context = context(task, receipts)
            assertTrue(context.closureOnly)
            val result = evaluate(task, context, receipts, closedClaims(), closureMappings(context))
            assertEquals(task, JsonPrimitive(true), result["completion_verified"])
            val groups = rows(result).filter { "issue_group" in it }
            assertEquals(2, groups.size)
            for (group in groups) {
                assertEquals(JsonPrimitive("PASS"), group["acceptance_state"])
                assertEquals(listOf(JsonPrimitive("CLOSURE")), group.getValue("issue_phases").jsonArray.map { it.jsonObject["stage"] })
                assertEquals(JsonPrimitive("OPERATION_ACCEPTANCE"), group["acceptance_evaluator_branch"])
                assertFalse(group.containsKey("issue_test_revision_scope"))
                assertFalse(group.containsKey("diagnosis_proof"))
            }
        }
    }

    @Test fun eachClosureRemainsIndependentAndCannotBorrowMissingGuardOrReadback() {
        val task = "Close all open issues"
        val receipts = closedIssues()
        val context = context(task, receipts)
        val secondClose = receipts.getValue("close-2")
        val secondRead = receipts.getValue("readback-2")
        val variants = listOf(
            receipts - "readback-2",
            receipts + ("close-2" to JsonObject(secondClose + ("arguments" to JsonObject(secondClose.getValue("arguments").jsonObject - "expected_updated_at")))),
            receipts + ("close-2" to JsonObject(secondClose + ("binding" to JsonPrimitive("another-task")))),
            receipts + ("close-2" to JsonObject(secondClose + ("verification_eligible" to JsonPrimitive(false)))),
            receipts + ("readback-2" to JsonObject(secondRead + ("evidence_origin" to JsonPrimitive("CACHED")))),
            receipts + ("readback-2" to changeResult(secondRead, "issue", issue(2, "open"))),
            receipts + ("readback-2" to changeResult(secondRead, "issue", issue(1, "closed")))
        )
        for ((index, changed) in variants.withIndex()) {
            val result = evaluate(task, context, changed, closedClaims(), closureMappings(context))
            assertEquals("variant $index", JsonPrimitive(false), result["completion_verified"])
            val groups = rows(result).filter { "issue_group" in it }
            assertEquals(JsonPrimitive("PASS"), groups.single { it["issue_number"] == JsonPrimitive(1) }["acceptance_state"])
            assertFalse(groups.single { it["issue_number"] == JsonPrimitive(2) }["acceptance_state"] == JsonPrimitive("PASS"))
        }
    }

    @Test fun closureProofSurvivesResumeButDoesNotWaiveClaimsDecisionsOrOtherWork() {
        val task = "Close all open issues"
        val receipts = closedIssues()
        val context = context(task, receipts)
        val completed = evaluate(task, context, receipts, closedClaims(), closureMappings(context))
        val resumed = context.copy(previous = rows(completed).filter { "issue_group" in it })
        assertEquals(JsonPrimitive(true), evaluate(task, resumed, receipts, closedClaims())["completion_verified"])
        assertEquals(JsonPrimitive(false), evaluate(task, resumed, receipts, emptyList())["completion_verified"])
        assertEquals(JsonPrimitive(false), evaluate(task, resumed, receipts + ("pending" to decision()), closedClaims())["completion_verified"])
        assertEquals(JsonPrimitive(false), evaluate(task, resumed, receipts - "readback-2", closedClaims())["completion_verified"])
        val compound = "$task\nUpdate README.md"
        val compoundContext = context(compound, receipts)
        val result = evaluate(compound, compoundContext, receipts, closedClaims(), closureMappings(compoundContext))
        assertEquals(JsonPrimitive(false), result["completion_verified"])
        assertTrue(rows(result).filter { "issue_group" in it }.all { it["acceptance_state"] == JsonPrimitive("PASS") })
    }

    @Test fun actualRepairRequestsRetainDiagnosisPublishedChangesAndRevisionTests() {
        for (task in listOf(
            "Fix all open issues and then close them",
            "Fix and close all open issues",
            "Review all issues and repair them and close them",
            "Close all open issues once fixed",
            "Examine all issues and close them after fixing them",
            "Close all open issues after you repair them",
            "Close all open issues once they have been resolved",
            "Examine all issues opened on GitHub and close them once fixed. If not able to be fixed, enhance report feature. Then close the Issue."
        )) {
            val receipts = closedIssues()
            val context = context(task, receipts)
            assertFalse(task, context.closureOnly)
            val result = evaluate(task, context, receipts, closedClaims(), closureMappings(context))
            assertEquals(task, JsonPrimitive(false), result["completion_verified"])
            for (group in rows(result).filter { "issue_group" in it }) {
                val phases = group.getValue("issue_phases").jsonArray.map { it.jsonObject }
                assertEquals(listOf("DIAGNOSIS", "CHANGE", "TEST", "CLOSURE").map(::JsonPrimitive), phases.map { it["stage"] })
                assertTrue(phases.filter { it["stage"] in setOf(JsonPrimitive("CHANGE"), JsonPrimitive("TEST")) }.all { it["state"] == JsonPrimitive("NOT_TESTED") })
                assertEquals(JsonPrimitive("PASS"), phases.single { it["stage"] == JsonPrimitive("CLOSURE") }["state"])
            }
            val downgraded = JsonObject(context.cohort + ("work_kind" to JsonPrimitive("CLOSURE_ONLY")))
            assertFalse(GitHubIssueRemediation.valid(downgraded, task, binding, repository))
            assertFalse(context.copy(cohort = JsonObject(context.cohort - "work_kind")).closureOnly)
        }
    }

    @Test fun conditionalRepairDoesNotBorrowQuotedExamplesOrUnrelatedFixes() {
        for (task in listOf(
            "Close all open issues and fix README.md",
            "Close all open issues after fixing README.md",
            "Close all open issues once the documentation is fixed",
            "Close all open issues. Explain the example: 'close them once fixed'",
            "Close all open issues. Review `after fixing them`"
        )) {
            val receipts = closedIssues()
            val context = context(task, receipts)
            assertTrue(task, context.closureOnly)
            val result = evaluate(task, context, receipts, closedClaims(), closureMappings(context))
            assertEquals(task, JsonPrimitive(false), result["completion_verified"])
            for (group in rows(result).filter { "issue_group" in it }) {
                assertEquals(task, listOf(JsonPrimitive("CLOSURE")), group.getValue("issue_phases").jsonArray.map { it.jsonObject["stage"] })
                assertEquals(task, JsonPrimitive("PASS"), group["acceptance_state"])
            }
        }
    }

    private fun evaluate(
        task: String,
        context: GitHubIssueRemediation.Context,
        receipts: Map<String, JsonObject>,
        claims: List<JsonObject> = listOf(claim("inventory")),
        acceptance: JsonObject = JsonObject(emptyMap())
    ): JsonObject {
        val answer = buildJsonObject {
            put(
                "requirement_claims",
                buildJsonObject {
                    GitHubRequestCompletion.requirements(task, context).forEach { put(GitHubRequestCompletion.id(it), JsonArray(claims.indices.map(::JsonPrimitive))) }
                }
            )
            put("acceptance", acceptance)
        }
        return GitHubRequestCompletion.evaluate(task, claims, answer, receipts, now, issueWork = context)
    }

    private fun context(task: String, receipts: Map<String, JsonObject>) = GitHubIssueRemediation.Context(requireNotNull(GitHubIssueRemediation.freeze(task, binding, repository, receipts)))
    private fun rows(report: JsonObject) = report.getValue("requirements").jsonArray.map { it.jsonObject }
    private fun claim(id: String) = buildJsonObject {
        put("kind", "fact")
        put("status", "VERIFIED")
        put("receipt_id", id)
    }
    private fun closedClaims() = listOf("inventory", "readback-1", "readback-2").map(::claim)
    private fun closureMappings(context: GitHubIssueRemediation.Context) = buildJsonObject {
        context.groups.forEach { group ->
            val number = group.getValue("issue_number").toString()
            put(
                GitHubRequestCompletion.id(GitHubIssueRemediation.requirement(group, context)),
                buildJsonObject {
                    put("operation_receipts", JsonArray(listOf(JsonPrimitive("close-$number"))))
                    put("verification_receipts", JsonArray(listOf(JsonPrimitive("readback-$number"))))
                }
            )
        }
    }

    private fun closedIssues() = linkedMapOf(
        "inventory" to inventory(listOf(1, 2)),
        "close-1" to close(1),
        "close-2" to close(2),
        "readback-1" to readback(1),
        "readback-2" to readback(2)
    )

    private fun inventory(numbers: List<Int> = emptyList()) = receipt(
        "github_issue",
        args("list", "state" to JsonPrimitive("open"), "page" to JsonPrimitive(1)),
        buildJsonObject {
            put("resource_type", "issue")
            put("items", JsonArray(numbers.map { issue(it, "open") }))
            put("returned", numbers.size)
            put("total_count", numbers.size)
            put("page", 1)
            put("has_more", false)
            put("traversal_state", "COMPLETE")
        }
    )

    private fun close(number: Int) = receipt(
        "github_issue",
        args("update", "issue_number" to JsonPrimitive(number), "state" to JsonPrimitive("closed"), "expected_updated_at" to JsonPrimitive("2026-11-21T12:00:00Z")),
        buildJsonObject {
            put("issue", issue(number, "closed"))
            put("upstream_completed", true)
        },
        2000
    )
    private fun readback(number: Int) = receipt("github_issue", args("context", "issue_number" to JsonPrimitive(number), "verify_live" to JsonPrimitive(true)), buildJsonObject { put("issue", issue(number, "closed")) }, 3000)
    private fun issue(number: Int, state: String) = buildJsonObject {
        put("number", number)
        put("title", "Issue $number")
        put("state", state)
        put("updated_at", "2026-11-21T12:00:00Z")
    }
    private fun decision() = JsonObject(receipt("github_decide", JsonObject(emptyMap()), JsonObject(emptyMap())) + ("error_code" to JsonPrimitive("GITHUB_DECISION_REQUIRED")))
    private fun approval() = receipt(
        "github_commit",
        args("commit"),
        buildJsonObject {
            put("status", "AWAITING_APPROVAL")
            put("committed", false)
            put("engineering_task_id", "approval-task")
        }
    )
    private fun args(action: String, vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf("action" to JsonPrimitive(action)) + fields)
    private fun receipt(tool: String, arguments: JsonObject, result: JsonObject, observedAt: Long = 1000) = buildJsonObject {
        put("binding", binding)
        put("repository", repository)
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("dispatched", true)
        put("evidence_origin", "LIVE")
        put("observed_at_ms", observedAt)
        put("tool", tool)
        put("source_head_sha", "a".repeat(40))
        put("arguments", arguments)
        put("result", result)
    }
    private fun changeResult(receipt: JsonObject, key: String, value: JsonElement) = JsonObject(receipt + ("result" to JsonObject(receipt.getValue("result").jsonObject + (key to value))))
}
