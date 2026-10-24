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
    @Test fun exportPreservesPhaseUsageConfidenceAndExplicitUnknownRuntimeIdentity() {
        val sample = BenchmarkSample("test", "Test", "task", BenchmarkOutcome.PASSED, canonicalAnswer = "READY", outputCharacters = 5, outputTokens = 3, inputTokenSource = "unknown", outputTokenSource = "provider_reported_initial", outputTokenConfidence = "partial_observation", phaseIntervals = listOf(BenchmarkPhaseInterval("loading", 0, 15)), phaseElapsedMs = mapOf("loading" to 15), nativeIdentity = BenchmarkNativeIdentity("run", "attempt", workerGenerationId = "worker", actualRuntime = "LLAMA_CPP", actualAccelerator = "cpu", configuredOffloadLayers = 8, observedAt = 1, unknownFields = setOf("actualOffloadLayers", "driverIdentity", "engineInstanceId")))
        val run = BenchmarkRun("run", "profile", "Profile", "LITERT_LM", "model", "key", true, BenchmarkMode.QUICK, 1, listOf(sample))
        // Old exports omitted defaults. The upgraded export must make their meaning explicit.
        val raw = Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(run)))
        val root = Json.parseToJsonElement(benchmarkExport(raw, false)).jsonObject
        assertEquals("1", root.getValue("storageSchemaVersion").jsonPrimitive.content)
        val exported = root.getValue("runs").jsonArray.single().jsonObject.getValue("samples").jsonArray.single().jsonObject
        assertEquals("true", exported.getValue("estimatedTokens").jsonPrimitive.content)
        assertEquals("partial_observation", exported.getValue("outputTokenConfidence").jsonPrimitive.content)
        assertEquals("15", exported.getValue("phaseElapsedMs").jsonObject.getValue("loading").jsonPrimitive.content)
        val identity = exported.getValue("nativeIdentity").jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, identity.getValue("actualOffloadLayers"))
        assertEquals(kotlinx.serialization.json.JsonNull, identity.getValue("driverIdentity"))
        assertEquals("8", identity.getValue("configuredOffloadLayers").jsonPrimitive.content)
    }

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
        assertFalse(root.containsKey("scoreHistory"))
        assertEquals("gptmobile.benchmarks.v2", root.getValue("format").jsonPrimitive.content)
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

    @Test fun queueExportsDeclareInProgressAndStripFrozenCredentials() {
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Profile", model = "model", token = "private-secret-token", secretRef = "private-vault-reference", apiUrl = "https://user:password@example.org/v1?api_key=private-query")
        val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "config")))
        val payload = BenchmarkBackupPayload(batches = listOf(batch))
        val json = Json { encodeDefaults = true }
        val output = benchmarkExport(json.encodeToString(BenchmarkBackupPayload.serializer(), payload), false)
        val root = Json.parseToJsonElement(output).jsonObject
        assertEquals("IN_PROGRESS_AT_EXPORT", root.getValue("snapshotState").jsonPrimitive.content)
        assertEquals("2", root.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("3", root.getValue("storageSchemaVersion").jsonPrimitive.content)
        assertFalse(output.contains("private-secret-token"))
        assertFalse(output.contains("private-vault-reference"))
        assertFalse(output.contains("password"))
        assertFalse(output.contains("private-query"))
        val frozen = root.getValue("batches").jsonArray.single().jsonObject.getValue("items").jsonArray.single().jsonObject.getValue("profile").jsonObject
        assertEquals("https://example.org/v1", frozen.getValue("apiUrl").jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, frozen.getValue("token"))
        assertEquals(kotlinx.serialization.json.JsonNull, frozen.getValue("secretRef"))
    }
}
