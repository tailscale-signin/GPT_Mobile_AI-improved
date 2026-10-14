package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseRecoveryContextTest {
    @Test fun healthToolsAlwaysPerformAFreshPermissionCheckedReadOnRecovery() = runTest {
        var calls = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("health_connect_summary", "health", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                calls++
                return AgentToolResult(callId, ToolResultContent.Text("Fresh read with current consent"), false)
            }
        }
        val event = ToolEvent("health-event", "old-run", 0, "old-call", null, "Health", tool.definition.name, tool.definition.name, "{}", "stale data", "TEXT", "COMPLETED")
        val result = ResponseRecoveryContext(emptyList(), emptyList(), listOf(event)).reuseCompletedTool(tool, tool.definition.name, null).execute("new-call", buildJsonObject {})
        assertEquals(1, calls)
        assertFalse(result.sharedResult)
    }

    @Test fun deniedPrefixReturnsTypedFailureWithoutSavedContentOrException() = runTest {
        val context = ResponseRecoveryContext(emptyList(), emptyList(), emptyList(), authorization = { false })
        assertTrue(context.authorizedPrefix(1000) is RecoveryPrefix.Blocked)
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(name = "Primary", apiUrl = "https://provider.example", token = "a", model = "m")
        assertTrue(sameRecoveryDestination(profile, profile.copy(name = "Renamed", isFavorite = true, labels = "new")))
        assertFalse(sameRecoveryDestination(profile, profile.copy(apiUrl = "https://other.example")))
        assertFalse(sameRecoveryDestination(profile, profile.copy(token = "b")))
        assertFalse(sameRecoveryDestination(profile, profile.copy(model = "other")))
    }

    private val run = AgentRun("old-run", 1, 1, 2, "old-profile", "LLAMA", "qwen", status = "FAILED")

    @Test
    fun completedActionsReuseTheirSavedResultWithoutRunningAgain() = runTest {
        var calls = 0
        val original = object : AgentTool {
            override val definition = AgentToolDefinition("write_file", "Write a file", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                calls++
                return AgentToolResult(callId, ToolResultContent.Text("new result"), false)
            }
        }
        val saved = ToolEvent("e", "old-run", 0, "old-call", "connection", "Workspace", "write_file", "write_file", "{\"path\":\"a.txt\"}", ToolResultCheckpoint.encode("saved result", "TEXT", "Short display"), "CHECKPOINT_V1", "COMPLETED")
        val wrapper = ResponseRecoveryContext(listOf(run), emptyList(), listOf(saved), rootWorkId = "1:1")
            .reuseCompletedTool(original, "write_file", "connection")
        val reused = wrapper.execute(
            "new-call",
            buildJsonObject {
                put("path", "a.txt")
                put("_recovery_effect_id", "e")
            }
        )
        assertEquals(0, calls)
        assertTrue(reused.sharedResult)
        assertEquals("new-call", reused.callId)
        assertEquals("saved result", (reused.content as ToolResultContent.Text).text)
        wrapper.execute("other-call", buildJsonObject { put("path", "b.txt") })
        assertEquals(1, calls)
    }

    @Test
    fun oneCharacterUnicodePageAlwaysAdvances() = runTest {
        val message = MessageV2(id = 2, chatId = 1, content = "😀", platformType = "p", currentRunId = "old-run")
        val recovery = ResponseRecoveryContext(listOf(run), listOf(message), emptyList())
        val offset = recovery.content.indexOf("😀")
        val result = recovery.tool().execute(
            "read",
            buildJsonObject {
                put("offset", offset)
                put("length", 1)
            }
        )
        val page = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(offset + 2, page.getValue("next_offset").jsonPrimitive.int)
        assertEquals("😀", page.getValue("saved_work").jsonPrimitive.content)
    }

    @Test
    fun retriesUseOriginalRevisionAndFullToolResearch() = runTest {
        val original = "Partial answer with https://example.org/source."
        val payload = "Evidence " + "x".repeat(70000) + " unique final evidence"
        val messages = listOf(
            MessageV2(id = 1, chatId = 1, content = "Research question", platformType = null),
            MessageV2(id = 2, chatId = 1, content = "New draft", platformType = "other-profile", currentRunId = "new-run", revisions = listOf(AssistantRevision(original, createdAt = 1, runId = "old-run")))
        )
        val event = ToolEvent("e", "old-run", 0, "call", null, "Search", "web_search", "web_search", "{\"query\":\"subject\"}", ToolResultCheckpoint.encode(payload, "TEXT", "Shortened excerpt"), "CHECKPOINT_V1", "COMPLETED")
        val recovery = ResponseRecoveryContext(listOf(run), messages, listOf(event))
        assertTrue(recovery.prefix(1000).contains(original))
        assertFalse(recovery.content.contains("New draft"))
        val recovered = StringBuilder()
        var offset = 0
        do {
            val result = recovery.tool().execute(
                "read",
                buildJsonObject {
                    put("offset", offset)
                    put("length", 8000)
                }
            )
            val value = (result.content as ToolResultContent.Json).value.jsonObject
            recovered.append(value.getValue("saved_work").jsonPrimitive.content)
            offset = value.getValue("next_offset").jsonPrimitive.int
        } while (offset < recovery.content.length)
        assertEquals(recovery.content, recovered.toString())
        assertTrue(recovered.contains(payload))
    }

    @Test
    fun unicodePaginationAndPendingActionsRetainTheirStatus() = runTest {
        val message = MessageV2(id = 2, chatId = 1, content = "😀".repeat(40000), platformType = "p", currentRunId = "old-run")
        val recovery = ResponseRecoveryContext(listOf(run), listOf(message), emptyList())
        var offset = 0
        val restored = StringBuilder()
        while (offset < recovery.content.length) {
            val result = recovery.tool().execute(
                "read",
                buildJsonObject {
                    put("offset", offset)
                    put("length", 7999)
                }
            )
            val page = (result.content as ToolResultContent.Json).value.jsonObject
            val text = page.getValue("saved_work").jsonPrimitive.content
            assertFalse(text.lastOrNull()?.isHighSurrogate() == true)
            restored.append(text)
            offset = page.getValue("next_offset").jsonPrimitive.int
        }
        assertEquals(recovery.content, restored.toString())
        assertTrue(recovery.prefix(500).contains("unknown outcome"))
    }

    @Test fun sameArgumentsInANewTaskDoNotSuppressNewIntentAndUnknownEffectsAreBlocked() = runTest {
        var calls = 0
        val original = object : AgentTool {
            override val definition = AgentToolDefinition("write_file", "Write", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                calls++
                return AgentToolResult(callId, ToolResultContent.Text("new"), false)
            }
        }
        val saved = ToolEvent("effect", run.runId, 0, "old", "connection", "Workspace", "write_file", "write_file", "{}", null, "TEXT", "CANCELED")
        ResponseRecoveryContext(listOf(run), emptyList(), listOf(saved)).reuseCompletedTool(original, "write_file", "connection").execute("new", buildJsonObject {})
        assertEquals(1, calls)
        val resumed = ResponseRecoveryContext(listOf(run), emptyList(), listOf(saved), rootWorkId = "1:1").reuseCompletedTool(original, "write_file", "connection")
        assertTrue(resumed.execute("resume", buildJsonObject { put("_recovery_effect_id", "effect") }).isError)
        assertEquals(1, calls)
    }

    @Test fun chatOnlyRecoveryUsesBoundedViewAndReportsOmissionWithoutAdvertisingUnavailableTool() {
        val message = MessageV2(id = 2, chatId = 1, content = "evidence ".repeat(10000), platformType = "p", currentRunId = run.runId)
        val view = ResponseRecoveryContext(listOf(run), listOf(message), emptyList()).prefix(1000, pagingAvailable = false)
        assertTrue(view.length < 2000)
        assertTrue(view.contains("Omitted"))
        assertFalse(view.contains("with read_recovery_context"))
    }

    @Test fun revocationBlocksLaterPagesAndSavedReceiptReuseWithoutExecutingMutation() = runTest {
        var allowed = true
        var writes = 0
        val message = MessageV2(id = 2, chatId = 1, content = "sensitive fixture", platformType = "p", currentRunId = "old-run")
        val recovery = ResponseRecoveryContext(listOf(run), listOf(message), emptyList(), rootWorkId = "1:1", authorization = { allowed })
        val reader = recovery.tool()
        assertFalse(reader.execute("before", buildJsonObject {}).isError)
        val mutation = object : AgentTool {
            override val definition = AgentToolDefinition("write_file", "write", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                writes++
                return AgentToolResult(callId, ToolResultContent.Text("written"), false)
            }
        }
        val wrapper = recovery.reuseCompletedTool(mutation, "write_file", "connection")
        allowed = false
        assertFalse(recovery.isAuthorized())
        val blocked = reader.execute("after", buildJsonObject {})
        assertTrue(blocked.isError)
        assertFalse(blocked.content.toString().contains("sensitive fixture"))
        assertTrue(wrapper.execute("write", buildJsonObject {}).isError)
        assertEquals(0, writes)
    }
}
