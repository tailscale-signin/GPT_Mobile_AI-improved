package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
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

class GitHubAcceptanceContractTest {
    private val request = "Inspect the repository instructions and relevant tests, identify the highest-confidence actionable correctness issue, and implement its complete fix on a new feature branch. Add a behavior regression, run relevant validation, review the diff and prepare a draft pull request. Report the exact changes and any validation that could not run."

    @Test fun capturedCompoundRequestHasStableAtomicCriteriaAndExactSourceSpans() {
        val requirements = GitHubRequestCompletion.requirements(request)
        val criteria = GitHubAcceptanceContract.criteria(request, requirements)
        assertEquals(8, criteria.size)
        assertEquals(GitHubAcceptanceContract.Producer.entries.filter { it != GitHubAcceptanceContract.Producer.REQUEST_SPECIFIC }, criteria.map { it.producer })
        for (criterion in criteria) {
            assertEquals(criterion.text, request.substring(requireNotNull(criterion.sourceStart), requireNotNull(criterion.sourceEnd)))
            assertEquals(GitHubRequestCompletion.id(criterion.text), criterion.id)
        }
        assertEquals(listOf(criteria.first().id), criteria[1].dependencies)
        assertEquals(criteria.dropLast(1).map { it.id }, criteria.last().dependencies)
        assertEquals(criteria, GitHubAcceptanceContract.criteria(request, GitHubRequestCompletion.requirements(request)))
    }

    @Test fun preservedConstraintsAndConditionalOrQuotedCommandsDoNotAcquireIndependentAuthority() {
        val task = "$request\nBudget: 48 tool calls. Do not merge or publish."
        val contract = GitHubAcceptanceContract.json(task, GitHubRequestCompletion.requirements(task))
        assertEquals(JsonPrimitive(task), contract["original_request"])
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(task)), contract["request_digest"])
        assertEquals(8, contract.getValue("criteria").jsonArray.size)
        for (text in listOf("Inspect the source and, only if approved, publish the release", "Review this example: `implement the fix and run tests`", "Do not merge or publish the branch", "Explain how to create a branch and run tests")) {
            assertEquals(listOf(text), GitHubAcceptanceContract.split(text))
        }
        assertEquals(GitHubAcceptanceContract.Producer.REQUEST_SPECIFIC, GitHubAcceptanceContract.producer("Inspect the files and delete the branch"))
    }

    @Test fun legacyContractRefinementPreservesOriginalAuthorityBudgetAndDeadline() {
        val retained = buildJsonObject {
            put("requested_actions", request)
            put("request_digest", GitHubEvidenceBook.digest(request))
            put("acceptance_criteria", JsonArray(listOf(JsonPrimitive(request))))
            put("tool_call_limit", 48)
            put("accepted_at_ms", 1000)
            put("deadline_at_ms", 61000)
            put("binding", "retained-task")
        }
        val refined = GitHubAcceptanceContract.refineRetained(retained)
        assertEquals(retained, JsonObject(refined - "acceptance_contract"))
        assertEquals(8, refined.getValue("acceptance_contract").jsonObject.getValue("criteria").jsonArray.size)
        assertEquals(refined, GitHubAcceptanceContract.refineRetained(refined))
        val changed = JsonObject(retained + ("requested_actions" to JsonPrimitive("Publish a different repository")))
        assertEquals(changed, GitHubAcceptanceContract.refineRetained(changed))
    }

    @Test fun patchAndDraftClaimsCannotPassMissingRegressionValidationOrDiffReview() {
        val requirements = GitHubRequestCompletion.requirements(request)
        val receipts = mapOf(
            "source" to sourceReceipt(),
            "patch" to receipt("github_commit", buildJsonObject { put("action", "commit") }),
            "draft" to receipt(
                "github_pr",
                buildJsonObject {
                    put("action", "create")
                    put("draft", true)
                }
            )
        )
        val claims = listOf(
            buildJsonObject {
                put("kind", "fact")
                put("status", "VERIFIED")
                put("receipt_id", "source")
            }
        )
        val answer = buildJsonObject {
            put("completion_verified", true)
            put("requirement_claims", buildJsonObject { requirements.forEach { put(GitHubRequestCompletion.id(it), JsonArray(listOf(JsonPrimitive(0)))) } })
            put(
                "acceptance",
                buildJsonObject {
                    requirements.forEach {
                        put(
                            GitHubRequestCompletion.id(it),
                            buildJsonObject {
                                put("implementation_receipts", JsonArray(listOf(JsonPrimitive("patch"))))
                                put("operation_receipts", JsonArray(listOf(JsonPrimitive("draft"))))
                            }
                        )
                    }
                }
            )
        }
        val result = GitHubRequestCompletion.evaluate(request, claims, answer, receipts)
        val rows = result.getValue("requirements").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive(false), result["completion_verified"])
        assertEquals(JsonPrimitive("PARTIAL"), result["task_outcome"])
        assertEquals(2, rows.count { it["status"] == JsonPrimitive("PASSED") })
        for (row in rows.drop(2)) assertFalse(row.toString(), row["status"] == JsonPrimitive("PASSED"))
        assertEquals(JsonPrimitive("DEPENDENCIES_INCOMPLETE"), rows.last()["acceptance_mismatch"])
        val ledger = GitHubAcceptanceLedger.attach(result).getValue("acceptance_ledger").jsonObject
        assertEquals(JsonPrimitive(false), ledger["completion_verified"])
        assertTrue(ledger.getValue("requirements").jsonArray.all { it.jsonObject["criterion_contract"] is JsonObject })
    }

    @Test fun inventoryAndMissingSourceReceiptsDoNotProveInspection() {
        val task = "Inspect repository instructions and implement the fix"
        val requirements = GitHubRequestCompletion.requirements(task)
        val answer = buildJsonObject { put("requirement_claims", buildJsonObject { put(GitHubRequestCompletion.id(requirements.first()), JsonArray(listOf(JsonPrimitive(0)))) }) }
        val claim = buildJsonObject {
            put("status", "VERIFIED")
            put("receipt_id", "inventory")
        }
        val inventory = receipt(
            "github_search",
            buildJsonObject {
                put("mode", "path")
                put("query", "instructions")
            }
        )
        for (receipts in listOf(emptyMap(), mapOf("inventory" to inventory))) {
            val result = GitHubRequestCompletion.evaluate(task, listOf(claim), answer, receipts)
            assertFalse(result.getValue("requirements").jsonArray.first().jsonObject["status"] == JsonPrimitive("PASSED"))
        }
    }

    private fun sourceReceipt() = JsonObject(
        receipt("github_read", JsonObject(emptyMap())) + (
            "result" to buildJsonObject {
                put("commit_sha", "a".repeat(40))
                put(
                    "items",
                    JsonArray(
                        listOf("AGENTS.md", "app/src/test/BehaviorTest.kt").map { path ->
                            buildJsonObject {
                                put("path", path)
                                put("sha", "b".repeat(40))
                                put("expected_blob_sha", "b".repeat(40))
                                put("commit_sha", "a".repeat(40))
                                put("start_line", 1)
                                put("end_line", 1)
                                put("total_lines", 1)
                                put("content", if (path == "AGENTS.md") "Run the relevant regression tests." else "@Test fun preservesTheRequestedBehavior() { assertTrue(actualResult) }")
                                put("range_complete", true)
                                put("complete_file", true)
                            }
                        }
                    )
                )
            }
            )
    )

    private fun receipt(tool: String, arguments: JsonObject) = buildJsonObject {
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("tool", tool)
        put("arguments", arguments)
        put("source_head_sha", "a".repeat(40))
        put("result", JsonObject(emptyMap()))
    }
}
