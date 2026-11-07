package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

class GitHubContinuityUpgradeTest {
    @Test fun completeAuthorizedApiCoverageCanFinishWithoutInventingDeviceAcceptance() {
        val tool = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("github_context", "Repository reads", Json.parseToJsonElement("""{"type":"object","properties":{"action":{"enum":["status","branches"]}}}""").jsonObject)
        fun receipt(action: String) = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("dispatched", action == "branches")
            put("evidence_origin", if (action == "branches") "LIVE" else "LOCAL")
            put("tool", "github_context")
            put("arguments", buildJsonObject { put("action", action) })
            put("result", JsonObject(emptyMap()))
        }
        val records = mapOf("one" to receipt("status"), "two" to receipt("branches"))
        val (text, report) = GitHubEvidencePolicy.testing(records, 1000, 0, "Test all GitHub tools", listOf(tool))
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        assertTrue(text.contains("Task outcome: acceptance verified"))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools and Android device behavior", records, listOf(tool)))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools and fix all failures", records, listOf(tool)))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools", records - "two", listOf(tool)))
        val withoutProvenance = records + ("two" to JsonObject(records.getValue("two") - "dispatched" - "evidence_origin"))
        assertFalse(GitHubTestChecklist.apiComplete("Test all GitHub tools", withoutProvenance, listOf(tool)))
        val cached = records + ("two" to JsonObject(records.getValue("two") + ("evidence_origin" to JsonPrimitive("CACHED"))))
        assertEquals(JsonPrimitive(false), GitHubEvidencePolicy.testing(cached, 1000, 0, "Test all GitHub tools", listOf(tool)).second["completion_verified"])
    }

    @Test fun testAcceptanceRequiresAllJobPagesAtTheImplementedHead() {
        val sha = "a".repeat(40)
        fun receipt(tool: String, action: String, data: JsonObject) = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("tool", tool)
            put("source_head_sha", sha)
            put("arguments", buildJsonObject { put("action", action) })
            put("result", data)
        }
        fun page(page: Int, conclusion: String) = buildJsonObject {
            put("run_id", 7)
            put("run_attempt", 2)
            put("head_sha", sha)
            put("page", page)
            put("has_more", page == 1)
            put("total_count", 2)
            put(
                "jobs",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", page)
                            put("name", "Unit tests $page")
                            put("status", "completed")
                            put("conclusion", conclusion)
                        }
                    )
                )
            )
        }
        val task = "Implement the feature"
        val acceptance = buildJsonObject {
            put("implementation_receipts", JsonArray(listOf(JsonPrimitive("commit"))))
            put("test_receipts", JsonArray(listOf(JsonPrimitive("first"), JsonPrimitive("last"))))
        }
        val answer = buildJsonObject { put("acceptance", buildJsonObject { put(GitHubRequestCompletion.id(task), acceptance) }) }
        val claims = listOf(buildJsonObject { put("status", "VERIFIED") })
        val records = linkedMapOf("commit" to receipt("github_commit", "commit", JsonObject(emptyMap())), "last" to receipt("github_ci", "jobs", page(2, "success")))
        assertEquals(JsonPrimitive(false), GitHubRequestCompletion.evaluate(task, claims, answer, records)["completion_verified"])
        records["first"] = receipt("github_ci", "jobs", page(1, "failure"))
        assertEquals(JsonPrimitive(false), GitHubRequestCompletion.evaluate(task, claims, answer, records)["completion_verified"])
        records["first"] = receipt("github_ci", "jobs", page(1, "success"))
        assertEquals(JsonPrimitive(true), GitHubRequestCompletion.evaluate(task, claims, answer, records)["completion_verified"])
        records["head"] = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("head_observation", true)
            put("source_head_sha", "b".repeat(40))
        }
        assertEquals(JsonPrimitive(false), GitHubRequestCompletion.evaluate(task, claims, answer, records)["completion_verified"])
    }

    @Test fun diagnosisGroupsEvidenceWithoutPretendingToKnowTheRootCause() {
        val logs = listOf("Test failed in FooTest.kt:12", "Test failed in FooTest.kt:17", "error: no space left on device")
        val groups = GitHubCiDiagnosis.group(
            logs.mapIndexed { index, text ->
                buildJsonObject {
                    put("line", index + 1)
                    put("text", text)
                }
            },
            setOf("app/FooTest.kt", "app/Other.kt")
        )
        assertEquals(2, groups.size)
        val test = groups.first().jsonObject
        assertEquals(JsonPrimitive(2), test["occurrences_in_retained_excerpt"])
        assertEquals(JsonArray(listOf(JsonPrimitive("app/FooTest.kt"))), test["matching_changed_paths"])
        assertEquals(JsonPrimitive("LOG_PATTERN_HYPOTHESIS_NOT_CONFIRMED_ROOT_CAUSE"), test["confidence"])
        assertEquals(JsonPrimitive("INFRASTRUCTURE"), groups.last().jsonObject["category"])
    }

    @Test fun lostWriteThenTokenRotationAndProcessRestartDoesNotRepeat() = runTest {
        val directory = Files.createTempDirectory("github-rotation").toFile()
        try {
            val oldIdentity = GitHubRepositoryStateStore.digest("old-token")
            val legacyScope = "$oldIdentity:connection:chat:task"
            val args = buildJsonObject { put("sha", "a".repeat(40)) }
            var writes = 0
            try {
                GitHubExecutionJournal(GitHubRepositoryStateStore(directory), legacyScope).run("commit", args) {
                    writes++
                    throw IOException("accepted remotely, response lost")
                }
            } catch (_: IOException) { }
            val stable = GitHubAuthorizationIdentity.scope("account-42", "connection")
            val result = GitHubExecutionJournal(GitHubRepositoryStateStore(directory), "$stable:connection:chat:task", legacyScopes = listOf(legacyScope)).run("commit", args) {
                writes++
                JsonObject(emptyMap())
            }.jsonObject
            assertEquals(1, writes)
            assertEquals(JsonPrimitive("RECONCILE_REQUIRED"), result["status"])
            assertFalse(stable == GitHubAuthorizationIdentity.scope("account-43", "connection"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun compactedFencesSurviveReceiptLossAndRestart() = runTest {
        val directory = Files.createTempDirectory("github-claims").toFile()
        try {
            val store = GitHubRepositoryStateStore(directory, publicationClaimLimit = 2)
            val keys = (1..7).map { "execution:" + GitHubRepositoryStateStore.digest("write-$it") }
            for (key in keys) {
                store.write("account", key, buildJsonObject { put("status", "RUNNING") })
                // Ordinary cache cleanup cannot remove critical receipts. Model
                // a missing body independently to exercise the archived fence.
                val filename = GitHubRepositoryStateStore.digest("account:$key") + ".record"
                assertTrue(directory.resolve("execution-receipts").resolve(filename).delete())
            }
            val reopened = GitHubRepositoryStateStore(directory, publicationClaimLimit = 2)
            for (key in keys) assertEquals(JsonPrimitive("RECONCILE_REQUIRED"), reopened.read("account", key)?.get("status"))
            assertTrue((reopened.publicationCapacity()["archived"] as JsonPrimitive).content.toInt() > 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cacheCleanupCannotRemoveProtectedExecutionReceipts() = runTest {
        val directory = Files.createTempDirectory("github-protected-claims").toFile()
        try {
            val store = GitHubRepositoryStateStore(directory, publicationClaimLimit = 2)
            val keys = (1..7).map { "execution:" + GitHubRepositoryStateStore.digest("write-$it") }
            val pending = buildJsonObject { put("status", "RUNNING") }
            for (key in keys) {
                store.write("account", key, pending)
                store.write("cache", key.removePrefix("execution:"), buildJsonObject { put("read", true) })
                store.remove("cache", key.removePrefix("execution:"))
                assertTrue(runCatching { store.remove("account", key) }.exceptionOrNull() is IllegalArgumentException)
            }
            val reopened = GitHubRepositoryStateStore(directory, publicationClaimLimit = 2)
            for (key in keys) {
                assertEquals(null, reopened.read("cache", key.removePrefix("execution:")))
                assertEquals(pending, reopened.read("account", key))
            }
            assertTrue((reopened.publicationCapacity()["archived"] as JsonPrimitive).content.toInt() > 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun generalAnswerCompletionDoesNotGrantEngineeringAcceptance() {
        val verified = listOf(buildJsonObject { put("status", "VERIFIED") })
        val answer = buildJsonObject { put("questions", JsonArray(emptyList())) }
        assertEquals(JsonPrimitive(true), GitHubRequestCompletion.evaluate("Where is authentication implemented?", verified, answer, emptyMap())["completion_verified"])
        for (question in listOf("Is authentication implemented?", "Does this function validate access?", "Can you explain the refresh flow?")) {
            assertEquals(JsonPrimitive(true), GitHubRequestCompletion.evaluate(question, verified, answer, emptyMap())["completion_verified"])
        }
        for (request in listOf("Can you fix authentication?", "Could you please run the tests?")) {
            assertEquals(JsonPrimitive(false), GitHubRequestCompletion.evaluate(request, verified, answer, emptyMap())["completion_verified"])
        }
        val engineering = GitHubRequestCompletion.evaluate("Implement authentication", verified, answer, emptyMap())
        assertEquals(JsonPrimitive(true), engineering["answer_verified"])
        assertEquals(JsonPrimitive(false), engineering["completion_verified"])
        assertEquals(JsonPrimitive(false), GitHubRequestCompletion.evaluate("Where is authentication implemented?", listOf(buildJsonObject { put("status", "STALE") }), answer, emptyMap())["completion_verified"])
    }

    @Test fun byteLimitAppliesBeforeParsingAndRejectsChunkedOversize() = runTest {
        val http = HttpClient(MockEngine { respond("x".repeat(4 * 1024 * 1024 + 1), headers = headersOf("Content-Type", "application/json")) })
        try {
            val failure = runCatching { http.githubRequest("https://api.github.com/test") {} }.exceptionOrNull()
            assertTrue(failure is GitHubResponseTooLarge)
        } finally {
            http.close()
        }
    }

    @Test fun calculationRequiresVerifiedNumbersAndHandlesScaledZero() {
        fun calculate(denominator: String, status: String) = GitHubVerifiedCalculation.evaluate(Json.parseToJsonElement("""{"operation":"ratio","sources":[{"value":10},{"value":$denominator}]}""").jsonObject) { input ->
            buildJsonObject {
                put("status", status)
                put("observed", input.getValue("value"))
            }
        }
        assertEquals(JsonPrimitive("VERIFIED"), calculate("2", "VERIFIED")["status"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), calculate("0.0", "VERIFIED")["status"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), calculate("2", "STALE")["status"])
    }

    @Test fun taskAliasesAndDiagnosticsDoNotInventTerminalCauses() {
        assertTrue(GitHubTaskContinuity.reuse("test all", "test all integration tools"))
        assertFalse(GitHubTaskContinuity.reuse("start a new test", "test all tools"))
        assertTrue(GitHubTaskContinuity.diagnostic("Why did the previous response stop?"))
        val diagnostic = GitHubTaskContinuity.explain(buildJsonObject { put("verification_failure", "GITHUB_TASK_INCOMPLETE") }, emptyMap())
        assertTrue(diagnostic.contains("GITHUB_TASK_INCOMPLETE"))
        assertTrue(diagnostic.contains("partial task completion"))
        assertFalse(diagnostic.contains("formatting rules"))
    }

    @Test fun authorizedActionsIncludeJobsLogsArtifactsAndReadPrerequisites() {
        val definition = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_ci", "github_ci")
        val rows = GitHubTestChecklist.rows(emptyMap(), listOf(definition)).map { it.jsonObject }
        for (action in listOf("jobs", "logs", "artifacts")) {
            val row = rows.single { it["id"] == JsonPrimitive("github_ci:$action") }
            assertEquals(JsonPrimitive("BLOCKED"), row["status"])
            assertTrue((row["blocker"] as JsonPrimitive).content.contains("observed workflow"))
        }
    }

    @Test fun githubReadBudgetCanContinueBeyondTwentyFourUniqueCalls() = runTest {
        var rounds = 0
        var operations = 0
        val session = object : dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession {
            override fun streamRound(tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>, exchanges: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange>) = kotlinx.coroutines.flow.flow {
                if (rounds++ < 30 && tools.isNotEmpty()) {
                    emit(
                        dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.ToolCall(
                            "page-$rounds",
                            "github_context",
                            buildJsonObject {
                                put("action", "branches")
                                put("page", rounds)
                            }
                        )
                    )
                } else {
                    emit(dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.TextDelta("Finished the requested pages."))
                }
                emit(dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Completed)
            }
        }
        val tool = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
            override val definition = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("github_context", "Read branches", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): dev.chungjungsoo.gptmobile.data.agent.AgentToolResult {
                operations++
                return dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(callId, dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json(arguments), false)
            }
        }
        dev.chungjungsoo.gptmobile.data.agent.AgentRunner(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxRounds = 40, maxToolCalls = 40, maxObservedToolCalls = 80, githubTask = true)).run(session, listOf(tool)).collect { }
        assertEquals(30, operations)
    }

    @Test fun sharedAndroidGatewayCheckContract() {
        val fixture = listOf(java.io.File("gateway/contracts/github-ci-eligibility-v1.json"), java.io.File("../gateway/contracts/github-ci-eligibility-v1.json")).first { it.isFile }
        val cases = Json.parseToJsonElement(fixture.readText()).jsonObject["cases"] as JsonArray
        for (raw in cases) {
            val case = raw.jsonObject
            val node = JsonObject(case.filterKeys { it != "passed" } + ("__typename" to JsonPrimitive("CheckRun")))
            val response = """{"data":{"repository":{"pullRequest":{"state":"OPEN","isDraft":false,"headRefOid":"${"a".repeat(40)}","baseRefOid":"${"b".repeat(40)}","mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","reviewDecision":"APPROVED","commits":{"nodes":[{"commit":{"oid":"${"a".repeat(40)}","statusCheckRollup":{"state":"SUCCESS","contexts":{"totalCount":2,"pageInfo":{"hasNextPage":false},"nodes":[$node,{"__typename":"CheckRun","name":"baseline","status":"COMPLETED","conclusion":"SUCCESS","isRequired":true}]}}}}]}}}}}"""
            val result = gitHubPullRequestHealth(Json.parseToJsonElement(response).jsonObject, "a".repeat(40))
            assertEquals(case["name"].toString(), case["passed"], JsonPrimitive(result.requestedValidationPassed))
        }
    }

    private fun healthPage(start: Int, end: Int, more: Boolean, head: String = "a".repeat(40), requiredConclusion: String = "SUCCESS"): JsonObject {
        val nodes = (start until end).joinToString(",") { """{"id":"check-$it","__typename":"CheckRun","name":"check-$it","status":"COMPLETED","conclusion":"${if (it == 0) requiredConclusion else "SUCCESS"}","isRequired":true}""" }
        return Json.parseToJsonElement("""{"data":{"repository":{"pullRequest":{"state":"OPEN","isDraft":false,"headRefOid":"$head","baseRefOid":"${"b".repeat(40)}","mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","reviewDecision":"APPROVED","commits":{"nodes":[{"commit":{"oid":"$head","statusCheckRollup":{"state":"SUCCESS","contexts":{"totalCount":150,"pageInfo":{"hasNextPage":$more,"endCursor":"$end"},"nodes":[$nodes]}}}}]}}}}}""").jsonObject
    }

    @Test fun moreThanHundredChecksAreCollectedAndHeadIsFenced() = runTest {
        var requests = 0
        val collected = collectGitHubHealth { cursor ->
            requests++
            if (cursor == null) healthPage(0, 100, true) else healthPage(100, 150, false)
        }
        assertEquals(3, requests)
        assertTrue(gitHubPullRequestHealth(collected, "a".repeat(40)).eligible)
        var firstReads = 0
        val moved = collectGitHubHealth { cursor -> if (cursor == null) healthPage(0, 100, true, head = if (++firstReads == 1) "a".repeat(40) else "c".repeat(40)) else healthPage(100, 150, false) }
        assertFalse(gitHubPullRequestHealth(moved, "a".repeat(40)).eligible)
    }

    @Test fun skippedOrNeutralRequiredChecksNeverCountAsFullyGreen() = runTest {
        for (conclusion in listOf("SKIPPED", "NEUTRAL")) {
            val collected = collectGitHubHealth { cursor -> if (cursor == null) healthPage(0, 100, true, requiredConclusion = conclusion) else healthPage(100, 150, false) }
            val health = gitHubPullRequestHealth(collected, "a".repeat(40))
            assertTrue(health.githubPermitsMerging)
            assertFalse(health.requestedValidationPassed)
            assertFalse(health.eligible)
        }
    }
}
