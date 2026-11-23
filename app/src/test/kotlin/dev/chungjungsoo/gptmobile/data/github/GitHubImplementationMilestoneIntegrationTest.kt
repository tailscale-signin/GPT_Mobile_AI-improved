package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubImplementationMilestoneIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainedTwoFeatureSourceReachesAWorkingBranchValidatedStageAndCurrentCommitWithoutRepeatingReadsOrWrites() = runTest {
        Fixture(temporary.newFolder()).use { fixture ->
            val saved = linkedMapOf<String, JsonObject>()
            val binding = GitHubEvidenceBook.digest("account:connection:conversation:two-features")
            fun book() = GitHubEvidenceBook(
                binding,
                "owner/repo",
                fixture.branch,
                fixture.operations,
                load = saved::get,
                save = { id, value -> saved[id] = value },
                retainedIds = { saved.keys.filter { it.startsWith("ev_") } }
            )
            var evidence = book()
            val task = "Implement both requested features: show decision details and support 8192-byte source pages on a working branch."
            evidence.retainTaskContract(task, 1, fixture.tools.values.map { it.definition }, 40)
            suspend fun execute(id: String, operation: String, arguments: JsonObject): JsonObject {
                val call = ProviderEvent.ToolCall(id, operation, arguments)
                val result = fixture.tools.getValue(operation).execute(id, arguments)
                evidence.observe(call, result)
                assertFalse(result.toString(), result.isError)
                return fixture.data(result)
            }
            suspend fun progress() = GitHubExecutionContinuation.implementationProgress(evidence.snapshot().first)

            val original = execute("source", "github_read", fixture.read(fixture.origin))
            assertEquals(JsonPrimitive(fixture.origin), original["commit_sha"])
            assertEquals(2, original.getValue("items").jsonArray.size)
            assertTrue(original.getValue("items").jsonArray.all { it.jsonObject["range_complete"] == JsonPrimitive(true) })
            assertEquals(JsonPrimitive(2), progress()["source_files_with_captured_ranges"])
            assertEquals(JsonPrimitive(true), progress()["first_patch_pending"])
            val sourceBlobReads = fixture.blobReads
            assertEquals(2, sourceBlobReads)

            // Simulate a new process on the same task. The next operation consumes
            // the retained pinned source rather than restarting source discovery.
            fixture.restart()
            evidence = book().also { it.restore() }
            assertEquals(task, evidence.acceptedTaskRequest())
            assertEquals(JsonPrimitive(2), progress()["source_files_with_captured_ranges"])
            val working = execute(
                "branch",
                "github_commit",
                fixture.arguments {
                    put("action", "branch")
                    put("branch", fixture.branch)
                    put("from_branch", "main")
                    put("expected_head_sha", fixture.origin)
                }
            )
            assertEquals(JsonPrimitive(true), working["context_selected"])
            assertEquals(JsonPrimitive(true), working["remote_state_verified"])
            assertEquals(JsonPrimitive(0), progress()["committed_changes_observed"])
            assertEquals(JsonPrimitive(true), progress()["first_patch_pending"])

            val stage = execute(
                "stage",
                "github_stage_patch",
                fixture.arguments {
                    put("branch", fixture.branch)
                    put("expected_head_sha", fixture.origin)
                    put(
                        "edits",
                        JsonArray(
                            fixture.before.map { (path, text) ->
                                buildJsonObject {
                                    put("path", path)
                                    put("expected_blob_sha", GitHubCommitReconciliation.blobSha(text))
                                    put("old_text", text)
                                    put("new_text", fixture.after.getValue(path))
                                }
                            }
                        )
                    )
                }
            )
            assertEquals(JsonPrimitive(2), stage["files_changed"])
            assertEquals(JsonPrimitive(false), stage["committed"])
            val checks = stage.getValue("validation").jsonArray
            assertEquals(2, checks.size)
            assertTrue(checks.all { it.jsonObject["status"] == JsonPrimitive("PRECHECKS_PASSED") })
            assertTrue(checks.all { it.jsonObject.getValue("external_checks").jsonArray.all { check -> check.jsonObject["status"] == JsonPrimitive("NOT_RUN") } })
            assertEquals("Staging must reuse both immutable source blobs", sourceBlobReads, fixture.blobReads)
            assertEquals(JsonPrimitive(1), progress()["staged_patches_observed"])
            assertEquals(JsonPrimitive(false), progress()["first_patch_pending"])
            assertEquals(JsonPrimitive(0), progress()["committed_changes_observed"])

            val commitArguments = fixture.arguments {
                put("action", "commit_staged_patch")
                put("stage_id", stage.getValue("stage_id"))
                put("branch", fixture.branch)
                put("expected_head_sha", fixture.origin)
                put("message", "Implement both requested features")
            }
            val committed = execute("commit", "github_commit", commitArguments)
            assertEquals(JsonPrimitive(fixture.published), committed["sha"])
            assertEquals(JsonPrimitive(fixture.origin), committed["parent_sha"])
            assertEquals(JsonPrimitive(2), committed["files_changed"])
            assertEquals(stage["stage_id"], committed["stage_id"])
            assertEquals(JsonPrimitive(1), progress()["committed_changes_observed"])
            assertEquals(1, fixture.branchWrites)
            assertEquals(1, fixture.commitWrites)
            assertEquals(1, fixture.refWrites)

            val current = execute(
                "current-head",
                "github_context",
                fixture.arguments {
                    put("action", "resolve")
                    put("ref", fixture.branch)
                    put("refresh", true)
                    put("verify_live", true)
                }
            )
            assertEquals(JsonPrimitive(fixture.published), current["commit_sha"])
            val changed = execute("current-source", "github_read", fixture.read(fixture.published))
            assertTrue(
                changed.getValue("items").jsonArray.all { row ->
                    val item = row.jsonObject
                    item["complete_file"] == JsonPrimitive(true) &&
                        item["sha"] == JsonPrimitive(GitHubCommitReconciliation.blobSha(fixture.after.getValue(item.getValue("path").jsonPrimitive.content)))
                }
            )
            val contents = changed.getValue("items").jsonArray.associate { row ->
                row.jsonObject.getValue("path").jsonPrimitive.content to row.jsonObject.getValue("content").jsonPrimitive.content
            }
            // Source ranges omit the terminal line separator; blob identity above
            // verifies the complete bytes, including that separator.
            assertEquals(fixture.after.mapValues { it.value.removeSuffix("\n") }, contents)
            assertEquals(JsonPrimitive(true), Json.parseToJsonElement(contents.getValue("config/decisions.json")).jsonObject["showDetails"])
            assertEquals(JsonPrimitive(8192), Json.parseToJsonElement(contents.getValue("config/source-pages.json")).jsonObject["maximumPageBytes"])
            assertEquals(JsonPrimitive(false), progress()["feature_completion_verified"])
            assertTrue(evidence.snapshot().first.values.all { it["binding"] == JsonPrimitive(binding) && it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] == JsonPrimitive(true) })

            fixture.restart()
            evidence = book().also { it.restore() }
            val replayed = execute("commit-recovery", "github_commit", commitArguments)
            assertEquals(committed["sha"], replayed["sha"])
            assertEquals(JsonPrimitive(1), progress()["committed_changes_observed"])
            assertEquals("Recovery cannot publish either feature twice", 1, fixture.commitWrites)
            assertEquals(1, fixture.refWrites)
        }
    }

    private class Fixture(private val directory: File) : AutoCloseable {
        val branch = "work-two-features"
        val origin = "a".repeat(40)
        val published = "d".repeat(40)
        private val baseTree = "b".repeat(40)
        private val resultTree = "e".repeat(40)
        val before = linkedMapOf("config/decisions.json" to "{\"showDetails\":false}\n", "config/source-pages.json" to "{\"maximumPageBytes\":1536}\n")
        val after = linkedMapOf("config/decisions.json" to "{\"showDetails\":true}\n", "config/source-pages.json" to "{\"maximumPageBytes\":8192}\n")
        val operations = setOf("github_read", "github_context", "github_stage_patch", "github_commit")
        private var head: String? = null
        private val blobReadCount = AtomicInteger()
        val blobReads get() = blobReadCount.get()
        var branchWrites = 0
        var commitWrites = 0
        var refWrites = 0
        private var committedMessage: String? = null
        private val http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val payload = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    request.method == HttpMethod.Post && path.endsWith("/git/refs") -> {
                        assertEquals(null, head)
                        assertEquals(JsonPrimitive("refs/heads/$branch"), payload?.get("ref"))
                        assertEquals(JsonPrimitive(origin), payload?.get("sha"))
                        head = origin
                        branchWrites++
                        respond(ref(branch, origin).toString(), HttpStatusCode.Created)
                    }
                    request.method == HttpMethod.Post && path.endsWith("/git/trees") -> {
                        assertEquals(JsonPrimitive(baseTree), payload?.get("base_tree"))
                        val files = requireNotNull(payload).getValue("tree").jsonArray.associate { it.jsonObject.getValue("path").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content }
                        assertEquals(after, files)
                        respond("""{"sha":"$resultTree"}""", HttpStatusCode.Created)
                    }
                    request.method == HttpMethod.Post && path.endsWith("/git/commits") -> {
                        assertEquals(JsonPrimitive(resultTree), payload?.get("tree"))
                        assertEquals(JsonArray(listOf(JsonPrimitive(origin))), payload?.get("parents"))
                        committedMessage = payload?.get("message")?.jsonPrimitive?.content
                        commitWrites++
                        respond("""{"sha":"$published"}""", HttpStatusCode.Created)
                    }
                    request.method == HttpMethod.Patch && path.endsWith("/git/refs/heads/$branch") -> {
                        assertEquals(origin, head)
                        assertEquals(JsonPrimitive(false), payload?.get("force"))
                        assertEquals(JsonPrimitive(published), payload?.get("sha"))
                        head = published
                        refWrites++
                        respond(ref(branch, published).toString())
                    }
                    request.method != HttpMethod.Get -> error("Unexpected mutation ${request.method} $path")
                    path == "/repos/owner/repo" -> respond("""{"default_branch":"main"}""")
                    path.endsWith("/git/ref/heads/main") -> respond(ref("main", origin).toString())
                    path.endsWith("/git/ref/heads/$branch") -> head?.let { respond(ref(branch, it).toString()) } ?: respond("{}", HttpStatusCode.NotFound)
                    "/git/commits/" in path -> {
                        val revision = path.substringAfterLast('/')
                        respond(
                            buildJsonObject {
                                put("sha", revision)
                                put("tree", buildJsonObject { put("sha", if (revision == published) resultTree else baseTree) })
                                put("parents", JsonArray(if (revision == published) listOf(buildJsonObject { put("sha", origin) }) else emptyList()))
                                committedMessage?.let { put("message", it) }
                            }.toString()
                        )
                    }
                    "/commits/" in path -> {
                        val selected = path.substringAfterLast('/')
                        val revision = if (selected == branch) {
                            requireNotNull(head)
                        } else if (selected == "main") {
                            origin
                        } else {
                            selected
                        }
                        respond("""{"sha":"$revision","commit":{"tree":{"sha":"${if (revision == published) resultTree else baseTree}"}}}""")
                    }
                    "/git/trees/" in path -> respond(tree(if (path.endsWith(resultTree)) after else before).toString())
                    "/git/blobs/" in path -> {
                        val sha = path.substringAfterLast('/')
                        val text = (before.values + after.values).single { GitHubCommitReconciliation.blobSha(it) == sha }
                        blobReadCount.incrementAndGet()
                        respond("""{"sha":"$sha","encoding":"base64","content":"${Base64.getEncoder().encodeToString(text.encodeToByteArray())}"}""")
                    }
                    else -> error("Unexpected read $path")
                }
            }
        )
        var tools = createTools()
            private set

        fun restart() {
            tools = createTools()
        }

        private fun createTools(): Map<String, GitHubOperationTool> {
            val backend = GitHubTool("fixture-token", http, repositoryContext = GitHubRepositoryContext("owner", "repo", "main"), connectionUid = "connection", conversationId = "conversation", taskId = "two-features", stateStore = GitHubRepositoryStateStore(directory))
            return operations.associateWith { GitHubOperationTool(it, backend, "two-feature-scope", views = GitHubResultViews()) }
        }

        fun arguments(content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            content()
        }

        fun read(revision: String) = arguments {
            put("commit_sha", revision)
            put(
                "ranges",
                JsonArray(
                    before.keys.map { path ->
                        buildJsonObject {
                            put("path", path)
                            put("start_line", 1)
                            put("end_line", 5)
                        }
                    }
                )
            )
        }

        fun data(result: AgentToolResult): JsonObject = ToolResultEnvelope.element(result.retainedContent ?: result.content).jsonObject.let { (it["data"] as? JsonObject) ?: it }

        private fun ref(name: String, revision: String) = buildJsonObject {
            put("ref", "refs/heads/$name")
            put("object", buildJsonObject { put("sha", revision) })
        }

        private fun tree(files: Map<String, String>) = buildJsonObject {
            put("truncated", false)
            put(
                "tree",
                JsonArray(
                    files.map { (path, text) ->
                        buildJsonObject {
                            put("path", path)
                            put("sha", GitHubCommitReconciliation.blobSha(text))
                            put("mode", "100644")
                            put("type", "blob")
                            put("size", text.encodeToByteArray().size)
                        }
                    }
                )
            )
        }

        override fun close() = http.close()
    }
}
