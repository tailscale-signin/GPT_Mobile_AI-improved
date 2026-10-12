package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveToolCatalogTest {
    private val tools = (1..80).map { AgentToolDefinition("task_$it", "Task $it", JsonObject(emptyMap())) }

    @Test fun catalogIsBoundedAndDiscoveryCannotGrantPermission() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task_1"))
        assertEquals(24, catalog.selected(tools).size)
        val result = catalog.execute("d", JsonObject(mapOf("query" to JsonPrimitive("task_80"))))
        assertFalse(result.isError)
        assertTrue(catalog.selected(tools).any { it.name == "task_80" })
        assertFalse(catalog.selected(tools.dropLast(1)).any { it.name == "task_80" })
        assertTrue(catalog.selected(tools).size <= 36)
    }

    @Test fun catalogDoesNotReplaceSmallOrCollidingToolSets() {
        assertNull(ProgressiveToolCatalog.create(tools.take(24), "task"))
        assertNull(ProgressiveToolCatalog.create(tools + AgentToolDefinition(ProgressiveToolCatalog.NAME, "Existing tool", JsonObject(emptyMap())), "task"))
    }

    @Test fun emptyDiscoveryDoesNotActivateTools() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task"))
        assertTrue(catalog.execute("d", JsonObject(emptyMap())).isError)
        assertEquals(24, catalog.selected(tools).size)
    }
}
