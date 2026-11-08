package dev.chungjungsoo.gptmobile.data.github

import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubPatchWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun explicitFixtureStageAndCommitDoesNotSelectTheFixtureAndReplaysAfterRestart() = runTest {
        val fixture = Fixture(temporary.newFolder())
        val session = fixture.session()
        session.execute("resolve_context", "owner", "repo", buildJsonObject { put("ref", "main") })
        val receipt = session.execute("stage_patch", "owner", "repo", fixture.immediate()).jsonObject
        assertEquals(JsonPrimitive(true), receipt["stage_persisted"])
        assertEquals(JsonPrimitive(true), receipt["committed"])
        assertEquals(JsonPrimitive(fixture.newHead), receipt["sha"])
        assertEquals("main", session.context()?.ref)
        assertEquals(fixture.origin, session.snapshot()?.commitSha)
        assertEquals(1, fixture.commits)
        val restarted = fixture.session()
        assertEquals(receipt, restarted.execute("stage_patch", "owner", "repo", fixture.immediate()))
        assertEquals(1, fixture.commits)
        val recovery = restarted.execute("recovery_context", "owner", "repo", buildJsonObject { put("branch", fixture.branch) }).jsonObject
        assertEquals(receipt["stage_id"], recovery["stage_id"])
        assertEquals(1, recovery["retained_stages"]!!.jsonArray.size)
        assertEquals("main", restarted.context()?.ref)
    }

    @Test fun lostCommitResponseRecoversDurableStageAndRemoteProofWithoutRepeatingPublication() = runTest {
        val fixture = Fixture(temporary.newFolder())
        fixture.loseResponse = true
        val session = fixture.session()
        session.execute("resolve_context", "owner", "repo", buildJsonObject { put("ref", "main") })
        assertThrows(GitHubExecutionBlocked::class.java) {
            kotlinx.coroutines.runBlocking { session.execute("stage_patch", "owner", "repo", fixture.immediate()) }
        }
        val restarted = fixture.session()
        val recovery = restarted.execute("recovery_context", "owner", "repo", buildJsonObject { put("branch", fixture.branch) }).jsonObject
        assertEquals(JsonPrimitive(true), recovery["stage_available"])
        assertNotNull(recovery["stage_id"])
        val commit = buildJsonObject {
            put("stage_id", recovery.getValue("stage_id"))
            put("branch", fixture.branch)
            put("expected_head_sha", fixture.origin)
            put("message", "Repair fixture")
            // Facade metadata differs from the immediate path, but is not a new publication.
            put("action", "commit_staged_patch")
            put("owner", "owner")
            put("repo", "repo")
        }
        val receipt = restarted.execute("commit_staged_patch", "owner", "repo", commit).jsonObject
        assertEquals(JsonPrimitive(fixture.newHead), receipt["sha"])
        assertEquals(JsonPrimitive(true), receipt["execution"]!!.jsonObject["remote_reconciled"])
        assertEquals(1, fixture.commits)
        assertEquals("main", restarted.context()?.ref)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { restarted.execute("commit_staged_patch", "owner", "repo", JsonObject(commit + ("branch" to JsonPrimitive("unrelated")))) }
        }
    }

    @Test fun immediatePublicationRequiresExplicitTargetLeaseAndMessageBeforePreparingAnything() = runTest {
        val fixture = Fixture(temporary.newFolder())
        for (missing in listOf("branch", "expected_head_sha", "message")) {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { fixture.session().execute("stage_patch", "owner", "repo", JsonObject(fixture.immediate() - missing)) }
            }
        }
        assertEquals(0, fixture.reads)
        assertEquals(0, fixture.commits)
    }

    @Test fun anExplicitStageWithoutAWorkspaceSelectionCanBeRecoveredOfflineAfterColdRestart() = runTest {
        val fixture = Fixture(temporary.newFolder())
        val session = fixture.session()
        val staged = session.execute("stage_patch", "owner", "repo", JsonObject(fixture.immediate() - "commit_immediately" - "message")).jsonObject
        assertNull(session.context())
        val reads = fixture.reads
        fixture.blockReads = true
        val restarted = fixture.session()
        val recovery = restarted.execute("recovery_context", "owner", "repo", buildJsonObject { put("branch", fixture.branch) }).jsonObject
        assertEquals(staged["stage_id"], recovery["stage_id"])
        assertEquals(JsonPrimitive(true), recovery["stage_available"])
        assertEquals(JsonPrimitive("RETAINED_STAGE"), recovery["context_source"])
        assertEquals(reads, fixture.reads)
        assertNull(restarted.context())
        assertEquals(0, fixture.commits)
    }

    private class Fixture(private val directory: File) {
        val branch = "codex-self-test/fixture"
        val origin = "a".repeat(40)
        val newHead = "d".repeat(40)
        private val tree = "b".repeat(40)
        private val blob = "c".repeat(40)
        var commits = 0
        var reads = 0
        var loseResponse = false
        var blockReads = false

        fun immediate() = buildJsonObject {
            put("branch", branch)
            put("expected_head_sha", origin)
            put("commit_immediately", true)
            put("message", "Repair fixture")
            put("target_file", "notes.txt")
            put("search_snippet", "Original fixture content.")
            put("replacement_snippet", "Changed fixture content.")
        }

        private fun receipt() = buildJsonObject {
            put("sha", newHead)
            put("branch", branch)
            put("parent_sha", origin)
            put("tree_sha", "e".repeat(40))
            put("files_changed", 1)
        }

        fun session() = GitHubRepositorySession(
            "account",
            "connection",
            "conversation",
            "task",
            GitHubRepositoryStateStore(directory),
            request = { path ->
                check(!blockReads) { "Offline recovery must use retained stage data." }
                reads++
                when {
                    "/commits/" in path -> buildJsonObject {
                        put("sha", origin)
                        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
                    }
                    "/git/trees/" in path -> buildJsonObject {
                        put("truncated", false)
                        put(
                            "tree",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("path", "notes.txt")
                                        put("sha", blob)
                                        put("mode", "100644")
                                        put("type", "blob")
                                        put("size", 26)
                                    }
                                )
                            )
                        )
                    }
                    "/git/blobs/" in path -> buildJsonObject {
                        put("sha", blob)
                        put("encoding", "base64")
                        put("content", Base64.getEncoder().encodeToString("Original fixture content.\n".encodeToByteArray()))
                    }
                    else -> error("Unexpected request $path")
                }
            },
            commit = { _, args ->
                assertEquals(branch, args["branch"]!!.jsonPrimitive.content)
                assertEquals(origin, args["expected_head_sha"]!!.jsonPrimitive.content)
                assertEquals("Changed fixture content.\n", args["files"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
                GitHubMutationAttempts.dispatched()
                commits++
                if (loseResponse) throw IOException("Response lost after publication")
                receipt()
            },
            reconcileCommit = { _, _: JsonObject -> if (commits > 0) receipt() as JsonElement else null }
        )
    }
}
