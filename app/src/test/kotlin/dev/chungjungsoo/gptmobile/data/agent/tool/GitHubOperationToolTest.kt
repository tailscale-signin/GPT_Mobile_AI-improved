package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolRoundAdmission
import dev.chungjungsoo.gptmobile.data.agent.planToolBatch
import dev.chungjungsoo.gptmobile.data.agent.toolRepeatKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationToolTest {
    @Test fun invalidSearchQueriesAreRejectedBeforeDispatch() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_search", backend, "search-validation")
        for (input in listOf("{}", "{\"query\":\"   \"}", "{\"query\":false}", "{\"query\":\"${"x".repeat(201)}\"}")) {
            val result = operation.execute("invalid", args(input))
            assertTrue(result.isError)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
        }
        assertTrue(backend.calls.isEmpty())
        operation.execute("valid", args("{\"query\":\"${"x".repeat(200)}\"}"))
        assertEquals(1, backend.calls.size)
    }

    @Test fun incompleteRepositoryOverridesAreRejectedWithoutDispatch() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_context", backend, "validation-test")
        val result = operation.execute("invalid", args("""{"action":"status","owner":"team"}"""))
        assertTrue(result.isError)
        assertTrue((result.content as ToolResultContent.Text).text.contains("both owner and repo"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun omittedRepositoryUsesPinnedContext() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_context", backend, "context-test")
        operation.execute("context", args("""{"action":"status"}"""))
        assertEquals(1, backend.calls.size)
        assertEquals("repo_status", backend.calls.single()["action"]?.jsonPrimitive?.content)
    }

    @Test fun compactContextCanRequestRemoteRepositoryStatus() {
        val arguments = GitHubOperationTool.nativeArguments("github_context", buildJsonObject { put("action", "status") })!!
        assertEquals("repo_status", arguments["action"]?.jsonPrimitive?.content)
        assertTrue(GitHubOperationTool.definition("github_context").inputSchema.toString().contains("status"))
    }
    private val sha = "a".repeat(40)
    private class Backend : AgentTool {
        override val definition = AgentToolDefinition("github", "Legacy backend", buildJsonObject {}, modelVisible = false)
        val calls = mutableListOf<JsonObject>()
        var body: JsonElement = buildJsonObject { put("ok", true) }
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            calls += arguments
            delay(1)
            return AgentToolResult(callId, ToolResultContent.Json(body), false)
        }
    }
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun result(value: AgentToolResult) = (value.content as ToolResultContent.Json).value.jsonObject
    private fun continuation(value: JsonObject) = value.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
    private fun valid(operation: String, value: JsonObject) = assertEquals("Advertised continuation must validate against its own facade", emptyList<String>(), ToolArgumentValidator.errors(value, GitHubOperationTool.definition(operation).inputSchema))

    @Test fun ordinaryRepositorySchemasStayBelowOneThousandEstimatedTokensAndHideTheLegacyMonolith() {
        val definitions = GitHubOperationTool.names.map { GitHubOperationTool.definition(it) }
        val catalog = requireNotNull(ProgressiveToolCatalog.create(listOf(Backend().definition) + definitions, "Inspect this repository"))
        val selected = catalog.selected(listOf(Backend().definition) + definitions).filter { GitHubOperationTool.operationName(it.name) != null }
        assertEquals(setOf("github_context", "github_search", "github_read"), selected.map { it.name }.toSet())
        val wire = JsonArray(
            selected.map {
                buildJsonObject {
                    put("name", it.name)
                    put("description", it.description)
                    put("parameters", it.inputSchema)
                }
            }
        ).toString()
        val estimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(wire)
        println("GitHub ordinary schema: ${wire.toByteArray().size} UTF-8 bytes, $estimate estimated tokens, ${selected.size} facade definitions")
        assertTrue("Ordinary schema estimate was $estimate", estimate < 1000)
        assertFalse(catalog.selected(listOf(Backend().definition) + definitions).any { it.name == "github" })
        assertEquals(setOf("github_context", "github_search", "github_read", "github_stage_patch", "github_commit", "github_write"), GitHubOperationTool.taskTools("Fix code in the repository, create a branch and push the commit"))
    }

    @Test fun directWriteAdvertisesGuardedMultiFileCommits() {
        val schema = GitHubOperationTool.definition("github_write").inputSchema
        val arguments = args(
            """{"action":"commit","branch":"fix/example","expected_head_sha":"$sha","message":"Fix code","files":[{"path":"src/Fix.kt","content":"updated","expected_blob_sha":"$sha"}]}"""
        )
        assertEquals(emptyList<String>(), ToolArgumentValidator.errors(arguments, schema))
        assertEquals("commit_files", GitHubOperationTool.nativeArguments("github_write", arguments)?.get("action")?.jsonPrimitive?.content)
        assertTrue(GitHubOperationTool.names.contains("github_write"))
        assertTrue(GitHubOperationTool.taskTools("Write files and push changes in GitHub").contains("github_write"))
    }

    @Test fun repairsAndExactModelFacingWriteNamesRetainExecutableSchemas() {
        for (task in listOf("Repair the repository failures", "Correct code in this repo", "Apply all these changes to GitHub", "Enable github_write__github_api for the current task")) {
            assertTrue("Missing write contract for: $task", "github_write" in GitHubOperationTool.taskTools(task))
        }
        val args = args("""{"action":"capabilities"}""")
        assertTrue(ToolArgumentValidator.errors(args, GitHubOperationTool.definition("github_context").inputSchema).isEmpty())
        assertEquals("write_capabilities", GitHubOperationTool.nativeArguments("github_context", args)?.get("action")?.jsonPrimitive?.content)
    }

    @Test fun directWriteRejectsMalformedFileListsBeforeDispatch() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "write-validation")
        val invalid = args("""{"branch":"fix/example","files":[{"path":"src/Fix.kt"}]}""")
        val result = tool.execute("invalid-write", invalid)
        assertTrue(result.isError)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun deletionSchemaRequiresExplicitIntentAndBlobWithoutReplacementContent() {
        val schema = GitHubOperationTool.definition("github_write").inputSchema
        fun arguments(file: String) = args("""{"branch":"fix/example","expected_head_sha":"$sha","message":"Delete file","files":[$file]}""")
        val valid = arguments("""{"path":"old.txt","delete":true,"expected_blob_sha":"$sha"}""")
        assertTrue(ToolArgumentValidator.errors(valid, schema).isEmpty())
        for (file in listOf(
            """{"path":"old.txt"}""",
            """{"path":"old.txt","delete":false}""",
            """{"path":"old.txt","delete":true}""",
            """{"path":"old.txt","delete":true,"expected_blob_sha":"$sha","content":"replacement"}"""
        )) {
            assertTrue(ToolArgumentValidator.errors(arguments(file), schema).isNotEmpty())
        }
        assertTrue("github_write" in GitHubOperationTool.taskTools("Delete old.txt in the GitHub repository"))
    }

    @Test fun directWriteRequiresAllCommitPreconditions() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "missing-fields")
        for (raw in listOf(
            """{"branch":"fix/example","message":"Update","files":[{"path":"new.txt","content":"text"}]}""",
            """{"expected_head_sha":"$sha","message":"Update","files":[{"path":"new.txt","content":"text"}]}""",
            """{"branch":"fix/example","expected_head_sha":"$sha","files":[{"path":"new.txt","content":"text"}]}""",
            """{"branch":"fix/example","expected_head_sha":"$sha","message":"Update"}"""
        )) {
            val result = tool.execute("missing", args(raw))
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun directWriteRejectsMalformedHeadShaWithoutDispatch() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "invalid-head")
        val result = tool.execute(
            "invalid-head",
            args("""{"branch":"fix/example","expected_head_sha":"main","message":"Update","files":[{"path":"new.txt","content":"text"}]}""")
        )
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun directWriteRoutesThroughAuthorizedBackend() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "write-test")
        val arguments = args(
            """{"action":"commit","branch":"fix/example","expected_head_sha":"$sha","message":"Fix code","files":[{"path":"src/Fix.kt","content":"updated"}]}"""
        )
        tool.execute("write", arguments)
        assertEquals(1, backend.calls.size)
        assertEquals("commit_files", backend.calls.single()["action"]?.jsonPrimitive?.content)
        assertEquals("fix/example", backend.calls.single()["branch"]?.jsonPrimitive?.content)
    }

    @Test fun readContinuationPreservesCommitAndLongLineOffsetsUsingOnlyAdvertisedFields() = runBlocking {
        val backend = Backend()
        backend.body = buildJsonObject {
            put("commit_sha", sha)
            put(
                "ranges",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "src/Long.kt")
                            put("content", "first part")
                            put(
                                "continuation",
                                buildJsonObject {
                                    put("path", "src/Long.kt")
                                    put("start_line", 2)
                                    put("end_line", 4)
                                    put("start_offset", 300)
                                    put("commit_sha", sha)
                                }
                            )
                        }
                    )
                )
            )
        }
        val operation = GitHubOperationTool("github_read", backend, "scope")
        val initial = args("""{"owner":"team","repo":"app","ranges":[{"path":"src/Long.kt","start_line":2,"end_line":4}]}""")
        val next = continuation(result(operation.execute("first", initial)))
        valid("github_read", next)
        assertFalse("path" in next)
        assertFalse("start_offset" in next)
        assertEquals(sha, next["commit_sha"]?.jsonPrimitive?.content)
        operation.execute("next", next)
        val native = backend.calls.last()
        assertEquals("read_ranges", native["action"]?.jsonPrimitive?.content)
        assertEquals(300, native["ranges"]!!.jsonArray.single().jsonObject["start_offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, native["ranges"]!!.jsonArray.single().jsonObject["end_line"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun searchAndChangedFileContinuationsRoundtripSnapshotAndCursor() = runBlocking {
        for ((operation, initial, nativeContinuation, expectedAction) in listOf(
            listOf("github_search", """{"query":"Needle","mode":"references","path_prefix":"src/"}""", """{"action":"search_repository","commit_sha":"$sha","index_cursor":24,"page":2,"query":"Needle","mode":"references","path_prefix":"src/"}""", "search_repository"),
            listOf("github_context", """{"action":"changes","base_sha":"${"b".repeat(40)}"}""", """{"action":"changed_since","base":"${"b".repeat(40)}","commit_sha":"$sha","page":2}""", "changed_since")
        )) {
            val backend = Backend().also { it.body = buildJsonObject { put("continuation", args(nativeContinuation)) } }
            val tool = GitHubOperationTool(operation, backend, "scope")
            val next = continuation(result(tool.execute("first", args(initial))))
            valid(operation, next)
            assertEquals(sha, next["commit_sha"]?.jsonPrimitive?.content)
            tool.execute("next", next)
            assertEquals(expectedAction, backend.calls.last()["action"]?.jsonPrimitive?.content)
            assertEquals("2", backend.calls.last()["page"]?.jsonPrimitive?.content)
            if (operation == "github_search") {
                assertEquals("24", backend.calls.last()["index_cursor"]?.jsonPrimitive?.content)
            } else {
                assertEquals("b".repeat(40), backend.calls.last()["base"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test fun independentPrFilesAndChecksContinuationsStayBoundToTheSameHead() = runBlocking {
        val backend = Backend().also {
            it.body = args("""{"files_continuation":{"action":"pr_context","pull_number":17,"page":2,"expected_head":"$sha","expected_base_sha":"${"b".repeat(40)}","include_patches":false,"include_files":true,"include_checks":false},"checks_continuation":{"action":"pr_context","pull_number":17,"page":1,"check_page":3,"expected_head":"$sha","expected_base_sha":"${"b".repeat(40)}","include_patches":false,"include_files":false,"include_checks":true}}""")
        }
        val tool = GitHubOperationTool("github_pr", backend, "scope")
        val initial = result(tool.execute("first", args("""{"action":"context","pull_number":17}""")))
        val continuations = initial["remote_continuations"]!!.jsonArray
        assertEquals(2, continuations.size)
        for (entry in continuations) {
            val next = entry.jsonObject["call"]!!.jsonObject["arguments"]!!.jsonObject
            valid("github_pr", next)
            assertEquals("context", next["action"]?.jsonPrimitive?.content)
            assertEquals(sha, next["expected_head_sha"]?.jsonPrimitive?.content)
            assertEquals("b".repeat(40), next["expected_base_sha"]?.jsonPrimitive?.content)
            assertEquals(entry.jsonObject["native"]!!.jsonObject["include_files"], next["include_files"])
            assertEquals(entry.jsonObject["native"]!!.jsonObject["include_checks"], next["include_checks"])
            tool.execute("page", next)
            assertEquals(sha, backend.calls.last()["expected_head"]?.jsonPrimitive?.content)
        }
    }

    @Test fun localPagesExposeEveryItemBeforeReturningAnyRemotePageCall() = runBlocking {
        val backend = Backend().also { target ->
            target.body = buildJsonObject {
                put("commit_sha", sha)
                put(
                    "files",
                    JsonArray(
                        (0 until 30).map { index ->
                            buildJsonObject {
                                put("filename", "file-$index.kt")
                                put("text", "x".repeat(240))
                            }
                        }
                    )
                )
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "search_repository")
                        put("commit_sha", sha)
                        put("index_cursor", 30)
                        put("query", "x")
                    }
                )
            }
        }
        val views = GitHubResultViews(pageBytes = 2048)
        val tool = GitHubOperationTool("github_search", backend, "task-A", views = views)
        var page = result(tool.execute("first", args("""{"query":"x"}""")))
        val seen = StringBuilder()
        var pages = 0
        while (true) {
            pages++
            assertTrue(page.toString().toByteArray().size <= 2048)
            seen.append(page["fragments"])
            if (page["local_page_complete"]!!.jsonPrimitive.boolean) break
            assertFalse("remote_continuations" in page)
            val next = continuation(page)
            assertEquals(setOf("view_cursor"), next.keys)
            valid("github_search", next)
            val local = tool.execute("local-$pages", next)
            assertFalse(local.dispatched)
            assertTrue(local.sharedResult)
            page = result(local)
            assertTrue(pages < 100)
        }
        assertTrue(pages > 1)
        for (index in 0 until 30) assertEquals(1, Regex("\"file-$index.kt\"").findAll(seen).count())
        assertEquals(1, backend.calls.size)
        val remote = continuation(page)
        valid("github_search", remote)
        assertEquals("30", remote["index_cursor"]?.jsonPrimitive?.content)
        tool.execute("remote", remote)
        assertEquals(2, backend.calls.size)
    }

    @Test fun savedCursorsCannotBeReusedAcrossTaskOrOperationScopes() = runBlocking {
        val backend = Backend().also { it.body = JsonPrimitive("captured ".repeat(2000)) }
        val views = GitHubResultViews(pageBytes = 2048)
        val read = GitHubOperationTool("github_read", backend, "task-A", views = views)
        val cursor = continuation(result(read.execute("first", buildJsonObject {})))
        val foreign = GitHubOperationTool("github_read", backend, "task-B", views = views).execute("foreign", cursor)
        val other = GitHubOperationTool("github_search", backend, "task-A", views = views).execute("other", cursor)
        assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        assertEquals("SAVED_VIEW_UNAVAILABLE", other.errorCode)
        assertFalse(foreign.dispatched)
        assertEquals(1, backend.calls.size)
    }

    @Test fun presentationCapacityFailureRetainsMutationReceiptAndCompletedDispatch() {
        val views = GitHubResultViews(pageBytes = 2048, capacityBytes = 100)
        val raw = AgentToolResult(
            "commit",
            ToolResultContent.Json(
                buildJsonObject {
                    put("sha", sha)
                    put("stage_id", "stage-42")
                    put("payload", "x".repeat(10000))
                }
            ),
            false,
            dispatched = true
        )
        val bounded = views.present("scope", "github_commit", "github_commit", raw)
        val envelope = result(bounded)
        assertTrue(bounded.isError)
        assertTrue(bounded.dispatched)
        assertTrue(envelope["upstream_completed"]!!.jsonPrimitive.boolean)
        assertEquals(sha, envelope["receipt"]!!.jsonObject["sha"]?.jsonPrimitive?.content)
        assertEquals("stage-42", envelope["receipt"]!!.jsonObject["stage_id"]?.jsonPrimitive?.content)
        assertTrue(envelope["next_step"]!!.jsonPrimitive.content.contains("never repeat a mutation"))
    }

    @Test fun onlyExplicitImmutableCommitReadsCanShareAcrossModelContributors() = runBlocking {
        val backend = Backend()
        val broker = SharedToolCallBroker()
        val tool = GitHubOperationTool("github_read", backend, "turn")
        val shared = broker.wrap("turn", "authorized-github", true, tool)
        val immutable = buildJsonObject {
            put("commit_sha", sha)
            put("evidence_id", "E1")
        }
        val results = coroutineScope { (1..5).map { index -> async { shared.execute("read-$index", immutable) } }.awaitAll() }
        assertEquals(1, backend.calls.size)
        assertEquals(4, results.count { it.sharedResult })
        repeat(2) { shared.execute("mutable-$it", buildJsonObject { put("evidence_id", "E1") }) }
        assertEquals(3, backend.calls.size)
        val commit = broker.wrap("turn", "authorized-write", true, GitHubOperationTool("github_commit", backend, "turn"))
        repeat(2) {
            commit.execute(
                "write-$it",
                buildJsonObject {
                    put("stage_id", "S1")
                    put("commit_sha", sha)
                }
            )
        }
        assertEquals(5, backend.calls.size)
    }

    @Test fun thirtyDifferentReadsAreAllowedWhileAnIdenticalOperationStopsAfterThree() {
        val calls = (0 until 30).map { index ->
            ProviderEvent.ToolCall(
                "read-$index",
                "github_read",
                buildJsonObject {
                    put("commit_sha", sha)
                    put("evidence_id", "E$index")
                }
            )
        }
        val admission = ToolRoundAdmission(maxCalls = 32, maxSameTool = 24)
        assertTrue(calls.all(admission::admit))
        val plan = planToolBatch(calls, 32, setOf("github_read"), emptyMap(), emptySet(), 24, ::toolRepeatKey)
        assertEquals(30, plan.executable.size)
        val repeated = (0..3).map { calls.first().copy(callId = "repeat-$it") }
        val repeatedPlan = planToolBatch(repeated, 32, setOf("github_read"), emptyMap(), emptySet(), 24, ::toolRepeatKey)
        assertEquals(3, repeatedPlan.executable.size)
        assertEquals(setOf("repeat-3"), repeatedPlan.perToolSuppressedIds)
        assertTrue(repeatedPlan.disabledNames.isEmpty())
        assertNotEquals(toolRepeatKey(calls[0]), toolRepeatKey(calls[1]))
    }

    @Test fun ciFailureAllowsLatestFailedRunOrAnExplicitRefWithoutInventingAnId() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_ci", backend, "turn")
        val latest = args("""{"action":"failure"}""")
        valid("github_ci", latest)
        tool.execute("latest", latest)
        assertEquals("ci_failure_digest", backend.calls.last()["action"]?.jsonPrimitive?.content)
        assertFalse("run_id" in backend.calls.last())
        tool.execute("branch", args("""{"action":"failure","ref":"fix/report"}"""))
        assertEquals("fix/report", backend.calls.last()["ref"]?.jsonPrimitive?.content)
    }
}
