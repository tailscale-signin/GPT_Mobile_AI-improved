package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMemoryToolTest {
    private fun repository(): FactVaultRepository = FactVaultRepository(
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

    @Test
    fun captureOnlyUsesActualUserTextAndMasterSwitchStopsIt() = runTest {
        val repository = repository()
        val user = MessageV2(id = 1, chatId = 1, content = "I prefer Kotlin", platformType = null)
        val tool = LocalMemoryTool(repository, user, true, true)
        assertFalse(tool.execute("1", buildJsonObject { put("query", "I prefer Java") }).isError)
        assertEquals(listOf("Kotlin"), repository.state.value.facts.map { it.fact.target.name })
        repository.setEnabled(false)
        assertTrue(tool.execute("2", JsonObject(emptyMap())).isError)
    }

    @Test
    fun recallRespectsCloudPolicyAndRedactsPersistentTrace() = runTest {
        val repository = repository()
        repository.prepareTurn("I prefer Kotlin", 1, 1)
        repository.updateSettings(repository.state.value.settings.copy(allowCloudRecall = false))
        val message = MessageV2(id = 2, chatId = 1, content = "What do I prefer?", platformType = null)
        val query = buildJsonObject { put("query", "Kotlin") }
        val cloud = LocalMemoryTool(repository, message, false, false).execute("1", query)
        assertFalse((cloud.content as ToolResultContent.Text).text.contains("Kotlin"))
        val local = LocalMemoryTool(repository, message, true, false).execute("2", query)
        assertTrue((local.content as ToolResultContent.Text).text.contains("Kotlin"))
        assertFalse((local.traceContent as ToolResultContent.Text).text.contains("Kotlin"))
    }
}
