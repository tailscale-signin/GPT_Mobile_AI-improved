package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSourceReadRecoveryTest {
    private val commit = "a".repeat(40)
    private val blob = "b".repeat(40)
    private val missing = "src/old/Config.kt"
    private val actual = "src/current/Config.kt"

    private fun validation(code: String) = buildJsonObject {
        put("schema", "github.read_validation.v1")
        put("status", "PRECHECK_REJECTED")
        put("read_dispatched", false)
        put("error_code", code)
        put("owner", "owner")
        put("repo", "repo")
        put("ref", "main")
        put("commit_sha", commit)
    }

    private fun pathValidation() = JsonObject(
        validation("GITHUB_PATH_NOT_FOUND") + buildJsonObject {
            put("path", missing)
            put("tree_complete", true)
            put(
                "candidates",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", actual)
                            put("sha", blob)
                        }
                    )
                )
            )
        }
    )

    private fun read() = buildJsonObject {
        put("action", "read_ranges")
        put("owner", "owner")
        put("repo", "repo")
        put("ref", "main")
        put("commit_sha", commit)
        put(
            "ranges",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", missing)
                        put("start_line", 10)
                        put("end_line", 40)
                    },
                    buildJsonObject {
                        put("path", missing)
                        put("start_line", 80)
                        put("end_line", 95)
                        put("start_offset", 3)
                    }
                )
            )
        )
    }

    @Test fun uniquePinnedPathCorrectionPreservesEveryRequestedRangeAndOffset() {
        val original = read()
        val corrected = requireNotNull(GitHubSourceReadRecovery.correctedArguments("github_read", original, pathValidation()))
        assertEquals(JsonObject(original - "ranges"), JsonObject(corrected - "ranges"))
        original.getValue("ranges").jsonArray.zip(corrected.getValue("ranges").jsonArray).forEach { (before, after) ->
            assertEquals(JsonObject(before.jsonObject + ("path" to JsonPrimitive(actual))), after)
        }
    }

    @Test fun correctionRequiresTheSameRepositoryCommitRefAndSinglePath() {
        val changedArguments = listOf(
            read() + ("owner" to JsonPrimitive("another-owner")),
            read() + ("repo" to JsonPrimitive("another-repo")),
            read() - "owner",
            read() + ("commit_sha" to JsonPrimitive("c".repeat(40))),
            read() + ("ref" to JsonPrimitive("another-branch")),
            read() + ("branch" to JsonPrimitive("another-branch")),
            read() + ("ranges" to JsonArray(read().getValue("ranges").jsonArray + buildJsonObject { put("path", "src/Unrelated.kt") })),
            read() + ("ranges" to JsonArray(listOf(JsonPrimitive("not-a-range"))))
        )
        changedArguments.forEach { assertNull(GitHubSourceReadRecovery.correctedArguments("github_read", JsonObject(it), pathValidation())) }
        for (handle in listOf("view_cursor", "evidence_id", "execution_id")) {
            assertNull(GitHubSourceReadRecovery.correctedArguments("github_read", JsonObject(read() + (handle to JsonPrimitive("retained"))), pathValidation()))
        }
    }

    @Test fun ambiguityIncompleteTreesDifferentBasenamesAndDispatchedReadsAreNotCorrected() {
        val changes = listOf(
            "tree_complete" to JsonPrimitive(false),
            "read_dispatched" to JsonPrimitive(true),
            "status" to JsonPrimitive("COMPLETED"),
            "schema" to JsonPrimitive("untrusted"),
            "candidates" to JsonArray(emptyList()),
            "candidates" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", actual)
                        put("sha", blob)
                    },
                    buildJsonObject {
                        put("path", "test/Config.kt")
                        put("sha", blob)
                    }
                )
            ),
            "candidates" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", "src/Other.kt")
                        put("sha", blob)
                    }
                )
            ),
            "candidates" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", "../Config.kt")
                        put("sha", blob)
                    }
                )
            ),
            "candidates" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", actual)
                        put("sha", "unknown")
                    }
                )
            )
        )
        changes.forEach { assertNull(GitHubSourceReadRecovery.correctedArguments("github_read", read(), JsonObject(pathValidation() + it))) }
        assertNull(GitHubSourceReadRecovery.correctedArguments("github_commit", read(), pathValidation()))
        assertNull(GitHubSourceReadRecovery.correctedArguments("github_read", JsonObject(read() + ("action" to JsonPrimitive("stage_patch"))), pathValidation()))
    }

    private fun search() = buildJsonObject {
        put("action", "search_repository")
        put("owner", "owner")
        put("repo", "repo")
        put("ref", "main")
        put("commit_sha", commit)
        put("query", "Config.kt")
        put("mode", "paths")
        put("path_prefix", "src/")
        put("index_cursor", 1000)
    }

    private fun searchValidation() = JsonObject(
        validation("GITHUB_SEARCH_CURSOR_UNBOUND") + buildJsonObject {
            put(
                "correction_call",
                buildJsonObject {
                    put("tool", "github_search")
                    put("arguments", JsonObject(search() + ("search_id" to JsonPrimitive("d".repeat(64)))))
                }
            )
        }
    )

    @Test fun searchRecoveryMayRestoreOnlyTheOmittedBindingForTheExactSearch() {
        val corrected = requireNotNull(GitHubSourceReadRecovery.correctedArguments("github_search", search(), searchValidation()))
        assertEquals(JsonObject(search() + ("search_id" to JsonPrimitive("d".repeat(64)))), corrected)
        for ((key, value) in listOf(
            "query" to JsonPrimitive("Other.kt"),
            "mode" to JsonPrimitive("literal"),
            "path_prefix" to JsonPrimitive("test/"),
            "index_cursor" to JsonPrimitive(2000),
            "ref" to JsonPrimitive("other"),
            "commit_sha" to JsonPrimitive("e".repeat(40)),
            "repo" to JsonPrimitive("other"),
            "search_id" to JsonPrimitive("stale")
        )) {
            assertNull("Changed $key must not reuse the retained binding", GitHubSourceReadRecovery.correctedArguments("github_search", JsonObject(search() + (key to value)), searchValidation()))
        }
        assertNull(GitHubSourceReadRecovery.correctedArguments("github_search", search(), JsonObject(searchValidation() - "correction_call")))
        val wrongCall = buildJsonObject {
            put("tool", "github_commit")
            put("arguments", corrected)
        }
        assertNull(GitHubSourceReadRecovery.correctedArguments("github_search", search(), JsonObject(searchValidation() + ("correction_call" to wrongCall))))
    }
}
