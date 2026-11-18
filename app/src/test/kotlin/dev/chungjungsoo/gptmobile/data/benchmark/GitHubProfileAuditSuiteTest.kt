package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProfileAuditSuiteTest {

    @Test fun selectedActionSchemasReachGitHubBenchmarkModelsWithoutWeakeningFixtures() = runBlocking {
        val cases = GitHubProfileAuditSuite.cases(allActions = false)
        for (test in cases) {
            val fixture = requireNotNull(GitHubProfileAuditSuite.fixture(test))
            val scenario = BenchmarkToolScenario(test, "EXACT-FIXTURE-CODE")
            val advertised = scenario.tools.single().definition.inputSchema
            val action = fixture.action ?: continue
            val contract = (advertised["properties"] as? JsonObject)?.get("action") as? JsonObject ?: continue
            assertEquals(test.id, JsonArray(listOf(JsonPrimitive(action))), contract["enum"])
            val exact = ProviderEvent.ToolCall("exact-" + test.id, fixture.operation, fixture.arguments)
            assertTrue(test.id, scenario.valid(exact))
            assertFalse(test.id, scenario.tools.single().execute(exact.callId, exact.arguments).isError)
        }
    }

    @Test
    fun quickSuiteUsesExactlyOneCasePerCurrentGitHubTool() {
        val quick = GitHubProfileAuditSuite.cases(allActions = false)
        assertEquals(GitHubOperationTool.names.size, quick.size)
        assertEquals(GitHubOperationTool.names, quick.mapNotNull { GitHubProfileAuditSuite.fixture(it)?.operation }.toSet())
        assertTrue(quick.all { it.category == "tools" && it.workload == GitHubProfileAuditSuite.WORKLOAD })
    }

    @Test
    fun fullSuiteCoversEveryAdvertisedActionWithUniqueAndValidArguments() {
        val cases = GitHubProfileAuditSuite.cases(allActions = true)
        assertEquals(GitHubProfileAuditSuite.actionCount(), cases.size)
        assertEquals(cases.size, cases.map { it.id }.distinct().size)
        assertTrue(cases.size >= GitHubProfileAuditSuite.familyCount())
        for (test in cases) {
            val fixture = GitHubProfileAuditSuite.fixture(test)
            assertNotNull("Missing scenario for " + test.id, fixture)
            val accepted = requireNotNull(fixture)
            assertTrue(
                "Invalid GitHub tool schema fixture " + test.id,
                ToolArgumentValidator.errors(accepted.arguments, accepted.definition.inputSchema).isEmpty()
            )
            assertTrue(
                "Invalid GitHub operation admission fixture " + test.id,
                GitHubOperationArguments.errors(accepted.operation.removeSuffix("_inspect"), accepted.arguments).isEmpty()
            )
        }
    }

    @Test
    fun copilotUpdateFixtureKeepsExplicitMinimalRepairPermission() {
        val test = GitHubProfileAuditSuite.cases(allActions = true)
            .single { it.id == "github-ai-v1:github_pr:agent_merge_update" }
        val fixture = requireNotNull(GitHubProfileAuditSuite.fixture(test))
        assertEquals(JsonArray(listOf(JsonPrimitive("fix_ci"))), fixture.arguments["allowed_actions"])
        assertTrue(GitHubOperationArguments.errors("github_pr", fixture.arguments).isEmpty())
    }

    @Test
    fun offlineWriteFixtureUsesOnlyAnInMemoryTool() = runBlocking {
        val test = GitHubProfileAuditSuite.cases(allActions = true).single { it.id == "github-ai-v1:github_write:commit" }
        val fixture = requireNotNull(GitHubProfileAuditSuite.fixture(test))
        val scenario = BenchmarkToolScenario(test, "OFFLINE-FIXTURE-OK")
        val tool = scenario.tools.single()
        assertEquals("github_write", tool.definition.name)
        val call = ProviderEvent.ToolCall("fixture-call-1", fixture.operation, fixture.arguments)
        assertTrue(scenario.valid(call))
        val result = tool.execute(call.callId, call.arguments)
        assertFalse(result.isError)
        assertTrue(scenario.passed(listOf(call), setOf(call.callId), emptySet()))
        assertFalse(scenario.valid(call.copy(arguments = kotlinx.serialization.json.buildJsonObject {})))
    }

    @Test
    fun failedGitHubFixtureArgumentsKeepOnlySafeFieldIdentities() {
        val benchmark = GitHubProfileAuditSuite.cases(allActions = false)
            .single { it.id == "github-ai-v1:github_search:search" }
        val fixture = requireNotNull(GitHubProfileAuditSuite.fixture(benchmark))
        val scenario = BenchmarkToolScenario(benchmark, "FIXTURE-OK")
        val wrong = kotlinx.serialization.json.JsonObject(
            fixture.arguments + mapOf(
                "query" to JsonPrimitive("PRIVATE_QUERY_MUST_NOT_LEAK"),
                "extra_field" to JsonPrimitive("PRIVATE_PAYLOAD_MUST_NOT_LEAK")
            )
        )
        val result = scenario.assess(
            listOf(ProviderEvent.ToolCall("wrong-1", fixture.operation, wrong)),
            emptySet(),
            emptySet(),
            "invalid",
            "invalid",
            true
        )
        assertEquals(false, result.argumentsCorrect)
        val evidence = requireNotNull(result.argumentEvidence)
        assertEquals(true, evidence.expectedToolSelected)
        assertEquals(false, evidence.schemaValid)
        assertEquals(false, evidence.exactArguments)
        assertEquals(listOf("extra_field"), evidence.additionalFieldNames)
        assertEquals(listOf("query"), evidence.changedFieldNames)
        assertTrue(evidence.missingFieldNames.isEmpty())
        assertFalse(evidence.toString().contains("PRIVATE_QUERY"))
        assertFalse(evidence.toString().contains("PRIVATE_PAYLOAD"))
    }

    @Test
    fun fivePreviouslyFailingGitHubFixturesAcceptTheirExactOfflineCalls() = runBlocking {
        val failedCases = setOf(
            "github-ai-v1:github_context:resolve",
            "github-ai-v1:github_context_inspect:resolve",
            "github-ai-v1:github_pr_inspect:list",
            "github-ai-v1:github_search:search",
            "github-ai-v1:github_stage_patch:default"
        )
        val cases = GitHubProfileAuditSuite.cases(allActions = false)
            .filter { it.id in failedCases }
        assertEquals(failedCases, cases.map { it.id }.toSet())
        for (benchmark in cases) {
            val fixture = requireNotNull(GitHubProfileAuditSuite.fixture(benchmark))
            val scenario = BenchmarkToolScenario(benchmark, "FIXTURE-OK")
            val call = ProviderEvent.ToolCall("valid-1", fixture.operation, fixture.arguments)
            assertTrue(benchmark.id, scenario.valid(call))
            val result = scenario.tools.single().execute(call.callId, call.arguments)
            assertFalse(benchmark.id, result.isError)
            val score = scenario.assess(listOf(call), setOf(call.callId), emptySet(), "FIXTURE-OK", "FIXTURE-OK", true)
            assertEquals(benchmark.id, true, score.argumentEvidence?.exactArguments)
            assertEquals(benchmark.id, true, score.argumentEvidence?.schemaValid)
        }
    }


    private fun engineeringArgs(action: String, vararg fields: Pair<String, JsonElement>): JsonObject =
        JsonObject(mapOf("action" to JsonPrimitive(action)) + fields.toMap())

    private fun field(name: String, value: String): Pair<String, JsonElement> =
        name to JsonPrimitive(value)

    @Test
    fun engineeringWorkflowFixturesEnforceRealEvidenceOrdering() = runBlocking {
        val challenges = GitHubEngineeringBenchmarkSuite.cases()
        assertEquals(7, challenges.size)
        assertEquals(7, challenges.map { it.id }.distinct().size)
        assertTrue(challenges.all { it.workload == GitHubEngineeringBenchmarkSuite.WORKLOAD })

        val before = "a".repeat(40)
        val after = "b".repeat(40)
        val code = "PKG-ENGINEERING"
        val cursor = "ISSUED-PAGE-"
        for (benchmark in challenges) {
            val kind = benchmark.id.substringAfterLast(':')
            val calls = when (kind) {
                "release-gate", "security-review" -> {
                    val patch = if (kind == "release-gate") {
                        "ciGreen && approved"
                    } else {
                        "role == \"admin\" && tokenValid"
                    }
                    listOf(
                        engineeringArgs("read"),
                        engineeringArgs(
                            "patch",
                            field("path", "src/main/kotlin/AccessGate.kt"),
                            field("patch", patch),
                            field("expected_head_sha", before)
                        ),
                        engineeringArgs("test", field("sha", after)),
                        engineeringArgs("verify", field("sha", after))
                    )
                }
                "pagination" -> listOf(
                    engineeringArgs("list"),
                    engineeringArgs("list", field("cursor", cursor + "2-" + code)),
                    engineeringArgs("list", field("cursor", cursor + "3-" + code)),
                    engineeringArgs("verify")
                )
                "copilot-handshake" -> listOf(
                    engineeringArgs("create"),
                    engineeringArgs("status", field("session_id", "SESSION-" + code)),
                    engineeringArgs("status", field("session_id", "SESSION-" + code)),
                    engineeringArgs(
                        "send",
                        field("session_id", "SESSION-" + code),
                        field("task", "Repair the fixture failing CI")
                    ),
                    engineeringArgs("result", field("session_id", "SESSION-" + code))
                )
                "unknown-dispatch" -> listOf(
                    engineeringArgs("dispatch"),
                    engineeringArgs("reconcile", field("job_id", "JOB-" + code)),
                    engineeringArgs("verify")
                )
                "agent-merge" -> listOf(
                    engineeringArgs("agent_merge_status"),
                    engineeringArgs(
                        "agent_merge_enable",
                        field("expected_head_sha", before),
                        "allowed_actions" to JsonArray(listOf(JsonPrimitive("fix_ci")))
                    ),
                    engineeringArgs("agent_merge_verify")
                )
                else -> listOf(engineeringArgs("status"))
            }
            val scenario = GitHubEngineeringScenario(benchmark, code)
            val successful = mutableSetOf<String>()
            val expectedErrors = mutableSetOf<String>()
            val proposals = calls.mapIndexed { index, args ->
                val call = ProviderEvent.ToolCall("fixture-" + index, scenario.tool.definition.name, args)
                assertTrue(benchmark.id + ":" + index, scenario.valid(call))
                val result = scenario.tool.execute(call.callId, call.arguments)
                if (scenario.expectedError(call, result)) {
                    expectedErrors.add(call.callId)
                } else {
                    assertFalse(benchmark.id + ":" + index, result.isError)
                    successful.add(call.callId)
                }
                call
            }
            assertTrue(benchmark.id, scenario.passed(proposals, successful, expectedErrors))
            assertEquals(
                "OFFLINE_ENGINEERING_SIMULATION",
                scenario.assess(proposals, successful, expectedErrors, code, code, true).evidenceClass
            )
        }
        assertEquals(
            GitHubProfileAuditSuite.actionCount(),
            benchmarkSuiteForVersion(10, BenchmarkMode.GITHUB_INTEGRATION).size
        )
        assertEquals(
            GitHubProfileAuditSuite.actionCount() + challenges.size,
            benchmarkSuiteForVersion(11, BenchmarkMode.GITHUB_INTEGRATION).size
        )
    }

    @Test
    fun copilotEarlySendAndUnauthorizedMergeRemainRejected() = runBlocking {
        val challenges = GitHubEngineeringBenchmarkSuite.cases()
        val code = "PKG-GUARDED"
        val handshake = GitHubEngineeringScenario(
            challenges.single { it.id.endsWith(":copilot-handshake") },
            code
        )
        val create = engineeringArgs("create")
        assertFalse(handshake.tool.execute("init", create).isError)
        val early = handshake.tool.execute(
            "too-early",
            engineeringArgs(
                "send",
                field("session_id", "SESSION-" + code),
                field("task", "Do not send before copilot-agent readiness")
            )
        )
        assertTrue(early.isError)
        val wrongStatus = handshake.tool.execute(
            "unrecoverable",
            engineeringArgs("status", field("session_id", "SESSION-" + code))
        )
        assertTrue(wrongStatus.isError)

        val agentMerge = GitHubEngineeringScenario(
            challenges.single { it.id.endsWith(":agent-merge") },
            code
        )
        assertFalse(agentMerge.tool.execute("observe", engineeringArgs("agent_merge_status")).isError)
        val prohibited = agentMerge.tool.execute(
            "prohibited",
            engineeringArgs(
                "agent_merge_enable",
                field("expected_head_sha", "a".repeat(40)),
                "allowed_actions" to JsonArray(
                    listOf(JsonPrimitive("fix_ci"), JsonPrimitive("resolve_merge_conflicts"))
                )
            )
        )
        assertTrue(prohibited.isError)

        val disabled = GitHubEngineeringScenario(
            challenges.single { it.id.endsWith(":copilot-default-off") },
            code
        )
        val enable = engineeringArgs("agent_merge_enable", field("expected_head_sha", "a".repeat(40)))
        assertFalse(ToolArgumentValidator.errors(enable, disabled.tool.definition.inputSchema).isEmpty())
        assertTrue(disabled.tool.execute("unapproved", enable).isError)
    }

    @Test
    fun alteredPaginationCursorAndDuplicateUnknownWriteFailClosed() = runBlocking {
        val challenges = GitHubEngineeringBenchmarkSuite.cases()
        val pagination = GitHubEngineeringScenario(
            challenges.single { it.id.endsWith(":pagination") },
            "PKG-SAFE"
        )
        assertFalse(pagination.tool.execute("page1", engineeringArgs("list")).isError)
        assertTrue(
            pagination.tool.execute(
                "altered",
                engineeringArgs("list", field("cursor", "GUESSED-PAGE-2"))
            ).isError
        )

        val dispatch = GitHubEngineeringScenario(
            challenges.single { it.id.endsWith(":unknown-dispatch") },
            "PKG-SAFE"
        )
        val uncertain = dispatch.tool.execute("dispatch", engineeringArgs("dispatch"))
        assertTrue(uncertain.isError)
        assertEquals("SIMULATED_DISPATCH_OUTCOME_UNKNOWN", uncertain.errorCode)
        assertTrue(dispatch.tool.execute("duplicate", engineeringArgs("dispatch")).isError)
    }
}
