package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedMemoryToolTest {
    private fun repository() = FactVaultRepository(
        object : SecretVault {
            private val values = mutableMapOf<String, ByteArray>()
            override suspend fun put(secretRef: String, secret: ByteArray) {
                values[secretRef] = secret.copyOf()
            }
            override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
            override suspend fun delete(secretRef: String) {
                values.remove(secretRef)
            }
        },
        KnowledgeGraphEngine()
    )

    @Test fun unavailableBackendsAreNotAdvertisedOrDispatched() = runTest {
        val tool = UnifiedMemoryTool(repository(), null, null, MessageV2(id = 1, chatId = 1, content = "Remember this", platformType = null), true)
        val actions = tool.definition.inputSchema["properties"]!!.jsonObject["action"]!!.jsonObject["enum"] as JsonArray
        assertEquals(setOf("capture", "recall", "forget"), actions.map { (it as JsonPrimitive).content }.toSet())
        assertTrue(
            tool.execute(
                "missing",
                buildJsonObject {
                    put("action", "read_graph")
                    put("input", JsonObject(emptyMap()))
                }
            ).isError
        )
    }

    @Test fun unifiedCaptureUsesUserTextAndRecallHonorsDestinationAndMasterSwitch() = runTest {
        val vault = repository()
        val user = MessageV2(id = 1, chatId = 1, content = "I prefer Kotlin", platformType = null)
        val local = UnifiedMemoryTool(vault, null, null, user, true)
        val capture = buildJsonObject {
            put("action", "capture")
            put("input", JsonObject(emptyMap()))
        }
        assertFalse(local.execute("capture", capture).isError)
        assertEquals(listOf("Kotlin"), vault.state.value.facts.map { it.fact.target.name })
        vault.updateSettings(vault.state.value.settings.copy(allowCloudRecall = false))
        val recall = buildJsonObject {
            put("action", "recall")
            put("input", buildJsonObject { put("query", "Kotlin") })
        }
        val cloud = UnifiedMemoryTool(vault, null, null, user, false)
        assertFalse((cloud.execute("cloud", recall).content as ToolResultContent.Text).text.contains("Kotlin"))
        assertFalse((local.execute("same-turn", recall).content as ToolResultContent.Text).text.contains("Kotlin"))
        val laterUser = user.copy(id = 2, content = "What language do I prefer?")
        val laterLocal = UnifiedMemoryTool(vault, null, null, laterUser, true)
        val laterCloud = UnifiedMemoryTool(vault, null, null, laterUser, false)
        assertFalse((laterCloud.execute("later-cloud", recall).content as ToolResultContent.Text).text.contains("Kotlin"))
        assertTrue((laterLocal.execute("later-local", recall).content as ToolResultContent.Text).text.contains("Kotlin"))
        vault.setEnabled(false)
        assertTrue(local.execute("disabled", capture).isError)
    }
}
