package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.providerInvocation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSearchSourceReadTest {
    private val head = "a".repeat(40)
    private val blob = "b".repeat(40)
    private val id = "ev_" + "c".repeat(24)
    private val sourceArguments = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put("mode", "paths")
        put("query", "*.md")
        put("commit_sha", head)
    }
    private val source = buildJsonObject {
        put("commit_sha", head)
        put(
            "items",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", ".github/instructions/engineering.md")
                        put("sha", blob)
                    }
                )
            )
        )
    }
    private fun receipt(result: JsonObject = source) = buildJsonObject {
        put("schema", "github.evidence.v1")
        put("receipt_id", id)
        put("binding", "task")
        put("tool", "github_search__selected")
        put("repository", "owner/repo")
        put("trusted", true)
        put("verification_eligible", true)
        put("success", true)
        put("source_head_sha", head)
        put("arguments", sourceArguments)
        put("result", result)
        put("source_result_sha256", GitHubRepositoryStateStore.digest(result.toString()))
    }
    private fun read() = ProviderEvent.ToolCall(
        "read-source",
        "github_read__selected",
        buildJsonObject {
            put("evidence_id", id)
            put(
                "ranges",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", ".github/instructions/engineering.md")
                            put("start_line", 17)
                            put("end_line", 42)
                            put("start_offset", 3)
                        }
                    )
                )
            )
        }
    )

    @Test fun trustedSearchReceiptReopensExactRequestedSourceAtItsObservedRevision() {
        val request = read()
        val repaired = requireNotNull(GitHubSearchSourceRead.repair(request, receipt(), "task"))
        assertEquals(request.callId, repaired.callId)
        assertEquals(request.name, repaired.name)
        assertEquals(request.arguments["ranges"], repaired.arguments["ranges"])
        assertEquals(JsonPrimitive("owner"), repaired.arguments["owner"])
        assertEquals(JsonPrimitive("repo"), repaired.arguments["repo"])
        assertEquals(JsonPrimitive(head), repaired.arguments["commit_sha"])
        assertFalse("evidence_id" in repaired.arguments)
        assertEquals(request.providerInvocation(), repaired.providerInvocation())
        assertNull(GitHubSearchSourceRead.repair(repaired, receipt(), "task"))
    }

    @Test fun untrustedDamagedDifferentTaskAndWrongToolReceiptsCannotSupplySource() {
        for (change in listOf(
            "binding" to JsonPrimitive("another-task"),
            "receipt_id" to JsonPrimitive("ev_" + "d".repeat(24)),
            "trusted" to JsonPrimitive(false),
            "verification_eligible" to JsonPrimitive(false),
            "success" to JsonPrimitive(false),
            "tool" to JsonPrimitive("github_search__other"),
            "tool" to JsonPrimitive("github_ci__selected"),
            "source_head_sha" to JsonPrimitive("d".repeat(40)),
            "source_result_sha256" to JsonPrimitive("f".repeat(64)),
            "arguments" to JsonObject(sourceArguments + ("commit_sha" to JsonPrimitive("d".repeat(40)))),
            "result" to JsonObject(source + ("commit_sha" to JsonPrimitive("d".repeat(40))))
        )) {
            assertNull("Must reject ${change.first}", GitHubSearchSourceRead.repair(read(), JsonObject(receipt() + change), "task"))
        }
    }

    @Test fun sourceRecoveryNeverChangesExplicitRepositoryRevisionReferenceOrUnobservedPath() {
        assertNull(GitHubSearchSourceRead.repair(read().copy(originalInvocationFingerprint = "d".repeat(64)), receipt(), "task"))
        for (change in listOf(
            "owner" to JsonPrimitive("owner"),
            "repo" to JsonPrimitive("other"),
            "commit_sha" to JsonPrimitive("d".repeat(40)),
            "ref" to JsonPrimitive("another-branch"),
            "branch" to JsonPrimitive("another-branch"),
            "view_cursor" to JsonPrimitive("saved:4"),
            "ranges" to JsonArray(listOf(buildJsonObject { put("path", "unobserved.md") }))
        )) {
            val request = read().copy(arguments = JsonObject(read().arguments + change))
            assertNull("Must preserve ${change.first}", GitHubSearchSourceRead.repair(request, receipt(), "task"))
        }
    }

    @Test fun conflictingPathBlobsAndNonSourceSearchResultsDoNotMakeAReadRecipe() {
        val duplicate = JsonObject(
            source + (
                "items" to JsonArray(
                    source.getValue("items").jsonArray + buildJsonObject {
                        put("path", ".github/instructions/engineering.md")
                        put("sha", "d".repeat(40))
                    }
                )
                )
        )
        assertNull(GitHubSearchSourceRead.repair(read(), receipt(duplicate), "task"))
        assertNull(GitHubSearchSourceRead.recipe("github_read__selected", sourceArguments, duplicate))
        assertNull(GitHubSearchSourceRead.recipe("github_read__selected", JsonObject(sourceArguments + ("commit_sha" to JsonPrimitive("d".repeat(40)))), source))
        assertNull(GitHubSearchSourceRead.recipe("github_read__selected", sourceArguments, buildJsonObject { put("items", JsonArray(emptyList())) }))
    }

    @Test fun pathSearchOffersABoundedPinnedReadWithoutClaimingTheFilesWereRead() {
        val recipe = requireNotNull(GitHubSearchSourceRead.recipe("github_read__selected", sourceArguments, source))
        assertEquals(JsonPrimitive("github_read__selected"), recipe["tool"])
        val arguments = recipe.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive(head), arguments["commit_sha"])
        val range = arguments.getValue("ranges").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(".github/instructions/engineering.md"), range["path"])
        assertEquals(JsonPrimitive(1), range["start_line"])
        assertEquals(JsonPrimitive(200), range["max_lines"])
        assertFalse("evidence_id" in arguments)
        assertFalse("content" in source.getValue("items").jsonArray.single().jsonObject)
    }
}
