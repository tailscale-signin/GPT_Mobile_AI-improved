package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkVersionedExportTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private fun run(id: String, revision: String = "active", version: Int = 8, measurement: Int = 3): BenchmarkRun {
        val manifest = benchmarkSuiteForVersion(version, BenchmarkMode.QUICK)
        return BenchmarkRun(
            id, "profile", "Profile", "CUSTOM", "model", revision, false, BenchmarkMode.QUICK, 1000,
            samples = manifest.map { test -> BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED, outputCharacters = 20, chunks = 2, durationMs = 100, firstTextMs = 10, lastTextMs = 100, referenceDecodeTokens = 20, requestDispatched = true, terminalReceived = true, workload = test.workload, warmup = test.warmup, tokenBasis = ReferenceTextTokenizer.VERSION, validatorVersion = test.validatorVersion, fixtureContentHash = test.contentHash, intelligenceAnswers = if (test.category == IntelligenceBenchmark.CATEGORY) BenchmarkScoreboard.domains.associateWith { true } else emptyMap()) },
            suiteVersion = version, measurementVersion = measurement, scoringVersion = measurement, plannedTrials = manifest.size
        )
    }

    @Test fun completeExportCarriesCapturedEnrollmentAndExactStoredSelectionReasons() {
        val active = run("active-run")
        val inactive = run("inactive-run", "retired")
        val snapshot = DynamicScoreEngine.snapshot(listOf(active, inactive), 4, 2000, mapOf("profile" to "active"))
        val payload = BenchmarkBackupPayload(runs = listOf(active, inactive), scoreTable = snapshot, enrollment = mapOf("profile" to "changed-after-snapshot"))
        val root = Json.parseToJsonElement(benchmarkExport(json.encodeToString(BenchmarkBackupPayload.serializer(), payload), false)).jsonObject
        assertEquals("changed-after-snapshot", root.getValue("enrollment").jsonObject.getValue("profile").jsonPrimitive.content)
        assertEquals("active", root.getValue("scoreTable").jsonObject.getValue("enrollment").jsonObject.getValue("profile").jsonPrimitive.content)
        val selections = root.getValue("scoreSelection").jsonArray.associate { it.jsonObject.getValue("runId").jsonPrimitive.content to it.jsonObject }
        val accepted = selections.getValue("active-run")
        assertEquals("true", accepted.getValue("capabilityIncluded").jsonPrimitive.content)
        assertEquals("CAPTURED_WITH_SCORE_SNAPSHOT", accepted.getValue("enrollmentProvenance").jsonPrimitive.content)
        assertEquals("active", accepted.getValue("enrolledRevision").jsonPrimitive.content)
        val rejected = selections.getValue("inactive-run")
        assertEquals("false", rejected.getValue("capabilityIncluded").jsonPrimitive.content)
        assertTrue(rejected.getValue("exclusionReasons").jsonArray.any { it.jsonPrimitive.content == "INACTIVE_OR_UNENROLLED_REVISION" })
        assertEquals(snapshot.policy, root.getValue("scoringPolicy").jsonPrimitive.content)
        assertEquals(2, root.getValue("coverage").jsonArray.size)
        assertFalse(root.containsKey("scoreHistory"))
    }

    @Test fun unknownNestedFieldsAndOriginalGradeHashAndRawTextSurviveExport() {
        val original = run("original").copy(samples = listOf(BenchmarkSample("extract-0", "Mass", "task", BenchmarkOutcome.FAILED, canonicalAnswer = "12", answerHash = benchmarkHash("12"), validatorVersion = "benchmark-validator-v3")))
        val encoded = json.encodeToJsonElement(BenchmarkRun.serializer(), original).jsonObject
        val sample = encoded.getValue("samples").jsonArray.single().jsonObject
        val rawRun = JsonObject(
            encoded + mapOf(
                "futureRunField" to JsonPrimitive("opaque"),
                "samples" to JsonArray(
                    listOf(
                        JsonObject(
                            sample + mapOf(
                                "futureNestedMeasurement" to buildJsonObject {
                                    put("source", "unrecognized-sensor")
                                    put("value", 123)
                                }
                            )
                        )
                    )
                )
            )
        )
        val raw = buildJsonObject {
            put("runs", JsonArray(listOf(rawRun)))
            put("futureTopLevel", buildJsonObject { put("retained", true) })
        }.toString()
        val exported = Json.parseToJsonElement(benchmarkExport(raw, false)).jsonObject
        val restored = exported.getValue("runs").jsonArray.single().jsonObject
        val restoredSample = restored.getValue("samples").jsonArray.single().jsonObject
        assertEquals("opaque", restored.getValue("futureRunField").jsonPrimitive.content)
        assertEquals("123", restoredSample.getValue("futureNestedMeasurement").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("FAILED", restoredSample.getValue("outcome").jsonPrimitive.content)
        assertEquals("12", restoredSample.getValue("canonicalAnswer").jsonPrimitive.content)
        assertEquals(benchmarkHash("12"), restoredSample.getValue("answerHash").jsonPrimitive.content)
        assertEquals(JsonNull, restoredSample.getValue("validation"))
        assertEquals(JsonNull, restoredSample.getValue("runtimeCleanup"))
        assertTrue(restoredSample.getValue("runtimeExecutions").jsonArray.isEmpty())
        assertTrue(exported.containsKey("futureTopLevel"))
    }

    @Test fun legacyZeroAliasesRemainInRawRecordsButCanonicalMeasurementsAreNullable() {
        val segment = LocalInferenceMetrics(segmentId = "native-segment", native = NativeInferenceMetrics(20, 10, 100.0, 20.0, decodeDurationMs = 500), firstVisibleAnswerMs = 42, decodeDurationMs = 500)
        val source = run("legacy-zero").copy(samples = listOf(BenchmarkSample("case", "Case", "tools", BenchmarkOutcome.FAILED, localSegments = listOf(segment))))
        val root = Json.parseToJsonElement(benchmarkExport(json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(source))), false)).jsonObject
        val raw = root.getValue("runs").jsonArray.single().jsonObject.getValue("samples").jsonArray.single().jsonObject.getValue("localSegments").jsonArray.single().jsonObject
        assertEquals("0", raw.getValue("timeToFirstTokenMs").jsonPrimitive.content)
        assertEquals("0.0", raw.getValue("tokensPerSecond").jsonPrimitive.content)
        val canonical = root.getValue("nativeSegmentMeasurements").jsonArray.single().jsonObject
        assertEquals("physical_generation_segment", canonical.getValue("scope").jsonPrimitive.content)
        val aliases = canonical.getValue("legacyTimingMeasurements").jsonObject
        assertEquals(JsonNull, aliases.getValue("timeToFirstTokenMs"))
        assertEquals(JsonNull, aliases.getValue("totalChunks"))
        assertEquals(JsonNull, aliases.getValue("tokensPerSecond"))
        assertEquals("42", canonical.getValue("firstVisibleAnswerMs").jsonPrimitive.content)
        assertEquals("500", canonical.getValue("native").jsonObject.getValue("decodeDurationMs").jsonPrimitive.content)
    }

    @Test fun incompatiblePolicyDomainsArePartitionedAndHeaderDoesNotClaimOneVersion() {
        val legacy = run("legacy")
        val revised = run("revised", version = 9, measurement = 4)
        val metrics = benchmarkMetricResults(listOf(legacy, revised)).filter { it.id == "delivery_completion" }
        assertEquals(2, metrics.size)
        assertEquals(setOf(listOf("legacy"), listOf("revised")), metrics.map { it.sourceRunIds }.toSet())
        assertEquals(setOf("benchmark-metrics-v3", "benchmark-metrics-v4"), metrics.map { it.policyVersion }.toSet())
        assertTrue(metrics.all { it.sourceCohortIds.size == 1 })
        val root = Json.parseToJsonElement(benchmarkExport(json.encodeToString(BenchmarkBackupPayload.serializer(), BenchmarkBackupPayload(runs = listOf(legacy, revised))), false)).jsonObject
        assertEquals(JsonNull, root.getValue("measurementPolicy"))
        assertEquals(setOf("benchmark-measurement-v3", "benchmark-measurement-v4"), root.getValue("measurementPolicies").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(2, root.getValue("policyDomains").jsonArray.size)
        assertTrue(root.getValue("runtimeEvidenceScope").jsonObject.getValue("runtimeDecisions").jsonPrimitive.content.startsWith("Unjoined runtime-wide"))
    }

    @Test fun suiteEightPublicGradeRemainsReadableAndSuiteNineHasASeparateIdentity() {
        fun recordedCapability(source: BenchmarkRun) = source.copy(
            executionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1,
            samples = source.samples.map { sample ->
                if (sample.category == IntelligenceBenchmark.CATEGORY) sample.copy(intelligenceParseable = true) else sample
            }
        )
        val legacyRun = recordedCapability(run("legacy-eight"))
        val currentLegacy = checkNotNull(BenchmarkScoreboard.fromRun(legacyRun))
        // Imported schema-2 entries predate the explicit policy and parseability fields.
        val legacy = currentLegacy.copy(executionPolicy = null, trials = currentLegacy.trials.map { it.copy(intelligenceParseable = null) })
        val revised = checkNotNull(BenchmarkScoreboard.fromRun(recordedCapability(run("current-nine", version = 9, measurement = 4))))
        assertEquals(8, legacy.suiteVersion)
        assertEquals(9, revised.suiteVersion)
        assertEquals(100.0, checkNotNull(BenchmarkScoreboard.evaluate(legacy)).score, .001)
        assertEquals(100.0, checkNotNull(BenchmarkScoreboard.evaluate(revised)).score, .001)
        assertEquals(2, BenchmarkScoreboard.best(listOf(legacy, revised)).size)
        assertNotEquals(legacy.key, revised.key)
        assertNull(BenchmarkScoreboard.fromRun(legacyRun.copy(executionPolicy = null)))
    }

    @Test fun balancedNewManifestKeepsOldOrderPromptsAndGradePolicyFrozen() {
        val old = benchmarkSuiteForVersion(8, BenchmarkMode.FULL)
        val current = benchmarkSuiteForVersion(9, BenchmarkMode.FULL)
        assertEquals(50, old.size)
        assertEquals(50, current.size)
        assertTrue(old.take(18).all { it.category == "speed" })
        assertEquals(listOf("task", "json", "tools", IntelligenceBenchmark.CATEGORY), current.take(4).map { it.category })
        assertEquals(old.map { it.id }.toSet(), current.map { it.id }.toSet())
        assertTrue(old.all { it.validatorVersion == "benchmark-validator-v3" && it.dependsOn.isEmpty() })
        assertTrue(current.all { it.validatorVersion == "benchmark-validator-v4" })
        assertEquals("Record: parcel READY_742 is not approved and weighs 12 kg. Reply only with its mass in kg.", old.single { it.id == "extract-0" }.prompt)
        assertFalse(old.single { it.id == "tool-0" }.prompt.contains("<progress_update>"))
        assertTrue(current.single { it.id == "tool-0" }.prompt.contains("<progress_update>"))
        assertEquals(setOf("speed-short-0"), current.single { it.id == "speed-short-1" }.dependsOn)
    }

    @Test fun olderSnapshotWithoutEnrollmentDoesNotInventCapturedAuthority() {
        val source = run("old")
        val snapshot = DynamicScoreEngine.snapshot(listOf(source), 1, 2000).copy(policy = "benchmark-score-v4", enrollment = null)
        val selection = benchmarkRunSelection(listOf(source), snapshot, null).single()
        assertNull(selection.enrolledRevision)
        assertEquals("UNKNOWN", selection.enrollmentProvenance)
        assertNotNull(selection.snapshotPolicy)
    }
}
