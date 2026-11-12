package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadRecoveryTest {
    private val head = "a".repeat(40)
    private val treeSha = "b".repeat(40)
    private val paths = listOf("README.md", "docs/Guide.md", "src/A.kt", "src/B.kt", "src/C.kt", "test/ATest.kt")

    private class Fixture(val session: GitHubRepositorySession, val blobs: AtomicInteger)

    private fun fixture(available: List<String> = paths, truncated: Boolean = false, source: String = "fun needle() = 1\nfun another() = 2"): Fixture {
        val blobs = AtomicInteger()
        val session = GitHubRepositorySession("account", "connection", "chat", "task", GitHubRepositoryStateStore(), { path ->
            when {
                "/commits/" in path -> buildJsonObject {
                    put("sha", path.substringAfterLast('/'))
                    put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", treeSha) }) })
                }
                "/git/trees/" in path -> buildJsonObject {
                    put("truncated", truncated)
                    put(
                        "tree",
                        JsonArray(
                            available.mapIndexed { index, name ->
                                buildJsonObject {
                                    put("path", name)
                                    put("sha", "%040x".format(index + 1))
                                    put("type", "blob")
                                    put("mode", "100644")
                                    put("size", source.toByteArray().size)
                                }
                            }
                        )
                    )
                }
                "/git/blobs/" in path -> {
                    blobs.incrementAndGet()
                    buildJsonObject {
                        put("encoding", "base64")
                        put("content", Base64.getEncoder().encodeToString(source.toByteArray()))
                    }
                }
                else -> error("Unexpected request $path")
            }
        }, { _, _ -> error("Read recovery must never mutate") })
        return Fixture(session, blobs)
    }

    private fun search() = buildJsonObject {
        put("ref", head)
        put("commit_sha", head)
        put("query", "*")
        put("mode", "paths")
        put("batch_size", 2)
        put("max_new_blobs", 2)
    }

    private fun read(path: String) = buildJsonObject {
        put("ref", head)
        put("commit_sha", head)
        put("path", path)
    }

    @Test fun changedSearchParametersRestartAtZeroAndRetainTheOriginalPages() = runBlocking {
        val changes = mapOf(
            "query" to JsonPrimitive("README"),
            "path_prefix" to JsonPrimitive("src/"),
            "path_suffix" to JsonPrimitive(".md"),
            "file_extension" to JsonPrimitive("kt"),
            "mode" to JsonPrimitive("literal"),
            "case_sensitive" to JsonPrimitive(true),
            "min_lines" to JsonPrimitive(1),
            "max_lines" to JsonPrimitive(10),
            "context_lines" to JsonPrimitive(5),
            "fuzzy_threshold" to JsonPrimitive(0.9),
            "batch_size" to JsonPrimitive(1),
            "max_new_blobs" to JsonPrimitive(1)
        )
        for ((field, value) in changes) {
            val fixture = fixture()
            val first = fixture.session.execute("search_repository", "owner", "repo", search()).jsonObject
            val original = first.getValue("continuation").jsonObject
            val restarted = fixture.session.execute("search_repository", "owner", "repo", JsonObject(original + (field to value))).jsonObject
            assertNotEquals("Fingerprint must include $field", first["search_id"], restarted["search_id"])
            assertEquals("Cursor must reset for $field", JsonPrimitive(0), restarted["interval_start"])
            assertEquals(JsonPrimitive("RESTARTED_READ_ONLY_SEARCH"), restarted.getValue("recovery").jsonObject["action"])
            val retained = fixture.session.execute("search_repository", "owner", "repo", search()).jsonObject
            assertEquals(first["items"], retained["items"])
            assertEquals(JsonPrimitive(true), retained["cache_hit"])
            assertFalse(retained.containsKey("recovery"))
            val next = fixture.session.execute("search_repository", "owner", "repo", original).jsonObject
            assertEquals(JsonPrimitive(2), next["interval_start"])
        }
    }

    @Test fun restartedTraversalDoesNotDuplicateOrSkipMatchingPaths() = runBlocking {
        val fixture = fixture()
        val first = fixture.session.execute("search_repository", "owner", "repo", search()).jsonObject
        var args = JsonObject(first.getValue("continuation").jsonObject + mapOf("path_prefix" to JsonPrimitive("src/"), "batch_size" to JsonPrimitive(1)))
        val actual = mutableListOf<String>()
        do {
            val page = fixture.session.execute("search_repository", "owner", "repo", args).jsonObject
            actual += page.getValue("items").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
            assertTrue(page.getValue("scanned_files").jsonPrimitive.int <= 1)
            args = page["continuation"] as? JsonObject ?: break
        } while (true)
        assertEquals(listOf("src/A.kt", "src/B.kt", "src/C.kt"), actual)
        assertEquals(actual.size, actual.distinct().size)
        assertEquals(0, fixture.blobs.get())
    }

    @Test fun invalidUnboundAndFabricatedCursorsReturnStructuredCorrectiveReads() = runBlocking {
        val fixture = fixture()
        val first = fixture.session.execute("search_repository", "owner", "repo", search()).jsonObject
        val issued = first.getValue("continuation").jsonObject
        val invalid = listOf(
            JsonObject(issued + ("index_cursor" to JsonPrimitive(-1))),
            JsonObject(issued + ("index_cursor" to JsonPrimitive("bad"))),
            JsonObject(issued + ("index_cursor" to JsonPrimitive(500))),
            JsonObject(issued + ("index_cursor" to JsonPrimitive(1))),
            JsonObject(issued - "search_id"),
            JsonObject(issued + ("search_id" to JsonPrimitive("unissued")))
        )
        for (args in invalid) {
            val failure = runCatching { fixture.session.execute("search_repository", "owner", "repo", args) }.exceptionOrNull()
            assertTrue(failure is GitHubReadValidation)
            val details = (failure as GitHubReadValidation).details
            assertEquals(JsonPrimitive(false), details["retry_unchanged"])
            val corrected = details.getValue("corrective_actions").jsonArray.single().jsonObject.getValue("arguments").jsonObject
            assertEquals(JsonPrimitive(0), corrected["index_cursor"])
            assertFalse(corrected.containsKey("search_id"))
            val recovered = fixture.session.execute("search_repository", "owner", "repo", corrected).jsonObject
            assertEquals(first["items"], recovered["items"])
        }
        assertEquals(0, fixture.blobs.get())
    }

    @Test fun searchIdentityIncludesRepositoryAndPinnedCommit() = runBlocking {
        val fixture = fixture()
        val first = fixture.session.execute("search_repository", "owner", "repo", search()).jsonObject
        val otherRepo = fixture.session.execute("search_repository", "owner", "another-repo", search()).jsonObject
        val otherCommit = fixture.session.execute("search_repository", "owner", "repo", JsonObject(search() + ("commit_sha" to JsonPrimitive("c".repeat(40))))).jsonObject
        assertNotEquals(first["search_id"], otherRepo["search_id"])
        assertNotEquals(first["search_id"], otherCommit["search_id"])
    }

    @Test fun omittedSearchBindingCanOnlyRecoverAnIssuedBoundaryOfTheExactRetainedDefinition() = runBlocking {
        val fixture = fixture()
        val first = fixture.session.execute("search_repository", "owner", "repo", search()).jsonObject
        val issued = first.getValue("continuation").jsonObject
        val unbound = JsonObject(issued - "search_id")
        val failure = runCatching { fixture.session.execute("search_repository", "owner", "repo", unbound) }.exceptionOrNull() as GitHubReadValidation
        val corrected = requireNotNull(GitHubSourceReadRecovery.correctedArguments("github_search", unbound, failure.details))
        assertEquals(issued.getValue("search_id"), corrected["search_id"])
        val next = fixture.session.execute("search_repository", "owner", "repo", corrected).jsonObject
        assertEquals(JsonPrimitive(2), next["interval_start"])
        for (changed in listOf(
            JsonObject(unbound + ("query" to JsonPrimitive("README"))),
            JsonObject(unbound + ("index_cursor" to JsonPrimitive(1))),
            JsonObject(unbound + ("commit_sha" to JsonPrimitive("c".repeat(40))))
        )) {
            val rejected = runCatching { fixture.session.execute("search_repository", "owner", "repo", changed) }.exceptionOrNull() as GitHubReadValidation
            assertFalse(rejected.details.containsKey("correction_call"))
        }
        val crossRepo = runCatching { fixture.session.execute("search_repository", "owner", "another-repo", unbound) }.exceptionOrNull() as GitHubReadValidation
        assertFalse(crossRepo.details.containsKey("correction_call"))
        assertEquals(0, fixture.blobs.get())
    }

    @Test fun missingAndMovedPathsUsePinnedCandidatesWithoutLosingSuccessfulEvidence() = runBlocking {
        val fixture = fixture(listOf("README.md", "src/moved/Config.kt", "src/other/Config.kt"))
        val original = fixture.session.execute("read_ranges", "owner", "repo", read("README.md")).jsonObject
        for (missing in listOf("docs/README.md", "src/old/Config.kt")) {
            val failure = runCatching { fixture.session.execute("read_ranges", "owner", "repo", read(missing)) }.exceptionOrNull() as GitHubReadValidation
            assertEquals("GITHUB_PATH_NOT_FOUND", failure.code)
            assertEquals(JsonPrimitive(head), failure.details["commit_sha"])
            assertEquals(JsonPrimitive(false), failure.details["read_dispatched"])
            val candidates = failure.details.getValue("candidates").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
            assertTrue(if (missing.endsWith("README.md")) candidates == listOf("README.md") else candidates == listOf("src/moved/Config.kt", "src/other/Config.kt"))
            assertEquals(missing.endsWith("README.md"), failure.details.containsKey("correction_call"))
        }
        assertEquals(1, fixture.blobs.get())
        val moved = fixture.session.execute("read_ranges", "owner", "repo", read("src/moved/Config.kt")).jsonObject
        assertEquals(JsonPrimitive("src/moved/Config.kt"), moved.getValue("items").jsonArray.single().jsonObject["path"])
        val reopened = fixture.session.execute("read_ranges", "owner", "repo", buildJsonObject { put("evidence_id", original.getValue("evidence_id")) }).jsonObject
        assertEquals(original["items"], reopened["items"])
        assertEquals(2, fixture.blobs.get())
    }

    @Test fun truncatedTreeDoesNotClaimMissingPathIsAbsent() = runBlocking {
        val fixture = fixture(truncated = true)
        val failure = runCatching { fixture.session.execute("read_ranges", "owner", "repo", read("docs/Missing.md")) }.exceptionOrNull() as GitHubReadValidation
        assertEquals("GITHUB_TREE_INCOMPLETE", failure.code)
        assertEquals(JsonPrimitive(false), failure.details["tree_complete"])
        assertEquals(0, fixture.blobs.get())
    }

    @Test fun batchedAndSubsequentReadsExposeCompleteImmutableRangeCoverage() = runBlocking {
        val fixture = fixture(source = (1..217).joinToString("\n") { "line $it" })
        val ranges = JsonArray(
            listOf(1..50, 51..100, 101..150, 151..200, 201..217).map { range ->
                buildJsonObject {
                    put("path", "src/A.kt")
                    put("start_line", range.first)
                    put("end_line", range.last)
                }
            }
        )
        val first = fixture.session.execute("read_ranges", "owner", "repo", JsonObject(read("src/A.kt") - "path" + ("ranges" to ranges))).jsonObject
        assertTrue(first.getValue("items").jsonArray.all { it.jsonObject["complete_file"] == JsonPrimitive(false) })
        val coverage = first.getValue("source_coverage").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(true), coverage["complete_file"])
        assertEquals(JsonPrimitive(false), coverage["read_required"])
        val repeated = fixture.session.execute("read_ranges", "owner", "repo", read("src/A.kt")).jsonObject
        assertEquals(JsonPrimitive(true), repeated.getValue("source_coverage").jsonArray.single().jsonObject["already_read"])
        assertEquals(1, fixture.blobs.get())
        val movedHead = fixture.session.execute("read_ranges", "owner", "repo", JsonObject(read("src/A.kt") + ("commit_sha" to JsonPrimitive("d".repeat(40))))).jsonObject
        assertEquals(JsonPrimitive(false), movedHead.getValue("source_coverage").jsonArray.single().jsonObject["already_read"])
    }
}
