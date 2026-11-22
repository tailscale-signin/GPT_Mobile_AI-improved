package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.providerInvocation
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubSearchReceiptRecoveryTest {
    @Test fun retainedSearchReceiptReopensPinnedSourceAfterRestartOnlyUnderTheCurrentCatalogAndTask() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val search = "github_search__selected"
        val read = "github_read__selected"
        val head = "a".repeat(40)
        val contracts = listOf(search, read).associateWith { GitHubOperationTool.definition(it.substringBefore("__"), it).inputSchema }
        fun book(binding: String = "task", trusted: Set<String> = setOf(search, read)) = GitHubEvidenceBook(
            binding,
            "owner/repo",
            "main",
            trusted,
            load = { saved[it] },
            save = { id, value -> saved[id] = value },
            retainedIds = { saved.keys.toList() },
            currentToolContracts = contracts
        )
        val original = book()
        original.observe(
            ProviderEvent.ToolCall(
                "paths",
                search,
                buildJsonObject {
                    put("owner", "owner")
                    put("repo", "repo")
                    put("mode", "paths")
                    put("query", "*.md")
                }
            ),
            AgentToolResult(
                "paths",
                ToolResultContent.Json(
                    buildJsonObject {
                        put("commit_sha", head)
                        put(
                            "items",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("path", "AGENTS.md")
                                        put("sha", "b".repeat(40))
                                    }
                                )
                            )
                        )
                    }
                ),
                false
            )
        )
        val id = original.snapshot().first.keys.single()
        val retained = saved.getValue(id)
        val request = ProviderEvent.ToolCall(
            "source",
            read,
            buildJsonObject {
                put("evidence_id", id)
                put(
                    "ranges",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", "AGENTS.md")
                                put("start_line", 11)
                                put("max_lines", 50)
                            }
                        )
                    )
                )
            }
        )
        val restarted = book()
        restarted.restore()
        val repaired = restarted.repairSourceRead(request)
        assertEquals(JsonPrimitive(head), repaired.arguments["commit_sha"])
        assertEquals(request.arguments["ranges"], repaired.arguments["ranges"])
        assertEquals(request.providerInvocation(), repaired.providerInvocation())
        assertFalse("evidence_id" in repaired.arguments)
        assertEquals(retained, saved.getValue(id))
        assertEquals(request, book(binding = "another-task").repairSourceRead(request))
        assertEquals(request, book(trusted = setOf(read)).repairSourceRead(request))
        assertEquals(request, book(trusted = setOf(search)).repairSourceRead(request))
        saved[id] = JsonObject(retained + ("source_result_sha256" to JsonPrimitive("d".repeat(64))))
        assertEquals(request, book().repairSourceRead(request))
    }
}
