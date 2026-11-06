package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRestMutationFenceTest {
    private val head = "a".repeat(40)
    private val account = "account"
    private val connection = "connection"
    private val conversation = "conversation"
    private val task = "task"

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun scope(taskId: String = task, identity: String = account) = listOf(identity, connection, conversation, taskId).joinToString(":")
    private fun client(http: HttpClient, store: GitHubRepositoryStateStore, taskId: String = task, legacy: List<String> = emptyList()) = GitHubWorkspaceClient(
        "token",
        http,
        connectionUid = connection,
        conversationId = conversation,
        taskId = taskId,
        stateStore = store,
        durableIdentity = account,
        legacyCredentialIdentities = legacy
    )

    private fun args(payload: String) = JsonObject(json(payload) + mapOf("owner" to JsonPrimitive("o"), "repo" to JsonPrimitive("r")))
    private fun contextual(base: JsonObject, index: Int) = JsonObject(
        base + mapOf(
            "ref" to JsonPrimitive("context-$index"),
            "page" to JsonPrimitive(index + 1),
            "response_detail" to JsonPrimitive(if (index == 0) "compact" else "full"),
            "expected_base_sha" to JsonPrimitive((if (index == 0) "b" else "c").repeat(40))
        )
    )

    @Test fun uncertainIssueCreationIgnoresContextAndDisplayChanges() = assertOnePost("create_issue", """{"title":"same title","body":"same body"}""")

    @Test fun uncertainReviewIgnoresContextDisplayAndReviewedBaseChanges() = assertOnePost(
        "submit_pull_request_review",
        """{"pull_number":7,"expected_head_sha":"$head","event":"COMMENT","body":"same body"}"""
    )

    @Test fun uncertainReleaseCreationIgnoresContextAndDisplayChanges() = assertOnePost(
        "create_release",
        """{"tag_name":"v-fixture","title":"same title","body":"same body"}"""
    )

    private fun assertOnePost(action: String, payload: String) = runTest {
        val store = GitHubRepositoryStateStore()
        val sent = mutableListOf<Pair<String, String>>()
        var observedBase = "b".repeat(40)
        HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    sent += request.url.encodedPath to (request.body as TextContent).text
                    throw IOException("Response lost after dispatch")
                }
                respond("""{"number":7,"state":"open","head":{"sha":"$head"},"base":{"sha":"$observedBase"}}""")
            }
        ).use { http ->
            repeat(3) { index ->
                observedBase = (if (index == 0) "b" else "c").repeat(40)
                val failure = runCatching { client(http, store).execute(action, contextual(args(payload), index)) }.exceptionOrNull()
                assertTrue("$action must retain its uncertain result: $failure", failure is GitHubExecutionBlocked)
            }
            assertEquals(1, sent.size)
        }
    }

    @Test fun newTaskHasItsOwnPayloadFenceWhileSameTaskCannotRepeat() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        HttpClient(
            MockEngine {
                writes++
                throw IOException("Response lost after dispatch")
            }
        ).use { http ->
            val input = args("""{"title":"one issue"}""")
            for ((taskId, index) in listOf(task to 0, task to 1, "new-task" to 2)) {
                assertTrue(runCatching { client(http, store, taskId).execute("create_issue", contextual(input, index)) }.exceptionOrNull() is GitHubExecutionBlocked)
            }
            assertEquals(2, writes)
        }
    }

    @Test fun reviewPreflightStillRejectsAChangedHeadBeforeAnyPost() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Post) writes++
                respond("""{"number":7,"state":"open","head":{"sha":"$head"}}""")
            }
        ).use { http ->
            val input = args("""{"pull_number":7,"expected_head_sha":"${"b".repeat(40)}","event":"COMMENT","body":"review"}""")
            assertTrue(runCatching { client(http, store).execute("submit_pull_request_review", input) }.exceptionOrNull()?.message.orEmpty().contains("head changed"))
            assertEquals(0, writes)
        }
    }

    private fun legacyIdentity(action: String, input: JsonObject, path: String, body: JsonObject) = buildJsonObject {
        put("path", path)
        put("invocation", GitHubRepositoryStateStore.digest("$action|${GitHubExecutionJournal.canonical(input)}"))
        put("body", body)
    }

    private suspend fun seedLegacyIssue(store: GitHubRepositoryStateStore, input: JsonObject, completed: Boolean, identity: String = account): String {
        val body = JsonObject(input.filterKeys { it in setOf("title", "body", "state") })
        val legacy = legacyIdentity("create_issue", input, "/repos/o/r/issues", body)
        val retainedScope = scope(identity = identity)
        val executionId = GitHubRepositoryStateStore.digest("$retainedScope|http.POST|${GitHubExecutionJournal.canonical(legacy)}")
        runCatching {
            GitHubExecutionJournal(store, retainedScope).run("http.POST", legacy, singleHttpRequest = true) {
                GitHubMutationAttempts.dispatched()
                if (!completed) throw IOException("Legacy response was lost")
                json("""{"number":41,"title":"one issue"}""")
            }
        }.also { result -> assertEquals(completed, result.isSuccess) }
        return executionId
    }

    @Test fun exactLegacyUnknownClaimMigratesWithoutRepeatingAcrossCredentialScopes() = runTest {
        for (oldAccount in listOf(account, "old-token-identity")) {
            val store = GitHubRepositoryStateStore()
            val input = contextual(args("""{"title":"one issue"}"""), 0)
            seedLegacyIssue(store, input, completed = false, identity = oldAccount)
            var writes = 0
            HttpClient(
                MockEngine {
                    writes++
                    respond("""{"number":42}""")
                }
            ).use { http ->
                for (candidate in listOf(input, contextual(input, 1))) {
                    assertTrue(runCatching { client(http, store, legacy = listOf(oldAccount)).execute("create_issue", candidate) }.exceptionOrNull() is GitHubExecutionBlocked)
                }
                assertEquals(0, writes)
            }
        }
    }

    @Test fun exactLegacyCompletedReceiptIsReusedThenSharedByPayloadVariants() = runTest {
        val store = GitHubRepositoryStateStore()
        val input = contextual(args("""{"title":"one issue"}"""), 0)
        seedLegacyIssue(store, input, completed = true)
        var writes = 0
        HttpClient(
            MockEngine {
                writes++
                respond("""{"number":42}""")
            }
        ).use { http ->
            for (candidate in listOf(input, contextual(input, 1))) {
                val result = client(http, store).execute("create_issue", candidate).jsonObject
                assertEquals(JsonPrimitive(41), result["issue"]!!.jsonObject["number"])
            }
            assertEquals(0, writes)
        }
    }

    @Test fun changedFirstLegacyRetryLatchesAmbiguityBeyondCacheRemovalOrRewrite() = runTest {
        val store = GitHubRepositoryStateStore()
        val input = contextual(args("""{"title":"one issue"}"""), 0)
        seedLegacyIssue(store, input, completed = false)
        var writes = 0
        HttpClient(
            MockEngine {
                writes++
                respond("""{"number":42}""")
            }
        ).use { http ->
            suspend fun blocked(index: Int) {
                val failure = runCatching { client(http, store).execute("create_issue", contextual(input, index)) }.exceptionOrNull()
                assertTrue(failure is GitHubExecutionBlocked)
                assertEquals(JsonPrimitive("GITHUB_LEGACY_HTTP_IDENTITY_UNRESOLVED"), (failure as GitHubExecutionBlocked).checkpoint["code"])
            }
            blocked(1)
            store.remove(scope(), "execution-context")
            blocked(2)
            store.write(scope(), "execution-context", json("""{"executions":[]}"""))
            blocked(3)
            // The exact saved request can still recover its retained uncertainty.
            assertTrue(runCatching { client(http, store).execute("create_issue", input) }.exceptionOrNull() is GitHubExecutionBlocked)
            assertEquals(0, writes)
        }
    }

    @Test fun commitReadbackSettlesTheOldPatchAndReleasesOnlyItsRecordedAmbiguity() = runTest {
        for (oldAccount in listOf(account, "old-token-identity")) {
            val store = GitHubRepositoryStateStore()
            val committed = "b".repeat(40)
            val baseTree = "c".repeat(40)
            val finalTree = "d".repeat(40)
            val content = "verified file"
            val blob = GitHubCommitReconciliation.blobSha(content)
            val input = args("""{"branch":"work","expected_head_sha":"$head","message":"add file","files":[{"path":"new.txt","content":"$content"}]}""")
            val legacy = legacyIdentity("commit_files", input, "/repos/o/r/git/refs/heads/work", json("""{"sha":"$committed","force":false}"""))
            assertTrue(
                runCatching {
                    GitHubExecutionJournal(store, scope(identity = oldAccount)).run("http.PATCH", legacy, singleHttpRequest = true) {
                        GitHubMutationAttempts.dispatched()
                        throw IOException("Legacy branch acknowledgement lost")
                    }
                }.exceptionOrNull() is GitHubExecutionBlocked
            )
            var writes = 0
            HttpClient(
                MockEngine { request ->
                    if (request.method != HttpMethod.Get) {
                        writes++
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals("/repos/o/r/issues", request.url.encodedPath)
                        respond("""{"number":42}""")
                    } else {
                        when (request.url.encodedPath) {
                            "/repos/o/r" -> respond("""{"default_branch":"main"}""")
                            "/repos/o/r/git/ref/heads/work" -> respond("""{"ref":"refs/heads/work","object":{"sha":"$committed"}}""")
                            "/repos/o/r/git/commits/$committed" -> respond("""{"sha":"$committed","parents":[{"sha":"$head"}],"message":"add file","tree":{"sha":"$finalTree"}}""")
                            "/repos/o/r/git/commits/$head" -> respond("""{"sha":"$head","tree":{"sha":"$baseTree"}}""")
                            "/repos/o/r/git/trees/$baseTree" -> respond("""{"sha":"$baseTree","truncated":false,"tree":[]}""")
                            "/repos/o/r/git/trees/$finalTree" -> respond("""{"sha":"$finalTree","truncated":false,"tree":[{"path":"new.txt","mode":"100644","type":"blob","sha":"$blob"}]}""")
                            else -> error("Unexpected read: ${request.url}")
                        }
                    }
                }
            ).use { http ->
                val issue = args("""{"title":"new authorized work"}""")
                assertTrue(runCatching { client(http, store, legacy = listOf(oldAccount)).execute("create_issue", issue) }.exceptionOrNull() is GitHubExecutionBlocked)
                assertEquals(0, writes)
                val recovered = client(http, store, legacy = listOf(oldAccount)).execute("commit_files", input).jsonObject
                assertEquals(JsonPrimitive(true), recovered["reconciled"])
                assertEquals(JsonPrimitive(committed), recovered["sha"])
                assertEquals(0, writes)
                assertEquals(JsonPrimitive(42), client(http, store, legacy = listOf(oldAccount)).execute("create_issue", issue).jsonObject["issue"]!!.jsonObject["number"])
                assertEquals(1, writes)
            }
        }
    }

    @Test fun exactLegacyFenceSurvivesADeletedReceiptBodyAndProcessRestart() = runTest {
        for (changedFirst in listOf(false, true)) {
            val directory = Files.createTempDirectory("github-rest-legacy-fence").toFile()
            try {
                val store = GitHubRepositoryStateStore(directory)
                val input = contextual(args("""{"title":"one issue"}"""), 0)
                val id = seedLegacyIssue(store, input, completed = true)
                // Simulate lost storage, not a supported cache removal of authority.
                val body = File(File(directory, "execution-receipts"), GitHubRepositoryStateStore.digest("${scope()}:execution:$id") + ".record")
                assertTrue(body.delete())
                var writes = 0
                HttpClient(
                    MockEngine {
                        writes++
                        respond("""{"number":42}""")
                    }
                ).use { http ->
                    val restarted = GitHubRepositoryStateStore(directory)
                    val candidates = listOf(input, contextual(input, 1)).let { if (changedFirst) it.reversed() else it }
                    for (candidate in candidates) {
                        assertTrue("changedFirst=$changedFirst", runCatching { client(http, restarted).execute("create_issue", candidate) }.exceptionOrNull() is GitHubExecutionBlocked)
                    }
                    assertEquals(0, writes)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
