package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubRecoveryCallsTest {
    private fun tool(name: String) = object : AgentTool {
        override val definition = GitHubOperationTool.definition(name.substringBefore("__"), name)
        override suspend fun execute(callId: String, arguments: JsonObject) = error("Routing must not execute tools")
    }

    @Test fun exactRetainedReadEnvelopeIsUnwrappedAndRoutedThroughAuthorizedTool() {
        val recipe = buildJsonObject {
            put("tool", "github_search")
            put("arguments", buildJsonObject { put("view_cursor", "issued:4") })
        }
        val result = AgentToolResult(
            "prior",
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.arguments.v1")
                    put("correction_call", recipe)
                }
            ),
            true,
            dispatched = false
        )
        val exchanges = listOf(AgentToolExchange(listOf(ProviderEvent.ToolCall("prior", "github_read", buildJsonObject {})), listOf(result)))
        val call = ProviderEvent.ToolCall("next", "github_read", buildJsonObject { put("correction_call", recipe) })
        val routed = GitHubRecoveryCalls.resolve(call, exchanges, mapOf("github_search" to tool("github_search")))
        assertEquals("github_search", routed.name)
        assertEquals(recipe["arguments"], routed.arguments)
        assertEquals("next", routed.callId)
        assertEquals(call, GitHubRecoveryCalls.resolve(call, emptyList(), mapOf("github_search" to tool("github_search"))))
        assertEquals(call, GitHubRecoveryCalls.resolve(call, exchanges, emptyMap()))
    }

    @Test fun exactMalformedSourceReadBecomesAnAdmittedToolProposalWithOriginalReplayIdentity() {
        val name = "github_read__selected"
        val original = Json.parseToJsonElement("""{"owner":"owner","repo":"repo","path":"src/Reader.kt","start_line":2,"max_lines":12}""").jsonObject
        val call = ProviderEvent.ToolCall("read", name, original).captureProviderInvocation()
        val repaired = GitHubRecoveryCalls.repairSourceRead(call, tool(name))
        assertEquals(call.callId, repaired.callId)
        assertEquals(call.name, repaired.name)
        assertEquals(call.providerInvocation(), repaired.providerInvocation())
        assertEquals(
            Json.parseToJsonElement("""{"owner":"owner","repo":"repo","action":"ranges","ranges":[{"path":"src/Reader.kt","start_line":2,"max_lines":12}]}""").jsonObject,
            repaired.arguments
        )
        assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, null))
        assertEquals(repaired, GitHubRecoveryCalls.repairSourceRead(repaired, tool(name)))
    }

    @Test fun unsupportedFirstPageSearchSizeIsDroppedWithoutChangingOriginalInvocation() {
        val name = "github_search__selected"
        val original = Json.parseToJsonElement(
            """{"action":"search","query":"important class","per_page":50}"""
        ).jsonObject
        val call = ProviderEvent.ToolCall("search", name, original).captureProviderInvocation()
        val registered = tool(name)
        val repaired = GitHubRecoveryCalls.repairSourceRead(call, registered)
        assertEquals(
            Json.parseToJsonElement("""{"action":"search","query":"important class"}""").jsonObject,
            repaired.arguments
        )
        assertEquals(call.callId, repaired.callId)
        assertEquals(call.name, repaired.name)
        assertEquals(call.providerInvocation(), repaired.providerInvocation())
        assertEquals(emptyList<String>(), ToolArgumentValidator.errors(repaired.arguments, registered.definition.inputSchema))
        assertEquals(repaired, GitHubRecoveryCalls.repairSourceRead(repaired, registered))
    }

    @Test fun unsafeOrAmbiguousSearchCorrectionsRemainRejectedWithoutDispatch() {
        val name = "github_search"
        for (value in listOf(
            """{"action":"search","query":"class","per_page":50,"view_cursor":"unissued"}""",
            """{"action":"search","query":"class","per_page":50,"index_cursor":4}""",
            """{"action":"search","query":"class","per_page":50,"search_id":"previous"}""",
            """{"action":"search","query":"class","per_page":50,"page":2}""",
            """{"action":"search","query":"class","per_page":50,"unknown":"unsafe"}""",
            """{"action":"search","per_page":50}""",
            """{"action":"search","query":" ","per_page":50}""",
            """{"action":"search","query":123,"per_page":50}"""
        )) {
            val call = ProviderEvent.ToolCall("search", name, Json.parseToJsonElement(value).jsonObject)
            assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, tool(name)))
        }
    }

    @Test fun malformedRecoveryLeavesUnsafePathsAndMixedSelectorsForRejection() {
        val name = "github_read"
        for (text in listOf(
            """{"path":"../secret.txt"}""",
            """{"path":"src/App.kt","view_cursor":"untrusted"}""",
            """{"path":"src/App.kt","start_line":0}""",
            """{"path":"src/App.kt","ranges":[{"path":"src/Other.kt"}]}"""
        )) {
            val call = ProviderEvent.ToolCall("read", name, Json.parseToJsonElement(text).jsonObject)
            assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, tool(name)))
            val mutation = call.copy(name = "github_write")
            assertEquals(mutation, GitHubRecoveryCalls.repairSourceRead(mutation, tool("github_write")))
        }
    }

    @Test fun retainedMutationRecipeIsNeverAutomaticallyExecutedOrUnwrapped() {
        val recipe = buildJsonObject {
            put("tool", "github_commit")
            put("arguments", buildJsonObject { put("message", "change") })
        }
        val result = AgentToolResult(
            "prior",
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("recovery_call", recipe)
                }
            ),
            false
        )
        val call = ProviderEvent.ToolCall("next", "github_read", buildJsonObject { put("recovery_call", recipe) })
        val exchanges = listOf(AgentToolExchange(listOf(call.copy(callId = "prior")), listOf(result)))
        assertEquals(call, GitHubRecoveryCalls.resolve(call, exchanges, mapOf("github_commit" to tool("github_commit"))))
    }
}
