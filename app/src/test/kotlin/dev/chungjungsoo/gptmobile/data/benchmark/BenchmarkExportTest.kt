package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkExportTest {
    private fun raw(): String = buildJsonObject {
        put("schemaVersion", 2)
        put(
            "runs",
            JsonArray(
                listOf("one", "two").map { id ->
                    Json.encodeToJsonElement(BenchmarkRun.serializer(), BenchmarkRun(id, id, id, "CUSTOM", "test", "config", false, BenchmarkMode.QUICK, 1))
                }
            )
        )
        put("scoreTable", buildJsonObject { put("generation", 7) })
        put("scoreHistory", JsonArray(listOf(buildJsonObject { put("generation", 6) })))
        put("runtimeTrials", JsonArray(listOf(JsonPrimitive("retained"))))
        put("runtimeQualifications", JsonArray(emptyList()))
        put("runtimeDecisions", JsonArray(emptyList()))
        put("note", "https://example.org?token=hidden")
    }.toString()

    @Test fun standardExportPreservesAllStoredEvidenceAndRedactsEndpointSecrets() {
        val output = benchmarkExport(raw(), false)
        val root = Json.parseToJsonElement(output).jsonObject
        assertEquals(2, root.getValue("runs").jsonArray.size)
        assertEquals(7, root.getValue("scoreTable").jsonObject.getValue("generation").jsonPrimitive.content.toInt())
        assertEquals(1, root.getValue("runtimeTrials").jsonArray.size)
        assertEquals(1, root.getValue("scoreHistory").jsonArray.size)
        assertEquals(2, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertFalse(output.contains("hidden"))
        assertFalse(root.containsKey("perRunScores"))
    }

    @Test fun diagnosticExportAddsScoreDimensionsWithoutDroppingRawRecords() {
        val root = Json.parseToJsonElement(benchmarkExport(raw(), true)).jsonObject
        assertEquals(2, root.getValue("runs").jsonArray.size)
        assertEquals(2, root.getValue("perRunScores").jsonArray.size)
        assertTrue(root.getValue("perRunScores").jsonArray.all { it.jsonObject.containsKey("dimensions") })
    }
}
