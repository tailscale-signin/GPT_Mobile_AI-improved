package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubAuthorizationIdentity
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.github.GitHubSavedViewStore
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
        assertEquals("SAVED_VIEW_UNAVAILABLE", restored.execute("lost", cursor).errorCode)
        available = true
        assertFalse(restored.execute("retry-local", cursor).isError)
        assertEquals(1, backend.calls)
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
            val bodyFile = java.io.File(directory, GitHubRepositoryStateStore.digest("account:saved-view-body:$id") + ".record")
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
        val pending = saved.values.last()
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
