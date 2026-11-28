package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogNullActionTest {
    private val definitions = (1..6).map { AgentToolDefinition("reader_$it", "Read document $it", JsonObject(emptyMap())) }

    @Test fun discoveryAndActionSelectionHaveDistinctValidArgumentForms() {
        for (githubMode in listOf(true, false)) {
            val catalog = ProgressiveToolCatalog(definitions, "Read document", githubMode = githubMode)
            val schema = catalog.definition.inputSchema
            for (arguments in listOf(
                JsonObject(emptyMap()),
                buildJsonObject { put("query", "document") },
                buildJsonObject { put("names", JsonArray(definitions.map { JsonPrimitive(it.name) })) },
                buildJsonObject {
                    put("names", JsonArray(listOf(JsonPrimitive("reader_1"))))
                    put("action", "read")
                },
                buildJsonObject {
                    put("inventory_cursor", 12)
                    put("inventory_version", "observed-version")
                }
            )) {
                assertTrue(arguments.toString(), ToolArgumentValidator.errors(arguments, schema).isEmpty())
            }
            for (arguments in listOf(
                buildJsonObject { put("action", JsonNull) },
                buildJsonObject { put("action", "read") },
                buildJsonObject {
                    put("action", "read")
                    put("names", JsonArray(emptyList()))
                },
                buildJsonObject {
                    put("action", "read")
                    put("names", JsonArray(definitions.map { JsonPrimitive(it.name) }))
                }
            )) {
                assertTrue(arguments.toString(), ToolArgumentValidator.errors(arguments, schema).isNotEmpty())
            }
            val alternatives = schema["anyOf"] as JsonArray
            val discovery = alternatives.first() as JsonObject
            assertFalse((discovery["properties"] as JsonObject).containsKey("action"))
            assertEquals(JsonPrimitive(false), discovery["additionalProperties"])
        }
    }

    @Test fun nullActionWithExactNamesPreservesEverySelectorAndCannotAcquireConnections() = runTest {
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(definitions, "Read document", acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        })
        val names = JsonArray(definitions.map { JsonPrimitive(it.name) })
        val original = buildJsonObject {
            put("action", JsonNull)
            put("names", names)
            put("query", "  document  ")
        }
        val canonical = buildJsonObject {
            put("names", names)
            put("query", "  document  ")
            put("catalog_only", true)
        }
        assertEquals(canonical, catalog.canonicalArguments(original))
        assertTrue(ToolArgumentValidator.errors(canonical, catalog.definition.inputSchema).isEmpty())
        val result = catalog.execute("null-action", original)
        assertFalse(result.toString(), result.isError)
        assertFalse(result.dispatched)
        assertEquals("null-action", result.callId)
        assertEquals(0, acquisitions)
        assertTrue(catalog.selected(definitions + catalog.definition).containsAll(definitions))
        assertFalse(catalog.selected(listOf(catalog.definition)).any { it.name.startsWith("reader_") })
    }

    @Test fun nullActionWithOnlyAQuerySearchesTheVisibleLocalInventory() = runTest {
        val hidden = AgentToolDefinition("private_reader", "Read document", JsonObject(emptyMap()), modelVisible = false)
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(definitions + hidden, "Read document", acquireCold = { _, _ ->
            acquisitions++
            listOf(AgentToolDefinition("cold_reader", "Read document", JsonObject(emptyMap())))
        })
        val original = buildJsonObject {
            put("action", JsonNull)
            put("query", "document")
            put("names", JsonNull)
        }
        assertEquals(
            buildJsonObject {
                put("query", "document")
                put("catalog_only", true)
            },
            catalog.canonicalArguments(original)
        )
        val result = catalog.execute("query-null-action", original)
        assertFalse(result.toString(), result.isError)
        assertFalse(result.dispatched)
        val selected = catalog.selected(definitions + hidden + catalog.definition)
        assertFalse(selected.any { it.name == hidden.name || it.name == "cold_reader" })
        assertEquals(0, acquisitions)
    }

    @Test fun nullActionDoesNotGuessUnknownNamesOrDiscardExplicitAcquisitionAndMalformedSelectors() = runTest {
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(definitions, "Read document", acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        })
        val base = buildJsonObject {
            put("action", JsonNull)
            put("names", JsonArray(listOf(JsonPrimitive(definitions.first().name))))
        }
        val invalid = listOf(
            buildJsonObject { put("action", JsonNull) },
            JsonObject(base + ("names" to JsonArray(listOf(JsonPrimitive("unknown_reader"))))),
            JsonObject(base + ("names" to JsonArray(listOf(JsonPrimitive(false))))),
            JsonObject(base + ("names" to JsonArray(emptyList()))),
            JsonObject(base + ("names" to JsonPrimitive(definitions.first().name))),
            JsonObject(base + ("catalog_only" to JsonPrimitive(false))),
            JsonObject(base + ("catalog_only" to JsonNull)),
            JsonObject(base + ("query" to JsonPrimitive("x".repeat(4097)))),
            JsonObject(base + ("inventory_cursor" to JsonPrimitive(0))),
            JsonObject(base + ("unexpected" to JsonPrimitive(true)))
        )
        for ((index, arguments) in invalid.withIndex()) {
            assertEquals(arguments.toString(), arguments, catalog.canonicalArguments(arguments))
            val result = catalog.execute("rejected-$index", arguments)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertEquals(0, acquisitions)
    }
}
