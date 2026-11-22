package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Acceptance fixtures mirror native source, commit-file, PR-readback and workflow-job receipts. */
class GitHubCriterionAcceptanceRegressionTest {
    private val head = "a".repeat(40)
    private val parent = "b".repeat(40)
    private val tree = "c".repeat(40)
    private val testPath = "app/src/test/kotlin/example/SourceRecoveryTest.kt"
    private val sourcePath = "app/src/main/kotlin/example/SourceRecovery.kt"
    private val regressionSource = "class SourceRecoveryTest {\n    @Test fun preservesPinnedRevision() { assertEquals(expected, actual) }\n}"
    private val instructionSource = "# Repository instructions\nAdd a regression and validate the changed revision."

    private fun receipt(tool: String, arguments: JsonObject, result: JsonObject, revision: String = head, repository: String = "owner/repo", time: Long = 1000, mutation: Boolean = false): JsonObject = buildJsonObject {
        put("schema", "github.evidence.v1")
        put("binding", "accepted-task")
        put("repository", repository)
        put("repository_role", "WRITE_DESTINATION")
        put("tool", tool)
        put("arguments", arguments)
        put("result", result)
        put("source_result_sha256", GitHubEvidenceBook.digest(result.toString()))
        put("source_head_sha", revision)
        put("trusted", true)
        put("verification_eligible", true)
        put("success", true)
        put("dispatched", true)
        put("shared_result", false)
        put("evidence_origin", "LIVE")
        put("observed_at_ms", time)
        put(
            "execution",
            buildJsonObject {
                put("phase", "COMPLETED")
                put("read_requests", if (mutation) 0 else 1)
                put("mutation_requests", if (mutation) 1 else 0)
                put("accepted_mutations", if (mutation) 1 else 0)
                put("unknown_mutations", 0)
            }
        )
    }

    private fun blob(content: String): String {
        val bytes = content.encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-1")
        hash.update("blob ${bytes.size}\u0000".encodeToByteArray())
        return hash.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun source(path: String, content: String, revision: String = head, start: Int = 1, total: Int = content.lines().size, fullContent: String? = null): JsonObject {
        val captured = content.lines()
        val end = start + captured.size - 1
        require(start >= 1 && end <= total)
        val complete = fullContent ?: (1..total).joinToString("\n") { line -> captured.getOrNull(line - start) ?: "// unread line $line" }
        require(complete.lines().size == total && complete.lines().subList(start - 1, end) == captured)
        val item = buildJsonObject {
            put("path", path)
            put("sha", blob(complete))
            put("expected_blob_sha", blob(complete))
            put("commit_sha", revision)
            put("start_line", start)
            put("end_line", end)
            put("content_start_line", start)
            put("total_lines", total)
            put("start_offset", 0)
            put("content", content)
            put("range_complete", true)
            put("complete_file", start == 1 && end == total)
            put("truncated", start > 1 || end < total)
            put("has_more", end < total)
            put("eof", end == total)
        }
        return receipt(
            "github_read",
            buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
                put("commit_sha", revision)
                put(
                    "ranges",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", path)
                                put("start_line", start)
                                put("end_line", end)
                            }
                        )
                    )
                )
            },
            buildJsonObject {
                put("evidence_id", GitHubEvidenceBook.digest(path + revision + content))
                put("commit_sha", revision)
                put("tree_sha", tree)
                put("items", JsonArray(listOf(item)))
            },
            revision = revision
        )
    }

    private fun assess(text: String, records: Map<String, JsonObject>, acceptance: JsonObject? = null): GitHubRequestCompletion.Assessment? =
        GitHubCriterionAcceptance.evaluate(GitHubAcceptanceContract.criteria(text, listOf(text)).single(), acceptance, records.keys, records, now = 1002)

    private fun assertNotPassed(text: String, records: Map<String, JsonObject>, acceptance: JsonObject? = null) {
        val assessment = assess(text, records, acceptance)
        assertFalse("$text unexpectedly passed: ${assessment?.reason}", assessment?.state == GitHubAcceptanceLedger.State.PASS)
    }

    @Test fun sourceOperationWithoutCapturedSourceDoesNotProveInspectionOrFinding() {
        val empty = receipt("github_read", buildJsonObject {}, buildJsonObject { put("commit_sha", head) })
        for (criterion in listOf("Inspect repository instructions", "Identify the actionable correctness issue")) {
            assertNotPassed(criterion, mapOf("empty-read" to empty))
        }
    }

    @Test fun politeInspectionCannotBorrowCommitAndTestEvidence() {
        val request = "Please inspect source and add a regression"
        val requirements = GitHubRequestCompletion.requirements(request)
        assertEquals(listOf("Please inspect source", "add a regression"), requirements)
        val criterion = GitHubAcceptanceContract.criteria(request, requirements).first()
        val records = linkedMapOf("implementation" to implementation(testPath), "unit-tests" to testJobs())
        assertEquals(GitHubAcceptanceLedger.State.PASS, GitHubRequestCompletion.engineeringAcceptance(regressionAcceptance, records).state)
        val missing = GitHubCriterionAcceptance.evaluate(criterion, regressionAcceptance, records.keys, records, now = 1002)
        assertEquals(GitHubAcceptanceLedger.State.NOT_TESTED, missing?.state)

        val observed = records + ("source" to source(sourcePath, "fun relevant() = true"))
        val accepted = GitHubCriterionAcceptance.evaluate(criterion, regressionAcceptance, observed.keys, observed, now = 1002)
        assertEquals(GitHubAcceptanceLedger.State.PASS, accepted?.state)
    }

    @Test fun compoundInstructionAndTestInspectionRequiresBothObservedCategories() {
        val criterion = "Inspect repository instructions and relevant tests"
        val instructions = source("AGENTS.md", instructionSource)
        val tests = source(testPath, regressionSource)
        assertNotPassed(criterion, mapOf("instructions" to instructions))
        assertNotPassed(criterion, mapOf("tests" to tests))
        assertNotPassed(criterion, mapOf("unrelated" to source(sourcePath, "fun existingImplementation() = true")))
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess(criterion, linkedMapOf("instructions" to instructions, "tests" to tests))?.state)
    }

    @Test fun instructionInspectionRequiresGapFreeWindowsAtOneExactSourceIdentity() {
        val criterion = "Inspect repository instructions"
        val complete = (1..6).joinToString("\n") { "Repository instruction $it." }
        fun window(start: Int, end: Int, revision: String = head, path: String = "AGENTS.md", text: String = complete): JsonObject =
            source(path, text.lines().subList(start - 1, end).joinToString("\n"), revision, start, total = 6, fullContent = text)
        val first = window(1, 2)
        val middle = window(3, 4)
        val last = window(5, 6)
        val records = linkedMapOf("first" to first, "middle" to middle, "last" to last)
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess(criterion, records)?.state)
        assertNotPassed(criterion, records - "middle")
        val named = "Inspect repository instructions in AGENTS.md and docs/instructions.md"
        val other = "docs/instructions.md"
        assertNotPassed(named, records + ("other-first" to window(1, 2, path = other)))
        val both = records + mapOf("other-first" to window(1, 2, path = other), "other-rest" to window(3, 6, path = other))
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess(named, both)?.state)
        for (different in listOf(
            window(3, 4, revision = parent),
            window(3, 4, path = "docs/instructions.md"),
            window(3, 4, text = complete.replace("instruction 3", "changed instruction 3")),
            JsonObject(middle + ("repository" to JsonPrimitive("other/repository"))),
            JsonObject(middle + ("binding" to JsonPrimitive("other-task")))
        )) {
            assertNotPassed(criterion, records + ("middle" to different))
        }
        val summary = JsonObject(
            first.getValue("result").jsonObject + (
                "source_coverage" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("schema", "github.source-coverage.v1")
                            put("path", "AGENTS.md")
                            put("blob_sha", blob(complete))
                            put("commit_sha", head)
                            put("total_lines", 6)
                            put("complete_file", true)
                        }
                    )
                )
                )
        )
        val summaryOnly = JsonObject(first + mapOf("result" to summary, "source_result_sha256" to JsonPrimitive(GitHubEvidenceBook.digest(summary.toString()))))
        assertNotPassed(criterion, mapOf("first-with-summary" to summaryOnly, "last" to last))
    }

    @Test fun explicitlyNamedSourcePathCannotBeReplacedWithAnUnrelatedRead() {
        assertNotPassed("Inspect source file `$sourcePath`", mapOf("another-file" to source("src/Other.kt", "fun unrelated() = true")))
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess("Inspect source file `$sourcePath`", mapOf("requested-file" to source(sourcePath, "fun relevant() = true")))?.state)
    }

    @Test fun aCompleteRelevantWindowCountsAsSourceInspectionWithoutClaimingAWholeFileRead() {
        val window = source(sourcePath, "fun relevant() = true", start = 10, total = 40)
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess("Inspect relevant source code", mapOf("source-window" to window))?.state)
    }

    private fun pullRequest(number: Int, draft: Boolean, title: String, revision: String = head): JsonObject = buildJsonObject {
        put("number", number)
        put("title", title)
        put("body", "Verified changes and validation evidence.")
        put("state", "open")
        put("draft", draft)
        put(
            "head",
            buildJsonObject {
                put("sha", revision)
                put("ref", "work/source-recovery")
            }
        )
        put("base", buildJsonObject { put("ref", "main") })
    }

    private fun draftCreation(number: Int = 17, draft: Boolean = true, title: String = "Expected title", repository: String = "owner/repo") = receipt(
        "github_pr",
        buildJsonObject {
            put("action", "create")
            put("owner", repository.substringBefore('/'))
            put("repo", repository.substringAfter('/'))
            put("title", title)
            put("body", "Verified changes and validation evidence.")
            put("head", "work/source-recovery")
            put("base", "main")
            put("expected_head_sha", head)
            put("draft", draft)
        },
        buildJsonObject { put("pull_request", pullRequest(number, draft, title)) },
        repository = repository,
        mutation = true
    )

    private fun draftReadback(number: Int = 17, draft: Boolean = true, title: String = "Expected title", revision: String = head, repository: String = "owner/repo") = receipt(
        "github_pr",
        buildJsonObject {
            put("action", "context")
            put("pull_number", number)
            put("verify_live", true)
        },
        buildJsonObject { put("pull_request", pullRequest(number, draft, title, revision)) },
        revision = revision,
        repository = repository,
        time = 1001
    )

    private fun links(operations: List<String> = listOf("draft-create"), verification: List<String> = listOf("draft-read")) = buildJsonObject {
        put("operation_receipts", JsonArray(operations.map(::JsonPrimitive)))
        put("verification_receipts", JsonArray(verification.map(::JsonPrimitive)))
    }

    @Test fun anUnverifiedDraftCannotBorrowTheReadbackOfAnotherOrdinaryPullRequest() {
        val records = linkedMapOf(
            "draft-create" to draftCreation(),
            "ordinary-create" to draftCreation(number = 18, draft = false),
            "ordinary-read" to draftReadback(number = 18, draft = false)
        )
        assertNotPassed("Prepare a draft pull request", records, links(listOf("draft-create", "ordinary-create"), listOf("ordinary-read")))
    }

    @Test fun draftAcceptancePreservesTheRequestedRepositoryAndTitle() {
        val criterion = "Prepare a draft pull request titled \"Expected title\" in owner/repo"
        for ((title, repository) in listOf("Different title" to "owner/repo", "Expected title" to "other/repository")) {
            assertNotPassed(
                criterion,
                linkedMapOf("draft-create" to draftCreation(title = title, repository = repository), "draft-read" to draftReadback(title = title, repository = repository)),
                links()
            )
        }
        assertEquals(
            GitHubAcceptanceLedger.State.PASS,
            assess(criterion, linkedMapOf("draft-create" to draftCreation(), "draft-read" to draftReadback()), links())?.state
        )
    }

    @Test fun politeDraftCriteriaRetainConstraintsAndExactReadback() {
        val records = linkedMapOf("draft-create" to draftCreation(), "draft-read" to draftReadback())
        for (prefix in listOf("Please", "PLEASE")) {
            val criterion = "$prefix prepare a draft pull request titled \"Expected title\" in owner/repo"
            assertEquals(GitHubAcceptanceLedger.State.PASS, assess(criterion, records, links())?.state)
            for ((title, repository) in listOf("Different title" to "owner/repo", "Expected title" to "other/repository")) {
                assertNotPassed(
                    criterion,
                    linkedMapOf("draft-create" to draftCreation(title = title, repository = repository), "draft-read" to draftReadback(title = title, repository = repository)),
                    links()
                )
            }
            for (read in listOf(draftReadback(number = 18), draftReadback(revision = parent), draftReadback(draft = false))) {
                assertNotPassed(criterion, records + ("draft-read" to read), links())
            }
            for (suffix in listOf("with all required checks passing", "into the release branch")) {
                assertNotPassed("$prefix prepare a draft pull request $suffix", records, links())
            }
        }
    }

    @Test fun unsupportedDraftConditionsCannotBeDiscardedByTheSimpleCreationEvaluator() {
        val records = linkedMapOf("draft-create" to draftCreation(), "draft-read" to draftReadback())
        for (criterion in listOf("Prepare a draft pull request with all required checks passing", "Prepare a draft pull request into the release branch")) {
            assertNotPassed(criterion, records, links())
        }
    }

    @Test fun draftCreationNeedsTheSameNumberRevisionAndDraftStateInItsLiveReadback() {
        for (read in listOf(draftReadback(number = 18), draftReadback(revision = parent), draftReadback(draft = false))) {
            assertNotPassed("Prepare a draft pull request", linkedMapOf("draft-create" to draftCreation(), "draft-read" to read), links())
        }
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess("Prepare a draft pull request", linkedMapOf("draft-create" to draftCreation(), "draft-read" to draftReadback()), links())?.state)
    }

    private fun implementation(changedPath: String) = receipt(
        "github_commit",
        buildJsonObject {
            put("action", "commit")
            put("stage_id", "observed-stage")
            put("branch", "work/source-recovery")
            put("expected_head_sha", parent)
            put("message", "Preserve source recovery identity with a regression")
        },
        buildJsonObject {
            put("sha", head)
            put("branch", "work/source-recovery")
            put("expected_head_sha", head)
            put("parent_sha", parent)
            put("tree_sha", tree)
            put("files_changed", 1)
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", changedPath)
                            put("status", "modified")
                            put("delete", false)
                            put("previous_blob_sha", "d".repeat(40))
                            put("expected_blob_sha", "d".repeat(40))
                            put("blob_sha", blob(regressionSource))
                            put("result_bytes", regressionSource.encodeToByteArray().size)
                        }
                    )
                )
            )
        },
        mutation = true
    )

    private fun testJobs(revision: String = head) = receipt(
        "github_ci",
        buildJsonObject {
            put("action", "jobs")
            put("run_id", 4_000_000_001L)
            put("run_attempt", 2)
            put("expected_head_sha", revision)
            put("page", 1)
            put("per_page", 100)
        },
        buildJsonObject {
            put("run_id", 4_000_000_001L)
            put("run_attempt", 2)
            put("head_sha", revision)
            put("page", 1)
            put("per_page", 100)
            put("total_count", 1)
            put("has_more", false)
            put(
                "jobs",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", 5_000_000_001L)
                            put("name", "Unit tests")
                            put("status", "completed")
                            put("conclusion", "success")
                        }
                    )
                )
            )
        },
        revision = revision,
        time = 1001
    )

    private val regressionAcceptance = buildJsonObject {
        put("implementation_receipts", JsonArray(listOf(JsonPrimitive("implementation"))))
        put("test_receipts", JsonArray(listOf(JsonPrimitive("unit-tests"))))
    }

    @Test fun readingAnUnchangedExistingTestDoesNotProveARegressionWasCommitted() {
        val records = linkedMapOf(
            "implementation" to implementation(sourcePath),
            "existing-test" to source(testPath, regressionSource),
            "unit-tests" to testJobs()
        )
        assertEquals(GitHubAcceptanceLedger.State.PASS, GitHubRequestCompletion.engineeringAcceptance(regressionAcceptance, records).state)
        assertNotPassed("Add a behavior regression", records, regressionAcceptance)
    }

    @Test fun aChangedRegressionRequiresItsSourceAndTestsAtTheExactCommittedRevision() {
        val records = linkedMapOf(
            "implementation" to implementation(testPath),
            "regression-source" to source(testPath, regressionSource),
            "unit-tests" to testJobs()
        )
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess("Add a behavior regression", records, regressionAcceptance)?.state)
        assertNotPassed("Add a behavior regression", records - "regression-source", regressionAcceptance)
        assertNotPassed("Add a behavior regression", records + ("regression-source" to source(testPath, regressionSource, revision = parent)), regressionAcceptance)
        assertNotPassed("Add a behavior regression", records + ("unit-tests" to testJobs(parent)), regressionAcceptance)
    }

    @Test fun regressionSourceCannotBeSynthesizedFromACommitFileNameOrEmptyReadResult() {
        val emptyRead = receipt("github_read", buildJsonObject {}, buildJsonObject { put("commit_sha", head) })
        val records = linkedMapOf("implementation" to implementation(testPath), "regression-source" to emptyRead, "unit-tests" to testJobs())
        assertNotPassed("Add a behavior regression", records, regressionAcceptance)
        val failed = JsonObject(source(testPath, regressionSource) + ("success" to JsonPrimitive(false)))
        assertNotPassed("Add a behavior regression", records + ("regression-source" to failed), regressionAcceptance)
    }

    @Test fun anExactRetainedCommitPreviewSuppliesRegressionSourceWithoutAnotherRead() {
        val committed = implementation(testPath)
        val preview = buildJsonObject {
            put("path", testPath)
            put("expected_blob_sha", "d".repeat(40))
            put("original_bytes", 100)
            put("result_bytes", regressionSource.encodeToByteArray().size)
            put("before", "class SourceRecoveryTest { }")
            put("after", regressionSource)
            put("preview_bounded", true)
        }
        fun withPreview(value: JsonObject) = JsonObject(committed + ("result" to JsonObject(committed.getValue("result").jsonObject + ("patch_preview" to JsonArray(listOf(value))))))
        val records = linkedMapOf("implementation" to withPreview(preview), "unit-tests" to testJobs())
        assertEquals(GitHubAcceptanceLedger.State.PASS, assess("Add a behavior regression", records, regressionAcceptance)?.state)
        for (mismatch in listOf("path" to JsonPrimitive("test/UnrelatedTest.kt"), "expected_blob_sha" to JsonPrimitive(parent), "after" to JsonPrimitive(""))) {
            assertNotPassed("Add a behavior regression", records + ("implementation" to withPreview(JsonObject(preview + mismatch))), regressionAcceptance)
        }
    }
}
