package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubExecutionContinuation
import dev.chungjungsoo.gptmobile.data.github.GitHubSearchFrontier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRemoteContinuationTest {
    @Test fun explicitRepositoryAuditsKeepTheirDetachedRestrictions() {
        val fixture = SearchScopeFixture("TODO", "literal")
        for (audit in listOf(
            "Audit all bugs in the repository",
            "Review the entire codebase",
            "Perform a comprehensive repository audit"
        )) {
            for (restriction in listOf("only within the login component", "take only a sample", "only within the TODO component")) {
                val task = "$audit; $restriction"
                assertNull(task, fixture.next(task))
            }
        }
    }

    @Test fun restrictedRepositoryAuditsPreserveIndependentSearchScope() {
        val requested = SearchScopeFixture("TODO", "literal")
        val unrelated = SearchScopeFixture("FIXME", "literal")
        val instructions = SearchScopeFixture("CONTRIBUTING", "paths")
        for (audit in listOf(
            "Audit all bugs in the repository",
            "Review the entire codebase",
            "Perform a comprehensive repository audit"
        )) {
            for (restriction in listOf("only within the login component", "take only a sample")) {
                val task = "$audit; $restriction; find every TODO"
                assertEquals(task, requested.nextArguments, requested.next(task)?.arguments)
                assertNull(task, unrelated.next(task))
                assertNull(task, instructions.next(task))
                val independentAudit = "$audit; $restriction; $audit"
                assertEquals(independentAudit, requested.nextArguments, requested.next(independentAudit)?.arguments)
            }
            for (condition in listOf("take a sample of README matches", "in the report, include proposed solutions", "only publish after the tests pass")) {
                val task = "$audit; $condition"
                assertEquals(task, requested.nextArguments, requested.next(task)?.arguments)
            }
        }
    }

    @Test fun detachedComponentAndUnqualifiedSampleRestrictionsStayWithTheirAudit() {
        val fixture = SearchScopeFixture("TODO", "literal")
        for (task in listOf(
            "Examine all issues and bugs, but only in the login component",
            "Examine all issues and bugs; only within the login component",
            "Examine all issues and bugs;\nonly within the login component",
            "Examine all issues and bugs.\n\nTake only a sample",
            "Examine all issues and bugs, affecting authentication",
            "Examine all issues and bugs; focus only on authentication",
            "Examine all issues and bugs; do not inspect outside authentication",
            "Examine all issues and bugs; take only a sample",
            "Examine all issues and bugs. Take only a sample",
            "Examine all issues and bugs; take a sample of those issues"
        )) {
            assertNull(task, fixture.next(task))
        }
    }

    @Test fun detachedRestrictionsDoNotSuppressIndependentAuditsNamedSamplesOrPublicationConditions() {
        val fixture = SearchScopeFixture("TODO", "literal")
        for (task in listOf(
            "Examine all issues and bugs, but only in the login component; examine all issues and bugs",
            "Examine all issues and bugs; take only a sample; examine all issues and bugs",
            "Examine all issues and bugs; take a sample of README matches",
            "Examine all issues and bugs; in the report, include proposed solutions",
            "Examine all issues and bugs; focus on explaining proposed solutions",
            "Examine all issues and bugs; publish only after the tests pass",
            "Examine all issues and bugs; only publish after the tests pass",
            "Examine all issues and bugs, offer solutions based on the latest PR branch"
        )) {
            assertEquals(task, fixture.nextArguments, fixture.next(task)?.arguments)
        }
    }

    @Test fun standaloneQuantifiedAuditUsesItsIssuedDerivedSearchFrontier() {
        val fixture = SearchScopeFixture("TODO", "literal")
        for (task in listOf(
            "Examine all issues and bugs",
            "Examine all issues and bugs, offer solutions based on the latest pr branch. I need this audit benchmark to be able to tell a difference between models on which one is superior using GitHub integration and coding. Offer design solution. And fixes and upgrades",
            "Please audit every remaining bug",
            "Check all current issues",
            "GitHub workspace: owner/repo\nBranch: feature\n\nExamine all issues and bugs",
            "Examine all issues and bugs; take a sample of README matches"
        )) {
            assertEquals(task, fixture.nextArguments, fixture.next(task)?.arguments)
        }
    }

    @Test fun standaloneAuditKeepsSamplesComponentQualifiersAndIncidentalInstructionsBounded() {
        for ((task, query, mode) in listOf(
            Triple("Examine all issues and bugs; take a sample of TODO matches", "TODO", "literal"),
            Triple("Examine a sample of all issues and bugs", "TODO", "literal"),
            Triple("Examine all issues and bugs in the login component", "TODO", "literal"),
            Triple("Examine all issues and bugs affecting authentication", "TODO", "literal"),
            Triple("Audit all current bugs in AuthController.kt", "TODO", "literal"),
            Triple("Find every FIXME", "TODO", "literal"),
            Triple("Examine all issues and bugs, then read CONTRIBUTING", "CONTRIBUTING", "paths"),
            Triple("Examine all issues and bugs", "AGENTS.md", "paths")
        )) {
            assertNull(task, SearchScopeFixture(query, mode).next(task))
        }
    }

    @Test fun explicitlyExhaustiveInstructionSearchUsesItsIssuedFrontier() {
        val fixture = SearchScopeFixture("CONTRIBUTING", "paths")
        for (task in listOf(
            "Perform an exhaustive search for CONTRIBUTING",
            "Perform a comprehensive search for CONTRIBUTING",
            "Comprehensively search for CONTRIBUTING",
            "Find every CONTRIBUTING.md in this repository",
            "Read all repository instructions"
        )) {
            assertEquals(task, fixture.nextArguments, fixture.next(task)?.arguments)
        }
    }

    @Test fun repositoryWideAuditPagesDerivedQueriesWithoutChangingTheirIncompleteEvidence() {
        val fixture = SearchScopeFixture("TODO", "literal")
        val originalReceipt = fixture.exchange.results.single().executionReceipt
        for (task in listOf(
            "Audit all bugs in the repository",
            "Examine all issues and bugs across the codebase",
            "Review the entire codebase",
            "Perform a comprehensive repository audit",
            "Audit all bugs in the repository; take a sample of README matches"
        )) {
            val continuation = requireNotNull(fixture.next(task)) { task }
            assertEquals(fixture.tool.name, continuation.name)
            assertEquals(task, fixture.nextArguments, continuation.arguments)
        }
        assertEquals(originalReceipt, fixture.exchange.results.single().executionReceipt)
        assertEquals(JsonPrimitive(false), originalReceipt?.get("search_frontier")?.jsonObject?.get("complete"))
    }

    @Test fun incidentalInstructionsSamplesAndUnrelatedNarrowQueriesRemainBounded() {
        val named = SearchScopeFixture("TODO", "literal")
        assertEquals(named.nextArguments, named.next("Find every TODO in the repository")?.arguments)
        for ((task, query, mode) in listOf(
            Triple("Inspect the repository instructions and relevant tests. Implement its complete fix.\nAcceptance criteria\n- Fix all issues", "CONTRIBUTING", "paths"),
            Triple("Audit all bugs in the repository, then read CONTRIBUTING", "CONTRIBUTING", "paths"),
            Triple("Perform a non-exhaustive search for CONTRIBUTING", "CONTRIBUTING", "paths"),
            Triple("Audit a sample of all bugs in the repository", "TODO", "literal"),
            Triple("Audit all bugs in the repository; take a sample of TODO matches", "TODO", "literal"),
            Triple("Find every FIXME in the repository", "TODO", "literal"),
            Triple("Find every FIXME across the entire repository", "TODO", "literal")
        )) {
            assertNull(task, SearchScopeFixture(query, mode).next(task))
        }
    }

    @Test fun negatedRepositoryAuditsDoNotAuthorizeDerivedQueryPaging() {
        val fixture = SearchScopeFixture("TODO", "literal")
        for (task in listOf(
            "Do not audit all bugs in the repository; inspect one config file",
            "Do not review the entire codebase; inspect one config file",
            "Don't audit all bugs in the repository; inspect one config file"
        )) {
            assertNull(task, fixture.next(task))
        }
    }

    @Test fun negatedRepositoryAuditPreservesAnIndependentExplicitQueryOnly() {
        val requested = SearchScopeFixture("TODO", "literal")
        val unrelated = SearchScopeFixture("FIXME", "literal")
        val instructions = SearchScopeFixture("CONTRIBUTING", "paths")
        for (task in listOf(
            "Do not audit all bugs in the repository; find every TODO",
            "Find every TODO; do not audit all bugs in the repository"
        )) {
            assertEquals(task, requested.nextArguments, requested.next(task)?.arguments)
            assertNull(task, unrelated.next(task))
            assertNull(task, instructions.next(task))
        }
    }

    @Test fun taskScopedPagingKeepsExactAuthorityDuplicateAndAttemptFences() {
        val fixture = SearchScopeFixture("TODO", "literal")
        val task = "Audit all bugs in the repository"
        val state = GitHubReplayRecovery.State(listOf(fixture.exchange.calls.single()))
        val continuation = requireNotNull(fixture.next(task, state))
        state.record(listOf(continuation))
        assertNull(fixture.next(task, state))
        val exhausted = GitHubReplayRecovery.State((1..32).map { ProviderEvent.ToolCall("remote-result-consume-$it", fixture.tool.name, buildJsonObject { put("index_cursor", it) }) })
        assertNull(fixture.next(task, exhausted))
        val original = fixture.exchange.calls.single()
        for ((key, value) in listOf(
            "query" to JsonPrimitive("FIXME"),
            "repo" to JsonPrimitive("other"),
            "commit_sha" to JsonPrimitive("c".repeat(40)),
            "index_cursor" to JsonPrimitive(1)
        )) {
            val changed = fixture.exchange.copy(calls = listOf(original.copy(arguments = JsonObject(original.arguments + (key to value)))))
            assertNull(key, fixture.next(task, exchange = changed))
        }
        val result = fixture.exchange.results.single()
        val unbound = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) - "resource_context"))
        assertNull(fixture.next(task, exchange = fixture.exchange.copy(results = listOf(unbound))))
    }

    private class SearchScopeFixture(query: String, mode: String) {
        val tool = GitHubOperationTool.definition("github_search", "github_search__native")
        private val arguments = buildJsonObject {
            put("query", query)
            put("mode", mode)
            put("owner", "owner")
            put("repo", "repo")
        }
        private val data = buildJsonObject {
            put("search_id", "b".repeat(64))
            put("commit_sha", "a".repeat(40))
            put("interval_start", 0)
            put("interval_end", 4)
            put("total_candidate_files", 10)
            put("returned", 0)
            put("complete", false)
            put("has_more", true)
        }
        val nextArguments = JsonObject(arguments + mapOf("index_cursor" to JsonPrimitive(4), "search_id" to data.getValue("search_id"), "commit_sha" to data.getValue("commit_sha")))
        private val recipe = buildJsonObject {
            put("tool", tool.name)
            put("arguments", nextArguments)
        }
        private val frontier = requireNotNull(GitHubSearchFrontier.capture(tool.name, arguments, data, listOf(recipe)))
        private val source = buildJsonObject {
            put("schema", "github.result.v1")
            put("operation", "github_search")
            put("data", data)
            put("continuation_call", recipe)
        }
        val exchange = AgentToolExchange(
            listOf(ProviderEvent.ToolCall("scope-search", tool.name, arguments)),
            listOf(
                AgentToolResult(
                    "scope-search",
                    requireNotNull(GitHubReplayRecovery.compact(ToolResultContent.Json(source), 4096, consumed = true)),
                    false,
                    executionReceipt = buildJsonObject {
                        put("schema", "github.dispatch.v1")
                        put("resolved_arguments", JsonObject(arguments + ("action" to JsonPrimitive("search_repository"))))
                        put(
                            "resource_context",
                            buildJsonObject {
                                put("owner", "owner")
                                put("repo", "repo")
                            }
                        )
                        put("search_frontier", frontier)
                    }
                )
            )
        )

        fun next(task: String, state: GitHubReplayRecovery.State? = null, exchange: AgentToolExchange = this.exchange) = GitHubReplayRecovery.nextRemoteRead(
            listOf(exchange),
            listOf(tool),
            state,
            allowSearchContinuation = GitHubExecutionContinuation.requiresExhaustiveSearch(task),
            searchTask = task
        )
    }

    @Test fun exhaustiveSearchUsesOnlyAnIssuedNativeFrontierAfterCompactionAndNeverRepeatsIt() {
        val tool = GitHubOperationTool.definition("github_search", "github_search__native")
        val arguments = buildJsonObject {
            put("query", "needle")
            put("owner", "owner")
            put("repo", "repo")
        }
        val data = buildJsonObject {
            put("search_id", "b".repeat(64))
            put("commit_sha", "a".repeat(40))
            put("interval_start", 0)
            put("interval_end", 4)
            put("total_candidate_files", 10)
            put("returned", 0)
            put("complete", false)
            put("has_more", true)
        }
        val next = JsonObject(arguments + mapOf("index_cursor" to JsonPrimitive(4), "search_id" to data.getValue("search_id"), "commit_sha" to data.getValue("commit_sha")))
        val recipe = buildJsonObject {
            put("tool", tool.name)
            put("arguments", next)
        }
        val frontier = requireNotNull(dev.chungjungsoo.gptmobile.data.github.GitHubSearchFrontier.capture(tool.name, arguments, data, listOf(recipe)))
        val source = buildJsonObject {
            put("schema", "github.result.v1")
            put("operation", "github_search")
            put("data", data)
            put("continuation_call", recipe)
        }
        val call = ProviderEvent.ToolCall("search-first", tool.name, arguments)
        val result = AgentToolResult(
            call.callId,
            requireNotNull(GitHubReplayRecovery.compact(ToolResultContent.Json(source), 4096, consumed = true)),
            false,
            executionReceipt = buildJsonObject {
                put("schema", "github.dispatch.v1")
                put("resolved_arguments", JsonObject(arguments + ("action" to JsonPrimitive("search_repository"))))
                put(
                    "resource_context",
                    buildJsonObject {
                        put("owner", "owner")
                        put("repo", "repo")
                    }
                )
                put("search_frontier", frontier)
            }
        )
        val exchanges = listOf(AgentToolExchange(listOf(call), listOf(result)))
        assertNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool)))
        val state = GitHubReplayRecovery.State(listOf(call))
        val continuation = requireNotNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool), state, allowSearchContinuation = true))
        assertEquals(next, continuation.arguments)
        state.record(listOf(continuation))
        assertNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool), state, allowSearchContinuation = true))
        val exhausted = GitHubReplayRecovery.State((1..32).map { ProviderEvent.ToolCall("remote-result-consume-$it", tool.name, buildJsonObject { put("index_cursor", it) }) })
        assertNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool), exhausted, allowSearchContinuation = true))
        val unbound = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) - "resource_context"))
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(AgentToolExchange(listOf(call), listOf(unbound))), listOf(tool), allowSearchContinuation = true))
    }

    @Test fun aSharedPayloadPreservesProvenCurrentPhysicalReads() {
        val shared = AgentToolResult(
            "same",
            ToolResultContent.Text("saved"),
            false,
            sharedResult = true,
            executionReceipt = buildJsonObject {
                put("read_requests", 3)
                put("read_response_observations", 3)
                put("read_source", "MIXED")
                put(
                    "tool_trace",
                    buildJsonObject {
                        put("call_id", "same")
                        put("dispatched", true)
                        put("remote_read_requests", 3)
                    }
                )
            }
        )
        assertEquals(shared, currentGitHubInvocationReceipt(shared))
    }

    @Test fun aNewWrapperTracePreservesCurrentMixedReadAccounting() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "fixture", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Json(buildJsonObject { put("workflows", JsonArray(emptyList())) }),
                false,
                dispatched = true,
                sharedResult = true,
                executionReceipt = buildJsonObject {
                    put("read_requests", 1)
                    put("read_response_observations", 1)
                    put("cached_read_observations", 1)
                    put("read_source", "MIXED")
                }
            )
        }
        val result = GitHubOperationTool("github_ci", backend, "mixed").execute("current", JsonObject(emptyMap()))
        val normalized = currentGitHubInvocationReceipt(result)
        assertTrue(normalized.dispatched)
        assertEquals(JsonPrimitive(1), normalized.executionReceipt!!["read_requests"])
        assertEquals(JsonPrimitive(1), normalized.executionReceipt!!["tool_trace"]!!.jsonObject["remote_read_requests"])
    }

    @Test fun fiftyFiveWorkflowsUseTwoIssuedPagesWithoutRepeatingTheFirst() = runBlocking {
        val pages = mutableListOf<Int>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "fixture", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                val page = arguments["page"]?.jsonPrimitive?.int ?: 1
                pages += page
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("has_more", page == 1)
                            put("total_count", 55)
                            put("workflows", JsonArray((if (page == 1) 1..30 else 31..55).map { buildJsonObject { put("id", it) } }))
                            if (page == 1) {
                                put(
                                    "continuation",
                                    buildJsonObject {
                                        put("action", "list_workflows")
                                        put("page", 2)
                                        put("per_page", 30)
                                    }
                                )
                            }
                        }
                    ),
                    false
                )
            }
        }
        val tool = GitHubOperationTool("github_ci", backend, "fifty-five", "github_ci__selected")
        val firstCall = ProviderEvent.ToolCall("first", tool.definition.name, JsonObject(emptyMap()))
        val first = tool.execute(firstCall.callId, firstCall.arguments)
        val exchanges = mutableListOf(AgentToolExchange(listOf(firstCall), listOf(first)))
        val state = GitHubReplayRecovery.State(listOf(firstCall))
        val next = requireNotNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool.definition), state))
        assertEquals(JsonPrimitive("workflows"), next.arguments["action"])
        assertEquals(JsonPrimitive(2), next.arguments["page"])
        val last = tool.execute(next.callId, next.arguments)
        state.record(listOf(next))
        exchanges += AgentToolExchange(listOf(next), listOf(last))
        assertEquals(listOf(1, 2), pages)
        val progress = requireNotNull(last.executionReceipt)["pagination_progress"]!!.jsonObject
        assertEquals(JsonPrimitive(55), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool.definition), state))
        assertEquals(JsonPrimitive("workflows"), first.executionReceipt!!["tool_trace"]!!.jsonObject["action"])
    }

    @Test fun reusedReceiptsHaveAFreshCallIdentityAndRetainOriginalObservationProvenance() {
        val original = AgentToolResult(
            "old",
            ToolResultContent.Text("saved"),
            false,
            executionReceipt = buildJsonObject {
                put("read_requests", 1)
                put(
                    "tool_trace",
                    buildJsonObject {
                        put("call_id", "old")
                        put("tool", "github_ci")
                        put("dispatched", true)
                    }
                )
            }
        )
        val reused = AgentContractValidation.receipt("new", original.copy(callId = "new", sharedResult = true))
        val receipt = requireNotNull(reused.executionReceipt)
        assertFalse(reused.dispatched)
        assertEquals(JsonPrimitive("new"), receipt["tool_trace"]!!.jsonObject["call_id"])
        assertEquals(JsonPrimitive("old"), receipt["source_observation_call_id"])
        assertEquals(JsonPrimitive(0), receipt["read_requests"])
        assertEquals(JsonPrimitive("old"), receipt["source_observation_trace"]!!.jsonObject["call_id"])
        assertEquals(JsonPrimitive(1), receipt["source_observation_read_requests"])
        assertEquals(reused, currentGitHubInvocationReceipt(reused))
    }

    @Test fun remoteContinuationsRejectChangedScopesMutationActionsAndUnissuedPages() {
        val original = workflowArguments()
        val invalid = listOf(
            workflowArguments(page = 3),
            JsonObject(workflowArguments(page = 2) + ("owner" to JsonPrimitive("different"))),
            workflowArguments(repo = "different", page = 2),
            JsonObject(workflowArguments(page = 2) + ("action" to JsonPrimitive("dispatch"))),
            JsonObject(workflowArguments(page = 2) + ("per_page" to JsonPrimitive(100))),
            JsonObject(workflowArguments(page = 2) + ("view_cursor" to JsonPrimitive("invented:1:1024")))
        )
        for (arguments in invalid) {
            assertNull(arguments.toString(), GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, arguments)), listOf(ciDefinition)))
        }
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, workflowArguments(page = 2), recipeTool = "github_ci__other")), listOf(ciDefinition)))
        val failed = issuedPage(original, workflowArguments(page = 2)).let { it.copy(results = it.results.map { result -> result.copy(isError = true) }) }
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(failed), listOf(ciDefinition)))
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, workflowArguments(page = 2))), listOf(ciDefinition.copy(modelVisible = false))))
    }

    @Test fun repositoryDefaultsMustComeFromTheOwningWorkflowReceipt() {
        val original = buildJsonObject { put("action", "workflows") }
        val next = JsonObject(workflowArguments(page = 2) + ("ref" to JsonPrimitive("main")))
        val receipt = buildJsonObject {
            put("schema", "github.dispatch.v1")
            put(
                "resolved_arguments",
                buildJsonObject {
                    put("action", "list_workflows")
                    put("owner", "Owner")
                    put("repo", "Repo")
                }
            )
            put(
                "resource_context",
                buildJsonObject {
                    put("owner", "owner")
                    put("repo", "repo")
                    put("selected_ref", "main")
                }
            )
        }
        fun exchange(arguments: JsonObject) = issuedPage(original, arguments).let { batch ->
            batch.copy(results = batch.results.map { it.copy(executionReceipt = receipt) })
        }
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, next)), listOf(ciDefinition)))
        assertEquals(next, GitHubReplayRecovery.nextRemoteRead(listOf(exchange(next)), listOf(ciDefinition))?.arguments)
        for (key in listOf("owner", "repo", "ref")) {
            val changed = JsonObject(next + (key to JsonPrimitive("different")))
            assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(exchange(changed)), listOf(ciDefinition)))
        }
    }

    @Test fun consumedDefaultPageSizeRemainsConsumedAfterReplayEviction() {
        val consumed = ProviderEvent.ToolCall("already-read", ciDefinition.name, JsonObject(workflowArguments(page = 2) - "per_page"))
        val state = GitHubReplayRecovery.State(listOf(consumed))
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(workflowArguments(), workflowArguments(page = 2))), listOf(ciDefinition), state))
    }

    @Test fun differentScopesHaveDistinctAutomaticIdsAndShareTheDurableThirtyTwoReadBudget() {
        val state = GitHubReplayRecovery.State()
        val ids = mutableSetOf<String>()
        repeat(32) { index ->
            // Only the latest issuer remains in replay; earlier calls remain in State.
            val exchange = issuedPage(workflowArguments(repo = "repo-$index"), workflowArguments(repo = "repo-$index", page = 2))
            val call = requireNotNull(GitHubReplayRecovery.nextRemoteRead(listOf(exchange), listOf(ciDefinition), state))
            ids += call.callId
            state.record(listOf(call))
        }
        assertEquals(32, ids.size)
        assertTrue(state.exhausted())
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(workflowArguments(repo = "next"), workflowArguments(repo = "next", page = 2))), listOf(ciDefinition), state))
    }

    private val ciDefinition = GitHubOperationTool.definition("github_ci", "github_ci__selected")

    private fun workflowArguments(repo: String = "repo", page: Int = 1) = buildJsonObject {
        put("action", "workflows")
        put("owner", "owner")
        put("repo", repo)
        put("page", page)
        put("per_page", 30)
    }

    private fun issuedPage(original: JsonObject, next: JsonObject, recipeTool: String = ciDefinition.name): AgentToolExchange {
        val call = ProviderEvent.ToolCall("issuer", ciDefinition.name, original)
        val result = AgentToolResult(
            call.callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put(
                        "continuation_call",
                        buildJsonObject {
                            put("tool", recipeTool)
                            put("arguments", next)
                        }
                    )
                }
            ),
            false
        )
        return AgentToolExchange(listOf(call), listOf(result))
    }
}
