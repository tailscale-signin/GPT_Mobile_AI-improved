package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubIssueRemediation
import dev.chungjungsoo.gptmobile.data.github.GitHubNextActionMode
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestCompletion
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRequirementToolFocusTest {
    @Test fun nextFocusSkipsPassedOptionalAndUnauthorizedCriteria() {
        val checkpoint = checkpoint(
            row("github_issue:context", state = "PASS"),
            JsonObject(row("github_commit:commit") + ("required" to JsonPrimitive(false))),
            JsonObject(row("github_issue:update") + ("readiness" to buildJsonObject { put("authorization", "NOT_AUTHORIZED") })),
            row("github_ci:jobs")
        )
        val focus = requireNotNull(GitHubRequirementToolFocus.next(checkpoint))
        assertEquals(listOf("github_ci"), focus.operations)
        assertEquals(mapOf("github_ci" to "jobs"), focus.actions)
        assertNull(GitHubRequirementToolFocus.next(JsonObject(checkpoint + ("completion_verified" to JsonPrimitive(true)))))
        assertNull(GitHubRequirementToolFocus.next(checkpoint(row("unrelated", "An unspecified observation"))))
    }

    @Test fun issueIntakeAndUnfinishedPhasesDriveSelectionWithoutChangingScope() {
        val inventory = row(GitHubRequestCompletion.id(GitHubIssueRemediation.INVENTORY_REQUIREMENT), GitHubIssueRemediation.INVENTORY_REQUIREMENT)
        assertEquals(mapOf("github_issue" to "list"), GitHubRequirementToolFocus.next(checkpoint(inventory))?.actions)
        val expected = mapOf("DIAGNOSIS" to "github_issue", "CHANGE" to "github_stage_patch", "TEST" to "github_ci", "CLOSURE" to "github_issue")
        for ((stage, first) in expected) {
            val focus = requireNotNull(GitHubRequirementToolFocus.next(checkpoint(row("broad", "Implement all repairs and verify the repository"), issuePhase(1, stage))))
            assertEquals(stage, first, focus.operations.first())
        }
        val unblocked = GitHubRequirementToolFocus.next(checkpoint(issuePhase(1, "TEST", "BLOCKED"), issuePhase(2, "DIAGNOSIS")))
        assertEquals("github_issue", unblocked?.operations?.first())
    }

    @Test fun aCompletedGroupNeverReentersTheNextRequirementFocus() {
        val completed = JsonObject(issuePhase(1, "TEST") + ("state" to JsonPrimitive("PASS")))
        val focus = GitHubRequirementToolFocus.next(checkpoint(completed, issuePhase(2, "CHANGE")))
        assertEquals("github_stage_patch", focus?.operations?.first())
    }

    @Test fun focusReusesExactContractsAndChangesOnlyTheAdvertisedWindow() {
        val definitions = definitions("github_issue", "github_read", "github_stage_patch", "github_commit", "github_ci", "github_context")
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(definitions, "Fix all open issues", acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        }, githubMode = true)
        val checkpoint = checkpoint(row("github_ci:jobs"))
        assertTrue(catalog.focusRequirement(checkpoint))
        val selected = catalog.selected(definitions + catalog.definition)
        val ci = selected.first { it.name == "github_ci__native" }
        val original = definitions.single { it.name == ci.name }
        assertEquals(GitHubActionSchema.project(original, "jobs"), ci)
        assertEquals(ci, GitHubActionSchema.authorized(original, ci))
        assertEquals(original, definitions.single { it.name == ci.name })
        assertTrue(selected.any { it.name == ProgressiveToolCatalog.NAME })
        assertFalse(catalog.focusRequirement(checkpoint))
        assertEquals(0, acquisitions)
        assertTrue(catalog.selected(listOf(catalog.definition)).all { it.name == ProgressiveToolCatalog.NAME })
    }

    @Test fun focusedActionPrefetchesOnlyItsAuthorizedEvidenceSource() {
        val definitions = definitions("github_commit", "github_stage_patch", "github_issue")
        val catalog = ProgressiveToolCatalog(definitions, "Inspect the repository", githubMode = true)
        catalog.focusRequirement(checkpoint(row("github_commit:commit_staged_patch")))
        val selected = catalog.selected(definitions + catalog.definition)
        assertTrue(selected.any { it.name == "github_stage_patch__native" })
        assertTrue(catalog.selected(definitions.filter { it.name != "github_stage_patch__native" } + catalog.definition).none { it.name == "github_stage_patch__native" })
    }

    @Test fun explicitDiscoveryActionsAndRecoveryDependenciesSurviveLaterFocus() = runTest {
        val recovery = AgentToolDefinition("read_recovery_context", "Read saved receipts", buildJsonObject { put("type", "object") })
        val decision = AgentToolDefinition("user_decision", "Request an authorized decision", buildJsonObject { put("type", "object") }, capabilities = setOf(AgentToolCapability.USER_DECISION))
        val dependency = AgentToolDefinition("source_guard", "Read the selected source scope", buildJsonObject { put("type", "object") })
        val read = GitHubOperationTool.definition("github_read", "github_read__native").copy(dependsOn = setOf(dependency.name))
        val definitions = definitions("github_ci", "github_issue") + listOf(recovery, decision, dependency, read)
        val catalog = ProgressiveToolCatalog(definitions, "Fix all issues", githubMode = true)
        catalog.execute(
            "explicit-logs",
            buildJsonObject {
                put("names", JsonArray(listOf(JsonPrimitive("github_ci__native"))))
                put("action", "logs")
            }
        )
        catalog.focusRequirement(checkpoint(issuePhase(1, "DIAGNOSIS")))
        val selected = catalog.selected(definitions + catalog.definition)
        val original = definitions.single { it.name == "github_ci__native" }
        assertEquals(GitHubActionSchema.project(original, "logs"), selected.single { it.name == original.name })
        assertTrue(selected.any { it.name == recovery.name })
        assertTrue(selected.any { it.name == decision.name })
        assertTrue(selected.any { it.name == dependency.name })
        assertTrue(selected.indexOfFirst { it.name == dependency.name } < selected.indexOfFirst { it.name == read.name })
    }

    @Test fun changedSemanticContractsInvalidateFocusButDocumentationDoesNot() {
        val initial = GitHubOperationTool.definition("github_ci", "github_ci__native")
        val catalog = ProgressiveToolCatalog(listOf(initial), "Inspect workflow jobs", githubMode = true)
        val checkpoint = checkpoint(row("github_ci:jobs"))
        catalog.focusRequirement(checkpoint)
        val docs = initial.copy(description = "Updated documentation")
        catalog.bindExecutionContracts(listOf(docs))
        assertFalse(catalog.focusRequirement(checkpoint))
        val properties = initial.inputSchema.getValue("properties").jsonObject
        val bounded = JsonObject(properties.getValue("run_id").jsonObject + ("maximum" to JsonPrimitive(12345)))
        val changed = initial.copy(inputSchema = JsonObject(initial.inputSchema + ("properties" to JsonObject(properties + ("run_id" to bounded)))))
        catalog.bindExecutionContracts(listOf(changed))
        assertTrue(catalog.focusRequirement(checkpoint))
        val advertised = catalog.selected(listOf(changed, catalog.definition)).single { it.name == changed.name }
        assertEquals(GitHubActionSchema.project(changed, "jobs"), advertised)
        assertEquals(JsonPrimitive(12345), advertised.inputSchema.getValue("properties").jsonObject.getValue("run_id").jsonObject["maximum"])
        assertFalse(catalog.focusRequirement(checkpoint))
    }

    @Test fun scopedStatusAndUnavailableOperationsKeepTheirAuthorizedDiscoveryFallback() {
        val definitions = definitions("github_context", "github_pr")
        val status = ProgressiveToolCatalog(definitions, "status", githubMode = true)
        val before = status.selected(definitions + status.definition)
        assertFalse(status.focusRequirement(checkpoint(issuePhase(1, "CHANGE"))))
        assertEquals(before, status.selected(definitions + status.definition))
        val ordinary = ProgressiveToolCatalog(definitions, "Review the repository", githubMode = true)
        ordinary.focusRequirement(checkpoint(row("github_ci:jobs")))
        val selected = ordinary.selected(definitions + ordinary.definition)
        assertFalse(selected.any { it.name.startsWith("github_ci") })
        assertTrue(selected.any { it.name == ProgressiveToolCatalog.NAME })
    }

    @Test fun runnerRefreshesFocusBeforeInitialAndEverySubsequentProviderSelection() = runTest {
        var phase = "inspect"
        var selectedName = ""
        var preparations = 0
        val tools = listOf("inspect", "verify").map { name ->
            object : AgentTool {
                override val definition = AgentToolDefinition(name, "Test observation", buildJsonObject { put("type", "object") })
                override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("Observed"), false)
            }
        }
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                assertTrue(preparations >= rounds + 1)
                assertEquals(listOf(phase), tools.map { it.name })
                if (rounds == 1) emit(ProviderEvent.ToolCall("inspection", "inspect", JsonObject(emptyMap()))) else emit(ProviderEvent.TextDelta("Finished with retained observations."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(maxRounds = 4, maxToolCalls = 4),
            onToolResultCommitted = { _, _ -> phase = "verify" },
            prepareToolSelection = {
                preparations++
                selectedName = phase
            }
        ).run(session, tools, selectDefinitions = { offered -> offered.filter { it.name == selectedName } }).toList()
        assertEquals(2, rounds)
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
        assertNotNull(events.filterIsInstance<AgentRunEvent.ToolFinished>().singleOrNull())
    }

    @Test fun activeChoosesAnAvailablePrerequisiteAndShadowRetainsLegacyChoice() {
        val definitions = definitions("github_ci", "github_issue")
        val report = checkpoint(
            row("github_ci:jobs"),
            row("github_pr:context"),
            row("github_issue:list"),
            JsonObject(row("github_issue:context") + ("pending_dependencies" to JsonArray(listOf(JsonPrimitive("github_issue:list")))))
        )
        val catalog = ProgressiveToolCatalog(definitions, "Inspect the repository and compare its results", githubMode = true)
        val planned = catalog.nextAction(report)
        assertEquals("github_issue:list", planned.focus?.criterion)
        assertEquals(1, planned.dependentCount)
        assertEquals(1, planned.unavailableCount)
        fun book(mode: GitHubNextActionMode) = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), nextActionMode = mode, chooseNextAction = catalog::nextAction)
        assertEquals(report, book(GitHubNextActionMode.LEGACY).withNextAction(report))
        val shadow = book(GitHubNextActionMode.SHADOW).withNextAction(report)
        assertEquals(JsonPrimitive(true), shadow.getValue("next_action_decision").jsonObject["legacy_choice_differs"])
        assertNull(GitHubNextActionPolicy.activeDecision(shadow))
        catalog.focusRequirement(shadow, GitHubNextActionMode.SHADOW)
        assertEquals("github_ci__native", catalog.selected(definitions + catalog.definition).first().name)
        val active = book(GitHubNextActionMode.ACTIVE).withNextAction(shadow)
        catalog.focusRequirement(active, GitHubNextActionMode.ACTIVE)
        assertEquals("github_issue__native", catalog.selected(definitions + catalog.definition).first().name)
        assertEquals(JsonPrimitive("UNBLOCKS_DEPENDENTS"), GitHubNextActionPolicy.activeDecision(active)?.get("reason"))
        assertFalse(active.getValue("next_action_decision").toString().contains("github_issue:list"))
    }

    @Test fun reconciliationBudgetAndRetainedEvidenceConstrainActiveAction() {
        val catalog = ProgressiveToolCatalog(definitions("github_context", "github_commit", "github_ci"), "Implement a GitHub repair", githubMode = true)
        val report = checkpoint(row("github_commit:commit_staged_patch"), row("github_ci:jobs"))
        val pending = JsonObject(report + ("execution_ledger" to buildJsonObject { put("unresolved_remote_outcomes", 1) }))
        val reconciliation = catalog.nextAction(pending)
        assertEquals(GitHubNextActionPolicy.Action.RECONCILE, reconciliation.action)
        assertEquals(mapOf("github_context" to "recovery"), reconciliation.focus?.actions)
        val exhausted = JsonObject(report + ("stage_budget" to buildJsonObject { put("acquisition_allowed", false) }))
        assertEquals("PRESERVE_VERIFICATION_RESERVE", catalog.nextAction(exhausted).reason)
        val saved = checkpoint(
            JsonObject(
                row("github_commit:commit_staged_patch") + buildJsonObject {
                    put("eligible_receipt_count", 1)
                    put("acceptance_mismatch", "NO_MATCHING_CLAIMS")
                }
            )
        )
        assertEquals(GitHubNextActionPolicy.Action.REUSE_EVIDENCE, catalog.nextAction(saved).action)
        assertTrue(catalog.nextAction(saved).focus?.operations?.isEmpty() == true)
    }

    @Test fun reservedCallsSelectMissingReadProofWithoutAdmittingWritesOrCleanupSpend() {
        val catalog = ProgressiveToolCatalog(definitions("github_ci", "github_commit"), "Implement and verify the repair", githubMode = true)
        val base = checkpoint(row("github_commit:commit_staged_patch"), row("github_ci:jobs"))
        fun reserved(allowed: Boolean) = JsonObject(
            base + (
                "stage_budget" to buildJsonObject {
                    put("remaining_calls", 1)
                    put("cleanup_reserve", if (allowed) 0 else 1)
                    put("acquisition_allowed", false)
                    put("verification_acquisition_allowed", allowed)
                }
                )
        )
        val selected = catalog.nextAction(reserved(true))
        assertEquals(GitHubNextActionPolicy.Action.ACQUIRE, selected.action)
        assertEquals("github_ci:jobs", selected.focus?.criterion)
        assertTrue(selected.verificationOnly)
        assertEquals("ACQUIRE_MISSING_VERIFICATION_EVIDENCE", selected.reason)
        assertEquals("PRESERVE_VERIFICATION_RESERVE", catalog.nextAction(reserved(false)).reason)
        val writesOnly = JsonObject(reserved(true) + ("acceptance_ledger" to checkpoint(row("github_commit:commit_staged_patch")).getValue("acceptance_ledger")))
        assertEquals(GitHubNextActionPolicy.Action.VERIFY, catalog.nextAction(writesOnly).action)
        val active = JsonObject(reserved(true) + ("next_action_decision" to selected.snapshot(GitHubNextActionMode.ACTIVE, null)))
        assertTrue(GitHubNextActionPolicy.verificationOnly(active))
        assertTrue(dev.chungjungsoo.gptmobile.data.github.GitHubExecutionContinuation.hasAcquisitionBudget(1, 1000, active))
        assertFalse(dev.chungjungsoo.gptmobile.data.github.GitHubExecutionContinuation.hasAcquisitionBudget(0, 1000, active))
        assertFalse(dev.chungjungsoo.gptmobile.data.github.GitHubExecutionContinuation.hasAcquisitionBudget(1, 0, active))
    }

    @Test fun liveCiWatchYieldsOnlyToIndependentSourceWork() {
        val catalog = ProgressiveToolCatalog(definitions("github_ci", "github_commit", "github_read"), "Fix and verify the repository", githubMode = true)
        fun waiting(vararg rows: JsonObject) = JsonObject(checkpoint(*rows) + ("observation_state" to JsonPrimitive("WAITING_FOR_CI")))
        val ci = row("github_ci:jobs")
        val mutation = row("github_commit:commit_staged_patch")
        assertEquals(GitHubNextActionPolicy.Action.WAIT, catalog.nextAction(waiting(ci, mutation)).action)
        val independent = catalog.nextAction(waiting(ci, mutation, row("github_read:ranges")))
        assertEquals("INDEPENDENT_DURING_CI", independent.reason)
        assertEquals("github_read:ranges", independent.focus?.criterion)
    }

    @Test fun measuresOnlyTheExactAdmittedActionAndIgnoresSharedOrSingleSamples() {
        val catalog = ProgressiveToolCatalog(definitions("github_ci", "github_issue"), "Inspect jobs and issues", githubMode = true)
        val report = checkpoint(row("github_ci:jobs"), row("github_issue:list"))
        val args = buildJsonObject { put("action", "list") }
        fun result(id: String) = AgentToolResult(id, ToolResultContent.Text("OK"), false, measurement = ToolPayloadMetrics(durationMs = 75))
        catalog.recordObservation("github_issue__native", result("A"), arguments = args)
        catalog.recordObservation("github_issue__native", result("A"), arguments = args)
        catalog.recordObservation("github_issue__native", result("shared").copy(sharedResult = true), arguments = args)
        assertNull(catalog.nextAction(report).measurement)
        catalog.recordObservation("github_issue__native", result("B"), arguments = args)
        catalog.recordObservation("github_issue__native", result("C"), arguments = args)
        val decision = catalog.nextAction(report)
        assertEquals("github_issue:list", decision.focus?.criterion)
        assertEquals(3, decision.measurement?.samples)
        assertEquals(75L, decision.measurement?.latencyMillis)
        assertNull(catalog.nextAction(checkpoint(row("github_issue:context"))).measurement)
    }

    private fun definitions(vararg operations: String) = operations.map { GitHubOperationTool.definition(it, it + "__native") }
    private fun checkpoint(vararg rows: JsonObject) = buildJsonObject { put("acceptance_ledger", buildJsonObject { put("requirements", JsonArray(rows.toList())) }) }
    private fun row(id: String, criterion: String = "", state: String = "NOT_TESTED") = buildJsonObject {
        put("id", id)
        put("criterion", criterion)
        put("state", state)
        put("required", true)
    }
    private fun issuePhase(number: Int, stage: String, phaseState: String = "NOT_TESTED") = JsonObject(
        row("issue-$number", "Resolve issue #$number") + buildJsonObject {
            put("issue_group", "issue-$number")
            put(
                "issue_phases",
                JsonArray(
                    listOf("DIAGNOSIS", "CHANGE", "TEST", "CLOSURE").map { name ->
                        buildJsonObject {
                            put("stage", name)
                            put("state", if (name == stage) phaseState else "PASS")
                        }
                    }
                )
            )
        }
    )
}
