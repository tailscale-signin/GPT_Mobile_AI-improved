package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.runBlocking
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
        tool("github_context", "status", "changes", "branch", "branch_dependencies", "recovery"),
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
    private fun result(call: ProviderEvent.ToolCall, fixtureBranch: String = branch): JsonObject {
        val branch = fixtureBranch
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

    @Test fun productionSchemasAdmitOwnedCleanupOnlyWithItsExactOriginLease() {
        val actual = tools.map { GitHubOperationTool.definition(it.name) }
        assertEquals(11, GitHubSelfTestFixtures.minimumCalls(actual))
        val definition = actual.single { it.name == "github_commit" }
        val cleanup = buildJsonObject {
            put("action", "delete_fixture_branch")
            put("branch", branch)
            put("origin_head_sha", origin)
            put("expected_head_sha", second)
        }
        assertTrue(ToolArgumentValidator.errors(cleanup, definition.inputSchema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(JsonObject(cleanup - "origin_head_sha"), definition.inputSchema).isNotEmpty())
    }

    @Test fun actualBoundToolReceiptsResumeTheFixtureWithoutReplayingAnyCompletedMutation() = runBlocking {
        val binding = GitHubEvidenceBook.digest("account:connection:conversation:task")
        val saved = linkedMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook(binding, "owner/repo", "main", tools.mapTo(mutableSetOf()) { it.name }, load = saved::get, save = { id, row -> saved[id] = row }, retainedIds = { saved.keys.filter { it.startsWith("ev_") } })
        var ledger = book()
        var ownedBranch = ""
        val dispatched = mutableListOf<ProviderEvent.ToolCall>()
        val boundTools = tools.associate { definition ->
            definition.name to GitHubBoundRepositoryTool(
                object : AgentTool {
                    override val definition = definition
                    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                        assertEquals(JsonPrimitive("owner"), arguments["owner"])
                        assertEquals(JsonPrimitive("repo"), arguments["repo"])
                        val call = ProviderEvent.ToolCall(callId, definition.name, arguments)
                        dispatched += call
                        val response = if (arguments["action"] == JsonPrimitive("status")) {
                            buildJsonObject {
                                put("branch", "main")
                                put("commit_sha", origin)
                                put("repository", buildJsonObject { put("default_branch", "main") })
                            }
                        } else {
                            result(call, ownedBranch)
                        }
                        return AgentToolResult(callId, ToolResultContent.Json(response), false)
                    }
                },
                GitHubRepositoryContext("owner", "repo", "main")
            )
        }
        val source = ProviderEvent.ToolCall("bound-source", "github_context", buildJsonObject { put("action", "status") })
        ledger.observe(source, boundTools.getValue(source.name).execute(source.callId, source.arguments))
        ownedBranch = "codex-self-test/" + GitHubEvidenceBook.digest(binding + ledger.snapshot().first.values.single().getValue("receipt_id").jsonPrimitive.content).take(20)
        repeat(11) { step ->
            val call = requireNotNull(GitHubSelfTestFixtures.next("Test all GitHub tools with writes", tools, ledger.snapshot().first)) { "Bound fixture stopped at $step" }
            ledger.observe(call, boundTools.getValue(call.name).execute(call.callId, call.arguments))
            if (step == 5) ledger = book().also { it.restore() }
        }
        val receipts = ledger.snapshot().first
        repeat(2) { assertNull(GitHubSelfTestFixtures.next("Test all GitHub tools with writes", tools, receipts)) }
        assertTrue(GitHubSelfTestFixtures.complete(receipts))
        assertEquals(1, dispatched.count { it.arguments["action"] == JsonPrimitive("delete_fixture_branch") })
        assertEquals(12, dispatched.size)
        assertTrue(receipts.values.all { it.getValue("arguments").jsonObject["owner"] == JsonPrimitive("owner") && it["action_identity_sha256"] != null })
    }

    @Test fun verifiedCleanupSurvivesLaterDuplicatePreflightRejectionsUntilANewCreation() {
        val records = completed()
        val originalCleanup = records.values.last()
        repeat(2) { attempt ->
            records["duplicate-$attempt"] = JsonObject(
                originalCleanup + mapOf(
                    "receipt_id" to JsonPrimitive("duplicate-$attempt"),
                    "success" to JsonPrimitive(false),
                    "dispatched" to JsonPrimitive(false),
                    "error_code" to JsonPrimitive("DUPLICATE_MUTATION_REJECTED"),
                    "result" to buildJsonObject { put("deleted", false) }
                )
            )
        }
        val checkpoint = GitHubSelfTestFixtures.checkpoint(records).single().jsonObject
        assertEquals(JsonPrimitive("CLEANED"), checkpoint["cleanup_status"])
        assertEquals(originalCleanup["receipt_id"], checkpoint["cleanup_receipt"])
        assertTrue(GitHubSelfTestFixtures.complete(records))
        assertNull(GitHubSelfTestFixtures.next("Test all tools with writes", tools, records))

        val oldCreation = records.values.first { it.getValue("arguments").jsonObject["action"] == JsonPrimitive("branch") }
        records["new-creation"] = JsonObject(oldCreation + ("receipt_id" to JsonPrimitive("new-creation")))
        val fixtures = GitHubSelfTestFixtures.checkpoint(records).map { it.jsonObject }
        assertEquals(JsonPrimitive("CLEANED"), fixtures.first()["cleanup_status"])
        assertEquals(JsonPrimitive("PENDING_OR_BLOCKED"), fixtures.last()["cleanup_status"])
        assertFalse(GitHubSelfTestFixtures.complete(records))
    }

    @Test fun uncertainDeletionResumesOnlyExactReadOnlyRecoveryAndPublishesItsProof() {
        val records = completed()
        val deletion = records.values.last()
        val deletionId = deletion.getValue("receipt_id").jsonPrimitive.content
        records[deletionId] = JsonObject(deletion + mapOf("success" to JsonPrimitive(false), "result" to buildJsonObject { put("deletion_state", "PENDING_VERIFICATION") }))
        val definitions = tools.map { GitHubOperationTool.definition(it.name) }
        val recovery = requireNotNull(GitHubSelfTestFixtures.next("Test all tools with writes", definitions, records))
        assertEquals("github_context", recovery.name)
        assertEquals(JsonPrimitive("recovery"), recovery.arguments["action"])
        assertEquals(JsonPrimitive(GitHubOwnedFixtures.PLAN), recovery.arguments["fixture_plan"])
        assertEquals(JsonPrimitive(branch), recovery.arguments["branch"])
        assertEquals(JsonPrimitive(origin), recovery.arguments["base_sha"])
        assertEquals(JsonPrimitive(second), recovery.arguments["commit_sha"])
        assertTrue(dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(recovery.name, recovery.arguments))
        assertTrue(ToolArgumentValidator.errors(recovery.arguments, definitions.first { it.name == recovery.name }.inputSchema).isEmpty())
        assertEquals(recovery, GitHubTestChecklist.nextCall("Test all GitHub tools with writes", definitions, records, 5))
        records[recovery.callId] = receipt(recovery, deletion.getValue("result").jsonObject)
        assertEquals(JsonPrimitive("CLEANED"), GitHubSelfTestFixtures.checkpoint(records).single().jsonObject["cleanup_status"])
        assertTrue(GitHubSelfTestFixtures.complete(records))
        assertNull(GitHubSelfTestFixtures.next("Test all tools with writes", definitions, records))
        assertEquals(1, records.values.count { it["arguments"]?.jsonObject?.get("action") == JsonPrimitive("delete_fixture_branch") })
    }

    @Test fun requiredLiveExistenceReadRunsBeforeCleanupAndIsRetiredAfterVerifiedAbsence() {
        val records = completed().also { it.remove(it.keys.last()) }
        val cachedCall = ProviderEvent.ToolCall(
            "fixture-dependencies",
            "github_context",
            buildJsonObject {
                put("action", "branch_dependencies")
                put("branch", branch)
                put("owner", "owner")
                put("repo", "repo")
            }
        )
        records[cachedCall.callId] = JsonObject(receipt(cachedCall, buildJsonObject { put("branch", branch) }) + ("evidence_origin" to JsonPrimitive("CACHED")))
        val definitions = tools.map { definition ->
            if (definition.name != "github_context") definition else definition.copy(inputSchema = JsonObject(definition.inputSchema + ("properties" to JsonObject(definition.inputSchema.getValue("properties").jsonObject + ("verify_live" to buildJsonObject { put("type", "boolean") })))))
        }
        val live = requireNotNull(GitHubTestChecklist.nextCall("Test all GitHub tools with writes", definitions, records, 100))
        assertEquals(JsonPrimitive("branch_dependencies"), live.arguments["action"])
        assertEquals(JsonPrimitive(true), live.arguments["verify_live"])
        records[live.callId] = receipt(live, buildJsonObject { put("branch", branch) })
        val cleanup = requireNotNull(GitHubTestChecklist.nextCall("Test all GitHub tools with writes", definitions, records, 99))
        assertEquals(JsonPrimitive("delete_fixture_branch"), cleanup.arguments["action"])
        records[cleanup.callId] = receipt(cleanup, result(cleanup))
        val retired = GitHubSelfTestFixtures.retiredReadPredicate(records)
        assertTrue(retired(records.getValue(cachedCall.callId)))
        assertFalse(retired(records.values.first { it["tool"] == JsonPrimitive("github_read") }))
        val next = GitHubTestChecklist.nextCall("Test all GitHub tools with writes", definitions, records, 98)
        assertFalse(next?.arguments?.get("action") == JsonPrimitive("branch_dependencies") && next.arguments["branch"] == JsonPrimitive(branch))
        assertEquals(JsonPrimitive(true), GitHubSelfTestFixtures.checkpoint(records).single().jsonObject["final_state_verified"])
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
        val noRecovery = tools.map { if (it.name == "github_context") tool("github_context", "status", "changes", "branch", "branch_dependencies") else it }
        assertFalse(GitHubContentFixture.available(noRecovery, branch, "main", origin))
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
