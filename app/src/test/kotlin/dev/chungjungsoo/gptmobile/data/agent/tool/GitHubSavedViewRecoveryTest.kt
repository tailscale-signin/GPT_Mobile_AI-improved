package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.GitHubReplayRecovery
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultDeliveryProof
import dev.chungjungsoo.gptmobile.data.github.GitHubAuthorizationIdentity
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.github.GitHubSavedViewStore
import dev.chungjungsoo.gptmobile.data.github.currentGitHubVerification
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedViewRecoveryTest {
    private val sha = "a".repeat(40)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun body(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun continuation(result: AgentToolResult) = body(result).getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
    private class Backend : AgentTool {
        var calls = 0
        val payload = "captured mutation receipt ".repeat(500)
        override val definition = AgentToolDefinition("github", "Backend", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            calls++
            return AgentToolResult(callId, ToolResultContent.Json(JsonPrimitive(payload)), false)
        }
    }

    @Test fun completeVisibleFailuresCarryTheOriginalCodeWithoutAnUnusableSavedPage() {
        val views = GitHubResultViews(pageBytes = 2048)
        for (code in listOf("GITHUB_TRANSIENT_SERVER", "GITHUB_CIRCUIT_OPEN", "OUTPUT_LIMIT")) {
            val result = AgentToolResult("original", ToolResultContent.Text("Service returned an error"), true, errorCode = code)
            val page = views.present("conversation", "github_search", "github_search__bound", result)
            assertTrue(page.isError)
            assertEquals(code, page.errorCode)
            assertEquals(JsonPrimitive("Service returned an error"), body(page)["data"])
            assertEquals(JsonPrimitive(true), body(page)["local_page_complete"])
            assertFalse(body(page).containsKey("continuation_call"))
            assertFalse(body(page).containsKey("recovery_call"))
            assertFalse(body(page).containsKey("recovery_options"))
        }
    }

    @Test fun reopenedFailureKeepsItsFailedReceiptWithoutFilingTheOriginalIncidentAgain() {
        val views = GitHubResultViews(pageBytes = 4096)
        val operation = "github_search"
        val model = "github_search__native"
        val result = AgentToolResult("archive-source", ToolResultContent.Text("earlier failure ".repeat(2600)), true, errorCode = "GITHUB_TRANSIENT_SERVER")
        val first = views.present("conversation", operation, model, result)
        val arguments = body(first).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject
        val cursor = arguments.getValue("view_cursor").jsonPrimitive.content
        val page = views.read("conversation", operation, model, cursor, "local-read")
        val event = dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent("event", "run", 1, page.callId, null, null, operation, model, arguments.toString(), null, null, "FAILED")
        val diagnostics = dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
        assertTrue(page.isError)
        assertEquals(result.errorCode, page.errorCode)
        assertTrue(diagnostics.archivedSourceFailure(event, page))
        assertFalse(diagnostics.archivedSourceFailure(event, page.copy(dispatched = true)))
        assertFalse(diagnostics.archivedSourceFailure(event.copy(arguments = "{}"), page))
        assertFalse(diagnostics.archivedSourceFailure(event, page.copy(outputBudgetExhausted = true)))
        assertFalse(diagnostics.archivedSourceFailure(event, result))
    }

    @Test fun captureSurvivesConcurrentViewEvictionBeforeDurableSave() = runBlocking {
        val store = GitHubSavedViewStore(GitHubRepositoryStateStore(), "storage")
        val views = GitHubResultViews(pageBytes = 2048)
        val backend = Backend()
        var captured: Pair<String, JsonObject>? = null
        val source = AgentToolResult("initial", ToolResultContent.Json(JsonPrimitive(backend.payload)), false)
        val first = views.present("scope", "github_commit", "github_commit", source) { captured = it }
        repeat(33) { views.present("parallel-$it", "github_commit", "github_commit", source) }
        assertEquals(null, views.checkpoint("scope", first))
        val (id, snapshot) = requireNotNull(captured)
        store.save(id, snapshot)
        val tool = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::loadWithDiagnostics)
        val reopened = tool.execute("reopen", continuation(first))
        assertFalse(reopened.isError)
        assertFalse(reopened.dispatched)
        assertEquals(0, backend.calls)
    }

    @Test fun lostBodyReopensOnlyTheIssuedAuthorizedImmutableRangesAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("saved-source-recovery").toFile()
        try {
            val store = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "storage")
            val request = json("""{"owner":"o","repo":"r","ranges":[{"path":"A.kt","start_line":1,"end_line":20}]}""")
            val pinnedRequest = JsonObject(request + ("commit_sha" to JsonPrimitive(sha)))
            var requests = 0
            var correct = true
            var readCoverage: JsonObject? = null
            val blobIdentity = json("""{"owner":"o","repo":"r","path":"A.kt","total_lines":20,"blob_sha":"${"b".repeat(40)}","commit_sha":"$sha"}""")
            val item = json("""{"start_line":1,"end_line":20,"captured_start_line":1,"captured_end_line":20,"range_complete":true}""")
            val backend = object : AgentTool {
                override val definition = AgentToolDefinition("github", "Backend", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    requests++
                    readCoverage = dev.chungjungsoo.gptmobile.data.github.GitHubSourceReadCoverage.merge(readCoverage, blobIdentity, "source-id", listOf(item))
                    return AgentToolResult(
                        callId,
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("commit_sha", sha)
                                put("source_coverage", kotlinx.serialization.json.JsonArray(listOf(requireNotNull(readCoverage))))
                                put(
                                    "items",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("path", "A.kt")
                                                put("sha", "b".repeat(40))
                                                put("start_line", 1)
                                                put("content", (if (correct) "source text " else "other text ").repeat(1300))
                                            }
                                        )
                                    )
                                )
                            }
                        ),
                        false
                    )
                }
            }
            fun tool(store: GitHubSavedViewStore, scope: String, permitted: Boolean = true, operation: String = "github_read") =
                GitHubOperationTool(
                    operation,
                    backend,
                    scope,
                    views = GitHubResultViews(pageBytes = 2048),
                    loadSavedView = store::loadWithDiagnostics,
                    saveSavedView = store::save,
                    restorePinnedSource = store::restorePinnedSource,
                    validateSavedViewAccess = { check(permitted) }
                )
            val live = tool(store, "scope")
            val first = live.execute("source", request)
            assertEquals(JsonPrimitive(false), readCoverage?.get("already_read"))
            val args = body(first).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject
            val id = args.getValue("view_cursor").jsonPrimitive.content.substringBefore(':')
            val originalCall = ProviderEvent.ToolCall("original-page", "github_read", args)
            val page = live.execute(originalCall.callId, args)
            assertFalse(page.isError)
            val projected = page.copy(content = requireNotNull(GitHubReplayRecovery.compact(page.content, 128)))
            val history = mutableListOf(AgentToolExchange(listOf(originalCall), listOf(projected)))
            val file = java.io.File(directory, "saved-results/" + GitHubRepositoryStateStore.digest("storage:saved-view-body:$id") + ".record")
            check(file.delete())
            val restart = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "storage")
            val authorized = tool(restart, "scope")
            fun next(): ProviderEvent.ToolCall? {
                val state = GitHubReplayRecovery.State(history.flatMap { it.calls }, history.flatMap { it.results })
                return state.restoreMissingPage(state.pendingSavedPageDeliveries(history, emptyMap()), history, listOf(authorized.definition), 4096)
            }
            suspend fun execute(call: ProviderEvent.ToolCall): AgentToolResult {
                val result = authorized.execute(call.callId, call.arguments)
                history += AgentToolExchange(listOf(call), listOf(result))
                return result
            }
            val unavailable = execute(requireNotNull(next()))
            assertEquals("SAVED_VIEW_UNAVAILABLE", unavailable.errorCode)
            assertFalse(unavailable.dispatched)
            val recipe = body(unavailable).getValue("source_recovery_call").jsonObject
            assertEquals(pinnedRequest, recipe.getValue("arguments"))
            assertEquals(JsonPrimitive(false), body(unavailable)["mutation_replay_allowed"])
            assertFalse("source_recovery_call" in body(tool(restart, "other").execute("foreign", args)))
            assertFalse("source_recovery_call" in body(tool(restart, "scope", operation = "github_commit").execute("write", args)))
            assertFalse("source_recovery_call" in body(authorized.execute("forged", json("""{"view_cursor":"$id:9999:2048"}"""))))
            assertEquals("SAVED_VIEW_AUTHORIZATION_CHANGED", tool(restart, "scope", false).execute("revoked", args).errorCode)
            assertEquals(1, requests)
            val fresh = requireNotNull(next())
            assertEquals(pinnedRequest, fresh.arguments)
            correct = false
            val changed = execute(fresh)
            assertEquals(JsonPrimitive(false), changed.executionReceipt?.get("recovered_saved_view"))
            assertEquals(null, next())
            // Independent positive-case replay from the same interrupted checkpoint.
            history.removeAt(history.lastIndex)
            correct = true
            val recovered = execute(requireNotNull(next()))
            assertEquals(JsonPrimitive(true), readCoverage?.get("already_read"))
            assertTrue(recovered.dispatched)
            assertEquals(JsonPrimitive(true), recovered.executionReceipt?.get("recovered_saved_view"))
            val local = execute(requireNotNull(next()))
            assertFalse(local.dispatched)
            assertEquals(page.content, local.content)
            assertEquals(page.executionReceipt?.get("saved_view_source_call_id"), local.executionReceipt?.get("saved_view_source_call_id"))
            assertEquals(3, requests)
            val finished = GitHubReplayRecovery.State(history.flatMap { it.calls }, history.flatMap { it.results })
            assertTrue(finished.pendingSavedPageDeliveries(history, ToolResultDeliveryProof.capture(listOf(local))).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun processRestartRestoresMutationResultAndLaterIssuedPagesWithoutMutationReplay() = runBlocking {
        val directory = Files.createTempDirectory("github-saved-result").toFile()
        try {
            val backend = Backend()
            fun tool(): GitHubOperationTool {
                val store = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "account:connection:conversation")
                return GitHubOperationTool("github_commit", backend, "selected-scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save)
            }
            val request = json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}""")
            var page = tool().execute("commit", request)
            assertFalse(page.isError)
            val fragments = sortedMapOf<Int, String>()
            var reopened = 0
            while (true) {
                body(page).getValue("fragments").jsonArray.forEach { fragment ->
                    val record = fragment.jsonObject
                    fragments[record.getValue("offset").jsonPrimitive.int] = record.getValue("value").jsonPrimitive.content
                }
                if (body(page)["local_page_complete"] == JsonPrimitive(true)) break
                assertTrue("Saved pagination must terminate", reopened++ < 200)
                page = tool().execute("restore-after-death-$reopened", continuation(page))
                assertFalse(page.isError)
                assertFalse(page.dispatched)
                assertTrue(page.sharedResult)
            }
            assertTrue(reopened > 1)
            assertEquals(backend.payload, fragments.values.joinToString(""))
            assertEquals(1, backend.calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun persistedViewCannotBeReadByAnotherScopeOperationOrUnissuedCursor() = runBlocking {
        val state = GitHubRepositoryStateStore()
        val store = GitHubSavedViewStore(state, "account")
        val backend = Backend()
        val original = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save)
        val first = original.execute("commit", json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}"""))
        val cursor = continuation(first)
        for ((operation, scope) in listOf("github_commit" to "another-account", "github_read" to "scope")) {
            val foreign = GitHubOperationTool(operation, backend, scope, views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save)
            assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.execute("foreign", cursor).errorCode)
        }
        val token = cursor.getValue("view_cursor").jsonPrimitive.content
        val forged = buildJsonObject { put("view_cursor", token.substringBefore(':') + ":9999:2048") }
        val restored = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save)
        assertEquals("SAVED_VIEW_UNAVAILABLE", restored.execute("forged", forged).errorCode)
        assertFalse(restored.execute("issued", cursor).isError)
        assertEquals(1, backend.calls)
    }

    @Test fun unavailableCursorMayRecoverAfterStoreBecomesAvailable() = runBlocking {
        val store = GitHubSavedViewStore(GitHubRepositoryStateStore(), "account")
        val backend = Backend()
        val first = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), saveSavedView = store::save)
            .execute("commit", json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}"""))
        var available = false
        val restored = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = { if (available) store.load(it) else null }, saveSavedView = store::save)
        val cursor = continuation(first)
        val lost = restored.execute("lost", cursor)
        assertEquals("SAVED_VIEW_UNAVAILABLE", lost.errorCode)
        assertEquals(JsonPrimitive("NOT_FOUND"), lost.executionReceipt?.get("saved_view_restore_state"))
        available = true
        val recovered = restored.execute("retry-local", cursor)
        assertFalse(recovered.isError)
        assertEquals(JsonPrimitive("RESTORED"), recovered.executionReceipt?.get("saved_view_restore_state"))
        assertEquals(1, backend.calls)
    }

    @Test fun savedViewLoadFailureRecordsOnlyTypedStateAndHashedExceptionClass() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_issue", backend, "scope", views = GitHubResultViews(), loadSavedView = { throw IllegalStateException("private storage path") })
        val result = tool.execute("saved-read", buildJsonObject { put("view_cursor", "lost-view:0:1536") })
        assertEquals("SAVED_VIEW_UNAVAILABLE", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, backend.calls)
        val details = requireNotNull(result.executionReceipt)
        assertEquals(JsonPrimitive("LOAD_FAILED"), details["saved_view_restore_state"])
        assertTrue((details["saved_view_restore_error_hash"] as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")))
        assertFalse(details.toString().contains("private storage path"))
        assertFalse(details.toString().contains("IllegalStateException"))
    }

    @Test fun missingJobsBodyAndInvalidViewIdRequireFreshScopedReadsEvenInReplay() = runBlocking {
        val store = GitHubSavedViewStore(GitHubRepositoryStateStore(), "account")
        val backend = Backend()
        val tool = GitHubOperationTool("github_ci", backend, "scope", views = GitHubResultViews(), loadSavedView = store::loadWithDiagnostics)
        for ((id, reason) in listOf(java.util.UUID.randomUUID().toString() to "BODY_MISSING", "invalid-id" to "INVALID_VIEW_ID")) {
            val result = tool.execute(reason, buildJsonObject { put("view_cursor", "$id:0:1536") })
            assertEquals("SAVED_VIEW_UNAVAILABLE", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals(JsonPrimitive(reason), result.executionReceipt?.get("saved_view_restore_state"))
            val replay = requireNotNull(GitHubReplayRecovery.compact(result.content, 1500))
            for (response in listOf(body(result), requireNotNull(GitHubReplayRecovery.envelope(replay)))) {
                assertEquals(JsonPrimitive("START_FRESH_SCOPED_READ"), response["recovery_action"])
                assertEquals(JsonPrimitive(false), response["mutation_replay_allowed"])
                assertTrue(response.keys.none { it.endsWith("_call") })
                assertFalse(response.toString().contains(id))
            }
        }
        assertEquals(0, backend.calls)
    }

    @Test fun secondaryStoreFailurePreservesAcceptedMutationReceipt() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_commit", backend, "scope", saveSavedView = { _, _ -> error("disk unavailable") })
        val result = tool.execute("commit", json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}"""))
        assertFalse(result.isError)
        assertTrue(result.dispatched)
        assertEquals(JsonPrimitive("UNAVAILABLE"), result.executionReceipt?.get("saved_view_persistence"))
        assertEquals(1, backend.calls)
    }

    @Test fun restoredViewsRemainBoundToPrincipalConnectionConversationAndRepository() = runBlocking {
        val store = GitHubSavedViewStore(GitHubRepositoryStateStore(), "shared-storage")
        val backend = Backend()
        fun scope(account: String = "account-a", connection: String = "connection", conversation: String = "conversation", repository: String = "owner/repo") =
            GitHubResultViews.scope(GitHubAuthorizationIdentity.scope(account, connection), connection, conversation, "task", repository)
        fun tool(scope: String) = GitHubOperationTool("github_commit", backend, scope, views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save)
        val first = tool(scope()).execute("commit", json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}"""))
        val cursor = continuation(first)
        for (changed in listOf(scope(account = "account-b"), scope(connection = "another-connection"), scope(conversation = "another-conversation"), scope(repository = "owner/another-repo"))) {
            val rejected = tool(changed).execute("foreign", cursor)
            assertEquals("SAVED_VIEW_UNAVAILABLE", rejected.errorCode)
            assertFalse(rejected.dispatched)
        }
        assertFalse(tool(scope(repository = "OWNER/REPO")).execute("same-account-after-rotation", cursor).isError)
        assertEquals(1, backend.calls)
    }

    @Test fun liveAuthorizationIsCheckedBeforeAndAfterLoadingSavedBytes() = runBlocking {
        val store = GitHubSavedViewStore(GitHubRepositoryStateStore(), "account")
        val backend = Backend()
        var authorized = true
        val original = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save, validateSavedViewAccess = { check(authorized) })
        val first = original.execute("commit", json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}"""))
        val cursor = continuation(first)
        authorized = false
        assertEquals("SAVED_VIEW_AUTHORIZATION_CHANGED", original.execute("cached-after-revocation", cursor).errorCode)
        authorized = true
        val restored = GitHubOperationTool(
            "github_commit",
            backend,
            "scope",
            views = GitHubResultViews(pageBytes = 2048),
            loadSavedView = { id -> store.load(id).also { authorized = false } },
            saveSavedView = store::save,
            validateSavedViewAccess = { check(authorized) }
        )
        val denied = restored.execute("revoked-during-load", cursor)
        assertEquals("SAVED_VIEW_AUTHORIZATION_CHANGED", denied.errorCode)
        assertFalse(denied.dispatched)
        assertFalse(denied.content.toString().contains(backend.payload.take(50)))
        authorized = true
        assertFalse(restored.execute("reauthorized", cursor).isError)
        assertEquals(1, backend.calls)
    }

    @Test fun missingPersistentBodyIsRewrittenEvenWhenItRemainsInMemory() = runBlocking {
        val directory = Files.createTempDirectory("github-saved-result-pruning").toFile()
        try {
            val store = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "account")
            val backend = Backend()
            val tool = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save)
            val first = tool.execute("commit", json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}"""))
            val id = first.executionReceipt!!.getValue("saved_view_id").jsonPrimitive.content
            val bodyFile = java.io.File(directory, "saved-results/" + GitHubRepositoryStateStore.digest("account:saved-view-body:$id") + ".record")
            assertTrue(bodyFile.delete())
            val next = tool.execute("persist-again", continuation(first))
            assertEquals(JsonPrimitive("RETAINED"), next.executionReceipt?.get("saved_view_persistence"))
            assertTrue(bodyFile.isFile)
            val restarted = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "account")
            val restored = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = restarted::load, saveSavedView = restarted::save)
            assertFalse(restored.execute("after-restart", continuation(next)).isError)
            assertEquals(1, backend.calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun persistenceFailureRemainsAnAcceptanceLimitationUntilTheSameViewIsRetained() = runBlocking {
        val store = GitHubSavedViewStore(GitHubRepositoryStateStore(), "account")
        val saved = linkedMapOf<String, JsonObject>()
        var available = false
        val backend = Backend()
        val tool = GitHubOperationTool(
            "github_commit",
            backend,
            "scope",
            views = GitHubResultViews(pageBytes = 2048),
            loadSavedView = store::load,
            saveSavedView = { id, value ->
                check(available)
                store.save(id, value)
            }
        )
        val args = json("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}""")
        val result = tool.execute("commit", args)
        val book = GitHubEvidenceBook("task", "owner/repo", "work", setOf("github_commit"), save = { id, value -> saved[id] = value })
        book.observe(ProviderEvent.ToolCall("commit", "github_commit", args), result)
        assertTrue(book.complete())
        assertEquals(JsonPrimitive(true), book.snapshot().first.values.single()["success"])
        book.retainProgress("Implement the feature", listOf(tool.definition))
        val pending = saved.currentGitHubVerification()
        assertEquals(JsonPrimitive(false), pending["evidence_retention_complete"])
        assertEquals(JsonPrimitive(false), pending["saved_view_retention_complete"])
        assertEquals(JsonPrimitive("SAVED_VIEW_PERSISTENCE_UNAVAILABLE"), pending.getValue("evidence_retention_limitations").jsonArray.single().jsonObject["code"])
        fun restoreBook() = GitHubEvidenceBook("task", "owner/repo", "work", setOf("github_commit"), save = { id, value -> saved[id] = value }, load = { saved[it] }, retainedIds = { saved.keys.filter { it.startsWith("ev_") } })
        val restored = restoreBook()
        restored.restore()
        assertEquals(JsonPrimitive(false), restored.retentionStatus()["evidence_retention_complete"])
        available = true
        val recovery = continuation(result)
        val retained = tool.execute("persist-local", recovery)
        // Simulate losing the successful persistence receipt before its primary
        // journal append. The next process must recover exact failed-handle proof.
        val restartedTool = GitHubOperationTool("github_commit", backend, "scope", views = GitHubResultViews(pageBytes = 2048), loadSavedView = store::load, saveSavedView = store::save)
        val resumedArguments = continuation(retained)
        val resumed = restartedTool.execute("persist-after-restart", resumedArguments)
        restored.observe(ProviderEvent.ToolCall("persist-after-restart", "github_commit", resumedArguments), resumed)
        assertEquals(JsonPrimitive(true), restored.retentionStatus()["evidence_retention_complete"])
        val restoredAgain = restoreBook()
        restoredAgain.restore()
        assertEquals(JsonPrimitive(true), restoredAgain.retentionStatus()["evidence_retention_complete"])
        assertEquals(1, backend.calls)
    }
}
