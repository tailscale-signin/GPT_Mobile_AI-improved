package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.terminalToolFailure
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationResilienceTest {
    private val parent = "a".repeat(40)
    private val published = "b".repeat(40)
    private val baseTree = "c".repeat(40)
    private val targetTree = "d".repeat(40)
    private val oldBlob = GitHubCommitReconciliation.blobSha("old")
    private val args = parse("""{"owner":"o","repo":"r","branch":"work","expected_head_sha":"$parent","message":"Fix","files":[{"path":"file.txt","expected_blob_sha":"$oldBlob","content":"new ☃"}]}""")
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private inner class Remote : AutoCloseable {
        var head = parent
        var candidateParent = parent
        var extraFile = false
        var truncated = false
        var loseRefResponse = true
        var refReads = 0
        var moveDuringVerification = false
        val mutations = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                if (request.method != HttpMethod.Get) {
                    mutations += "${request.method.value} $path"
                    when {
                        path.endsWith("/trees") -> respond("""{"sha":"$targetTree"}""")
                        path.endsWith("/commits") -> respond("""{"sha":"$published"}""")
                        path.contains("/refs/heads/") -> {
                            head = published
                            if (loseRefResponse) throw IOException("Lost acknowledgement after remote ref update")
                            respond("""{"ref":"refs/heads/work","object":{"sha":"$head"}}""")
                        }
                        else -> error("Unexpected mutation")
                    }
                } else {
                    reads += path
                    when {
                        path == "/repos/o/r" -> respond("""{"default_branch":"main"}""")
                        path == "/repos/o/r/commits/work" -> respond("""{"sha":"$parent","commit":{"tree":{"sha":"$baseTree"}}}""")
                        path.endsWith("/blobs/$oldBlob") -> respond("""{"sha":"$oldBlob","size":3,"encoding":"base64","content":"b2xk"}""")
                        path.contains("/ref/heads/") -> {
                            refReads++
                            if (moveDuringVerification && refReads > 1) head = "e".repeat(40)
                            respond("""{"ref":"refs/heads/work","object":{"sha":"$head"}}""")
                        }
                        path.endsWith("/commits/$parent") -> respond("""{"sha":"$parent","tree":{"sha":"$baseTree"}}""")
                        path.endsWith("/commits/$published") -> respond("""{"sha":"$published","message":"Fix","parents":[{"sha":"$candidateParent"}],"tree":{"sha":"$targetTree"}}""")
                        path.contains("/trees/") -> {
                            val target = path.endsWith(targetTree)
                            val tree = if (target) targetTree else baseTree
                            val blob = if (target) GitHubCommitReconciliation.blobSha("new ☃") else oldBlob
                            val extra = if (target && extraFile) ",{\"path\":\"unrelated.txt\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"$oldBlob\"}" else ""
                            respond("""{"sha":"$tree","truncated":$truncated,"tree":[{"path":"file.txt","mode":"100755","type":"blob","sha":"$blob"}$extra]}""")
                        }
                        else -> error("Unexpected read $path")
                    }
                }
            }
        )
        suspend fun recover() = GitHubCommitReconciliation.recover("/repos/o/r", args) { path ->
            val response = http.get("https://api.github.com$path").bodyAsText()
            Json.parseToJsonElement(response)
        }
        override fun close() = http.close()
    }

    @Test fun stagedRecoveryStaysAnErrorUntilRemoteProofSucceedsAndThenReturnsTheSavedPreview() = runTest {
        Remote().use { remote ->
            val tool = GitHubTool("token", remote.http, stateStore = GitHubRepositoryStateStore())
            fun body(result: AgentToolResult): JsonObject = when (val content = result.content) {
                is ToolResultContent.Json -> content.value.jsonObject
                is ToolResultContent.Text -> parse(content.text)
                else -> error("Expected a structured receipt")
            }
            val staged = tool.execute("stage", parse("""{"action":"stage_patch","owner":"o","repo":"r","ref":"work","target_file":"file.txt","search_snippet":"old","replacement_snippet":"new ☃"}"""))
            assertFalse(staged.content.toString(), staged.isError)
            val stageId = body(staged)["stage_id"]!!.jsonPrimitive.content
            val commit = parse("""{"action":"commit_staged_patch","owner":"o","repo":"r","ref":"work","stage_id":"$stageId","branch":"work","expected_head_sha":"$parent","message":"Fix"}""")
            assertTrue(tool.execute("commit", commit).isError)
            remote.refReads = 0
            remote.moveDuringVerification = true
            val blocked = tool.execute("blocked", commit)
            assertTrue(blocked.isError)
            assertEquals("RECONCILIATION_REQUIRED", blocked.errorCode)
            assertEquals(3, remote.mutations.size)
            remote.moveDuringVerification = false
            remote.head = published
            val recovered = tool.execute("recover", commit)
            assertFalse(recovered.content.toString(), recovered.isError)
            assertTrue(body(recovered)["patch_preview"]!!.jsonArray.isNotEmpty())
            assertEquals(published, body(recovered)["sha"]!!.jsonPrimitive.content)
            assertEquals(3, remote.mutations.size)
        }
    }

    @Test fun lostRefAcknowledgementRecoversAcrossClientsWithoutRepeatingAnyMutation() = runTest {
        Remote().use { remote ->
            val store = GitHubRepositoryStateStore()
            val first = GitHubWorkspaceClient("token", remote.http, stateStore = store, conditionalReads = false)
            val failure = runCatching { first.execute("commit_files", args) }.exceptionOrNull() as GitHubExecutionBlocked
            assertEquals("true", failure.checkpoint["dispatched"].toString())
            assertEquals(3, remote.mutations.size)
            val recovered = GitHubWorkspaceClient("token", remote.http, stateStore = store, conditionalReads = false).execute("commit_files", args).jsonObject
            assertEquals(published, recovered["sha"]!!.jsonPrimitive.content)
            assertEquals("true", recovered["reconciled"].toString())
            val checkpoints = GitHubExecutionJournal(store, GitHubRepositoryStateStore.digest("token") + ":::").context()
            assertEquals("0", checkpoints["recovery_metrics"]!!.jsonObject["reconciliation_required_count"].toString())
            assertEquals(3, remote.mutations.size)
            val file = recovered["files"]!!.jsonArray.single().jsonObject
            assertEquals("modified", file["status"]!!.jsonPrimitive.content)
            assertEquals(GitHubCommitReconciliation.blobSha("new ☃"), file["blob_sha"]!!.jsonPrimitive.content)
            assertFalse("content" in file)
            assertFalse(remote.reads.any { "/contents/" in it || "/blobs/" in it })
        }
    }

    @Test fun lostBranchResponseIsReconciledBeforeAnotherCreationRequest() = runTest {
        var exists = false
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post -> {
                        writes++
                        exists = true
                        throw IOException("Response lost after branch creation")
                    }
                    request.url.encodedPath.endsWith("/main") -> respond("""{"ref":"refs/heads/main","object":{"sha":"$parent"}}""")
                    exists -> respond("""{"ref":"refs/heads/work","object":{"sha":"$parent"}}""")
                    else -> respond("{}", HttpStatusCode.NotFound)
                }
            }
        )
        try {
            val arguments = parse("""{"action":"create_branch","owner":"o","repo":"r","branch":"work","base":"main","expected_head_sha":"$parent"}""")
            assertTrue(GitHubTool("token", http).execute("first", arguments).isError)
            val recovery = GitHubTool("token", http).execute("second", arguments)
            assertFalse(recovery.isError)
            assertEquals("true", (recovery.content as ToolResultContent.Json).value.jsonObject["reconciled"].toString())
            assertEquals(1, writes)
        } finally {
            http.close()
        }
    }

    @Test fun matchingFilesDoNotExcuseAnUnrelatedChangeWrongParentOrIncompleteTree() = runTest {
        Remote().use { remote ->
            remote.head = published
            assertNotNull(remote.recover())
            remote.extraFile = true
            assertNull(remote.recover())
            remote.extraFile = false
            remote.candidateParent = "f".repeat(40)
            assertNull(remote.recover())
            remote.candidateParent = parent
            remote.truncated = true
            assertNull(remote.recover())
            assertTrue(remote.mutations.isEmpty())
        }
    }

    @Test fun changingRemoteHeadAndUnchangedHeadRemainUncertain() = runTest {
        Remote().use { remote ->
            assertNull(remote.recover())
            remote.head = published
            remote.refReads = 0
            remote.moveDuringVerification = true
            assertNull(remote.recover())
            assertTrue(remote.mutations.isEmpty())
        }
    }

    @Test fun journalAcceptsReadOnlyRemoteProofAndRetainsItWithoutASecondWrite() = runTest {
        val store = GitHubRepositoryStateStore()
        val journal = GitHubExecutionJournal(store, "scope")
        var writes = 0
        runCatching {
            journal.run("commit", args) {
                writes++
                throw IOException("lost")
            }
        }
        val result = GitHubExecutionJournal(store, "scope").run("commit", args, reconcile = { buildJsonObject { put("sha", published) } }) {
            writes++
            error("Cannot replay")
        }.jsonObject
        assertEquals(1, writes)
        assertEquals("true", result["execution"]!!.jsonObject["remote_reconciled"].toString())
        assertEquals(published, journal.run("commit", args) { error("Use retained receipt") }.jsonObject["sha"]!!.jsonPrimitive.content)
    }

    @Test fun compoundWriteStaysUncertainWhenALaterAdmissionIsRejected() = runTest {
        val journal = GitHubExecutionJournal(GitHubRepositoryStateStore(), "scope")
        val failure = runCatching {
            journal.run("compound", args) {
                GitHubMutationAttempts.dispatched()
                throw GitHubRequestScheduler.AdmissionRejected("Last request was never dispatched")
            }
        }.exceptionOrNull() as GitHubExecutionBlocked
        assertEquals("true", failure.checkpoint["dispatched"].toString())
        val result = journal.run("compound", args) { error("Earlier accepted mutation cannot be replayed") }.jsonObject
        assertEquals("RECONCILE_REQUIRED", result["status"]!!.jsonPrimitive.content)
    }

    @Test fun readOnlyReconciliationAndLateAcknowledgementKeepTheFirstReceipt() = runTest {
        val store = GitHubRepositoryStateStore()
        val journal = GitHubExecutionJournal(store, "scope")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            journal.run("commit", args) {
                entered.complete(Unit)
                release.await()
                buildJsonObject {
                    put("sha", published)
                    put("source", "late acknowledgement")
                }
            }
        }
        entered.await()
        val verified = journal.run("commit", args, reconcile = {
            buildJsonObject {
                put("sha", published)
                put("source", "remote proof")
            }
        }) { error("Cannot repeat a running mutation") }.jsonObject
        release.complete(Unit)
        val late = owner.await().jsonObject
        assertEquals("remote proof", verified["source"]!!.jsonPrimitive.content)
        assertEquals(verified["source"], late["source"])
        assertEquals(verified["source"], journal.run("commit", args) { error("Cannot republish") }.jsonObject["source"])
    }

    @Test fun provenUnsentAdmissionCanBeAttemptedAgainWithoutAReconciliationLoop() = runTest {
        val journal = GitHubExecutionJournal(GitHubRepositoryStateStore(), "scope")
        runCatching { journal.run("write", args) { throw GitHubRequestScheduler.AdmissionRejected("Queue full") } }
        val result = journal.run("write", args) { buildJsonObject { put("sha", published) } }.jsonObject
        assertEquals(published, result["sha"]!!.jsonPrimitive.content)
        assertEquals("2", result["execution"]!!.jsonObject["attempt"].toString())
    }

    @Test fun queueDeadlineRemovesWaiterAndDoesNotLeakAPermitOrDispatch() = runTest {
        val scheduler = GitHubRequestScheduler(concurrency = 1, queueTimeoutMillis = 25)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            scheduler.execute {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val blocked = async { runCatching { scheduler.execute { error("Queued request must not dispatch") } }.exceptionOrNull() }
        assertTrue(blocked.await() is GitHubRequestScheduler.AdmissionRejected)
        assertEquals("0", scheduler.metrics()["queued_requests"].toString())
        release.complete(Unit)
        owner.await()
        assertEquals("next", scheduler.execute { "next" })
        assertEquals("0", scheduler.metrics()["active_requests"].toString())
    }

    @Test fun headersUseAnActualMinimalApiFieldSelection() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("/graphql", request.url.encodedPath)
                val payload = parse((request.body as TextContent).text)
                val query = payload["query"]!!.jsonPrimitive.content
                assertFalse(query.contains("body"))
                assertFalse(query.contains("comments"))
                assertFalse(query.contains("reviews"))
                assertEquals("42", payload["variables"]!!.jsonObject["number"].toString())
                respond("""{"data":{"repository":{"pullRequest":{"number":42,"title":"Fix","state":"OPEN","isDraft":true,"headRefOid":"$published","baseRefOid":"$parent"}}}}""")
            }
        )
        try {
            val result = GitHubWorkspaceClient("token", http).execute("pr_headers", parse("""{"owner":"o","repo":"r","pull_number":42}""")).jsonObject
            assertEquals(published, result["head_sha"]!!.jsonPrimitive.content)
            assertEquals("false", result["merge_readiness_verified"].toString())
        } finally {
            http.close()
        }
    }

    @Test fun publicFacadeNormalizesPartialRepositoryArgumentsBeforeValidation() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("/graphql", request.url.encodedPath)
                val variables = parse((request.body as TextContent).text)["variables"]!!.jsonObject
                assertEquals("selected-owner", variables["owner"]!!.jsonPrimitive.content)
                assertEquals("repo", variables["repo"]!!.jsonPrimitive.content)
                respond("""{"data":{"repository":{"pullRequest":{"number":42,"title":"Fix","state":"OPEN"}}}}""")
            }
        )
        try {
            val backend = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("selected-owner", "repo", "main"))
            val tool = GitHubOperationTool("github_pr", backend, "partial-repository")
            for (fields in listOf("\"owner\":\"selected-owner\"", "\"repo\":\"repo\"", "\"repo\":\"https://github.com/selected-owner/repo.git\"")) {
                val result = tool.execute("read-$requests", parse("""{"action":"headers","pull_number":42,$fields}"""))
                assertFalse(result.toString(), result.isError)
            }
            val before = requests
            val rejected = tool.execute("conflict", parse("""{"action":"headers","pull_number":42,"repo":"another-repository"}"""))
            assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
            assertFalse(rejected.dispatched)
            assertEquals(before, requests)
            assertTrue(requests > 0)
        } finally {
            http.close()
        }
    }

    @Test fun evidenceIdsAndGuessedFragmentOffsetsCannotDispatchOrSkipPages() = runTest {
        var calls = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "test", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls++
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("text", "x".repeat(20_000)) }), false)
            }
        }
        val views = GitHubResultViews(pageBytes = 2048)
        val operation = GitHubOperationTool("github_search", backend, "scope", views = views)
        assertTrue(operation.execute("wrong", parse("""{"query":"find","evidence_id":"E1"}""")).isError)
        assertEquals(0, calls)
        val first = operation.execute("first", parse("""{"query":"find"}"""))
        val page = (first.content as ToolResultContent.Json).value.jsonObject
        val cursor = page["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject["view_cursor"]!!.jsonPrimitive.content
        val guessed = cursor.substringBefore(':') + ":1"
        assertEquals("SAVED_VIEW_UNAVAILABLE", operation.execute("guess", buildJsonObject { put("view_cursor", guessed) }).errorCode)
        assertFalse(operation.execute("next", buildJsonObject { put("view_cursor", cursor) }).isError)
        assertEquals(1, calls)
    }

    @Test fun rateLimitsPermissionsRulesAndTransientFailuresStayDistinct() {
        assertEquals("GITHUB_PRIMARY_RATE_LIMITED", GitHubFailurePolicy.classify(403, remaining = "0"))
        assertEquals("GITHUB_SECONDARY_RATE_LIMITED", GitHubFailurePolicy.classify(403, "You have exceeded a secondary rate limit", remaining = "4999"))
        assertTrue(terminalToolFailure("GITHUB_PRIMARY_RATE_LIMITED"))
        assertTrue(terminalToolFailure("GITHUB_SECONDARY_RATE_LIMITED"))
        assertEquals("GITHUB_ENDPOINT_PERMISSION_REQUIRED", GitHubFailurePolicy.classify(403, "Resource not accessible by integration", remaining = "4999"))
        assertEquals("GITHUB_BRANCH_RULES_BLOCKED", GitHubFailurePolicy.classify(422, "GH013: Repository rule violations"))
        assertEquals("GITHUB_FORBIDDEN_UNKNOWN", GitHubFailurePolicy.classify(403, "Forbidden", remaining = "4999"))
        assertEquals("GITHUB_TRANSIENT_SERVER", GitHubFailurePolicy.classify(503))
        assertEquals("AUTH_REQUIRED", GitHubFailurePolicy.classify(401))
        assertEquals(1_901_000L, GitHubWatchBackoff.next(1_000L, 100, 1_901_000L))
        assertEquals(901_000L, GitHubWatchBackoff.next(1_000L, 100))
    }
}
