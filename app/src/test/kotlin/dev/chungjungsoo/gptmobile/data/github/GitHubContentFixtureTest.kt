package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubContentFixtureTest {
    private val origin = "a".repeat(40)
    private val first = "b".repeat(40)
    private val second = "c".repeat(40)
    private val branch = "codex-self-test/" + GitHubEvidenceBook.digest("tasksource").take(20)
    private fun tool(name: String, vararg actions: String) = AgentToolDefinition(
        name,
        name,
        buildJsonObject {
            put("type", "object")
            if (actions.isNotEmpty()) put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray(actions.map(::JsonPrimitive))) }) })
        }
    )
    private val tools = listOf(
        tool("github_context", "status", "changes", "branch"),
        tool("github_commit", "branch", "commit_staged_patch", "delete_fixture_branch", "delete_branch"),
        tool("github_write", "commit"),
        tool("github_stage_patch"),
        tool("github_read", "ranges"),
        tool("github_search", "search")
    )
    private fun receipt(call: ProviderEvent.ToolCall, result: JsonObject, success: Boolean = true) = buildJsonObject {
        put("receipt_id", call.callId)
        put("binding", "task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", success)
        put("dispatched", true)
        put("evidence_origin", "LIVE")
        put("tool", call.name)
        put("arguments", call.arguments)
        put("result", result)
    }
    private fun initial(sourceBranch: String = "main", sourceSha: String = origin) = linkedMapOf(
        "source" to receipt(
            ProviderEvent.ToolCall("source", "github_context", buildJsonObject { put("action", "status") }),
            buildJsonObject {
                put("branch", sourceBranch)
                put("commit_sha", sourceSha)
                put("repository", buildJsonObject { put("default_branch", "main") })
            }
        )
    )
    private fun result(call: ProviderEvent.ToolCall): JsonObject {
        val args = call.arguments
        val action = args["action"]?.jsonPrimitive?.content.orEmpty()
        val head = args["commit_sha"]?.jsonPrimitive?.content.orEmpty()
        val revised = head == second
        val content = GitHubOwnedFixtures.content(branch, revised)
        val blob = GitHubCommitReconciliation.blobSha(content)
        return when {
            action == "branch" && call.name == "github_commit" -> buildJsonObject {
                put("branch", branch)
                put("head_sha", origin)
                put("created", true)
                put("receipt_verified", true)
                put("ownership_verified", true)
                put("fixture_plan", GitHubOwnedFixtures.PLAN)
            }
            call.name == "github_write" || action == "commit_staged_patch" -> {
                val updated = action == "commit_staged_patch"
                buildJsonObject {
                    put("branch", branch)
                    put("sha", if (updated) second else first)
                    put("parent_sha", if (updated) first else origin)
                    put(
                        "files",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("path", GitHubOwnedFixtures.path(branch))
                                    put("blob_sha", GitHubCommitReconciliation.blobSha(GitHubOwnedFixtures.content(branch, updated)))
                                }
                            )
                        )
                    )
                }
            }
            call.name == "github_stage_patch" -> buildJsonObject {
                put("stage_id", "stage-owned")
                put("branch", branch)
                put("expected_head_sha", first)
                put("committed", false)
                put("files_changed", 1)
            }
            call.name == "github_read" -> buildJsonObject {
                put("commit_sha", head)
                put(
                    "items",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", GitHubOwnedFixtures.path(branch))
                                put("sha", blob)
                                put("content", content.trimEnd())
                            }
                        )
                    )
                )
            }
            call.name == "github_search" -> buildJsonObject {
                put("commit_sha", head)
                put("matches_complete", true)
                put(
                    "items",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", GitHubOwnedFixtures.path(branch))
                                put("sha", blob)
                                put(
                                    "matches",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("line", 3)
                                                put("text", "phase: ${if (revised) "staged" else "created"}")
                                            }
                                        )
                                    )
                                )
                            }
                        )
                    )
                )
            }
            action == "changes" -> buildJsonObject {
                put("base_sha", origin)
                put("head_sha", head)
                put("files_total", 1)
                put(
                    "files",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("filename", GitHubOwnedFixtures.path(branch))
                                put("sha", blob)
                                put("patch_available", true)
                                put("patch", "@@ -0,0 +1,3 @@\n+${content.replace("\n", "\n+")}")
                            }
                        )
                    )
                )
            }
            action == "delete_fixture_branch" -> buildJsonObject {
                put("branch", branch)
                put("origin_head_sha", origin)
                put("observed_head_sha", args.getValue("expected_head_sha"))
                put("ownership_verified", true)
                put("deleted", true)
                put("absence_verified", true)
            }
            else -> error("Unexpected fixture call $call")
        }
    }
    private fun completed(sourceBranch: String = "main"): LinkedHashMap<String, JsonObject> {
        val records = initial(sourceBranch)
        repeat(11) {
            val call = requireNotNull(GitHubSelfTestFixtures.next("Test all GitHub tools with writes", tools, records)) { "Fixture stopped at $it" }
            records[call.callId] = receipt(call, result(call))
        }
        assertNull(GitHubSelfTestFixtures.next("Test all GitHub tools with writes", tools, records))
        return records
    }

    @Test fun lifecycleExercisesRealContentSearchNonemptyDiffAndStagedCommitBeforeCleanup() {
        val records = completed()
        val fixture = GitHubSelfTestFixtures.checkpoint(records).single().jsonObject
        assertEquals(JsonPrimitive(true), fixture["content_verified"])
        assertEquals(JsonPrimitive(true), fixture["final_state_verified"])
        assertTrue(GitHubSelfTestFixtures.complete(records))
        assertEquals(11, GitHubSelfTestFixtures.minimumCalls(tools))
        val creation = records.values.first { (it["arguments"] as? JsonObject)?.get("action") == JsonPrimitive("branch") }
        assertEquals(JsonPrimitive(false), creation["arguments"]?.jsonObject?.get("select_created_branch"))
        val writes = records.values.filter { it["tool"] == JsonPrimitive("github_write") || it["tool"] == JsonPrimitive("github_stage_patch") }
        assertTrue(writes.all { it["arguments"]?.jsonObject?.get("branch") == JsonPrimitive(branch) })
        assertTrue(records.values.none { it["arguments"]?.jsonObject?.get("action") in listOf(JsonPrimitive("resolve"), JsonPrimitive("merge")) })
    }

    @Test fun selectedFeatureBranchCanFinishOwnedContentFixtureWithoutSwitchingOrTouchingItsSource() {
        val sourceBranch = "feature/in-progress"
        val records = completed(sourceBranch)
        val creation = records.values.single { it["arguments"]?.jsonObject?.get("action") == JsonPrimitive("branch") }
        val creationArguments = creation.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive(sourceBranch), creationArguments["from_branch"])
        assertEquals(JsonPrimitive(origin), creationArguments["expected_head_sha"])
        assertEquals(JsonPrimitive(GitHubOwnedFixtures.PLAN), creationArguments["fixture_plan"])
        assertEquals(JsonPrimitive(false), creationArguments["select_created_branch"])
        assertEquals(JsonPrimitive(sourceBranch), records.getValue("source").getValue("result").jsonObject["branch"])

        val staged = records.values.single { it["tool"] == JsonPrimitive("github_stage_patch") }.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive(branch), staged["branch"])
        assertEquals(JsonPrimitive(first), staged["expected_head_sha"])
        val commit = records.values.single { it["arguments"]?.jsonObject?.get("action") == JsonPrimitive("commit_staged_patch") }.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive("stage-owned"), commit["stage_id"])
        assertEquals(JsonPrimitive(first), commit["expected_head_sha"])
        val cleanup = records.values.last().getValue("arguments").jsonObject
        assertEquals(JsonPrimitive("delete_fixture_branch"), cleanup["action"])
        assertEquals(JsonPrimitive(branch), cleanup["branch"])
        assertEquals(JsonPrimitive(origin), cleanup["origin_head_sha"])
        assertEquals(JsonPrimitive(second), cleanup["expected_head_sha"])
        assertTrue(
            records.values.drop(1).all { record ->
                val arguments = record.getValue("arguments").jsonObject
                arguments["branch"] == null || arguments["branch"] == JsonPrimitive(branch)
            }
        )
        assertTrue(records.values.none { it["arguments"]?.jsonObject?.get("action") in listOf(JsonPrimitive("resolve"), JsonPrimitive("merge"), JsonPrimitive("delete_branch")) })
        val fixture = GitHubSelfTestFixtures.checkpoint(records).single().jsonObject
        assertEquals(JsonPrimitive(true), fixture["ownership_verified"])
        assertEquals(JsonPrimitive(true), fixture["content_verified"])
        assertEquals(JsonPrimitive(true), fixture["final_state_verified"])
        assertTrue(GitHubSelfTestFixtures.complete(records))
    }

    @Test fun featureBranchStartupRequiresPinnedSourceAndOwnedCleanupWhileLegacyFallbackRemainsDefaultOnly() {
        val legacyTools = tools.map { if (it.name == "github_commit") tool("github_commit", "branch", "commit_staged_patch", "delete_branch") else it }
        assertNull(GitHubSelfTestFixtures.next("Test all tools", legacyTools, initial("feature/in-progress")))
        assertNotNull(GitHubSelfTestFixtures.next("Test all tools", legacyTools, initial()))
        for (sha in listOf("", "a".repeat(39), "branch-name")) {
            assertNull(GitHubSelfTestFixtures.next("Test all tools", tools, initial("feature/in-progress", sha)))
        }
        assertNull(GitHubSelfTestFixtures.next("Test all tools read only", tools, initial("feature/in-progress")))
    }

    @Test fun jsonCheckpointRestorationResumesTheNextStageWithoutReplayingWrites() {
        val records = initial()
        repeat(6) {
            val call = requireNotNull(GitHubSelfTestFixtures.next("Test all tools", tools, records))
            records[call.callId] = receipt(call, result(call))
        }
        val restored = Json.parseToJsonElement(JsonObject(records).toString()).jsonObject.mapValues { it.value.jsonObject }
        val next = requireNotNull(GitHubSelfTestFixtures.next("Test all tools", tools, restored))
        assertEquals("github_commit", next.name)
        assertEquals(JsonPrimitive("commit_staged_patch"), next.arguments["action"])
        assertEquals(JsonPrimitive("stage-owned"), next.arguments["stage_id"])
        assertFalse(restored.values.any { it["arguments"] == next.arguments && it["tool"] == JsonPrimitive(next.name) })
    }

    @Test fun emptyOrMismatchedReadSearchAndDiffNeverSatisfyTheContentFixture() {
        val good = completed()
        for (tool in listOf("github_read", "github_search", "github_context")) {
            val candidate = good.entries.first { it.value["tool"] == JsonPrimitive(tool) && it.key != "source" }
            for (replacement in listOf(
                buildJsonObject {},
                buildJsonObject {
                    put("commit_sha", second)
                    put("items", JsonArray(emptyList()))
                    put("files_total", 0)
                    put("files", JsonArray(emptyList()))
                }
            )) {
                val changed = LinkedHashMap(good).apply { put(candidate.key, JsonObject(candidate.value + ("result" to replacement))) }
                assertFalse(tool, GitHubSelfTestFixtures.complete(changed))
                val fixture = GitHubSelfTestFixtures.checkpoint(changed).single().jsonObject
                assertEquals(JsonPrimitive(false), fixture["content_verified"])
                assertEquals(JsonPrimitive(true), fixture["final_state_verified"])
            }
        }
    }

    @Test fun unrelatedSuccessfulWritesCannotHideBehindAnOtherwiseCleanedFixture() {
        for (tool in listOf("github_write", "github_pr", "github_issue", "github_ci", "github_release")) {
            val extra = ProviderEvent.ToolCall(
                "unrelated",
                tool,
                buildJsonObject {
                    put(
                        "action",
                        when (tool) {
                            "github_write" -> "commit"
                            "github_release" -> "publish"
                            "github_ci" -> "dispatch"
                            else -> "create"
                        }
                    )
                    put("branch", "someone-elses-work")
                }
            )
            val records = completed().apply { put(extra.callId, receipt(extra, buildJsonObject { put("success", true) })) }
            assertFalse(tool, GitHubSelfTestFixtures.complete(records))
        }
    }

    @Test fun reserveAndFailurePathsCleanOnlyOwnedStateWithoutClaimingFullCompletion() {
        val records = initial()
        val create = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        records[create.callId] = receipt(create, result(create))
        assertNull(GitHubSelfTestFixtures.next("test all tools", tools, records, allowCreate = false, cleanupOnly = true))
        val early = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records, allowCreate = false, cleanupOnly = true, abortIncomplete = true))
        assertEquals(JsonPrimitive("delete_fixture_branch"), early.arguments["action"])
        assertEquals(JsonPrimitive(origin), early.arguments["expected_head_sha"])
        records[early.callId] = receipt(early, result(early))
        assertFalse(GitHubSelfTestFixtures.complete(records))
        assertEquals(JsonPrimitive("CLEANED"), GitHubSelfTestFixtures.checkpoint(records).single().jsonObject["cleanup_status"])
    }

    @Test fun queuedEngineeringReviewDoesNotPublishAgainOrDeleteItsDestination() {
        val records = initial()
        val create = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        records[create.callId] = receipt(create, result(create))
        val write = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        records[write.callId] = receipt(
            write,
            buildJsonObject {
                put("engineering_review_receipt", "pending")
                put("stage_id", "awaiting-approval")
            }
        )
        assertNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        assertNull(GitHubSelfTestFixtures.next("test all tools", tools, records, cleanupOnly = true, abortIncomplete = true))
        assertFalse(GitHubSelfTestFixtures.complete(records))
    }

    @Test fun uncertainPublicationReadsTheActualBranchShapeThenRequestsOnlyLeasedCleanup() {
        val records = initial()
        val create = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        records[create.callId] = receipt(create, result(create))
        val write = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        records[write.callId] = receipt(write, buildJsonObject { put("error", "lost response") }, success = false)
        val inspect = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        assertEquals("github_context", inspect.name)
        assertEquals(JsonPrimitive("branch"), inspect.arguments["action"])
        assertEquals(JsonPrimitive(true), inspect.arguments["verify_live"])
        records[inspect.callId] = receipt(
            inspect,
            buildJsonObject {
                put(
                    "branch",
                    buildJsonObject {
                        put("name", branch)
                        put("commit", buildJsonObject { put("sha", first) })
                    }
                )
            }
        )
        val cleanup = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, records))
        assertEquals(JsonPrimitive("delete_fixture_branch"), cleanup.arguments["action"])
        assertEquals(JsonPrimitive(first), cleanup.arguments["expected_head_sha"])
        assertEquals(JsonPrimitive(origin), cleanup.arguments["origin_head_sha"])
        assertFalse(GitHubSelfTestFixtures.complete(records))
    }

    @Test fun undiscoveredOrRevokedCleanupNeverPermitsContentFixtureCreation() {
        val noCleanup = tools.map { if (it.name == "github_commit") tool("github_commit", "branch", "commit_staged_patch") else it }
        assertNull(GitHubSelfTestFixtures.next("test all tools", noCleanup, initial()))
        assertNull(GitHubSelfTestFixtures.next("test all tools read-only", tools, initial()))
        assertNotNull(GitHubSelfTestFixtures.prerequisite("github_pr", "comment"))
        assertFalse(GitHubSelfTestFixtures.supports("github_pr", "comment"))
        assertFalse(GitHubSelfTestFixtures.supports("github_release", "publish"))
    }

    @Test fun localWatchAndPredispatchRejectionDoNotInventAnUncleanedRemoteResource() {
        for (tool in listOf("github_watch_checks", "github_decide")) {
            val records = completed()
            val call = ProviderEvent.ToolCall(
                tool,
                tool,
                buildJsonObject {
                    put("action", "watch")
                    put("pr_number", 1)
                }
            )
            records[tool] = receipt(call, buildJsonObject { put("subscription_id", "local") })
            assertTrue(tool, GitHubSelfTestFixtures.complete(records))
        }
        val records = completed()
        val rejected = ProviderEvent.ToolCall("denied", "github_issue", buildJsonObject { put("action", "create") })
        records[rejected.callId] = JsonObject(receipt(rejected, buildJsonObject {}, success = false) + ("dispatched" to JsonPrimitive(false)))
        assertTrue(GitHubSelfTestFixtures.complete(records))
        records[rejected.callId] = JsonObject(records.getValue(rejected.callId) + ("execution" to buildJsonObject { put("unknown_mutations", 1) }))
        assertFalse(GitHubSelfTestFixtures.complete(records))
    }
}
