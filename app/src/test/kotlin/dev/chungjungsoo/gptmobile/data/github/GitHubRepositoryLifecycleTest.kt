package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositoryLifecycleTest {
    private val args = buildJsonObject {
        put("destination_owner", "me")
        put("destination_repo", "copy")
        put("owner_type", "user")
        put("visibility", "private")
        put("source_owner", "upstream")
        put("source_repo", "project")
        put("source_ref", "main")
        put("copy_mode", "snapshot")
    }

    private class Fixture(val content: String = "hello\n") {
        val source = "a".repeat(40)
        val tree = "b".repeat(40)
        val initial = "c".repeat(40)
        val imported = "d".repeat(40)
        var sourceHead = source
        var destinationHead = initial
        var created = false
        var creates = 0
        var failAfterCreate = false
        var refusePush = false
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, JsonObject>>()
        val plans = mutableMapOf<String, JsonObject>()
        val bytes = content.toByteArray()
        val blob = MessageDigest.getInstance("SHA-1").apply {
            update("blob ${bytes.size}\u0000".toByteArray())
            update(bytes)
        }.digest().joinToString("") { "%02x".format(it) }

        private fun repository() = buildJsonObject {
            put("id", 72)
            put("full_name", "me/copy")
            put("private", true)
            put("fork", false)
            put("default_branch", "main")
        }

        private fun commit(sha: String) = buildJsonObject {
            put("sha", sha)
            put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
        }

        suspend fun read(path: String): JsonObject {
            reads += path
            return when (path) {
                "/user" -> buildJsonObject { put("login", "me") }
                "/repos/upstream/project" -> buildJsonObject { put("private", true) }
                "/repos/upstream/project/commits/main" -> commit(sourceHead)
                "/repos/upstream/project/commits/$source" -> commit(source)
                "/repos/upstream/project/git/trees/$tree?recursive=1" -> buildJsonObject {
                    put("truncated", false)
                    put(
                        "tree",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("path", "README.md")
                                    put("type", "blob")
                                    put("mode", "100644")
                                    put("sha", blob)
                                    put("size", bytes.size)
                                }
                            )
                        )
                    )
                }
                "/repos/upstream/project/git/blobs/$blob" -> buildJsonObject {
                    put("sha", blob)
                    put("encoding", "base64")
                    put("content", Base64.getEncoder().encodeToString(bytes))
                }
                "/repos/me/copy" -> {
                    check(created)
                    if (failAfterCreate) {
                        failAfterCreate = false
                        error("simulated interruption after create")
                    }
                    repository()
                }
                "/repos/me/copy/git/ref/heads/main" -> buildJsonObject { put("object", buildJsonObject { put("sha", destinationHead) }) }
                "/repos/me/copy/git/commits/$initial" -> buildJsonObject { put("parents", JsonArray(emptyList())) }
                "/repos/me/copy/commits/main" -> commit(destinationHead)
                else -> error("Unexpected read: $path")
            }
        }

        suspend fun write(method: String, path: String, body: JsonObject): JsonObject {
            writes += path to body
            return when (path) {
                "/user/repos" -> {
                    // The real WorkspaceClient journal retains this exact POST.
                    if (!created) creates++
                    created = true
                    repository()
                }
                "/repos/me/copy/git/blobs" -> buildJsonObject { put("sha", blob) }
                "/repos/me/copy/git/trees" -> buildJsonObject { put("sha", tree) }
                "/repos/me/copy/git/commits" -> {
                    assertEquals(JsonArray(listOf(JsonPrimitive(initial))), body["parents"])
                    buildJsonObject { put("sha", imported) }
                }
                "/repos/me/copy/git/refs/heads/main" -> {
                    assertEquals("PATCH", method)
                    assertEquals(JsonPrimitive(false), body["force"])
                    check(!refusePush && destinationHead == initial) { "concurrent destination edit" }
                    destinationHead = imported
                    buildJsonObject { put("object", buildJsonObject { put("sha", imported) }) }
                }
                else -> error("Unexpected write: $path")
            }
        }

        fun client() = GitHubRepositoryLifecycle(::read, ::write, loadPlan = { plans[it] }, savePlan = { key, value ->
            check(plans[key] == null || plans[key] == value)
            plans[key] = value
        })
    }

    @Test fun snapshotQualifiesEveryBlobThenVerifiesExactTreeAndRetainsRecoveryProof() = runTest {
        val fixture = Fixture()
        val result = fixture.client().execute("import_repository", args)
        assertEquals(JsonPrimitive("VERIFIED"), result["status"])
        assertEquals(JsonPrimitive(false), result["history_preserved"])
        assertEquals(result["source_tree_sha"], result["destination_tree_sha"])
        assertEquals(1, fixture.creates)
        assertEquals(JsonPrimitive(fixture.source), result["source_commit_sha"])
        val readback = fixture.client().execute("repository_lifecycle_status", JsonObject(args + mapOf("operation" to JsonPrimitive("import_repository"), "expected_repository_id" to JsonPrimitive(72))))
        assertEquals(JsonPrimitive("VERIFIED"), readback["status"])
        assertEquals(JsonPrimitive(fixture.imported), readback["destination_commit_sha"])
        assertEquals(1, fixture.creates)
    }

    @Test fun interruptedCreatePinsSourceAndRequiresReviewedDestinationBeforeContinuing() = runTest {
        val fixture = Fixture().apply { failAfterCreate = true }
        assertTrue(runCatching { fixture.client().execute("import_repository", args) }.isFailure)
        fixture.sourceHead = "e".repeat(40)
        val blocked = fixture.client().execute("import_repository", args)
        assertEquals(JsonPrimitive("GITHUB_IMPORT_BASE_CONFIRMATION_REQUIRED"), blocked["error_code"])
        assertEquals(JsonPrimitive(fixture.source), blocked["source_commit_sha"])
        assertFalse(fixture.writes.any { it.first.endsWith("/git/trees") })
        val resumed = fixture.client().execute("import_repository", JsonObject(args + ("expected_head_sha" to JsonPrimitive(fixture.initial))))
        assertEquals(JsonPrimitive("VERIFIED"), resumed["status"])
        assertEquals(JsonPrimitive(fixture.source), resumed["source_commit_sha"])
        assertTrue(fixture.reads.contains("/repos/upstream/project/commits/${fixture.source}"))
        assertEquals(1, fixture.creates)
    }

    @Test fun aConcurrentDestinationEditCannotBeOverwrittenOrReportedAsVerified() = runTest {
        val fixture = Fixture().apply { refusePush = true }
        assertTrue(runCatching { fixture.client().execute("import_repository", args) }.isFailure)
        fixture.destinationHead = "f".repeat(40)
        val status = JsonObject(args + mapOf("operation" to JsonPrimitive("import_repository"), "expected_repository_id" to JsonPrimitive(72)))
        assertTrue(runCatching { fixture.client().execute("repository_lifecycle_status", status) }.isFailure)
        assertEquals("f".repeat(40), fixture.destinationHead)
    }

    @Test fun snapshotLfsCannotCreateAnIncompleteDestination() = runTest {
        val fixture = Fixture("version https://git-lfs.github.com/spec/v1\noid sha256:${"a".repeat(64)}\nsize 42\n")
        val failure = runCatching { fixture.client().execute("import_repository", args) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Git LFS"))
        assertEquals(0, fixture.creates)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun unsupportedHostTransfersAreSpecificAndDoNotCreateAnything() = runTest {
        val fixture = Fixture()
        for (operation in listOf("clone_repository", "import_repository")) {
            val result = fixture.client().execute(operation, JsonObject(args + ("copy_mode" to JsonPrimitive("history"))))
            assertEquals(JsonPrimitive("GITHUB_GATEWAY_CAPABILITY_REQUIRED"), result["error_code"])
            assertEquals(JsonPrimitive(false), result["dispatched"])
        }
        assertTrue(fixture.reads.isEmpty())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun statusCannotTurnAnExistingRepositoryIntoCreationProof() = runTest {
        val fixture = Fixture().apply { created = true }
        val status = JsonObject(args + mapOf("operation" to JsonPrimitive("create_repository"), "expected_repository_id" to JsonPrimitive(72)))
        assertTrue(runCatching { fixture.client().execute("repository_lifecycle_status", status) }.isFailure)
        assertTrue(fixture.reads.isEmpty())
    }

    @Test fun gatewaySupportIsQualifiedInsteadOfInferredFromCallbackPresence() = runTest {
        val client = GitHubRepositoryLifecycle({ error("no native read") }, { _, _, _ -> error("no write") }, gateway = { error("offline") })
        val result = client.execute("repository_lifecycle_capabilities", JsonObject(emptyMap()))
        assertEquals(JsonPrimitive(true), result["create"])
        assertEquals(JsonPrimitive(false), result["history_import"])
        assertEquals(JsonPrimitive(false), result["gateway_clone"])
    }

    @Test fun explanatoryExamplesDoNotBecomeLifecycleMutations() {
        for (request in listOf("Explain how to create a new repository", "What does fork a repository mean?", "Review this log:\n```\ncreate a repository\n```")) {
            assertEquals(null, GitHubRepositoryLifecycle.intent(request))
        }
    }
}
