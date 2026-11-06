package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMergedBranchAuditTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun tool(name: String, vararg actions: String) = AgentToolDefinition(name, name, json("""{"properties":{"action":{"enum":${JsonArray(actions.map(::JsonPrimitive))}}}}"""))
    private fun receipt(tool: String, args: String, result: String, id: String = "read") = json("""{"receipt_id":"$id","binding":"task","repository":"owner/repo","trusted":true,"success":true,"dispatched":true,"tool":"$tool","arguments":$args,"result":$result}""")
    private val sha = "a".repeat(40)
    private val source get() = receipt("github_context", """{"action":"status"}""", """{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}""", "source")
    private val branch get() = "codex-self-test/" + GitHubEvidenceBook.digest("tasksource").take(20)
    private val creation get() = receipt("github_commit", """{"action":"branch","branch":"$branch","from_branch":"main","expected_head_sha":"$sha"}""", """{"branch":"$branch","created":true,"receipt_verified":true,"head_sha":"$sha"}""", "creation")
    private val cleanup get() = receipt("github_commit", """{"action":"delete_branch","branch":"$branch","expected_head_sha":"$sha"}""", """{"deleted":true,"absence_verified":true,"branch":"$branch","observed_head_sha":"$sha"}""", "cleanup")
    private val fixtureTools = listOf(tool("github_context", "status"), tool("github_commit", "branch", "delete_branch"))
    private fun fixtures() = linkedMapOf("source" to source, "creation" to creation, "cleanup" to cleanup)
    private fun cleaned(records: Map<String, JsonObject>) = (GitHubSelfTestFixtures.checkpoint(records).single() as JsonObject)["cleanup_status"] == JsonPrimitive("CLEANED")

    @Test fun acceptedDeletionWithoutRemoteAbsenceCannotCompleteTheFixture() {
        val accepted = json(cleanup.toString().replace("\"absence_verified\":true", "\"absence_verified\":false"))
        assertFalse(cleaned(fixtures().apply { put("cleanup", accepted) }))
        val legacy = json(cleanup.toString().replace("\"absence_verified\":true,", ""))
        assertFalse(cleaned(fixtures().apply { put("cleanup", legacy) }))
    }

    @Test fun ownedUnchangedFixtureCanCompleteItsExactApiScope() {
        assertTrue(cleaned(fixtures()))
        assertTrue(GitHubTestChecklist.apiComplete("Test all GitHub tools", fixtures(), fixtureTools))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools and Android behavior", fixtures(), fixtureTools))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools", fixtures(), fixtureTools + tool("github_pr", "merge")))
    }

    @Test fun absentOrFailedCleanupNeverCompletesFixture() {
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools", fixtures() - "cleanup", fixtureTools))
        for (field in listOf("success", "verification_eligible")) {
            val records = fixtures().apply { put("cleanup", JsonObject(cleanup + (field to JsonPrimitive(false)))) }
            assertFalse(cleaned(records))
            assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools", records, fixtureTools))
        }
    }

    @Test fun cleanupMustFollowCreationAndMatchTaskRepositoryAndHead() {
        assertFalse(cleaned(linkedMapOf("source" to source, "cleanup" to cleanup, "creation" to creation)))
        for (field in listOf("binding", "repository")) {
            assertFalse(cleaned(fixtures().apply { put("cleanup", JsonObject(cleanup + (field to JsonPrimitive("other")))) }))
        }
        assertFalse(cleaned(fixtures().apply { put("cleanup", json(cleanup.toString().replace(sha, "b".repeat(40)))) }))
        assertFalse(cleaned(fixtures() - "source"))
    }

    @Test fun revokedOrFailedCreationIsNotOwnershipProof() {
        for (field in listOf("success", "verification_eligible")) {
            assertFalse(cleaned(fixtures().apply { put("creation", JsonObject(creation + (field to JsonPrimitive(false)))) }))
        }
        assertFalse(cleaned(fixtures().apply { put("creation", json(creation.toString().replace("\"created\":true", "\"created\":false"))) }))
    }

    @Test fun readOnlyTaskDoesNotRequireOrScheduleMutationFixtures() {
        val records = mapOf("source" to source)
        assertTrue(GitHubTestChecklist.apiComplete("Test all GitHub tools read-only", records, fixtureTools))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools", records, fixtureTools))
        assertEquals(null, GitHubSelfTestFixtures.next("Test all tools read only", fixtureTools, records))
        val rows = GitHubTestChecklist.rows(records, fixtureTools, "Test all tools without changes")
        assertEquals(2, rows.count { (it as JsonObject)["status"] == JsonPrimitive("NOT_REQUESTED") })
    }

    @Test fun accountDiscoveryIsReportedOutsidePinnedScopeWithoutInventingPass() {
        val tools = listOf(tool("github_context", "status", "repositories"))
        val records = mapOf("source" to source)
        val excluded = GitHubTestChecklist.rows(records, tools).last() as JsonObject
        assertEquals(JsonPrimitive("OUT_OF_SCOPE"), excluded["status"])
        assertFalse("blocker" in excluded)
        assertTrue(GitHubTestChecklist.apiComplete("Test all GitHub tools", records, tools))
    }

    private fun page(number: Int, last: Boolean, ids: List<Int> = listOf(number), total: Int = 3, size: Int = 1) = receipt(
        "github_ci",
        """{"action":"workflows","page":$number,"per_page":$size}""",
        """{"workflows":${JsonArray(ids.map { json("""{"id":$it}""") })},"has_more":${!last},"total_count":$total}""",
        "page$number"
    )
    private fun coverage(vararg receipts: JsonObject): String = ((GitHubTestChecklist.inventoryCoverage(receipts.mapIndexed { i, r -> "$i" to r }.toMap()).single() as JsonObject)["coverage"] as JsonPrimitive).content

    @Test fun lastPageDoesNotProveMissingEarlierInventoryPages() {
        assertEquals("incomplete or pending pagination", coverage(page(3, true)))
        assertEquals("incomplete or pending pagination", coverage(page(1, false), page(3, true)))
        assertEquals("retrieval complete at observation", coverage(page(1, false), page(2, false), page(3, true)))
    }

    @Test fun inconsistentTotalsDuplicateIdsAndShortResultsRemainIncomplete() {
        assertEquals("incomplete or pending pagination", coverage(page(1, true)))
        assertEquals("incomplete or pending pagination", coverage(page(1, false, total = 2), page(2, true, total = 3)))
        assertEquals("incomplete or pending pagination", coverage(page(1, false, total = 2), page(2, true, ids = listOf(1), total = 2)))
    }

    @Test fun latestPageReplacesOlderRowsWithoutMixingPageSizes() {
        assertEquals("retrieval complete at observation", coverage(page(1, true, ids = listOf(9), total = 1), page(1, true, total = 1)))
        assertEquals(2, GitHubTestChecklist.inventoryCoverage(mapOf("a" to page(1, true, total = 1), "b" to page(1, true, total = 1, size = 10))).size)
    }

    @Test fun observedDefaultPageSizeMatchesExplicitContinuationWithoutHidingFailures() {
        val first = receipt("github_ci", """{"action":"workflows","page":1}""", """{"workflows":[{"id":1}],"per_page":1,"has_more":true,"total_count":2}""")
        val last = page(2, true, total = 2)
        assertEquals("retrieval complete at observation", coverage(first, last))
        val failed = receipt("github_ci", """{"action":"workflows","page":1}""", "{}").let { JsonObject(it + ("success" to JsonPrimitive(false))) }
        assertEquals("incomplete or pending pagination", coverage(first, last, failed))
        assertEquals("retrieval complete at observation", coverage(failed, first, last))
        assertEquals(2, GitHubTestChecklist.inventoryCoverage(mapOf("a" to first, "b" to page(2, true, total = 2, size = 10))).size)
    }

    @Test fun readRecoveryRetainsFailureButAllowsCompletedInventory() {
        val read = page(1, true, total = 1)
        val failed = JsonObject(read + ("success" to JsonPrimitive(false)))
        assertEquals("retrieval complete at observation", coverage(failed, read))
        assertEquals("incomplete or pending pagination", coverage(read, failed))
    }

    @Test fun partialIndexIsEndpointEvidenceWithoutClaimingFullIndexCoverage() {
        val records = mapOf("index" to receipt("github_context", """{"action":"index"}""", """{"complete":false}"""))
        val row = GitHubTestChecklist.rows(records, listOf(tool("github_context", "index"))).single() as JsonObject
        assertEquals(JsonPrimitive("PASSED"), row["status"])
        assertEquals(JsonPrimitive("PARTIAL"), row["inventory_status"])
    }

    @Test fun exhaustiveScopeIncludesCommitsAndWorkflowHistoryInPlannerAndCompletion() {
        for (task in listOf("test all tools and all commits", "test all tools and complete workflow history", "test all tools and all runs")) {
            assertTrue(GitHubChecklistScope.exhaustive(task))
        }
        assertFalse(GitHubChecklistScope.exhaustive("test all GitHub tools"))
    }

    @Test fun laterFailureCannotBeReportedAsRecoveredFromEarlierSuccess() {
        val failed = JsonObject(source + ("success" to JsonPrimitive(false)))
        val tools = listOf(tool("github_context", "status"))
        val records = linkedMapOf("earlier" to source, "later" to failed)
        assertEquals(JsonPrimitive("FAILED"), (GitHubTestChecklist.rows(records, tools).single() as JsonObject)["status"])
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools", records, tools))
        assertEquals(JsonPrimitive("RECOVERED"), (GitHubTestChecklist.rows(linkedMapOf("earlier" to failed, "later" to source), tools).single() as JsonObject)["status"])
    }

    @Test fun negatedWriteRequestsRemainReadOnlyAndCannotScheduleFixtures() {
        for (task in listOf(
            "Test all GitHub tools read-only, do not include writes",
            "Test all GitHub tools read-only; never perform mutations",
            "Test all GitHub tools read-only, don't allow the writes"
        )) {
            assertTrue(task, GitHubChecklistScope.readOnly(task))
            assertEquals(null, GitHubSelfTestFixtures.next(task, fixtureTools, mapOf("source" to source)))
            assertTrue(GitHubTestChecklist.apiComplete(task, mapOf("source" to source), fixtureTools))
        }
    }

    @Test fun explicitlyRequestedWritesCannotBeExcludedByNegatedReadOnlyWording() {
        assertFalse(GitHubChecklistScope.readOnly("Test all tools, not read-only"))
        assertFalse(GitHubChecklistScope.readOnly("Test read-only tools and include writes"))
    }

    @Test fun defaultReportsAreBoundedWhileDetailedCoverageIsRetained() {
        val tools = listOf(tool("github_ci", *(1..40).map { "operation_$it" }.toTypedArray()))
        val compact = GitHubTestChecklist.render(emptyMap(), tools, "Test all GitHub tools")
        val detailed = GitHubTestChecklist.render(emptyMap(), tools, "Test all GitHub tools with a detailed report")
        assertTrue(compact.length < 4000)
        assertTrue(compact.contains("All 40 capability rows"))
        assertEquals(40, (GitHubTestChecklist.checkpoint("Test all GitHub tools", emptyMap(), tools)["capabilities"] as JsonArray).size)
        assertTrue(detailed.contains("operation_40"))
        assertTrue(detailed.length > compact.length)
    }

    @Test fun malformedInventoryCannotEstablishExhaustiveCompletion() {
        for (result in listOf("{}", "{\"items\":[\"unknown\"],\"has_more\":false}", "{\"items\":[{}],\"has_more\":false}")) {
            val records = mapOf("read" to receipt("github_context", """{"action":"branches"}""", result))
            assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools and all branches", records, listOf(tool("github_context", "branches"))))
        }
    }

    @Test fun exhaustiveCommitCoverageCannotIgnoreMissingPageOne() {
        val records = mapOf("read" to receipt("github_context", """{"action":"commits","page":2}""", """{"items":[{"sha":"$sha"}],"has_more":false}"""))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools and all commits", records, listOf(tool("github_context", "commits"))))
    }

    @Test fun successfulExactReopenResolvesOldCursorFailureWithoutHidingIt() {
        val args = """{"action":"workflows","page":1,"per_page":1}"""
        val source = JsonObject(page(1, true, total = 1) + ("view" to json("""{"recovery_call":{"tool":"github_ci","arguments":{"view_cursor":"saved:0"}}}""")))
        val lost = json("""{"tool":"github_ci","trusted":true,"success":false,"arguments":{"view_cursor":"saved:1"},"error_code":"SAVED_VIEW_UNAVAILABLE","result":{"recovery_call":{"tool":"github_ci","arguments":$args}}}""")
        assertEquals("incomplete or pending pagination", coverage(source, lost))
        assertEquals("retrieval complete at observation", coverage(source, lost, page(1, true, total = 1)))
    }
}
