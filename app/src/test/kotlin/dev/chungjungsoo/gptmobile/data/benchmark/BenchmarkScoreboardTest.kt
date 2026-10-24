package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkScoreboardTest {
    @Test fun legacyAndRevisedFixturesKeepSeparatePublicIdentities() {
        val current = checkNotNull(BenchmarkScoreboard.fromRun(run("current")))
        val legacy = current.copy(id = "legacy", suiteVersion = 6)
        assertNotNull(BenchmarkScoreboard.evaluate(legacy))
        assertEquals(2, BenchmarkScoreboard.best(listOf(legacy, current)).size)
        assertNull(BenchmarkScoreboard.fromRun(run("old").copy(suiteVersion = 6)))
        assertNull(BenchmarkScoreboard.evaluate(current.copy(suiteVersion = BenchmarkSuiteRegistry.VERSION + 1)))
    }

    @Test fun exportedV2RunsRetainTheirExactFixtureAndPublicSubmissionSchema() {
        val source = run("export-roundtrip")
        // Match BenchmarkStore's canonical backup serializer: storage version is explicit.
        val backup = Json { encodeDefaults = true }.encodeToString(BenchmarkBackupPayload(runs = listOf(source)))
        val exported = benchmarkExport(backup, false)
        val header = Json.parseToJsonElement(exported).jsonObject
        assertEquals("2", header.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("3", header.getValue("storageSchemaVersion").jsonPrimitive.content)
        val restored = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(exported).runs.single()
        assertEquals(benchmarkSuiteForRun(source), benchmarkSuiteForRun(restored))
        val submission = checkNotNull(BenchmarkScoreboard.fromRun(restored))
        assertEquals(2, submission.schemaVersion)
        assertEquals(BenchmarkSuiteRegistry.VERSION, submission.suiteVersion)
        assertNotNull(BenchmarkScoreboard.evaluate(submission))
    }

    @Test fun `Android matches the repository scoring contract for both plans and kinds`() {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("scoreboard-contract.json")).bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonArray.forEach { item ->
            val fixture = item.jsonObject
            val submission = Json.decodeFromJsonElement<ScoreboardSubmission>(fixture.getValue("submission"))
            assertEquals(fixture.getValue("score").jsonPrimitive.double, checkNotNull(BenchmarkScoreboard.evaluate(submission)).score, .00001)
        }
    }

    @Test fun `best result is one entire run keyed by provider and model independent of profile name`() {
        val first = checkNotNull(BenchmarkScoreboard.fromRun(run("first")))
        val lower = checkNotNull(BenchmarkScoreboard.fromRun(run("second").copy(profileUid = "other-profile", profileName = "Renamed", startedAt = 200, samples = run("first").samples.map { if (it.category == "task") it.copy(outcome = BenchmarkOutcome.FAILED) else it })))
        assertEquals(65.0, checkNotNull(BenchmarkScoreboard.evaluate(lower)).score, .001)
        assertEquals("first", BenchmarkScoreboard.best(listOf(lower, first)).single().submission.id)
        assertEquals(2, BenchmarkScoreboard.best(listOf(first, first.copy(provider = "GOOGLE"))).size)
        assertEquals(2, BenchmarkScoreboard.best(listOf(first, first.copy(model = "Model"))).size)
    }

    @Test fun `incomplete historical local and unsupported runs cannot rank`() {
        val run = run("test")
        listOf(run.copy(local = true), run.copy(suiteVersion = 5), run.copy(canceled = true), run.copy(finished = false), run.copy(stoppedReason = "interrupted"), run.copy(samples = run.samples.dropLast(1)), run.copy(samples = run.samples.map { it.copy(outcome = BenchmarkOutcome.UNSUPPORTED) })).forEach { assertNull(BenchmarkScoreboard.fromRun(it)) }
        val data = checkNotNull(BenchmarkScoreboard.fromRun(run))
        assertNull(BenchmarkScoreboard.evaluate(data.copy(trials = data.trials.map { it.copy(speed = Double.NaN) })))
        assertNull(BenchmarkScoreboard.evaluate(data.copy(model = "https://private/endpoint")))
        assertNull(BenchmarkScoreboard.evaluate(data.copy(provider = "LITERT_LM")))
        assertNull(BenchmarkScoreboard.evaluate(data.copy(trials = data.trials.dropLast(1) + data.trials.first())))
    }

    @Test fun `public payload omits private run content and uses the actual OpenRouter model`() {
        val data = checkNotNull(BenchmarkScoreboard.fromRun(run("public").copy(provider = "OPENROUTER", model = "vendor/model:batch")))
        assertEquals("vendor/model", data.model)
        val encoded = Json.encodeToString(data)
        listOf("SECRET", "profileUid", "profileName", "preview", "error", "device", "configKey", "endpointUrl").forEach { assertFalse(encoded.contains(it)) }
    }

    @Test fun `Quick consistency is missing not a fabricated score`() {
        val entry = checkNotNull(BenchmarkScoreboard.fromRun(run("quick")))
        val result = checkNotNull(BenchmarkScoreboard.evaluate(entry))
        assertEquals(100.0, result.score, .001)
        assertNull(result.metrics.first { it.label == "Consistency" }.value)
        assertNotNull(result.metrics.first { it.label == "Generation speed" }.value)
    }

    @Test fun `delegation plans are exact independent reviewed manifests and preserve legacy history`() {
        assertEquals(24, delegationBenchmarkSuite().size)
        for ((mode, count) in listOf(BenchmarkMode.QUICK to 5, BenchmarkMode.FULL to 50)) {
            val suite = delegationBenchmarkSuite(mode)
            assertEquals(count, suite.size)
            assertEquals(count, suite.map { it.id }.distinct().size)
            assertTrue(suite.all { it.benchmarkReview })
        }
        assertEquals(8, delegationBenchmarkSuite(BenchmarkMode.FULL).map { it.workload }.distinct().size)
        val preset = delegationBenchmarkSettings("reviewer")
        assertTrue(preset.enabled && preset.researchEnabled && preset.reviewerEnabled && preset.allowRemoteWorkers)
        assertFalse(preset.reviewerAutoCorrect || preset.fallbackToAnotherProfile)
        assertEquals(0, preset.reviewerRetryLimit)
    }

    @Test fun `loss and gain segments encode exact differences on a fixed axis`() {
        assertEquals(ScoreDelta(65.0, 0.0, 15.0), ScoreDelta.between(65.0, 80.0))
        assertEquals(ScoreDelta(65.0, 15.0, 0.0), ScoreDelta.between(80.0, 65.0))
        assertEquals(ScoreDelta(80.0, 0.0, 0.0), ScoreDelta.between(80.0, null))
        assertEquals(ScoreDelta(0.0, 0.0, 100.0), ScoreDelta.between(0.0, 100.0))
        assertEquals(ScoreDelta(0.0, 100.0, 0.0), ScoreDelta.between(100.0, 0.0))
        assertEquals(0.0, ScoreDelta.between(65.0, 65.0).change, .001)
    }

    private fun run(id: String) = BenchmarkRun(
        id, "SECRET-uid", "SECRET-profile", "OPENAI", "model", "SECRET-config", false,
        BenchmarkMode.QUICK, 100, suiteVersion = BenchmarkSuiteRegistry.VERSION, measurementVersion = 2, plannedTrials = 5,
        device = "SECRET-device",
        samples = benchmarkSuite(BenchmarkMode.QUICK).map { test ->
            BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED, durationMs = 500, firstTextMs = 100, lastTextMs = 500, chunks = 2, referenceDecodeTokens = 20, preview = "SECRET-response", error = "SECRET-endpoint", workload = test.workload, intelligenceAnswers = if (test.category == "intelligence") BenchmarkScoreboard.domains.associateWith { true } else emptyMap())
        }
    )
}
