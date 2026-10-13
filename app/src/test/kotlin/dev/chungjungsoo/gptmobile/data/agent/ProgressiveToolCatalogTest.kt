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
        assertTrue(catalog.selected(tools).size <= ProgressiveToolCatalog.INITIAL_TOOLS)
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

    @Test fun repeatedSearchesProgressThroughUndiscoveredTools() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task"))
        val query = JsonObject(mapOf("query" to JsonPrimitive("task")))
        val seen = catalog.selected(tools).map { it.name }.toMutableSet()
        repeat(6) { round ->
            catalog.execute("discovery-$round", query)
            val advertised = catalog.selected(tools).map { it.name }.toSet()
            assertTrue(advertised.size <= ProgressiveToolCatalog.INITIAL_TOOLS)
            if (seen.size < tools.size) assertTrue(advertised.any { it !in seen })
            seen += advertised
        }
        assertEquals(tools.map { it.name }.toSet(), seen)
        val result = catalog.execute("reactivate", JsonObject(mapOf("names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("task_1"))))))
        assertFalse(result.isError)
        assertTrue(catalog.selected(tools).any { it.name == "task_1" })
        assertEquals(ProgressiveToolCatalog.INITIAL_TOOLS, catalog.selected(tools).size)
    }

    @Test fun explicitKnownToolRemainsDiscoverableAfterActivation() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task_1"))
        val result = catalog.execute("known", JsonObject(mapOf("query" to JsonPrimitive("task_1"))))
        assertFalse(result.isError)
        assertTrue(result.content.toString().contains("task_1"))
        assertTrue(catalog.selected(tools).any { it.name == "task_1" })
    }

    @Test fun fewHugeSchemasStillActivateProgressiveDiscovery() = runTest {
        val huge = AgentToolDefinition("huge", "x".repeat(100000), JsonObject(emptyMap()))
        val source = listOf(huge) + tools.take(4)
        val catalog = requireNotNull(ProgressiveToolCatalog.create(source, "huge"))
        assertFalse(catalog.selected(source).any { it.name == "huge" })
        val result = catalog.execute("discover", JsonObject(mapOf("query" to JsonPrimitive("huge"))))
        assertTrue(result.content.toString().contains("schema_budget_excluded"))
        assertFalse(catalog.selected(source).any { it.name == "huge" })
    }

    @Test fun repeatedDiscoveryDoesNotAccumulateAllSchemas() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task"))
        for (index in 1..80) {
            catalog.execute("call-$index", JsonObject(mapOf("query" to JsonPrimitive("task_$index"))))
            assertTrue(catalog.selected(tools).size <= ProgressiveToolCatalog.INITIAL_TOOLS)
            assertTrue(catalog.selected(tools).sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() } <= ProgressiveToolCatalog.SCHEMA_TOKEN_ALLOWANCE)
            assertTrue(catalog.selected(tools).any { it.name == "task_$index" })
        }
    }
}
