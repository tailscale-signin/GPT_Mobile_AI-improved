package dev.chungjungsoo.gptmobile.data.github

import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPersistentEvidenceTest {
    @Test fun pathSearchFindsFilesBeyondTheContentScanWindowWithoutFetchingBlobs() = runTest {
        val entries = (0 until 2100).map { item("src/File${it.toString().padStart(4, '0')}.kt", sha(it + 1), 30) } +
            item("z/scripts/target.py", sha(3000), 30)
        val client = session(GitHubRepositoryStateStore(), request = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(entries)
                else -> error("Path search must use tree metadata without fetching blobs")
            }
        })
        val result = client.execute("search_repository", "owner", "repo", args("query" to JsonPrimitive(".py"), "mode" to JsonPrimitive("paths"))).jsonObject
        assertEquals("z/scripts/target.py", result.getValue("items").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
        assertTrue(result.getValue("complete").jsonPrimitive.booleanOrNull == true)
        assertEquals(0, result.getValue("new_blobs").jsonPrimitive.intOrNull)
    }

    @Test fun wildcardPathListingUsesExactContinuationWithoutDuplicatingEntries() = runTest {
        val entries = (0 until 25).map { item("src/File${it.toString().padStart(2, '0')}.kt", sha(it + 1), 30) }
        val client = session(GitHubRepositoryStateStore(), request = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(entries)
                else -> error("No blob fetch expected")
            }
        })
        val first = client.execute("search_repository", "owner", "repo", args("query" to JsonPrimitive("*"), "mode" to JsonPrimitive("paths"))).jsonObject
        assertEquals(20, first.getValue("items").jsonArray.size)
        val second = client.execute("search_repository", "owner", "repo", first.getValue("continuation").jsonObject).jsonObject
        val paths = (first.getValue("items").jsonArray + second.getValue("items").jsonArray).map { it.jsonObject.getValue("path").jsonPrimitive.content }
        assertEquals(25, paths.distinct().size)
        assertFalse(second.getValue("has_more").jsonPrimitive.booleanOrNull == true)
    }

    @Test fun slowSearchReturnsResumableCursorAndNeverSkipsTimedOutFile() = runTest {
        var slow = true
        var fetched = 0
        val store = GitHubRepositoryStateStore()
        val client = session(store, request = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item("src/Target.kt", sha(1), 30)))
                else -> {
                    fetched++
                    if (slow) kotlinx.coroutines.delay(20_000)
                    blob(sha(1), "fun findMe() = 1")
                }
            }
        })
        val first = client.execute("search_repository", "owner", "repo", args("query" to JsonPrimitive("findMe"))).jsonObject
        assertEquals(0, first.getValue("scanned_files").jsonPrimitive.intOrNull)
        assertTrue(first.getValue("read_deadline_reached").jsonPrimitive.booleanOrNull == true)
        assertEquals(0, first.getValue("continuation").jsonObject.getValue("index_cursor").jsonPrimitive.intOrNull)
        assertFalse(first.getValue("complete").jsonPrimitive.booleanOrNull == true)
        slow = false
        val resumed = client.execute("search_repository", "owner", "repo", first.getValue("continuation").jsonObject).jsonObject
        assertEquals("src/Target.kt", resumed.getValue("items").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(2, fetched)
    }

    @Test fun missingWorkflowPathSuggestsActualPinnedFilesWithoutReadingInventedPath() = runTest {
        val client = session(GitHubRepositoryStateStore(), request = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item(".github/workflows/build.yml", sha(1), 30)))
                else -> error("No nonexistent blob may be fetched")
            }
        })
        val failure = runCatching { client.execute("read_ranges", "owner", "repo", args("path" to JsonPrimitive(".github/workflows/main.yml"))) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains(".github/workflows/build.yml"))
        assertTrue(failure?.message.orEmpty().contains("mode=paths"))
    }

    @Test fun changesWithoutBaseReturnsAnActionableErrorBeforeAnyNetworkWork() = runTest {
        val client = session(GitHubRepositoryStateStore(), request = { error("Invalid request must not resolve the repository") })
        val failure = runCatching { client.execute("changed_since", "owner", "repo", buildJsonObject {}) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("CHANGES_BASE_REQUIRED"))
        assertTrue(failure?.message.orEmpty().contains("base_sha"))
        assertTrue(failure?.message.orEmpty().contains("github_pr"))
    }

    private val head = "a".repeat(40)
    private val old = "b".repeat(40)
    private val headTree = "c".repeat(40)
    private val oldTree = "d".repeat(40)
    private fun sha(index: Int) = index.toString(16).padStart(40, '0')
    private fun args(vararg fields: Pair<String, JsonElement>) = buildJsonObject {
        put("ref", "work")
        fields.forEach { (key, value) -> put(key, value) }
    }
    private fun item(path: String, sha: String, size: Int) = buildJsonObject {
        put("path", path)
        put("sha", sha)
        put("type", "blob")
        put("mode", "100644")
        put("size", size)
    }
    private fun commit(sha: String, tree: String) = buildJsonObject {
        put("sha", sha)
        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
    }
    private fun tree(entries: List<JsonObject>, truncated: Boolean = false) = buildJsonObject {
        put("tree", JsonArray(entries))
        put("truncated", truncated)
    }
    private fun blob(sha: String, text: String) = buildJsonObject {
        put("sha", sha)
        put("encoding", "base64")
        put("content", Base64.getEncoder().encodeToString(text.encodeToByteArray()))
    }
    private fun session(store: GitHubRepositoryStateStore, task: String = "task", account: String = "account", request: suspend (String) -> JsonElement, write: suspend (String, JsonObject) -> JsonElement = { _, _ -> error("No write expected") }) = GitHubRepositorySession(account, "connection", "conversation", task, store, request, write)

    @Test fun largeBlobAndLongLineUseBoundedExpandableEvidenceAcrossRestart() = runTest {
        val directory = Files.createTempDirectory("github-large-evidence").toFile()
        val text = "val payload = \"" + "alpha🧪".repeat(50000) + "\"\nfun finalMarker() = 7\n"
        var requests = 0
        val fetch: suspend (String) -> JsonElement = { path ->
            requests++
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item("src/Large.kt", sha(1), text.encodeToByteArray().size)))
                "/git/blobs/" in path -> blob(sha(1), text)
                else -> error(path)
            }
        }
        try {
            val first = session(GitHubRepositoryStateStore(directory), request = fetch)
            val page = first.execute("read_ranges", "owner", "repo", args("path" to JsonPrimitive("src/Large.kt"), "end_line" to JsonPrimitive(1))).jsonObject
            val firstItem = page["items"]!!.jsonArray.single().jsonObject
            assertTrue(firstItem["truncated"]!!.jsonPrimitive.booleanOrNull == true)
            assertFalse(firstItem["complete_file"]!!.jsonPrimitive.booleanOrNull == true)
            assertTrue(firstItem["content"]!!.jsonPrimitive.content.encodeToByteArray().size <= 32768)
            assertEquals(3, requests)
            val restored = session(GitHubRepositoryStateStore(directory), request = { error("Immutable content must survive restart: $it") })
            val expanded = restored.execute("read_ranges", "owner", "repo", args("evidence_id" to page.getValue("evidence_id"), "ranges" to JsonArray(listOf(firstItem.getValue("continuation"))))).jsonObject
            val next = expanded["items"]!!.jsonArray.single().jsonObject
            assertEquals(firstItem["content"]!!.jsonPrimitive.content.length, next["start_offset"]!!.jsonPrimitive.intOrNull)
            assertEquals(text.substring(firstItem["content"]!!.jsonPrimitive.content.length).take(next["content"]!!.jsonPrimitive.content.length), next["content"]!!.jsonPrimitive.content)
            val found = restored.execute("search_repository", "owner", "repo", args("query" to JsonPrimitive("finalMarker"), "mode" to JsonPrimitive("definitions"))).jsonObject
            assertEquals("src/Large.kt", found["items"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
            assertEquals(head, restored.snapshot()!!.commitSha)
            assertTrue(restored.snapshot()!!.evidenceHandles.isNotEmpty())
            assertTrue(runCatching { session(GitHubRepositoryStateStore(directory), account = "other-account", request = fetch).execute("read_ranges", "owner", "repo", args("evidence_id" to page.getValue("evidence_id"))) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun taskPinsRevisionButExplicitContextChangeAndNewTaskBootstrapAreSupported() = runTest {
        val store = GitHubRepositoryStateStore()
        val fetch: suspend (String) -> JsonElement = { path ->
            when {
                path.endsWith("/commits/other") || path.endsWith("/commits/$old") -> commit(old, oldTree)
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(emptyList())
                else -> error(path)
            }
        }
        val client = session(store, request = fetch)
        client.execute("resolve_context", "owner", "repo", args())
        client.execute("search_repository", "owner", "repo", args("ref" to JsonPrimitive("other"), "query" to JsonPrimitive("x")))
        assertEquals(head, client.snapshot()!!.commitSha)
        client.execute("resolve_context", "owner", "repo", args("ref" to JsonPrimitive("other")))
        assertEquals(old, client.snapshot()!!.commitSha)
        assertTrue(runCatching { client.execute("read_ranges", "owner", "repo", args("commit_sha" to JsonPrimitive(head), "ranges" to JsonArray(emptyList()))) }.isFailure)
    }

    @Test fun immutableAlternateReadDoesNotRebindTaskAndConversationSelectionSeedsNextTask() = runTest {
        val store = GitHubRepositoryStateStore()
        val fetch: suspend (String) -> JsonElement = { path ->
            when {
                path.endsWith("/commits/other") || path.endsWith("/commits/$old") -> commit(old, oldTree)
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item("A.kt", sha(1), 8)))
                "/git/blobs/" in path -> blob(sha(1), "val x=1\n")
                else -> error(path)
            }
        }
        val first = session(store, request = fetch)
        first.execute("resolve_context", "owner", "repo", args("ref" to JsonPrimitive("other")))
        first.execute("read_ranges", "owner", "repo", args("commit_sha" to JsonPrimitive(head), "path" to JsonPrimitive("A.kt")))
        assertEquals(old, first.snapshot()!!.commitSha)
        val next = session(store, task = "next-task", request = fetch)
        assertEquals("other", next.context()!!.ref)
        val fresh = next.execute("resolve_context", "owner", "repo", buildJsonObject {}).jsonObject
        assertEquals(old, fresh["commit_sha"]!!.jsonPrimitive.content)
        assertNotEquals(first.snapshot()!!.taskId, next.snapshot()!!.taskId)
    }

    @Test fun anotherSessionSelectingANewRevisionIsNotOverwrittenByAnOlderEvidenceRead() = runTest {
        val store = GitHubRepositoryStateStore()
        val fetch: suspend (String) -> JsonElement = { path ->
            when {
                path.endsWith("/commits/other") -> commit(old, oldTree)
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item("A.kt", sha(1), 8)))
                "/git/blobs/" in path -> blob(sha(1), "val x=1\n")
                else -> error(path)
            }
        }
        val earlier = session(store, request = fetch)
        earlier.execute("resolve_context", "owner", "repo", args())
        val later = session(store, request = fetch)
        later.execute("resolve_context", "owner", "repo", args("ref" to JsonPrimitive("other")))
        val read = earlier.execute("read_ranges", "owner", "repo", args("commit_sha" to JsonPrimitive(head), "path" to JsonPrimitive("A.kt"))).jsonObject
        assertEquals(head, read["commit_sha"]!!.jsonPrimitive.content)
        assertEquals(old, earlier.snapshot()!!.commitSha)
        assertEquals(old, later.snapshot()!!.commitSha)
        assertTrue(later.snapshot()!!.evidenceHandles.contains(read["evidence_id"]!!.jsonPrimitive.content))
        assertEquals("other", session(store, task = "following-task", request = fetch).context()!!.ref)
    }

    @Test fun concurrentEvidenceReadsRetainEveryHandleWithoutRebindingTheSnapshot() = runTest {
        val store = GitHubRepositoryStateStore()
        val body = (1..8).joinToString("\n") { "line $it" }
        val fetch: suspend (String) -> JsonElement = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item("A.kt", sha(1), body.length)))
                "/git/blobs/" in path -> blob(sha(1), body)
                else -> error(path)
            }
        }
        val client = session(store, request = fetch)
        client.execute("resolve_context", "owner", "repo", args())
        val handles = (1..8).map { line ->
            async {
                session(store, request = fetch).execute("read_ranges", "owner", "repo", args("path" to JsonPrimitive("A.kt"), "start_line" to JsonPrimitive(line), "end_line" to JsonPrimitive(line))).jsonObject["evidence_id"]!!.jsonPrimitive.content
            }
        }.awaitAll()
        assertEquals(8, handles.distinct().size)
        assertEquals(handles.toSet(), client.snapshot()!!.evidenceHandles.toSet())
        assertEquals(head, client.snapshot()!!.commitSha)
    }

    @Test fun revisionChangeWhilePatchBodiesLoadCannotPublishAnOldStageOverTheNewSelection() = runTest {
        val store = GitHubRepositoryStateStore()
        val readingBlob = CompletableDeferred<Unit>()
        val releaseBlob = CompletableDeferred<Unit>()
        val fetch: suspend (String) -> JsonElement = { path ->
            when {
                path.endsWith("/commits/other") -> commit(old, oldTree)
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item("A.kt", sha(1), 8)))
                else -> {
                    readingBlob.complete(Unit)
                    releaseBlob.await()
                    blob(sha(1), "val x=1\n")
                }
            }
        }
        val client = session(store, request = fetch)
        client.execute("resolve_context", "owner", "repo", args())
        val staged = async {
            runCatching {
                client.execute(
                    "stage_patch",
                    "owner",
                    "repo",
                    args(
                        "edits" to JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("path", "A.kt")
                                    put("expected_blob_sha", sha(1))
                                    put("old_text", "x=1")
                                    put("new_text", "x=2")
                                }
                            )
                        )
                    )
                )
            }
        }
        readingBlob.await()
        session(store, request = fetch).execute("resolve_context", "owner", "repo", args("ref" to JsonPrimitive("other")))
        releaseBlob.complete(Unit)
        assertTrue(staged.await().exceptionOrNull()?.message.orEmpty().contains("Task revision changed"))
        assertEquals(old, client.snapshot()!!.commitSha)
        assertEquals(null, client.snapshot()!!.stagedPatchId)
    }

    @Test fun searchContinuationIsBoundedIncludesScopeAndReusesUnchangedBlobs() = runTest {
        var blobs = 0
        val entries = (1..30).map { item("src/F${it.toString().padStart(2, '0')}.kt", sha(it), 24) }
        val fetch: suspend (String) -> JsonElement = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(entries)
                "/git/blobs/" in path -> {
                    blobs++
                    blob(path.substringAfterLast('/'), "val ordinary = 1\n")
                }
                else -> error(path)
            }
        }
        val client = session(GitHubRepositoryStateStore(), request = fetch)
        val first = client.execute("search_repository", "owner", "repo", args("query" to JsonPrimitive("missing"), "path_prefix" to JsonPrimitive("src/"))).jsonObject
        assertEquals(4, blobs)
        assertTrue(first["has_more"]!!.jsonPrimitive.booleanOrNull == true)
        assertFalse(first["complete"]!!.jsonPrimitive.booleanOrNull == true)
        val continuation = first["continuation"]!!.jsonObject
        assertEquals("src/", continuation["path_prefix"]!!.jsonPrimitive.content)
        var page = client.execute("search_repository", "owner", "repo", continuation).jsonObject
        while (page["has_more"]?.jsonPrimitive?.booleanOrNull == true) {
            page = client.execute("search_repository", "owner", "repo", page.getValue("continuation").jsonObject).jsonObject
        }
        assertEquals(30, blobs)
        client.execute("search_repository", "owner", "repo", args("query" to JsonPrimitive("missing")))
        assertEquals(30, blobs)
        assertEquals(30, client.execute("index_status", "owner", "repo", args()).jsonObject["indexed_blobs"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun binaryCandidatesConsumeTheFetchBudgetInsteadOfBypassingIt() = runTest {
        var blobs = 0
        val client = session(GitHubRepositoryStateStore(), request = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree((1..100).map { item("binary$it", sha(it), 1) })
                else -> {
                    blobs++
                    blob(path.substringAfterLast('/'), "\u0000")
                }
            }
        })
        val result = client.execute("search_repository", "owner", "repo", args("query" to JsonPrimitive("absent"))).jsonObject
        assertEquals(4, blobs)
        assertEquals(4, result["skipped_files"]!!.jsonPrimitive.intOrNull)
        assertTrue(result["has_more"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test fun stagedExactPatchPersistsOriginalBlobAndCommitsOnceAcrossRestart() = runTest {
        val dir = Files.createTempDirectory("github-stage").toFile()
        var writes = 0
        val fetch: suspend (String) -> JsonElement = { path ->
            when {
                "/commits/" in path -> commit(head, headTree)
                "/git/trees/" in path -> tree(listOf(item("A.kt", sha(1), 30)))
                else -> blob(sha(1), "fun answer() = 41\n")
            }
        }
        val write: suspend (String, JsonObject) -> JsonElement = { _, data ->
            writes++
            assertEquals(head, data["expected_head_sha"]!!.jsonPrimitive.content)
            val file = data["files"]!!.jsonArray.single().jsonObject
            assertEquals(sha(1), file["expected_blob_sha"]!!.jsonPrimitive.content)
            assertEquals("fun answer() = 42\n", file["content"]!!.jsonPrimitive.content)
            buildJsonObject { put("sha", old) }
        }
        try {
            val client = session(GitHubRepositoryStateStore(dir), request = fetch, write = write)
            val edits = JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", "A.kt")
                        put("expected_blob_sha", sha(1))
                        put("old_text", "41")
                        put("new_text", "42")
                    }
                )
            )
            val staged = client.execute("stage_patch", "owner", "repo", args("edits" to edits)).jsonObject
            assertFalse(staged["committed"]!!.jsonPrimitive.booleanOrNull == true)
            assertTrue(staged.toString().contains("42"))
            val restored = session(GitHubRepositoryStateStore(dir), request = fetch, write = write)
            val commitArgs = args("stage_id" to staged.getValue("stage_id"), "branch" to JsonPrimitive("work"), "message" to JsonPrimitive("Fix answer"))
            val receipt = restored.execute("commit_staged_patch", "owner", "repo", commitArgs)
            assertEquals(receipt, session(GitHubRepositoryStateStore(dir), request = fetch, write = write).execute("commit_staged_patch", "owner", "repo", commitArgs))
            assertEquals(1, writes)
            assertTrue(runCatching { session(GitHubRepositoryStateStore(dir), task = "another", request = fetch, write = write).execute("commit_staged_patch", "owner", "repo", commitArgs) }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun exactPatchesRejectAmbiguousAnchorsWrongBlobAndTraversal() {
        val original = mapOf("A.kt" to (sha(1) to "x x"))
        fun edit(blob: String = sha(1), path: String = "A.kt", occurrence: Int? = null) = JsonArray(
            listOf(
                buildJsonObject {
                    put("path", path)
                    put("expected_blob_sha", blob)
                    put("old_text", "x")
                    put("new_text", "y")
                    occurrence?.let { put("occurrence", it) }
                }
            )
        )
        assertTrue(runCatching { GitHubPatchStager.apply(original, edit()) }.isFailure)
        assertTrue(runCatching { GitHubPatchStager.apply(original, edit(sha(2), occurrence = 1)) }.isFailure)
        assertTrue(runCatching { GitHubPatchStager.apply(original, edit(path = "../A.kt", occurrence = 1)) }.isFailure)
        assertEquals("x y", GitHubPatchStager.apply(original, edit(occurrence = 2)).single().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun exactPatchAnchorsCountOverlappingOccurrencesBeforeSelectingAnEdit() {
        val original = mapOf("A.kt" to (sha(1) to "aaa"))
        fun edit(occurrence: Int? = null) = JsonArray(
            listOf(
                buildJsonObject {
                    put("path", "A.kt")
                    put("expected_blob_sha", sha(1))
                    put("old_text", "aa")
                    put("new_text", "y")
                    occurrence?.let { put("occurrence", it) }
                }
            )
        )
        assertTrue(runCatching { GitHubPatchStager.apply(original, edit()) }.exceptionOrNull()?.message.orEmpty().contains("Ambiguous"))
        assertEquals("ya", GitHubPatchStager.apply(original, edit(1)).single().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("ay", GitHubPatchStager.apply(original, edit(2)).single().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun treeComparisonPagesEveryOneOf350ChangedFiles() = runTest {
        val client = session(GitHubRepositoryStateStore(), request = { path ->
            when {
                path.endsWith("/commits/$old") -> commit(old, oldTree)
                "/commits/" in path -> commit(head, headTree)
                oldTree in path -> tree((1..350).map { item("src/$it.kt", sha(it), 1) })
                headTree in path -> tree((1..350).map { item("src/$it.kt", sha(it + 1000), 1) })
                else -> error(path)
            }
        })
        val names = mutableSetOf<String>()
        var request = args("base" to JsonPrimitive(old))
        repeat(4) { page ->
            val result = client.execute("changed_since", "owner", "repo", request).jsonObject
            assertEquals(350, result["files_total"]!!.jsonPrimitive.intOrNull)
            assertTrue(result["tree_complete"]!!.jsonPrimitive.booleanOrNull == true)
            result["files"]!!.jsonArray.forEach { names += it.jsonObject["filename"]!!.jsonPrimitive.content }
            if (page < 3) request = result["continuation"]!!.jsonObject else assertFalse(result["has_more"]!!.jsonPrimitive.booleanOrNull == true)
        }
        assertEquals(350, names.size)
    }

    @Test fun localIndexMakesBoundsAndOneCharacterSymbolsExplicit() {
        val index = GitHubLocalIndex.build("val x = 1\n".repeat(10000))
        assertTrue(index["definitions"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == "x" })
        assertTrue(index["definitions"]!!.jsonArray.size <= 2000)
        assertTrue(index["references"]!!.jsonArray.size <= 6000)
        assertFalse(index["definitions_complete"]!!.jsonPrimitive.booleanOrNull == true)
        assertFalse(index["references_complete"]!!.jsonPrimitive.booleanOrNull == true)
        assertFalse(index["compiler_resolution"]!!.jsonPrimitive.booleanOrNull == true)
        assertNotNull(index["line_count"])
    }
}
