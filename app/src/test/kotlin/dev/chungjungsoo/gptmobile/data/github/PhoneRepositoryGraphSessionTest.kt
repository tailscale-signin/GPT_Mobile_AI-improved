package dev.chungjungsoo.gptmobile.data.github

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRepositoryGraphSessionTest {
    private val head = "a".repeat(40)
    private val otherHead = "c".repeat(40)
    private val treeSha = "b".repeat(40)
    private val state = GitHubRepositoryStateStore()
    private var blobs = 0
    private var treeOverride: JsonObject? = null
    private var treeSize = 50
    private var truncated = false
    private var invalidSource = false
    private var sourceText: String? = null
    private var sourceByBlob: Map<String, String> = emptyMap()
    private var delayedBlob: String? = null

    private suspend fun request(path: String): JsonElement = when {
        "/commits/" in path -> buildJsonObject {
            put("sha", path.substringAfterLast('/'))
            put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", treeSha) }) })
        }
        "/git/trees/" in path -> treeOverride ?: buildJsonObject {
            put("sha", treeSha)
            put("truncated", truncated)
            put(
                "tree",
                buildJsonArray {
                    (1..treeSize).forEach { n ->
                        add(
                            buildJsonObject {
                                put("path", "src/File$n.kt")
                                put("sha", "%040x".format(n))
                                put("size", 40)
                                put("type", "blob")
                                put("mode", "100644")
                            }
                        )
                    }
                }
            )
        }
        "/git/blobs/" in path -> {
            blobs++
            if (path.substringAfterLast('/') == delayedBlob) kotlinx.coroutines.delay(1000)
            buildJsonObject {
                put("sha", path.substringAfterLast('/'))
                put("encoding", "base64")
                put("content", Base64.getEncoder().encodeToString((if (invalidSource) "\u0000" else sourceByBlob[path.substringAfterLast('/')] ?: sourceText ?: "package sample\nfun useful() = 1\n").toByteArray()))
            }
        }
        else -> error("Unexpected transport $path")
    }

    private fun session(task: String = "map", account: String = "account", store: GitHubRepositoryStateStore = state, contextReadTimeoutMs: Long = 2500) = GitHubRepositorySession(
        account,
        "connection",
        "map-conversation",
        task,
        store,
        ::request,
        { _, _ -> error("Phone map must never write to GitHub") },
        taskContextBlobTimeoutMs = contextReadTimeoutMs
    )

    private fun arguments(action: String, cursor: String? = null, revision: String = head) = buildJsonObject {
        put("action", action)
        put("ref", "main")
        put("commit_sha", revision)
        cursor?.let { put("index_cursor", it) }
    }

    @Test fun preparingTheMapDoesNotResumeItsCallerForEveryFile() = runTest {
        val resumes = AtomicInteger()
        val scheduler = StandardTestDispatcher(testScheduler)
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                resumes.incrementAndGet()
                scheduler.dispatch(context, block)
            }
        }
        val result = withContext(caller) { session().repositoryMap("owner", "repo", arguments("architecture")) }
        assertEquals(treeSize, result.getValue("graph").jsonObject.getValue("expected_files").jsonPrimitive.int)
        assertTrue("Map preparation posted ${resumes.get()} times to the caller for $treeSize files", resumes.get() <= 3)
    }

    @Test fun repeatedBlobDeadlinesStopRefetchingAndIndependentPinnedReadsCanRecoverTheBundle() = runTest {
        treeSize = 2
        delayedBlob = "%040x".format(1)
        val args = JsonObject(arguments("task_context_bundle") + ("query" to JsonPrimitive("src/File1.kt src/File2.kt")))
        val reader = session(contextReadTimeoutMs = 100)
        repeat(2) {
            val partial = reader.repositoryMap("owner", "repo", args)
            assertEquals(JsonPrimitive(1), partial.getValue("deferred_reason_counts").jsonObject["BLOB_READ_DEADLINE"])
            assertEquals(JsonPrimitive(false), partial["source_complete"])
            assertTrue((partial.getValue("selected_paths") as JsonArray).contains(JsonPrimitive("src/File2.kt")))
        }
        val before = blobs
        val bounded = session(contextReadTimeoutMs = 100).repositoryMap("owner", "repo", args)
        assertEquals(before, blobs)
        assertEquals(JsonPrimitive(1), bounded.getValue("deferred_reason_counts").jsonObject["NO_PROGRESS_LIMIT_REACHED"])
        assertEquals(JsonPrimitive(false), bounded["automatic_retry"])

        delayedBlob = null
        reader.execute(
            "read_ranges",
            "owner",
            "repo",
            buildJsonObject {
                put("ref", "main")
                put("commit_sha", head)
                put("path", "src/File1.kt")
                put("start_line", 1)
                put("end_line", 2)
            }
        )
        val fetched = blobs
        val recovered = reader.repositoryMap("owner", "repo", args)
        assertEquals(fetched, blobs)
        assertEquals(JsonPrimitive(true), recovered["source_complete"])
        assertEquals(JsonPrimitive(2), recovered["cached_files"])
        assertEquals(JsonObject(emptyMap()), recovered["deferred_reason_counts"])
        val summary = reader.repositoryMap("owner", "repo", arguments("status")).getValue("task_context").jsonObject
        assertFalse(summary.toString().contains("excerpt"))
        assertFalse(summary.toString().contains("fun useful"))
    }

    @Test fun malformedTreesCannotBecomeSharedSourceForAnotherTask() = runTest {
        val args = arguments("architecture")
        val legacyTree = buildJsonObject {
            put("tree_sha", treeSha)
            put("tree", JsonArray(emptyList()))
            put("truncated", false)
        }
        state.write("account:owner/repo", "phone-graph:tree-v1:$treeSha", legacyTree)
        state.write("account:owner/repo", "tree:$head", legacyTree)
        val responses = listOf(
            buildJsonObject {
                put("sha", otherHead)
                put("tree", JsonArray(emptyList()))
            } to "EVIDENCE_REVISION_MISMATCH",
            buildJsonObject { put("sha", treeSha) } to "GITHUB_TREE_UNAVAILABLE"
        )
        for ((response, expected) in responses) {
            treeOverride = response
            val failure = runCatching { session().repositoryMap("owner", "repo", args) }.exceptionOrNull()
            assertEquals(expected, (failure as? GitHubReadValidation)?.code)
        }
        treeOverride = null
        val recovered = session("new-chat-task").repositoryMap("owner", "repo", args)
        assertEquals(treeSize, recovered.getValue("graph").jsonObject.getValue("expected_files").jsonPrimitive.int)
        treeOverride = buildJsonObject { put("message", "Unexpected tree refetch") }
        val reused = session("another-task").repositoryMap("owner", "repo", args)
        assertEquals(recovered["nodes"], reused["nodes"])
    }

    @Test fun initialMapShowsTheRealTreeWithoutDownloadingAnySource() = runTest {
        val reader = session()
        val status = reader.repositoryMap("owner", "repo", arguments("status"))
        assertEquals(head, status.getValue("graph").jsonObject.getValue("commit_sha").jsonPrimitive.content)
        assertTrue(status.getValue("has_more").jsonPrimitive.boolean)
        assertEquals(0, blobs)
        val map = reader.repositoryMap("owner", "repo", arguments("architecture"))
        assertTrue((map["nodes"] as JsonArray).isNotEmpty())
        assertEquals(0, blobs)
        assertFalse(map.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
    }

    @Test fun preparedTaskContextReusesPinnedPacketAndPublishesScopedMetadataOnly() = runTest {
        val reader = session()
        val task = JsonObject(arguments("task_context_bundle") + mapOf("query" to JsonPrimitive("src/File1.kt useful"), "variant" to JsonPrimitive("TASK")))
        val first = reader.repositoryMap("owner", "repo", task)
        assertTrue((first["selected_ranges"] as JsonArray).isNotEmpty())
        val fetched = blobs
        val again = reader.repositoryMap("owner", "repo", task)
        assertEquals(fetched, blobs)
        assertEquals(JsonPrimitive("PINNED_PACKET_REUSED"), again["cache_status"])
        val summary = reader.repositoryMap("owner", "repo", arguments("status"))["task_context"]!!.jsonObject
        assertEquals(JsonPrimitive(head), summary["source_sha"])
        assertEquals(JsonPrimitive(true), summary["source_current"])
        assertFalse(summary.toString().contains("excerpt"))
        assertFalse(summary.toString().contains("query"))
        assertTrue(session(account = "another-account").repositoryMap("owner", "repo", arguments("status"))["task_context"] == null)
        assertTrue(session("new-revision").repositoryMap("owner", "repo", arguments("status", revision = otherHead))["task_context"] == null)
    }

    @Test fun aSharedCommitKeepsTheSelectedBranchWhileReusingImmutableSource() = runTest {
        treeSize = 1
        val task = JsonObject(arguments("task_context_bundle") + ("query" to JsonPrimitive("src/File1.kt useful")))
        session("main-task").repositoryMap("owner", "repo", task)
        val featureTask = JsonObject(task + ("ref" to JsonPrimitive("feature/same-source")))
        val feature = session("feature-task").repositoryMap("owner", "repo", featureTask)
        assertEquals(JsonPrimitive(head), feature["source_sha"])
        assertEquals(JsonPrimitive("feature/same-source"), feature["branch"])
        assertEquals(JsonPrimitive("SOURCE_REUSED"), feature["cache_status"])
        assertEquals(1, blobs)
        val again = session("feature-follow-up").repositoryMap("owner", "repo", featureTask)
        assertEquals(JsonPrimitive("feature/same-source"), again["branch"])
        assertEquals(JsonPrimitive("PINNED_PACKET_REUSED"), again["cache_status"])
        assertEquals(1, blobs)
    }

    @Test fun packetReuseRequiresTheSameRepositoryRevisionQueryAndConfiguration() = runTest {
        treeSize = 1
        val task = JsonObject(arguments("task_context_bundle") + ("query" to JsonPrimitive("src/File1.kt useful")))
        session("original").repositoryMap("owner", "repo", task)
        val variants = listOf(
            "query" to JsonObject(task + ("query" to JsonPrimitive("src/File1.kt"))),
            "variant" to JsonObject(task + ("variant" to JsonPrimitive("REFERENCE"))),
            "bytes" to JsonObject(task + ("budget_bytes" to JsonPrimitive(4096))),
            "preparation" to JsonObject(task + ("preparation_budget_ms" to JsonPrimitive(2000))),
            "revision" to JsonObject(task + ("commit_sha" to JsonPrimitive(otherHead)))
        )
        for ((label, changed) in variants) {
            val result = session(label).repositoryMap("owner", "repo", changed)
            assertFalse("Changed $label cannot reuse another request's packet", result["cache_status"] == JsonPrimitive("PINNED_PACKET_REUSED"))
            assertEquals(JsonPrimitive("PINNED_PACKET_REUSED"), session("$label-again").repositoryMap("owner", "repo", changed)["cache_status"])
        }
        assertEquals("Request changes can still reuse immutable source", 1, blobs)
        val spelling = session("repository-spelling").repositoryMap("Owner", "repo", task)
        assertEquals(JsonPrimitive("Owner/repo"), spelling["repository"])
        assertEquals(JsonPrimitive("SOURCE_REUSED"), spelling["cache_status"])
        assertEquals(1, blobs)
        val repository = session("other-repository").repositoryMap("owner", "other-repo", task)
        assertEquals(JsonPrimitive("owner/other-repo"), repository["repository"])
        assertEquals(JsonPrimitive("SOURCE_FETCHED"), repository["cache_status"])
        assertEquals(2, blobs)
        val account = session("other-account", account = "another-account").repositoryMap("owner", "repo", task)
        assertEquals(JsonPrimitive("SOURCE_FETCHED"), account["cache_status"])
        assertEquals(3, blobs)
    }

    @Test fun forceLivePolicyBypassesThePreparedPacketAndSourceCache() = runTest {
        treeSize = 1
        val task = JsonObject(arguments("task_context_bundle") + ("query" to JsonPrimitive("src/File1.kt useful")))
        val reader = session()
        reader.repositoryMap("owner", "repo", task)
        assertEquals(JsonPrimitive("PINNED_PACKET_REUSED"), reader.repositoryMap("owner", "repo", task)["cache_status"])
        val live = withContext(GitHubDispatchAccounting(forceLiveReads = true)) { reader.repositoryMap("owner", "repo", task) }
        assertEquals(2, blobs)
        assertEquals(JsonPrimitive("SOURCE_FETCHED"), live["cache_status"])
        assertEquals(JsonPrimitive(false), live["cache_hit"])
        assertEquals(JsonPrimitive(0), live["cached_files"])
    }

    @Test fun unreadableRetainedSourceIsReportedAsFetchedAfterRestart() = runTest {
        treeSize = 1
        val directory = Files.createTempDirectory("phone-context-source-refetch").toFile()
        try {
            val task = JsonObject(arguments("task_context_bundle") + mapOf("query" to JsonPrimitive("src/File1.kt useful"), "variant" to JsonPrimitive("SEMANTIC")))
            session("original", store = GitHubRepositoryStateStore(directory)).repositoryMap("owner", "repo", task)
            assertEquals(1, blobs)
            val blobSha = "%040x".format(1)
            val record = File(File(directory, "source-blobs"), GitHubRepositoryStateStore.digest("account:owner/repo:blob:$blobSha") + ".record")
            assertTrue(record.isFile)
            record.writeText("unreadable retained source")
            val restored = GitHubRepositoryStateStore(directory)
            assertTrue(restored.contains("account:owner/repo", "blob:$blobSha"))
            val recovered = session("recovered", store = restored).repositoryMap("owner", "repo", task)
            assertEquals(2, blobs)
            assertEquals(JsonPrimitive("SOURCE_FETCHED"), recovered["cache_status"])
            assertEquals(JsonPrimitive(false), recovered["cache_hit"])
            assertEquals(JsonPrimitive(0), recovered["cached_files"])
            val retained = session("retained", store = GitHubRepositoryStateStore(directory)).repositoryMap("owner", "repo", task)
            assertEquals(2, blobs)
            assertEquals(JsonPrimitive("SOURCE_REUSED"), retained["cache_status"])
            assertEquals(JsonPrimitive(true), retained["cache_hit"])
            assertEquals(JsonPrimitive(1), retained["cached_files"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun preparedSymbolCatalogCanFindAFunctionWhoseFilenameDoesNotMatch() = runTest {
        session().repositoryMap("owner", "repo", arguments("index"))
        val fetched = blobs
        val result = session().repositoryMap("owner", "repo", JsonObject(arguments("task_context_bundle") + ("query" to JsonPrimitive("useful"))))
        assertTrue((result["selected_ranges"] as JsonArray).isNotEmpty())
        assertEquals(fetched, blobs)
        assertTrue(result["coverage"]!!.jsonObject["indexed_files"]!!.jsonPrimitive.int >= 24)
    }

    @Test fun restartedTaskReusesReferenceCatalogAndUnchangedCallerBlobs() = runTest {
        val directory = Files.createTempDirectory("task-neighborhood").toFile()
        val paths = listOf("src/RepairStore.kt", "src/Screen.kt")
        sourceByBlob = mapOf(
            "%040x".format(1) to "class RepairStore { fun repairReceipt() = true }",
            "%040x".format(2) to "fun refreshScreen() = RepairStore().repairReceipt()"
        )
        treeOverride = buildJsonObject {
            put("sha", treeSha)
            put("truncated", false)
            put(
                "tree",
                JsonArray(
                    paths.mapIndexed { index, path ->
                        buildJsonObject {
                            put("path", path)
                            put("sha", "%040x".format(index + 1))
                            put("size", 64)
                            put("type", "blob")
                            put("mode", "100644")
                        }
                    }
                )
            )
        }
        try {
            session(store = GitHubRepositoryStateStore(directory)).repositoryMap("owner", "repo", arguments("index"))
            val fetched = blobs
            val task = JsonObject(arguments("task_context_bundle", revision = otherHead) + ("query" to JsonPrimitive("repairReceipt in src/RepairStore.kt")))
            val result = session("another-task", store = GitHubRepositoryStateStore(directory)).repositoryMap("owner", "repo", task)
            val ranges = result.getValue("selected_ranges").let { it as JsonArray }.map { it.jsonObject }
            assertEquals(JsonPrimitive("RETAINED_LEXICAL_CALLER"), ranges.single { it["path"] == JsonPrimitive(paths[1]) }["reason"])
            assertEquals(fetched, blobs)
            assertEquals(JsonPrimitive(otherHead), result["source_sha"])
            assertEquals(JsonPrimitive(1), result.getValue("coverage").jsonObject["caller_files"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun compactCatalogFindsADeepFunctionBeyondTheFirstDeclarations() = runTest {
        treeSize = 1
        sourceText = (1..80).joinToString("\n") { "fun ordinary$it() = $it" } + "\nfun archiveReceipt() = true\n"
        session().repositoryMap("owner", "repo", arguments("index"))
        val result = session().repositoryMap("owner", "repo", JsonObject(arguments("task_context_bundle") + ("query" to JsonPrimitive("archiveReceipt"))))
        val range = (result["selected_ranges"] as JsonArray).single().jsonObject
        assertEquals(81, range["start_line"]!!.jsonPrimitive.int)
        assertTrue(range["excerpt"]!!.jsonPrimitive.content.contains("archiveReceipt"))
    }

    @Test fun semanticRankingReceivesTheSourceAccountsAdmittedIdentity() = runTest {
        var account: String? = null
        val ranker = GitHubTaskContextRanking { _, records, admittedAccount ->
            account = admittedAccount
            records.mapValues { 0.5 }
        }
        val result = withContext(ranker) {
            session(account = "source-account").repositoryMap("owner", "repo", JsonObject(arguments("task_context_bundle") + mapOf("query" to JsonPrimitive("src/File1.kt"), "variant" to JsonPrimitive("SEMANTIC"))))
        }
        assertEquals("source-account", account)
        assertEquals(JsonPrimitive(true), result["semantic_applied"])
        assertFalse(result.toString().contains("source-account"))
    }

    @Test fun phoneIndexResumesAfterRestartAndReusesImmutableSourceAcrossCommits() = runTest {
        val first = session().repositoryMap("owner", "repo", arguments("index"))
        assertEquals(24, blobs)
        assertTrue(first.getValue("has_more").jsonPrimitive.boolean)
        val observed = session("after-restart").repositoryMap("owner", "repo", arguments("status"))
        assertEquals(first["index_cursor"], observed["index_cursor"])
        val second = session("after-restart").repositoryMap("owner", "repo", arguments("index", observed.getValue("index_cursor").jsonPrimitive.content))
        assertEquals(48, blobs)
        val last = session("after-restart").repositoryMap("owner", "repo", arguments("index", second.getValue("index_cursor").jsonPrimitive.content))
        assertEquals(50, blobs)
        assertFalse(last.getValue("has_more").jsonPrimitive.boolean)
        assertTrue(last.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        val changed = session("new-commit").repositoryMap("owner", "repo", arguments("index", revision = otherHead))
        assertEquals("Unchanged blob shards must be reused for the next revision", 50, blobs)
        assertEquals(otherHead, changed.getValue("graph").jsonObject.getValue("commit_sha").jsonPrimitive.content)
    }

    @Test fun aContinuationCannotMoveToAnotherRevisionOrAccount() = runTest {
        val first = session().repositoryMap("owner", "repo", arguments("index"))
        val cursor = first.getValue("index_cursor").jsonPrimitive.content
        val anotherRevision = runCatching { session("new").repositoryMap("owner", "repo", arguments("index", cursor, otherHead)) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_CURSOR_STALE", (anotherRevision as GatewayGraphResultException).code)
        val anotherAccount = runCatching { session("new-account", "other-account").repositoryMap("owner", "repo", arguments("index", cursor)) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_CURSOR_STALE", (anotherAccount as GatewayGraphResultException).code)
        assertEquals(24, blobs)
    }

    @Test fun anInventedFutureCursorCannotSkipSourceWhileOlderCheckpointsStillResume() = runTest {
        val first = session().repositoryMap("owner", "repo", arguments("index"))
        val issued = first.getValue("index_cursor").jsonPrimitive.content
        val invented = issued.substringBeforeLast(':') + ":$treeSize"
        val rejected = runCatching { session("after-restart").repositoryMap("owner", "repo", arguments("index", invented)) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_CURSOR_UNISSUED", (rejected as GatewayGraphResultException).code)
        assertEquals("Reject the future position before reading or skipping another blob", 24, blobs)
        val unchanged = session("after-rejection").repositoryMap("owner", "repo", arguments("status"))
        assertEquals(first["prepared_through"], unchanged["prepared_through"])
        assertEquals(first["index_cursor"], unchanged["index_cursor"])
        assertFalse(unchanged.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)

        session("continued").repositoryMap("owner", "repo", arguments("index", issued))
        assertEquals(48, blobs)
        // The original position is now older than the durable frontier. It is
        // still safe: cached source is reused and the frontier cannot regress.
        val replayed = session("older-checkpoint").repositoryMap("owner", "repo", arguments("index", issued))
        assertEquals(50, blobs)
        assertTrue(replayed.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        val final = session("final-status").repositoryMap("owner", "repo", arguments("status"))
        assertEquals(JsonPrimitive(treeSize), final["prepared_through"])
        assertTrue(final.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
    }

    @Test fun sourceCacheFromAnotherAccountCannotPopulateTheMap() = runTest {
        treeSize = 1
        session().repositoryMap("owner", "repo", arguments("index"))
        val status = session("other", "other-account").repositoryMap("owner", "repo", arguments("status"))
        assertEquals(0, status.getValue("graph").jsonObject.getValue("processed_files").jsonPrimitive.int)
        assertFalse(status.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
    }

    @Test fun completedTraversalOfATruncatedTreeNeverClaimsWholeRepositoryCoverage() = runTest {
        treeSize = 1
        truncated = true
        val result = session().repositoryMap("owner", "repo", arguments("index"))
        assertEquals(1, blobs)
        assertFalse(result.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        assertFalse(result.getValue("graph").jsonObject.getValue("complete").jsonPrimitive.boolean)
    }

    @Test fun unsupportedOperationsAndBackgroundJobsNeverDispatchSourceReads() = runTest {
        val unsupported = runCatching { session().repositoryMap("owner", "repo", arguments("note")) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_OPERATION_UNSUPPORTED", (unsupported as GatewayGraphResultException).code)
        val background = runCatching { session().repositoryMap("owner", "repo", JsonObject(arguments("index") + ("background" to JsonPrimitive(true)))) }.exceptionOrNull()
        assertTrue(background?.message.orEmpty().contains("bounded foreground"))
        assertEquals(0, blobs)
    }

    @Test fun anUnusableSourceCanBeRetriedAfterTraversalFinishes() = runTest {
        treeSize = 1
        invalidSource = true
        val failed = session().repositoryMap("owner", "repo", arguments("index"))
        assertFalse(failed.getValue("has_more").jsonPrimitive.boolean)
        assertEquals(1, failed.getValue("graph").jsonObject.getValue("retryable_files").jsonPrimitive.int)
        assertFalse(failed.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        invalidSource = false
        val retried = session().repositoryMap("owner", "repo", JsonObject(arguments("index") + ("retry_failed" to JsonPrimitive(true))))
        assertTrue(retried.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        assertEquals(0, retried.getValue("graph").jsonObject.getValue("retryable_files").jsonPrimitive.int)
    }
}
