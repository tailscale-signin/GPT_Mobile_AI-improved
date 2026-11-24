package dev.chungjungsoo.gptmobile.data.github

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

class GitHubAcceptanceWorkTest {
    private fun row(id: String, state: String = "BLOCKED", producer: String = "VALIDATION_JOBS") = buildJsonObject {
        put("id", id)
        put("state", state)
        put("criterion", "Verify $id")
        put("criterion_contract", buildJsonObject { put("evidence_producer", producer) })
    }

    @Test fun independentWorkLeadsAndVerifiedRowsStayOutOfThePlan() {
        val waiting = JsonObject(row("report") + ("pending_dependencies" to JsonArray(listOf(JsonPrimitive("tests")))))
        val denied = JsonObject(row("denied") + ("blocker_code" to JsonPrimitive("APPROVAL_REQUIRED")))
        val finished = JsonObject(row("done", "PASS") + ("required" to JsonPrimitive(true)))
        val optional = JsonObject(row("optional") + ("required" to JsonPrimitive(false)))
        val report = buildJsonObject { put("acceptance_ledger", buildJsonObject { put("requirements", JsonArray(listOf(waiting, denied, finished, optional, row("tests")))) }) }
        val rows = GitHubAcceptanceWork.outstanding(report)
        assertEquals(listOf("tests", "report", "denied"), rows.map { (it["id"] as JsonPrimitive).content })
        val page = GitHubAcceptanceWork.projection(rows, limit = 2)
        assertEquals(JsonPrimitive(1), page["omitted"])
        val work = (page.getValue("criteria") as JsonArray).map { it.jsonObject }
        assertEquals(JsonArray(listOf(JsonPrimitive("github_ci"))), work.first()["tool_families"])
        assertFalse(work.last().containsKey("tool_families"))
        assertEquals(JsonPrimitive(true), work.last()["deferred"])
        assertTrue(GitHubAcceptanceWork.step(denied)?.tools?.isEmpty() == true)
    }

    @Test fun replayIncludesOnlyActionableCriteriaAndTheirTransitiveDependencies() {
        fun rowWithLinks(id: String, state: String, vararg prerequisites: String) = JsonObject(
            row(id, state) + mapOf(
                "receipt_ids" to JsonArray(listOf(JsonPrimitive("evidence-$id"))),
                "criterion_contract" to buildJsonObject { put("depends_on", JsonArray(prerequisites.map(::JsonPrimitive))) }
            )
        )
        val denied = JsonObject(rowWithLinks("denied", "BLOCKED", "private") + ("blocker_code" to JsonPrimitive("APPROVAL_REQUIRED")))
        val rows = listOf(rowWithLinks("release", "FAIL", "test"), rowWithLinks("test", "PASS", "source"), rowWithLinks("source", "PASS", "test"), denied, rowWithLinks("private", "PASS"), rowWithLinks("other", "PASS"))
        val report = buildJsonObject { put("acceptance_ledger", buildJsonObject { put("requirements", JsonArray(rows)) }) }
        assertEquals(setOf("evidence-release", "evidence-test", "evidence-source"), GitHubAcceptanceWork.replayReceiptIds(report))
    }

    @Test fun advisoryClausesMustAccountForTheWholeOriginalRequirement() {
        val left = "The parser should log missing symbols"
        val right = "the settings should show the selected profile"
        val task = "$left and $right"
        fun plan(first: String, second: String) = buildJsonObject {
            put("steps", JsonArray(listOf(first, second).map { buildJsonObject { put("criterion", it) } }))
        }
        val proposals = plan(left, right)
        val bound = requireNotNull(GitHubAcceptanceContract.semanticSteps(task, proposals))
        assertEquals(listOf(left, right), GitHubRequestCompletion.requirements(task, null, bound))
        assertEquals(listOf(task), GitHubRequestCompletion.requirements(task, null, JsonObject(bound + ("request_digest" to JsonPrimitive("different")))))
        assertTrue(GitHubAcceptanceContract.semanticSteps("$task only after approval", proposals) == null)
        assertTrue(GitHubAcceptanceContract.semanticSteps("$left after tests and $right", proposals) == null)
        assertTrue(GitHubAcceptanceContract.semanticSteps(task, plan(right, left)) == null)
        assertTrue(GitHubAcceptanceContract.semanticSteps(task, plan(left, "publish the change")) == null)
    }

    @Test fun existingProofIsRemappedBeforeAcquiringMoreEvidence() {
        val uncovered = JsonObject(row("test") + ("acceptance_mismatch" to JsonPrimitive("NO_MATCHING_CLAIMS")))
        val observed = JsonObject(uncovered + ("eligible_receipt_count" to JsonPrimitive(1)))
        assertEquals(listOf("github_ci"), GitHubAcceptanceWork.step(uncovered)?.tools)
        assertTrue(GitHubAcceptanceWork.step(observed)?.tools?.isEmpty() == true)
    }
}
