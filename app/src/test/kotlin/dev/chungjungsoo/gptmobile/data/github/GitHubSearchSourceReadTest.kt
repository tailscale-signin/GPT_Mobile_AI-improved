package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
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

    @Test fun definitionHitsHydrateTheirOwnLinesAcrossFilesAndPreserveSavedFirstPageRecipes() {
        fun hit(path: String, lines: List<Int>) = buildJsonObject {
            put("path", path)
            put("sha", blob)
            put("matches", JsonArray(lines.map { line -> buildJsonObject { put("line", line) } }))
        }
        val searched = JsonObject(
            source + ("items" to JsonArray(listOf(hit("src/A.kt", listOf(1420, 1425, 1984)), hit("src/B.kt", listOf(512)))))
        )
        val reader = AgentToolDefinition("github_read__selected", "read", JsonObject(emptyMap()))
        val issued = requireNotNull(GitHubSearchSourceRead.recipe(reader.name, sourceArguments, searched))
        val ranges = issued.getValue("arguments").jsonObject.getValue("ranges").jsonArray.map { it.jsonObject }
        assertEquals(listOf("src/A.kt", "src/B.kt", "src/A.kt").map(::JsonPrimitive), ranges.map { it["path"] })
        assertEquals(listOf(1420, 512, 1984).map(::JsonPrimitive), ranges.map { it["start_line"] })
        assertEquals(listOf(32, 32, 32).map(::JsonPrimitive), ranges.map { it["max_lines"] })
        fun follow(selector: JsonObject) = GitHubSearchSourceRead.next(
            id,
            receipt(JsonObject(searched + ("source_read_call" to selector))),
            "task",
            listOf(reader),
            listOf(reader),
            emptyList()
        )
        assertEquals(issued["arguments"], follow(issued)?.arguments)
        val oldPaths = JsonObject(source + ("items" to JsonArray(listOf(hit("src/A.kt", emptyList()), hit("src/B.kt", emptyList())))))
        val legacy = requireNotNull(GitHubSearchSourceRead.recipe(reader.name, sourceArguments, oldPaths))
        assertEquals(legacy["arguments"], follow(legacy)?.arguments)
        val wrongLines = JsonObject(source + ("items" to JsonArray(listOf(hit("src/A.kt", listOf(1300)), hit("src/B.kt", listOf(512))))))
        assertNull(follow(requireNotNull(GitHubSearchSourceRead.recipe(reader.name, sourceArguments, wrongLines))))
    }

    @Test fun expiredSearchWithoutLiveSelectorDiscoversThenReadsOnlyTheIssuedPinnedRange() {
        val reader = AgentToolDefinition("github_read__selected", "read", JsonObject(emptyMap()))
        val discovery = AgentToolDefinition(GitHubAuthorizedCatalog.TOOL, "discover", JsonObject(emptyMap()))
        val recipe = requireNotNull(GitHubSearchSourceRead.recipe(reader.name, sourceArguments, source))
        val stored = receipt(JsonObject(source + ("source_read_call" to recipe)))
        fun next(tools: List<AgentToolDefinition>, row: JsonObject = stored, prior: List<JsonObject> = emptyList()) =
            GitHubSearchSourceRead.next(id, row, "task", tools, listOf(reader), prior)
        val loadReader = requireNotNull(next(listOf(discovery)))
        assertEquals(discovery.name, loadReader.name)
        assertEquals(buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(reader.name)))) }, loadReader.arguments)
        val attempted = listOf(buildJsonObject { put("call_id", loadReader.callId) })
        assertNull(next(listOf(discovery), prior = attempted))
        val read = requireNotNull(next(listOf(reader), prior = attempted))
        assertEquals(recipe["arguments"], read.arguments)
        assertFalse("verify_live" in read.arguments)
        assertNull(
            next(
                listOf(reader),
                prior = listOf(
                    buildJsonObject {
                        put("tool", read.name)
                        put("arguments", read.arguments)
                    }
                )
            )
        )
        for (change in listOf(
            "binding" to JsonPrimitive("other"),
            "repository" to JsonPrimitive("other/repo"),
            "source_head_sha" to JsonPrimitive(blob),
            "source_result_sha256" to JsonPrimitive("0".repeat(64)),
            "verification_eligible" to JsonPrimitive(false),
            "trusted" to JsonPrimitive(false)
        )) {
            assertNull(change.first, next(listOf(reader), JsonObject(stored + change)))
        }
        val foreign = JsonObject(recipe + ("tool" to JsonPrimitive("github_read__other")))
        assertNull(next(listOf(reader), receipt(JsonObject(source + ("source_read_call" to foreign)))))
        assertNull(next(listOf(reader.copy(name = "github_read__other"))))
    }
}
