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
    @Test fun coldExportUsesCompleteFixtureEvidenceForScoreEligibility() {
        val plan = UnifiedProfileBenchmarkSuite.cases(0)
        val test = plan.first { it.category == "task" }
        val observed = BenchmarkSample(
            test.id, test.label, test.category, BenchmarkOutcome.PASSED,
            workload = test.workload, requestAdmitted = true, requestDispatched = true, terminalReceived = true,
            outputCharacters = 2, preview = "12", canonicalAnswer = "12",
            fixtureContentHash = test.contentHash, validatorVersion = test.validatorVersion,
            validation = BenchmarkValidationResult(test.validatorVersion, true), evidenceScope = "SYNTHETIC_BENCHMARK"
        )
        val run = BenchmarkRun("run", "profile", "Profile", "OPENAI", "model", "configuration", false, BenchmarkMode.STANDARD, 1, listOf(observed), finished = false, suiteVersion = 14, measurementVersion = 4, plannedTrials = plan.size, caseManifest = plan, fixtureRotationSeed = 0, executionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1)
        val snapshot = DynamicScoreEngine.snapshot(listOf(run), 1, 101, mapOf("profile" to "configuration"))
        val projected = benchmarkExportComparisonRun(run)
        assertTrue(benchmarkRunSelection(listOf(run), snapshot, null).single().hasMeasuredCapability)
        assertFalse(benchmarkRunSelection(listOf(projected), snapshot, null).single().hasMeasuredCapability)

        fun selection(source: BenchmarkExportSource): JsonArray {
            val bytes = java.io.ByteArrayOutputStream()
            val output = BenchmarkStreamingExport(bytes)
            writeBenchmarkExport(output, source, false) {}
            output.finish()
            return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())).jsonObject.getValue("scoreSelection").jsonArray
        }
        val warm = BenchmarkExportSource(listOf(run), emptyList(), snapshot, null, null)
        val cold = warm.copy(runs = listOf(projected), readCompleteRuns = { sequenceOf(run) })
        assertEquals(selection(warm), selection(cold))
        assertEquals(JsonPrimitive(true), selection(cold).single().jsonObject["hasMeasuredCapability"])
    }

    @Test fun gatewayLabelsAndImportedBlockerNamesDoNotLeakAddressesInEitherPublicExport() {
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "gateway", name = "Gateway · 192.0.2.8:8090", compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA, model = "model")
        val run = BenchmarkRun("run", profile.uid, profile.name, "LLAMA", "model", "key", false, BenchmarkMode.STANDARD, 1, preparation = BenchmarkPreparationReceipt("prep", "admission", "WAITING", 1, blockerLabels = listOf("INTERACTIVE:0123456789ab", "Earlier queued measurement", "Earlier queued inference", "private owner 192.0.2.8")))
        val batch = BenchmarkBatch("batch", BenchmarkMode.STANDARD, 1, listOf(BenchmarkQueueItem(0, profile, run.id, "key")))
        val raw = Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(run), batches = listOf(batch)))
        for (detailed in listOf(false, true)) {
            val output = benchmarkExport(raw, detailed)
            assertFalse(output.contains("192.0.2.8"))
            assertFalse(output.contains("private owner"))
            assertTrue(output.contains("Gateway · [address]"))
            assertTrue(output.contains("INTERACTIVE:0123456789ab"))
            assertTrue(output.contains("Earlier queued measurement"))
            assertTrue(output.contains("Earlier queued inference"))
        }
        for (label in listOf("Gateway · https://private.example:8090/v1", "Gateway · [2001:db8:1234::abcd]:8090", "Gateway · workstation.local:8090")) {
            assertEquals("Gateway · [address]", benchmarkPublicProfileLabel(label))
        }
        assertEquals("GPT-5.6 Luna", benchmarkPublicProfileLabel("GPT-5.6 Luna"))
        val privateExport = redactBenchmarkExport(buildJsonObject { put("profileName", profile.name) }, includePrivateProfileDetails = true).jsonObject
        assertEquals(profile.name, privateExport.getValue("profileName").jsonPrimitive.content)
    }

    @Test fun validatorPolicySeparatesExecutedGradesFromNeverStartedFixtures() {
        val cases = listOf(BenchmarkCase("passed", "Passed", "task", "Reply ready", validatorVersion = "planned-v8"), BenchmarkCase("skipped", "Skipped", "task", "Reply ready", validatorVersion = "planned-v9"))
        val samples = listOf(
            BenchmarkSample("passed", "Passed", "task", BenchmarkOutcome.PASSED, validatorVersion = "legacy-alias", validation = BenchmarkValidationResult("actual-v8", true)),
            BenchmarkSample("skipped", "Skipped", "task", BenchmarkOutcome.SKIPPED, validatorVersion = "legacy", requestDispatched = false, dispositionOnly = true)
        )
        val run = BenchmarkRun("run", "profile", "Profile", "OPENAI", "model", "key", false, BenchmarkMode.STANDARD, 1, samples, caseManifest = cases)
        val raw = Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(run)))
        val policy = Json.parseToJsonElement(benchmarkExport(raw, false)).jsonObject.getValue("policyDomains").jsonArray.single().jsonObject
        assertEquals(listOf("actual-v8"), policy.getValue("validatorVersions").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("planned-v8", "planned-v9"), policy.getValue("plannedValidatorVersions").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun routeBoundPriceSnapshotRoundTripsWithoutPrivateSourceDetails() {
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Profile", model = "model")
        val now = requireNotNull(dev.chungjungsoo.gptmobile.data.pricing.reviewTime("2026-11-22")) + 1_000
        val price = dev.chungjungsoo.gptmobile.data.pricing.ModelPrice("OPENAI", "model", "1", "2", source = dev.chungjungsoo.gptmobile.data.pricing.PriceSource.ACCOUNT_OVERRIDE, sourceUrl = "Private account contract 123", reviewedOn = "2026-11-22")
        val manual = dev.chungjungsoo.gptmobile.data.pricing.ManualPrice(dev.chungjungsoo.gptmobile.data.pricing.priceContextKey(profile), price)
        val snapshot = benchmarkPricingSnapshot(profile, listOf(manual), emptyList(), now, "key")
        val sample = BenchmarkSample("passed", "Passed", "task", BenchmarkOutcome.PASSED, durationMs = 200, requestDispatched = true, inputTokens = 10, outputTokens = 20, estimatedTokens = false, inputTokenConfidence = "final_reported", outputTokenConfidence = "final_reported")
        val run = BenchmarkRun("run", "profile", "Profile", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, now, listOf(sample), pricingSnapshot = snapshot)
        val raw = Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(run)))
        val output = benchmarkExport(raw, false)
        assertFalse(output.contains(price.sourceUrl))
        val root = Json.parseToJsonElement(output).jsonObject
        val restored = benchmarkExportJson.decodeFromJsonElement(BenchmarkRun.serializer(), root.getValue("runs").jsonArray.single())
        assertEquals(snapshot, restored.pricingSnapshot)
        assertEquals("ACCOUNT_OVERRIDE", restored.pricingSnapshot?.sourceKind)
        assertEquals(benchmarkHash(price.sourceUrl), restored.pricingSnapshot?.sourceDigest)
        assertEquals(50L, benchmarkTaskEfficiency(restored).apiCostEstimateMicros)
    }

    @Test fun standardAndDiagnosticExportsRedactLegacyPriceContractNotes() {
        val source = "https://private.example/contracts/customer-123?account=secret"
        val snapshot = BenchmarkPricingSnapshot("profile", "OPENAI", "model", "USD", 1, dev.chungjungsoo.gptmobile.data.accounting.ModelPrice("model", 1_000_000, 2_000_000, source, 1))
        val run = BenchmarkRun("run", "profile", "Profile", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1, pricingSnapshot = snapshot)
        val raw = Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(run)))
        listOf(false, true).forEach { detailed ->
            val output = benchmarkExport(raw, detailed)
            assertFalse(output.contains("private.example"))
            assertFalse(output.contains("customer-123"))
            val retained = Json.parseToJsonElement(output).jsonObject.getValue("runs").jsonArray.single().jsonObject.getValue("pricingSnapshot").jsonObject
            assertEquals("LEGACY_ALLOWANCE_RATE", retained.getValue("sourceKind").jsonPrimitive.content)
            assertEquals(benchmarkHash(source), retained.getValue("sourceDigest").jsonPrimitive.content)
        }
    }

    @Test fun taskEfficiencyIsExportedWithUnknownCostAndItsPopulationScope() {
        val sample = BenchmarkSample("passed", "Passed", "task", BenchmarkOutcome.PASSED, durationMs = 200, requestDispatched = true)
        val run = BenchmarkRun("run", "profile", "Profile", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1, listOf(sample))
        val raw = Json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(run)))
        val root = Json.parseToJsonElement(benchmarkExport(raw, false)).jsonObject
        val efficiency = root.getValue("perRunMetrics").jsonArray.single().jsonObject.getValue("taskEfficiency").jsonObject
        assertEquals("1", efficiency.getValue("successfulTasks").jsonPrimitive.content)
        assertEquals("200.0", efficiency.getValue("elapsedMsPerSuccessfulTask").jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, efficiency.getValue("apiCostEstimateMicrosPerSuccessfulTask"))
        assertEquals("benchmark-task-efficiency-v1", efficiency.getValue("policyVersion").jsonPrimitive.content)
        assertTrue(root.getValue("units").jsonObject.containsKey("apiCostEstimate"))
    }

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
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Profile", model = "model", token = "private-secret-token", secretRef = "private-vault-reference", apiUrl = "https://user:password@example.org/v1?api_key=private-query", batchApiUrl = "https://user:password@batch.example.net/private-path?api_key=private-batch-query", systemPrompt = "private instructions")
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
        assertFalse(output.contains("example.org"))
        assertFalse(output.contains("batch.example.net"))
        assertFalse(output.contains("private-path"))
        assertFalse(output.contains("private instructions"))
        assertEquals(kotlinx.serialization.json.JsonNull, frozen["apiUrl"])
        assertEquals(kotlinx.serialization.json.JsonNull, frozen["batchApiUrl"])
        assertEquals("OPENAI", frozen["routeLabel"]?.jsonPrimitive?.content)
        assertEquals(benchmarkHash("private instructions"), frozen["systemPromptDigest"]?.jsonPrimitive?.content)
        val private = redactBenchmarkExport(json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2.serializer(), profile), includePrivateProfileDetails = true).jsonObject
        assertEquals("https://example.org/v1", private["apiUrl"]?.jsonPrimitive?.content)
        assertEquals("https://batch.example.net/private-path", private["batchApiUrl"]?.jsonPrimitive?.content)
        assertEquals("private instructions", private["systemPrompt"]?.jsonPrimitive?.content)
        assertEquals(kotlinx.serialization.json.JsonNull, private["token"])
        assertEquals(kotlinx.serialization.json.JsonNull, frozen.getValue("token"))
        assertEquals(kotlinx.serialization.json.JsonNull, frozen.getValue("secretRef"))
    }
}
