package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIssueRemediationTest {
    private val task = "Examine all open issues, fix them all, and then close them."
    private val binding = "issue-task"
    private val repository = "owner/repo"
    private val sha = "a".repeat(40)
    private val laterSha = "b".repeat(40)
    private val now = 10_000L

    @Test fun intakeRequiresBulkRemediationIntentRatherThanAnIssueMention() {
        for (request in listOf(task, "Fix every open issue", "Please resolve all reported issues", "Review all issues and close them", "Can you fix the remaining issues?")) {
            assertTrue(request, GitHubIssueRemediation.requested(request))
            assertFalse(request, GitHubRequestCompletion.informational(request))
            assertTrue(request, GitHubRequestCompletion.requiresRepositoryEvidence(request))
        }
        for (request in listOf("List all open issues", "Review all issues and recommend solutions", "Explain how to close all issues", "Fix issue #1 and list all open issues", "Review this comment: 'fix all issues'", "Review this excerpt:\n> fix all issues", "Explain the example: `close all issues`")) {
            assertFalse(request, GitHubIssueRemediation.requested(request))
            assertFalse(request, GitHubRequestCompletion.requirements(request).contains(GitHubIssueRemediation.INVENTORY_REQUIREMENT))
        }
    }

    @Test fun freezeRequiresACompleteConsistentLiveIssueTraversal() {
        val first = inventory(listOf(1), page = 1, more = true, total = 2, after = "cursor-one")
        val second = inventory(listOf(2), page = 2, total = 2, requestedAfter = "cursor-one")
        assertNull(freeze(linkedMapOf("first" to first)))
        assertNull(freeze(linkedMapOf("second" to second)))
        assertNull(freeze(linkedMapOf("first" to first, "second" to changeArguments(second, "after", JsonPrimitive("different-cursor")))))
        assertNull(freeze(linkedMapOf("first" to first, "second" to changeArguments(second, "state", JsonPrimitive("all")))))
        assertNull(freeze(linkedMapOf("first" to first, "second" to changeResult(second, "total_count", JsonPrimitive(3)))))
        assertNull(freeze(linkedMapOf("first" to first, "second" to changeResult(second, "items", JsonArray(listOf(issue(1)))))))
        val cohort = requireNotNull(freeze(linkedMapOf("first" to first, "second" to second)))
        assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2)), cohort.getValue("groups").jsonArray.map { it.jsonObject["issue_number"] })
        assertEquals(JsonArray(listOf(JsonPrimitive("first"), JsonPrimitive("second"))), cohort["inventory_receipt_ids"])
        assertTrue(GitHubIssueRemediation.valid(cohort, task, binding, repository))
        assertFalse(GitHubIssueRemediation.valid(cohort, task, "another-task", repository))
        assertFalse(GitHubIssueRemediation.valid(cohort, "Fix all remaining issues", binding, repository))
    }

    @Test fun staleUnauthorizedAndPullRequestInventoriesCannotCreateACohort() {
        val source = inventory(listOf(1, 2))
        for (replacement in listOf(
            "trusted" to JsonPrimitive(false),
            "success" to JsonPrimitive(false),
            "verification_eligible" to JsonPrimitive(false),
            "binding" to JsonPrimitive("other"),
            "repository" to JsonPrimitive("owner/other"),
            "repository_role" to JsonPrimitive("READ_SOURCE"),
            "evidence_origin" to JsonPrimitive("CACHED"),
            "observed_at_ms" to JsonNull
        )) {
            assertNull(replacement.first, freeze(mapOf("list" to JsonObject(source + replacement))))
        }
        assertNull(freeze(mapOf("list" to changeResult(source, "items", JsonArray(listOf(JsonObject(issue(1) + ("pull_request" to JsonObject(emptyMap()))), issue(2)))))))
        assertNull(freeze(mapOf("list" to inventory((1..257).toList()))))
        assertNotNull(freeze(mapOf("empty" to inventory(emptyList()))))
    }

    @Test fun frozenCohortSurvivesNewIssuesRefreshAndProcessRestart() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook(
            binding,
            repository,
            "work",
            setOf("github_issue"),
            now = { now },
            load = { saved[it] },
            save = { id, row -> saved[id] = row },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } }
        )
        suspend fun observe(book: GitHubEvidenceBook, id: String, numbers: List<Int>) {
            val source = inventory(numbers)
            val call = ProviderEvent.ToolCall(id, "github_issue", source.getValue("arguments").jsonObject)
            book.observe(call, AgentToolResult(id, ToolResultContent.Json(source.getValue("result")), false))
        }
        val original = book()
        observe(original, "intake", listOf(1, 2))
        val accepted = requireNotNull(original.issueRemediation(task))
        observe(original, "refresh", listOf(2, 3))
        assertEquals(accepted.cohort, original.issueRemediation(task)?.cohort)
        val resumed = book()
        resumed.restore()
        assertEquals(accepted.cohort, resumed.issueRemediation(task)?.cohort)
        assertEquals(1, saved.keys.count { it.startsWith("issue_cohort_") })
    }

    @Test fun twoIndependentGroupsKeepCompletedWorkWhenAnotherGroupIsBlocked() {
        val receipts = observations()
        val context = context(receipts)
        val first = assess(1, context, receipts)
        val second = assess(2, context, receipts, mapping = acceptance(2, test = "tests-blocked"))
        assertEquals(JsonPrimitive("PASS"), first["acceptance_state"])
        assertEquals(JsonPrimitive("BLOCKED"), second["acceptance_state"])
        assertEquals(JsonPrimitive("PASS"), phase(second, "DIAGNOSIS")["state"])
        assertEquals(JsonPrimitive("PASS"), phase(second, "CHANGE")["state"])
        assertEquals(JsonPrimitive("BLOCKED"), phase(second, "TEST")["state"])
        val saved = context.copy(previous = listOf(first, second))
        val nextTestOnly = buildJsonObject {
            put("implementation_receipts", JsonArray(emptyList()))
            put("operation_receipts", JsonArray(emptyList()))
            put("verification_receipts", JsonArray(emptyList()))
            put("test_receipts", JsonArray(listOf(JsonPrimitive("tests"))))
        }
        val finishedSecond = GitHubIssueRemediation.assess(saved.groups[1], saved, nextTestOnly, emptySet(), false, receipts, now)
        assertEquals(JsonPrimitive("PASS"), finishedSecond["acceptance_state"])
        val progressed = LinkedHashMap(receipts).apply {
            put("new-head", receipt("github_context", arguments("branch", "branch" to JsonPrimitive("work")), buildJsonObject { put("commit_sha", laterSha) }, revision = laterSha, at = 4000, head = true))
        }
        val result = GitHubRequestCompletion.evaluate(task, emptyList(), JsonObject(emptyMap()), progressed, now, issueWork = saved)
        val rows = result.getValue("requirements").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("PASS"), rows.single { it["issue_number"] == JsonPrimitive(1) }["acceptance_state"])
        assertEquals(JsonPrimitive("BLOCKED"), rows.single { it["issue_number"] == JsonPrimitive(2) }["acceptance_state"])
        assertEquals(JsonPrimitive(false), result["completion_verified"])
        assertEquals(JsonArray(listOf(JsonPrimitive(sha))), first["issue_revisions"])
        assertTrue(first.getValue("issue_test_revision_scope").toString().contains("PUBLISHED_FIX_REVISION"))
        assertEquals(GitHubAcceptanceLedger.State.BLOCKED, GitHubRequestCompletion.engineeringAcceptance(acceptance(1), progressed, repository).state)
        val ledger = GitHubAcceptanceLedger.attach(result).getValue("acceptance_ledger").jsonObject
        val projected = ledger.getValue("requirements").jsonArray.map { it.jsonObject }.single { it["issue_number"] == JsonPrimitive(1) }
        assertEquals(first["issue_phases"], projected["issue_phases"])
        assertEquals(first["issue_revisions"], projected["issue_revisions"])
    }

    @Test fun progressCheckpointAndRestartPreservePerIssueCompletedProof() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        var observedAt = 1000L
        fun book() = GitHubEvidenceBook(
            binding,
            repository,
            "work",
            setOf("github_issue", "github_read", "github_commit", "github_ci"),
            now = { observedAt },
            load = { saved[it] },
            save = { id, row -> saved[id] = row },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } }
        )
        val original = book()
        for ((id, source) in observations()) {
            observedAt = source.getValue("observed_at_ms").toString().toLong()
            val call = ProviderEvent.ToolCall(id, source.getValue("tool").toString().trim('"'), source.getValue("arguments").jsonObject)
            original.observe(call, AgentToolResult(id, ToolResultContent.Json(source.getValue("result")), false))
        }
        observedAt = now
        val receipts = original.snapshot().first
        val ids = receipts.map { (id, row) -> row.getValue("call_id").toString().trim('"') to id }.toMap()
        val context = requireNotNull(original.issueRemediation(task))
        fun assessment(number: Int, test: String) = GitHubIssueRemediation.assess(
            context.groups[number - 1],
            context,
            JsonObject(acceptance(number, test).mapValues { (_, value) -> JsonArray(value.jsonArray.map { JsonPrimitive(ids.getValue(it.toString().trim('"'))) }) }),
            setOf(ids.getValue("issue-$number"), ids.getValue("source")),
            true,
            receipts,
            now
        )
        val first = assessment(1, "tests")
        val second = assessment(2, "tests-blocked")
        assertEquals(JsonPrimitive("PASS"), first["acceptance_state"])
        original.retainVerdict(
            buildJsonObject {
                put("task", task)
                put("issue_cohort", context.cohort)
                put("requirements", JsonArray(listOf(first, second)))
                put("completion_verified", false)
            }
        )
        original.retainProgress(task, emptyList())
        val resumed = book()
        resumed.restore()
        val retained = requireNotNull(resumed.issueRemediation(task))
        assertEquals(JsonPrimitive("PASS"), retained.previous.single { it["issue_number"] == JsonPrimitive(1) }["acceptance_state"])
        assertEquals(JsonPrimitive("BLOCKED"), retained.previous.single { it["issue_number"] == JsonPrimitive(2) }["acceptance_state"])
        assertEquals(first["diagnosis_proof"], retained.previous.single { it["issue_number"] == JsonPrimitive(1) }["diagnosis_proof"])
        assertFalse(GitHubIssueRemediation.verificationWindow(task, retained, resumed.snapshot().first, now).contains(GitHubIssueRemediation.requirement(retained.groups.first(), retained)))
    }

    @Test fun completionRequiresAllIssuePhasesAndAcceptsLinkedDiagnosticImprovements() {
        val receipts = observations()
        val context = context(receipts)
        val claims = listOf("issue-1", "issue-2", "source").map { receiptId ->
            buildJsonObject {
                put("kind", "inferred")
                put("status", "INFERRED")
                put("receipt_id", receiptId)
                put("text", "The report does not establish a reproducible cause; the committed targeted diagnostics distinguish the missing observation")
            }
        }
        val answer = buildJsonObject {
            put(
                "requirement_claims",
                buildJsonObject {
                    GitHubRequestCompletion.requirements(task, context).forEach { requirement ->
                        val group = GitHubIssueRemediation.group(requirement, context)
                        val indexes = if (group == null) listOf(0, 1, 2) else listOf(if (group["issue_number"] == JsonPrimitive(1)) 0 else 1, 2)
                        put(GitHubRequestCompletion.id(requirement), JsonArray(indexes.map(::JsonPrimitive)))
                    }
                }
            )
            put(
                "acceptance",
                buildJsonObject {
                    put(GitHubRequestCompletion.id(task), acceptance(1))
                    context.groups.forEachIndexed { index, group -> put(GitHubRequestCompletion.id(GitHubIssueRemediation.requirement(group, context)), acceptance(index + 1)) }
                }
            )
        }
        val completed = GitHubRequestCompletion.evaluate(task, claims, answer, receipts, now, issueWork = context)
        assertEquals(JsonPrimitive(true), completed["completion_verified"])
        assertEquals(4, completed.getValue("requirements").jsonArray.size)
        val missingClosure = GitHubRequestCompletion.evaluate(task, claims, answer, receipts - "readback-2", now, issueWork = context)
        assertEquals(JsonPrimitive(false), missingClosure["completion_verified"])
        val rows = missingClosure.getValue("requirements").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("PASS"), rows.single { it["issue_number"] == JsonPrimitive(1) }["acceptance_state"])
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(rows.single { it["issue_number"] == JsonPrimitive(2) }, "CLOSURE")["state"])
    }

    @Test fun noFixOrDiagnosisCanBeBorrowedFromAnUnrelatedIssueOrPreparation() {
        val receipts = observations()
        val context = context(receipts)
        val wrongReport = GitHubIssueRemediation.assess(context.groups.first(), context, acceptance(1), setOf("issue-2", "source"), true, receipts, now)
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(wrongReport, "DIAGNOSIS")["state"])
        val prepared = JsonObject(
            receipts.getValue("commit") + (
                "result" to buildJsonObject {
                    put("stage_id", "stage")
                    put("committed", false)
                    put("sha", sha)
                }
                )
        )
        val preparation = assess(1, context, receipts + ("commit" to prepared))
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(preparation, "CHANGE")["state"])
        val uncertain = JsonObject(receipts.getValue("commit") + ("execution" to buildJsonObject { put("unknown_mutations", 1) }))
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(assess(1, context, receipts + ("commit" to uncertain)), "CHANGE")["state"])
        val noClaims = GitHubIssueRemediation.assess(context.groups.first(), context, acceptance(1), setOf("issue-1", "source"), false, receipts, now)
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(noClaims, "DIAGNOSIS")["state"])
    }

    @Test fun closureNeedsTheGuardedExactIssueOperationAndLatestLiveReadback() {
        val receipts = observations()
        val context = context(receipts)
        val operation = receipts.getValue("close-1")
        val noGuard = JsonObject(operation + ("arguments" to JsonObject(operation.getValue("arguments").jsonObject - "expected_updated_at")))
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(assess(1, context, receipts + ("close-1" to noGuard)), "CLOSURE")["state"])
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(assess(1, context, receipts - "readback-1"), "CLOSURE")["state"])
        val reopened = receipt("github_issue", arguments("context", "issue_number" to JsonPrimitive(1)), buildJsonObject { put("issue", issue(1)) }, at = 5000)
        assertEquals(JsonPrimitive("BLOCKED"), phase(assess(1, context, receipts + ("reopened" to reopened)), "CLOSURE")["state"])
        val cached = JsonObject(receipts.getValue("readback-1") + ("evidence_origin" to JsonPrimitive("CACHED")))
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(assess(1, context, receipts + ("readback-1" to cached)), "CLOSURE")["state"])
    }

    @Test fun savedDiagnosisIsRecheckedAgainstReceiptContentAndCurrentAuthorization() {
        val receipts = observations()
        val context = context(receipts)
        val retained = context.copy(previous = listOf(assess(1, context, receipts)))
        fun resumed(current: Map<String, JsonObject>) = GitHubIssueRemediation.assess(retained.groups.first(), retained, null, emptySet(), false, current, now)
        assertEquals(JsonPrimitive("PASS"), resumed(receipts)["acceptance_state"])
        val revoked = JsonObject(receipts.getValue("source") + ("verification_eligible" to JsonPrimitive(false)))
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(resumed(receipts + ("source" to revoked)), "DIAGNOSIS")["state"])
        val altered = changeResult(receipts.getValue("source"), "commit_sha", JsonPrimitive(laterSha))
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(resumed(receipts + ("source" to altered)), "DIAGNOSIS")["state"])
        val reopened = receipt("github_issue", arguments("context", "issue_number" to JsonPrimitive(1)), buildJsonObject { put("issue", issue(1)) }, at = 5000)
        assertEquals(JsonPrimitive("BLOCKED"), phase(resumed(receipts + ("reopened" to reopened)), "CLOSURE")["state"])
    }

    @Test fun exactRevisionTestsRejectFailedSkippedIncompleteAndSupersededAttempts() {
        val receipts = observations()
        val context = context(receipts)
        for ((conclusion, expected) in listOf("failure" to "FAIL", "skipped" to "BLOCKED", "cancelled" to "FAIL")) {
            val changed = jobs("tests", conclusion = conclusion)
            assertEquals(conclusion, JsonPrimitive(expected), phase(assess(1, context, receipts + ("tests" to changed)), "TEST")["state"])
        }
        val wrongRevision = jobs("tests", revision = laterSha)
        assertEquals(JsonPrimitive("NOT_TESTED"), phase(assess(1, context, receipts + ("tests" to wrongRevision)), "TEST")["state"])
        val incomplete = changeResult(receipts.getValue("tests"), "has_more", JsonPrimitive(true))
        assertEquals(JsonPrimitive("BLOCKED"), phase(assess(1, context, receipts + ("tests" to incomplete)), "TEST")["state"])
        val newerAttempt = receipt(
            "github_ci",
            arguments("status"),
            buildJsonObject {
                put("id", 7)
                put("run_attempt", 2)
                put("head_sha", sha)
                put("status", "in_progress")
            }
        )
        assertEquals(JsonPrimitive("BLOCKED"), phase(assess(1, context, receipts + ("new-attempt" to newerAttempt)), "TEST")["state"])
    }

    @Test fun boundedVerificationMakesProgressWithoutDroppingUnselectedGroups() {
        val receipts = LinkedHashMap(observations()).apply { put("inventory", inventory((1..70).toList())) }
        val context = context(receipts)
        val completed = assess(1, context, receipts)
        val attempted = context.groups.drop(1).take(28).map { group ->
            GitHubIssueRemediation.assess(group, context, JsonObject(emptyMap()), emptySet(), false, receipts, now)
        }
        val retained = context.copy(previous = listOf(completed) + attempted)
        val selected = GitHubIssueRemediation.verificationWindow(task, retained, receipts, now)
        assertEquals(GitHubClaimBatches.REQUIREMENTS_PER_BATCH * GitHubClaimBatches.MAX_BATCHES, selected.size)
        assertFalse(selected.contains(GitHubIssueRemediation.requirement(retained.groups.first(), retained)))
        assertTrue(selected.contains(GitHubIssueRemediation.requirement(retained.groups[29], retained)))
        assertFalse(selected.contains(GitHubIssueRemediation.requirement(retained.groups[1], retained)))
        assertEquals(72, GitHubRequestCompletion.requirements(task, retained).size)
        val report = GitHubRequestCompletion.evaluate(task, emptyList(), JsonObject(emptyMap()), receipts, now, issueWork = retained)
        assertEquals(72, report.getValue("requirements").jsonArray.size)
        assertEquals(JsonPrimitive(false), report["completion_verified"])
    }

    @Test fun nativePublishedCommitAndRecoveredApprovalShapesSupplyTheirActualRevision() = runTest {
        val book = GitHubEvidenceBook(binding, repository, "work", setOf("github_commit", "github_write", "github_stage_patch", "github_context"), now = { now })
        val nativeResult = buildJsonObject {
            put("sha", sha)
            put("parent_sha", laterSha)
            put("expected_head_sha", sha)
            put("branch", "work")
            put("files_changed", 1)
            put("execution", buildJsonObject { put("status", "COMPLETED") })
        }
        val cases = listOf(
            Triple("github_commit", arguments("commit"), nativeResult),
            Triple("github_write", arguments("commit"), nativeResult),
            Triple("github_stage_patch", arguments("", "commit_immediately" to JsonPrimitive(true)), JsonObject(nativeResult + ("committed" to JsonPrimitive(true)))),
            Triple("github_context", arguments("recovery", "engineering_task_id" to JsonPrimitive("approved-task")), JsonObject(nativeResult + mapOf("engineering_task_id" to JsonPrimitive("approved-task"), "approval_state" to JsonPrimitive("PUBLISHED"), "remote_state_verified" to JsonPrimitive(true))))
        )
        for ((index, case) in cases.withIndex()) {
            val (tool, arguments, result) = case
            val call = ProviderEvent.ToolCall("published-$index", tool, arguments)
            book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(result), false))
        }
        val published = book.snapshot().first.values
        assertEquals(4, published.size)
        for (receipt in published) {
            assertEquals(JsonPrimitive(sha), receipt["source_head_sha"])
            assertEquals(sha, GitHubIssueRemediation.publishedRevision(receipt))
        }
        val pending = buildJsonObject {
            put("stage_id", "saved-stage")
            put("committed", false)
            put("sha", sha)
        }
        val pendingCall = ProviderEvent.ToolCall("prepared", "github_commit", arguments("commit"))
        book.observe(pendingCall, AgentToolResult(pendingCall.callId, ToolResultContent.Json(pending), false))
        val proposal = book.snapshot().first.values.last()
        assertEquals(JsonNull, proposal["source_head_sha"])
        assertNull(GitHubIssueRemediation.publishedRevision(proposal))
    }

    private fun observations(): LinkedHashMap<String, JsonObject> = linkedMapOf(
        "inventory" to inventory(listOf(1, 2)),
        "issue-1" to receipt("github_issue", arguments("context", "issue_number" to JsonPrimitive(1)), buildJsonObject { put("issue", issue(1)) }),
        "issue-2" to receipt("github_issue", arguments("context", "issue_number" to JsonPrimitive(2)), buildJsonObject { put("issue", issue(2)) }),
        "source" to receipt(
            "github_read",
            arguments("ranges"),
            buildJsonObject {
                put("commit_sha", sha)
                put("source", "retained pinned implementation")
            }
        ),
        "commit" to receipt(
            "github_commit",
            arguments("commit"),
            buildJsonObject {
                put("sha", sha)
                put("files_changed", 1)
                put("execution", buildJsonObject { put("status", "COMPLETED") })
            },
            head = true
        ),
        "tests" to jobs("tests"),
        "tests-blocked" to jobs("tests-blocked", run = 8, conclusion = "skipped"),
        "close-1" to close(1),
        "close-2" to close(2),
        "readback-1" to readback(1),
        "readback-2" to readback(2)
    )

    private fun context(receipts: Map<String, JsonObject>) = GitHubIssueRemediation.Context(requireNotNull(freeze(receipts)))
    private fun freeze(receipts: Map<String, JsonObject>) = GitHubIssueRemediation.freeze(task, binding, repository, receipts)
    private fun assess(number: Int, context: GitHubIssueRemediation.Context, receipts: Map<String, JsonObject>, mapping: JsonObject = acceptance(number)) =
        GitHubIssueRemediation.assess(context.groups.single { it["issue_number"] == JsonPrimitive(number) }, context, mapping, setOf("issue-$number", "source"), true, receipts, now)
    private fun phase(row: JsonObject, name: String) = row.getValue("issue_phases").jsonArray.map { it.jsonObject }.single { it["stage"] == JsonPrimitive(name) }
    private fun acceptance(number: Int, test: String = "tests") = buildJsonObject {
        put("implementation_receipts", JsonArray(listOf(JsonPrimitive("commit"))))
        put("test_receipts", JsonArray(listOf(JsonPrimitive(test))))
        put("operation_receipts", JsonArray(listOf(JsonPrimitive("close-$number"))))
        put("verification_receipts", JsonArray(listOf(JsonPrimitive("readback-$number"))))
    }

    private fun inventory(numbers: List<Int>, page: Int = 1, more: Boolean = false, total: Int = numbers.size, after: String? = null, requestedAfter: String? = null) = receipt(
        "github_issue",
        buildJsonObject {
            put("action", "list")
            put("state", "open")
            put("page", page)
            requestedAfter?.let { put("after", it) }
        },
        buildJsonObject {
            put("resource_type", "issue")
            put("items", JsonArray(numbers.map(::issue)))
            put("returned", numbers.size)
            put("page", page)
            put("total_count", total)
            put("has_more", more)
            put("traversal_state", if (more) "MORE" else "COMPLETE")
            if (more) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("page", page + 1)
                        after?.let { put("after", it) }
                    }
                )
            }
        }
    )

    private fun close(number: Int) = receipt(
        "github_issue",
        arguments("update", "issue_number" to JsonPrimitive(number), "state" to JsonPrimitive("closed"), "expected_updated_at" to JsonPrimitive("2026-11-21T12:00:00Z")),
        buildJsonObject {
            put("issue", issue(number, "closed"))
            put("upstream_completed", true)
        },
        at = 2000
    )
    private fun readback(number: Int) = receipt("github_issue", arguments("context", "issue_number" to JsonPrimitive(number), "verify_live" to JsonPrimitive(true)), buildJsonObject { put("issue", issue(number, "closed")) }, at = 3000)
    private fun issue(number: Int, state: String = "open") = buildJsonObject {
        put("number", number)
        put("title", "Issue $number")
        put("state", state)
        put("updated_at", "2026-11-21T12:00:00Z")
    }
    private fun arguments(action: String, vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf("action" to JsonPrimitive(action)) + extra)
    private fun receipt(tool: String, arguments: JsonObject, result: JsonObject, revision: String = sha, at: Long = 1000, head: Boolean = false) = buildJsonObject {
        put("binding", binding)
        put("repository", repository)
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("dispatched", true)
        put("evidence_origin", "LIVE")
        put("observed_at_ms", at)
        put("tool", tool)
        put("source_head_sha", revision)
        put("head_observation", head)
        put("arguments", arguments)
        put("result", result)
    }
    private fun jobs(id: String, run: Int = 7, conclusion: String = "success", revision: String = sha) = receipt(
        "github_ci",
        arguments("jobs"),
        buildJsonObject {
            put("run_id", run)
            put("run_attempt", 1)
            put("head_sha", revision)
            put("page", 1)
            put("has_more", false)
            put("total_count", 1)
            put(
                "jobs",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", if (id == "tests") 9 else 10)
                            put("name", "Unit tests")
                            put("status", "completed")
                            put("conclusion", conclusion)
                        }
                    )
                )
            )
        },
        revision
    )
    private fun changeArguments(receipt: JsonObject, key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(receipt + ("arguments" to JsonObject(receipt.getValue("arguments").jsonObject + (key to value))))
    private fun changeResult(receipt: JsonObject, key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(receipt + ("result" to JsonObject(receipt.getValue("result").jsonObject + (key to value))))
}
