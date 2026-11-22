package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiInvestigationRecoveryTest {
    private val head = "a".repeat(40)
    private val ci = "github_ci__selected"
    private val context = "github_context__selected"
    private val compactContext = "github_context_inspect__selected"
    private val tools = listOf("github_context", "github_ci").map { GitHubOperationTool.definition(it, it + "__selected") }

    // Provider fixtures isolate alias identity from the independently exercised native catalog.
    private val compactTools = tools.map { if (it.name == context) it.copy(name = compactContext) else it }
    private val scope = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put("selected_ref", "main")
    }

    private fun observe(plan: GitHubCiInvestigationRecovery, call: ProviderEvent.ToolCall, data: JsonObject, error: Boolean = false, observedScope: JsonObject = scope) {
        plan.observe(
            AgentToolExchange(
                listOf(call),
                listOf(
                    AgentToolResult(
                        call.callId,
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("schema", "github.operation.v1")
                                put("data", JsonObject(emptyMap()))
                            }
                        ),
                        error,
                        retainedContent = ToolResultContent.Json(data),
                        executionReceipt = buildJsonObject { put("resource_context", observedScope) }
                    )
                )
            )
        )
    }

    private fun seeded(task: String = "Diagnose CI failures"): GitHubCiInvestigationRecovery = GitHubCiInvestigationRecovery(task).also { plan ->
        observe(plan, ProviderEvent.ToolCall("workflows", ci, buildJsonObject { put("action", "workflows") }), buildJsonObject { put("total_count", 57) })
    }

    private fun pinned(): GitHubCiInvestigationRecovery = seeded().also { plan ->
        assertTrue(plan.activate(tools))
        val status = requireNotNull(plan.nextCall(tools))
        assertEquals(context, status.name)
        assertEquals(JsonPrimitive("main"), status.arguments["ref"])
        observe(plan, status, buildJsonObject { put("commit_sha", head) })
    }

    private fun run(id: Long = 4_000_000_001L, attempt: Int = 2, sha: String = head, conclusion: String = "failure") = buildJsonObject {
        put("id", id)
        put("run_attempt", attempt)
        put("head_sha", sha)
        put("status", "completed")
        put("conclusion", conclusion)
    }

    @Test fun failedUnobservedLogSelectorStartsPinnedDiscoveryWithoutReusingTheFailedIds() {
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(
            plan,
            ProviderEvent.ToolCall(
                "unobserved-log",
                ci,
                buildJsonObject {
                    put("action", "logs")
                    put("run_id", 111)
                    put("job_id", 222)
                    put("commit_sha", head)
                }
            ),
            buildJsonObject { put("error", "not found") },
            error = true
        )
        assertTrue(plan.activate(tools))
        val status = requireNotNull(plan.nextCall(tools))
        assertEquals(context, status.name)
        assertEquals(JsonPrimitive(head), status.arguments["ref"])
        observe(plan, status, buildJsonObject { put("commit_sha", head) })
        val runs = requireNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive("runs"), runs.arguments["action"])
        assertEquals(JsonPrimitive(head), runs.arguments["commit_sha"])
        assertFalse("run_id" in runs.arguments || "job_id" in runs.arguments)
        observe(plan, runs, buildJsonObject { put("workflow_runs", JsonArray(listOf(run(id = 333)))) })
        val jobs = requireNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive("jobs"), jobs.arguments["action"])
        assertEquals(JsonPrimitive(333L), jobs.arguments["run_id"])
        assertEquals(JsonPrimitive(2), jobs.arguments["run_attempt"])
        assertEquals(JsonPrimitive(head), jobs.arguments["expected_head_sha"])
    }

    @Test fun pinnedRunsAlreadyObservedCanStartInvestigationWithoutListingWorkflowsAgain() {
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(
            plan,
            ProviderEvent.ToolCall(
                "observed-runs",
                ci,
                buildJsonObject {
                    put("action", "runs")
                    put("commit_sha", head)
                    put("page", 1)
                    put("per_page", 100)
                }
            ),
            buildJsonObject { put("workflow_runs", JsonArray(listOf(run()))) }
        )
        assertTrue(plan.activate(tools))
        val status = requireNotNull(plan.nextCall(tools))
        observe(plan, status, buildJsonObject { put("commit_sha", head) })
        assertEquals(JsonPrimitive("jobs"), requireNotNull(plan.nextCall(tools)).arguments["action"])
    }

    @Test fun failedSelectorWithConflictingRepositoryObservationCannotSeedRecovery() {
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(
            plan,
            ProviderEvent.ToolCall(
                "foreign-log",
                ci,
                buildJsonObject {
                    put("action", "logs")
                    put("owner", "owner")
                    put("repo", "another-repo")
                    put("job_id", 222)
                }
            ),
            buildJsonObject {},
            error = true
        )
        assertFalse(plan.activate(tools))
        assertNull(plan.nextCall(tools))
    }

    @Test fun workflowSeedUsesOnlyAdmittedContextFacadesFromTheNativeCatalog() {
        val catalog = GitHubTool(modelToolName = "github__selected", persistentState = false).operations().map { it.definition }
        val contexts = catalog.filter { it.name in setOf(context, compactContext) }
        assertEquals(
            GitHubOperationTool.names.filter { it in setOf("github_context", "github_context_inspect") }.map { it + "__selected" }.toSet(),
            contexts.map { it.name }.toSet()
        )
        assertTrue(contexts.isNotEmpty())
        for (definition in contexts) {
            val admitted = listOf(definition, catalog.single { it.name == ci })
            val plan = seeded()
            assertTrue(plan.activate(admitted))
            val status = requireNotNull(plan.nextCall(admitted))
            assertEquals(definition.name, status.name)
            assertEquals(JsonPrimitive("main"), status.arguments["ref"])
            assertEquals(JsonPrimitive("repo_status"), GitHubOperationTool.nativeArguments(status.name, status.arguments)?.get("action"))
            observe(plan, status, buildJsonObject { put("commit_sha", head) })
            val runs = requireNotNull(plan.nextCall(admitted))
            assertEquals(ci, runs.name)
            assertEquals(JsonPrimitive("runs"), runs.arguments["action"])
            assertEquals(JsonPrimitive(head), runs.arguments["commit_sha"])
        }
    }

    @Test fun successfulCompactStatusAdvancesToRunsAfterTheAdmittedFacadeChanges() {
        val plan = seeded()
        assertTrue(plan.activate(compactTools))
        val status = requireNotNull(plan.nextCall(compactTools))
        assertEquals(compactContext, status.name)
        observe(
            plan,
            status,
            buildJsonObject {
                put("commit_sha", head)
                put("branch", "main")
            }
        )
        val runs = requireNotNull(plan.nextCall(tools))
        assertEquals(ci, runs.name)
        assertEquals(JsonPrimitive("runs"), runs.arguments["action"])
        assertEquals(JsonPrimitive(head), runs.arguments["commit_sha"])
    }

    @Test fun failedOrMismatchedContextReadsRemainConsumedAcrossFacadeChanges() {
        for ((observedName, admitted) in listOf(context to compactTools, compactContext to tools)) {
            for ((error, mismatchedReceipt) in listOf(true to false, true to true, false to true)) {
                val plan = seeded()
                observe(
                    plan,
                    ProviderEvent.ToolCall(
                        "consumed-status",
                        observedName,
                        buildJsonObject {
                            put("action", "status")
                            put("repo", "OWNER/Repo.git")
                            put("ref", "main")
                        }
                    ),
                    buildJsonObject { put("commit_sha", head) },
                    error = error,
                    observedScope = if (mismatchedReceipt) JsonObject(scope + ("repo" to JsonPrimitive("different"))) else scope
                )
                assertFalse(plan.activate(admitted))
                assertNull(plan.nextCall(admitted))
            }
        }
    }

    @Test fun compactContextPreservesConnectionRepositoryAndRevisionGuards() {
        assertFalse(seeded().activate(compactTools.map { if (it.name == compactContext) it.copy(name = "github_context_inspect__different") else it }))
        assertFalse(seeded().activate(compactTools.map { if (it.name == compactContext) it.copy(modelVisible = false) else it }))
        for (different in listOf("connection", "repository", "branch")) {
            val plan = seeded()
            val repository = if (different == "repository") "different" else "repo"
            val ref = if (different == "branch") "release" else "main"
            observe(
                plan,
                ProviderEvent.ToolCall(
                    "foreign-status",
                    if (different == "connection") "github_context_inspect__different" else compactContext,
                    buildJsonObject {
                        put("action", "status")
                        put("owner", "owner")
                        put("repo", repository)
                        put("ref", ref)
                    }
                ),
                buildJsonObject {
                    put("commit_sha", "b".repeat(40))
                    put("branch", ref)
                },
                observedScope = JsonObject(scope + mapOf("repo" to JsonPrimitive(repository), "selected_ref" to JsonPrimitive(ref)))
            )
            assertTrue(plan.activate(compactTools))
            val status = requireNotNull(plan.nextCall(compactTools))
            assertEquals(compactContext, status.name)
            assertEquals(JsonPrimitive("repo"), status.arguments["repo"])
            assertEquals(JsonPrimitive("main"), status.arguments["ref"])
        }
        val selected = JsonObject(scope + ("selected_ref" to JsonPrimitive(head)))
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(plan, ProviderEvent.ToolCall("workflows", ci, buildJsonObject { put("action", "workflows") }), JsonObject(emptyMap()), observedScope = selected)
        assertTrue(plan.activate(compactTools))
        val status = requireNotNull(plan.nextCall(compactTools))
        assertEquals(JsonPrimitive(head), status.arguments["ref"])
        observe(plan, status, buildJsonObject { put("commit_sha", "b".repeat(40)) }, observedScope = selected)
        assertNull(plan.nextCall(compactTools))
    }

    @Test fun stalledWorkflowDiscoveryAdvancesThroughPinnedRunsJobsAndRelevantLogRanges() {
        val plan = pinned()
        val runs = requireNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive("runs"), runs.arguments["action"])
        assertEquals(JsonPrimitive(head), runs.arguments["commit_sha"])
        observe(plan, runs, buildJsonObject { put("workflow_runs", JsonArray(listOf(run()))) })
        val jobs = requireNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive("jobs"), jobs.arguments["action"])
        assertEquals(JsonPrimitive(4_000_000_001L), jobs.arguments["run_id"])
        assertEquals(JsonPrimitive(2), jobs.arguments["run_attempt"])
        assertEquals(JsonPrimitive(head), jobs.arguments["expected_head_sha"])
        observe(
            plan,
            jobs,
            buildJsonObject {
                put("run_id", 4_000_000_001L)
                put("run_attempt", 2)
                put("head_sha", head)
                put("jobs", JsonArray(listOf(run(id = 5_000_000_002L))))
            }
        )
        val logs = requireNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive("logs"), logs.arguments["action"])
        assertEquals(JsonPrimitive(5_000_000_002L), logs.arguments["job_id"])
        assertEquals(JsonPrimitive(4_000_000_001L), logs.arguments["run_id"])
        assertEquals(JsonPrimitive(2), logs.arguments["run_attempt"])
        assertEquals(JsonPrimitive(head), logs.arguments["expected_head_sha"])
        observe(plan, logs, buildJsonObject { put("total_lines", 900) })
        val tail = requireNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive(701), tail.arguments["start_line"])
        assertEquals(JsonPrimitive(900), tail.arguments["end_line"])
        observe(plan, tail, buildJsonObject { put("total_lines", 900) })
        assertNull(plan.nextCall(tools))
    }

    @Test fun aNewerSuccessfulAttemptAndDifferentRevisionDoNotProduceInventedFailedJobs() {
        val plan = pinned()
        observe(plan, requireNotNull(plan.nextCall(tools)), buildJsonObject { put("workflow_runs", JsonArray(listOf(run(attempt = 1), run(attempt = 2, conclusion = "success"), run(id = 9, sha = "b".repeat(40))))) })
        assertNull(plan.nextCall(tools))
    }

    @Test fun failedReadsAreNotRetriedAndMissingAttemptsCannotAuthorizeJobInspection() {
        val failed = pinned()
        observe(failed, requireNotNull(failed.nextCall(tools)), JsonObject(emptyMap()), error = true)
        assertNull(failed.nextCall(tools))
        val missingAttempt = pinned()
        observe(missingAttempt, requireNotNull(missingAttempt.nextCall(tools)), buildJsonObject { put("workflow_runs", JsonArray(listOf(JsonObject(run() - "run_attempt")))) })
        assertNull(missingAttempt.nextCall(tools))
    }

    @Test fun changedJobRevisionOrAttemptDoesNotAuthorizeLogDownload() {
        for (wrong in listOf("head_sha" to JsonPrimitive("b".repeat(40)), "run_attempt" to JsonPrimitive(3), "run_id" to JsonPrimitive(77))) {
            val plan = pinned()
            observe(plan, requireNotNull(plan.nextCall(tools)), buildJsonObject { put("workflow_runs", JsonArray(listOf(run()))) })
            observe(
                plan,
                requireNotNull(plan.nextCall(tools)),
                JsonObject(
                    buildJsonObject {
                        put("run_id", 4_000_000_001L)
                        put("run_attempt", 2)
                        put("head_sha", head)
                        put("jobs", JsonArray(listOf(run(id = 5_000_000_002L))))
                    } + wrong
                )
            )
            assertNull(plan.nextCall(tools))
        }
    }

    @Test fun onlyTheAdmittedConnectionAndTrustedDiagnosisIntentCanStartRecovery() {
        assertFalse(seeded("List every workflow definition").activate(tools))
        assertFalse(seeded().activate(tools.filterNot { it.name == ci }))
        assertFalse(seeded().activate(tools.map { it.copy(name = it.name.replace("selected", "different")) }))
        assertNull(seeded().nextCall(tools))
        val plan = pinned()
        val runs = requireNotNull(plan.nextCall(tools))
        observe(plan, runs, buildJsonObject { put("workflow_runs", JsonArray(listOf(run()))) }, observedScope = JsonObject(scope + ("repo" to JsonPrimitive("different"))))
        // No jobs may be derived from the other repository's observation.
        assertNull(plan.nextCall(tools))
    }

    @Test fun retainedSourceIdentitiesSurviveAnEmptyDisplayProjection() {
        val plan = pinned()
        observe(plan, requireNotNull(plan.nextCall(tools)), buildJsonObject { put("workflow_runs", JsonArray(listOf(run()))) })
        assertNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive("jobs"), plan.nextCall(tools)?.arguments?.get("action"))
    }

    @Test fun sameRepositoryStatusForAnotherBranchCannotSetTheDiagnosisHead() {
        for (explicitRef in listOf(true, false)) {
            val plan = seeded()
            val arguments = buildJsonObject {
                put("action", "status")
                if (explicitRef) put("ref", "release")
            }
            observe(
                plan,
                ProviderEvent.ToolCall("other-branch", context, arguments),
                buildJsonObject {
                    put("commit_sha", "b".repeat(40))
                    put("branch", "release")
                }
            )
            assertTrue(plan.activate(tools))
            val next = requireNotNull(plan.nextCall(tools))
            assertEquals(context, next.name)
            assertEquals(JsonPrimitive("main"), next.arguments["ref"])
        }
    }

    @Test fun workflowShaArgumentsCannotOverrideTheObservedSelectedHead() {
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(
            plan,
            ProviderEvent.ToolCall(
                "workflows",
                ci,
                buildJsonObject {
                    put("action", "workflows")
                    put("commit_sha", head)
                    put("ref", "main")
                }
            ),
            buildJsonObject {
                put("resource_type", "workflow_definitions")
                put("branch_head_verified", false)
            }
        )
        observe(
            plan,
            ProviderEvent.ToolCall(
                "newer-head",
                context,
                buildJsonObject {
                    put("action", "status")
                    put("ref", "main")
                }
            ),
            buildJsonObject {
                put("commit_sha", "b".repeat(40))
                put("branch", "main")
            }
        )
        assertTrue(plan.activate(tools))
        assertEquals(JsonPrimitive("b".repeat(40)), plan.nextCall(tools)?.arguments?.get("commit_sha"))
    }

    @Test fun distinctExplicitRefsCannotBeCombinedWhenNoSelectedDefaultWasReturned() {
        val explicitScope = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
        }
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(
            plan,
            ProviderEvent.ToolCall(
                "workflows",
                ci,
                buildJsonObject {
                    put("action", "workflows")
                    put("ref", "main")
                }
            ),
            JsonObject(emptyMap()),
            observedScope = explicitScope
        )
        observe(
            plan,
            ProviderEvent.ToolCall(
                "status",
                context,
                buildJsonObject {
                    put("action", "status")
                    put("ref", "release")
                }
            ),
            buildJsonObject { put("commit_sha", "b".repeat(40)) },
            observedScope = explicitScope
        )
        assertTrue(plan.activate(tools))
        assertEquals(context, plan.nextCall(tools)?.name)
        assertEquals(JsonPrimitive("main"), plan.nextCall(tools)?.arguments?.get("ref"))
    }

    @Test fun aForeignRepositoryReadCannotSuppressSelectedStatusOrRuns() {
        val foreign = buildJsonObject {
            put("owner", "other")
            put("repo", "other-repo")
            put("selected_ref", "main")
        }
        val plan = seeded()
        observe(
            plan,
            ProviderEvent.ToolCall(
                "foreign-status",
                context,
                buildJsonObject {
                    put("action", "status")
                    put("owner", "other")
                    put("repo", "other-repo")
                    put("ref", "main")
                }
            ),
            buildJsonObject {
                put("commit_sha", head)
                put("branch", "main")
            },
            observedScope = foreign
        )
        assertTrue(plan.activate(tools))
        val status = requireNotNull(plan.nextCall(tools))
        assertEquals(context, status.name)
        assertEquals(JsonPrimitive("owner"), status.arguments["owner"])
        assertEquals(JsonPrimitive("repo"), status.arguments["repo"])
        observe(
            plan,
            status,
            buildJsonObject {
                put("commit_sha", head)
                put("branch", "main")
            }
        )
        observe(
            plan,
            ProviderEvent.ToolCall(
                "foreign-runs",
                ci,
                buildJsonObject {
                    put("action", "runs")
                    put("repo", "OTHER/Other-Repo.git")
                    put("commit_sha", head)
                }
            ),
            buildJsonObject { put("workflow_runs", JsonArray(listOf(run()))) },
            observedScope = foreign
        )
        val runs = requireNotNull(plan.nextCall(tools))
        assertEquals(JsonPrimitive("runs"), runs.arguments["action"])
        assertEquals(JsonPrimitive("owner"), runs.arguments["owner"])
        assertEquals(JsonPrimitive("repo"), runs.arguments["repo"])
    }

    @Test fun canonicalSameRepositoryFailuresRemainConsumedDespiteMismatchedReceipts() {
        for (mismatchedReceipt in listOf(false, true)) {
            val plan = seeded()
            observe(
                plan,
                ProviderEvent.ToolCall(
                    "failed-status",
                    context,
                    buildJsonObject {
                        put("action", "status")
                        put("repo", "OWNER/Repo.git")
                        put("ref", "main")
                    }
                ),
                JsonObject(emptyMap()),
                error = true,
                observedScope = if (mismatchedReceipt) JsonObject(scope + ("repo" to JsonPrimitive("different"))) else scope
            )
            assertFalse(plan.activate(tools))
            val pinned = pinned()
            observe(
                pinned,
                ProviderEvent.ToolCall(
                    "failed-runs",
                    ci,
                    buildJsonObject {
                        put("action", "runs")
                        put("owner", "OWNER")
                        put("repo", "Repo")
                        put("head_sha", head)
                    }
                ),
                JsonObject(emptyMap()),
                error = true,
                observedScope = if (mismatchedReceipt) JsonObject(scope + ("repo" to JsonPrimitive("different"))) else scope
            )
            assertNull(pinned.nextCall(tools))
        }
    }

    @Test fun ignoredWorkflowShaArgumentsStillRequireAStatusObservation() {
        for (key in listOf("commit_sha", "head_sha", "expected_head_sha")) {
            val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
            observe(
                plan,
                ProviderEvent.ToolCall(
                    "workflows",
                    ci,
                    buildJsonObject {
                        put("action", "workflows")
                        put(key, head)
                    }
                ),
                buildJsonObject { put("branch_head_verified", false) },
                observedScope = JsonObject(scope + (key to JsonPrimitive(head)))
            )
            assertTrue(plan.activate(tools))
            val next = requireNotNull(plan.nextCall(tools))
            assertEquals(context, next.name)
            assertEquals(JsonPrimitive("main"), next.arguments["ref"])
            assertFalse(next.arguments.containsKey(key))
        }
    }

    @Test fun aLiteralSelectedShaConstrainsStatusBeforeItCanPinRuns() {
        val selected = JsonObject(scope + ("selected_ref" to JsonPrimitive(head)))
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(plan, ProviderEvent.ToolCall("workflows", ci, buildJsonObject { put("action", "workflows") }), JsonObject(emptyMap()), observedScope = selected)
        assertTrue(plan.activate(tools))
        val status = requireNotNull(plan.nextCall(tools))
        assertEquals(context, status.name)
        assertEquals(JsonPrimitive(head), status.arguments["ref"])
        observe(plan, status, buildJsonObject { put("commit_sha", head) }, observedScope = selected)
        assertEquals(JsonPrimitive(head), plan.nextCall(tools)?.arguments?.get("commit_sha"))

        val wrongHead = GitHubCiInvestigationRecovery("Diagnose CI failures")
        observe(wrongHead, ProviderEvent.ToolCall("workflows", ci, buildJsonObject { put("action", "workflows") }), JsonObject(emptyMap()), observedScope = selected)
        assertTrue(wrongHead.activate(tools))
        observe(wrongHead, requireNotNull(wrongHead.nextCall(tools)), buildJsonObject { put("commit_sha", "b".repeat(40)) }, observedScope = selected)
        assertNull(wrongHead.nextCall(tools))
    }
}
