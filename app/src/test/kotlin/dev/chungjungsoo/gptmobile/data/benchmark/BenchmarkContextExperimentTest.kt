package dev.chungjungsoo.gptmobile.data.benchmark

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkContextExperimentTest {
    private val fixture = BenchmarkEngineeringTestFixtures(families = 2, repeats = 2)

    @Test fun frozenArmsKeepOneProfileAndCounterbalanceEveryRepeat() {
        val plan = plan()
        val queue = BenchmarkEngineeringRegistry.queue(fixture.profiles.take(1), plan)
        assertEquals(listOf("CURRENT", "REFERENCE", "TASK", "SEMANTIC", "SEMANTIC", "TASK", "REFERENCE", "CURRENT"), queue.map { it.engineeringContextVariant })
        assertTrue(queue.all { it.profile.uid == "a" && it.profile.model == "model-a" })
        val batch = BenchmarkBatch(plan.id, BenchmarkMode.GITHUB_INTEGRATION, 0, queue, executionPolicy = plan.executionPolicy, engineeringPlan = plan)
        assertTrue(benchmarkEngineeringQueueMatches(batch, plan))
        assertFalse(benchmarkEngineeringQueueMatches(batch.copy(items = queue.mapIndexed { index, item -> if (index == 1) item.copy(engineeringContextVariant = "TASK") else item }), plan))
        assertFalse(plan.copy(contextExperiment = plan.contextExperiment!!.copy(budgetBytes = 8000)).valid())
        assertNotEquals(plan.cohortId, plan.copy(contextExperiment = plan.contextExperiment!!.copy(budgetBytes = 8000)).frozen().cohortId)
        assertFalse(plan.copy(profileUids = fixture.plan.profileUids, profileConfigKeys = fixture.plan.profileConfigKeys).frozen().valid())
    }

    @Test fun legacyRunsKeepTheirExistingQueueAndSerializationIdentity() {
        val legacy = fixture.plan
        val restored = Json.decodeFromString<BenchmarkEngineeringPlan>(Json.encodeToString(legacy))
        assertEquals(legacy.cohortId, restored.identityDigest())
        assertNull(restored.contextExperiment)
        assertTrue(restored.contextManifestDigests.isEmpty())
        assertTrue(BenchmarkEngineeringRegistry.queue(fixture.profiles, restored).all { it.engineeringContextVariant == null })
        assertEquals(1, benchmarkEngineeringComparisons(fixture.runs()).size)
        assertTrue(benchmarkContextComparisons(fixture.runs()).isEmpty())
    }

    @Test fun actualSelectorChangesPromptAndMeasuresItsOwnColdAndWarmReads() = runBlocking {
        val descriptor = descriptor()
        val plan = plan(descriptor)
        val task = fixture.cases.first().id
        val current = benchmarkPrepareContext(descriptor, plan, task, "CURRENT")
        val reference = benchmarkPrepareContext(descriptor, plan, task, "REFERENCE")
        val selected = benchmarkPrepareContext(descriptor, plan, task, "TASK")
        val fallback = benchmarkPrepareContext(descriptor, plan, task, "SEMANTIC")
        val semantic = benchmarkPrepareContext(descriptor, plan, task, "SEMANTIC", localRanker = { _, records -> records.keys.associateWith { 0.5 } })
        assertEquals("Public original source prompt", current.prompt)
        assertEquals(0, current.receipt.contextBytes)
        assertEquals(0, current.receipt.coldSourceReads)
        assertTrue(reference.prompt.contains("Pagination reference"))
        assertEquals(0, reference.receipt.selectedSourceCount)
        assertTrue(selected.prompt.contains("mergeRevision"))
        assertTrue(selected.receipt.selectedSourceCount > 0)
        assertTrue(selected.receipt.warmCacheHits > 0)
        assertEquals(selected.receipt.coldSourceReads, selected.receipt.warmSourceReads)
        assertEquals(0, selected.receipt.repeatedSourceReads)
        assertEquals("LEXICAL_FALLBACK", fallback.receipt.retrievalMode)
        assertEquals("LOCAL_SEMANTIC_AND_LEXICAL", semantic.receipt.retrievalMode)
        assertEquals(current.receipt.manifestDigest, selected.receipt.manifestDigest)
        assertTrue(selected.receipt.contextBytes <= plan.contextExperiment!!.budgetBytes)
        assertNull(selected.receipt.relevantFileRecall)
        assertNull(selected.receipt.testRecall)
        val exported = Json.encodeToString(selected.receipt)
        assertFalse(exported.contains("src/Pagination.kt"))
        assertFalse(exported.contains("mergeRevision"))
        assertFalse(selected.receipt.boundTo(plan, task, "TASK"))
        assertTrue(selected.receipt.copy(dispatched = true).boundTo(plan, task, "TASK"))
    }

    @Test fun wrongBranchManifestAndBlobFailBeforeContextCanDispatch() = runBlocking {
        val original = descriptor()
        val plan = plan(original)
        val changed = descriptor("fun mergeRevision(value: Int) = value + 2\n")
        assertFails("BENCHMARK_CONTEXT_CHANGED_AFTER_FREEZE") { benchmarkPrepareContext(changed, plan, fixture.cases.first().id, "TASK") }
        val inputs = original.getValue("contextInputs") as JsonObject
        val rows = inputs.getValue("files") as JsonArray
        val changedRows = JsonArray(
            rows.mapIndexed { index, row ->
                if (index == rows.lastIndex) JsonObject((row as JsonObject) + ("blobSha" to JsonPrimitive("0".repeat(40)))) else row
            }
        )
        val invalid = JsonObject(inputs + ("files" to changedRows) + ("sourceSha" to JsonPrimitive(benchmarkHash(BenchmarkPhoneGitHubContract.canonical(changedRows)))))
        val forged = JsonObject(original + ("contextInputs" to invalid) + ("contextManifestDigest" to JsonPrimitive(benchmarkHash(BenchmarkPhoneGitHubContract.canonical(invalid)))))
        assertFails("BENCHMARK_CONTEXT_BLOB_CHANGED") { benchmarkContextInputs(forged) }
    }

    @Test fun staleReceiptCannotPairAndMissingMeasurementsStayUnknown() = runBlocking {
        val plan = plan(variants = listOf("CURRENT", "TASK"))
        val runs = runs(plan)
        val comparison = benchmarkContextComparisons(runs).single()
        assertEquals(4, comparison.matchedPairs)
        assertEquals(100.0, comparison.differencePercentagePoints)
        assertEquals("INSUFFICIENT_EVIDENCE", comparison.state)
        assertNull(comparison.candidate.inputTokens)
        assertNull(comparison.candidate.estimatedApiCostMicros)
        assertNull(comparison.candidate.falseCompletionCount)
        val candidate = runs.first { it.engineeringContextVariant == "TASK" }
        for (corrupt in listOf<(BenchmarkContextReceipt) -> BenchmarkContextReceipt>(
            { it.copy(sourceSha = "0".repeat(64)) },
            { it.copy(referenceDigest = "0".repeat(64)) },
            { it.copy(indexDigest = "0".repeat(64)) },
            { it.copy(manifestDigest = "0".repeat(64)) },
            { it.copy(dispatched = false) }
        )) {
            val damaged = candidate.copy(samples = candidate.samples.map { it.copy(contextReceipt = corrupt(requireNotNull(it.contextReceipt))) })
            val result = benchmarkContextComparisons(runs.map { if (it.id == candidate.id) damaged else it }).single()
            assertEquals(2, result.matchedPairs)
            assertEquals("INCOMPLETE", result.state)
        }
        assertEquals("IDENTITY_MISMATCH", benchmarkContextComparisons(runs + runs.first()).single().state)
    }

    @Test fun semanticFallbackNeverProducesASemanticAdvantageClaim() = runBlocking {
        val plan = plan(variants = listOf("CURRENT", "SEMANTIC"))
        val result = benchmarkContextComparisons(runs(plan)).single()
        assertEquals("SEMANTIC_FALLBACK_RECORDED", result.state)
        assertEquals(listOf("LEXICAL_FALLBACK"), result.candidate.retrievalModes)
    }

    private fun plan(descriptor: JsonObject = descriptor(), variants: List<String> = BenchmarkContextExperiment.VARIANTS): BenchmarkEngineeringPlan {
        val (inputs, manifest) = benchmarkContextInputs(descriptor)
        return fixture.plan.copy(
            profileUids = listOf("a"),
            profileConfigKeys = fixture.plan.profileConfigKeys.filterKeys { it == "a" },
            externalRouteDigests = fixture.plan.externalRouteDigests.filterKeys { it == "a" },
            contextExperiment = BenchmarkContextExperiment(variants),
            contextManifestDigests = fixture.cases.associate { it.id to manifest },
            contextSourceIdentities = fixture.cases.associate { it.id to benchmarkContextSourceIdentity(inputs) }
        ).frozen()
    }

    private suspend fun runs(plan: BenchmarkEngineeringPlan): List<BenchmarkRun> = benchmarkEngineeringQueueOrder(plan).map { (_, repeat, variant) ->
        val arm = requireNotNull(variant)
        fixture.runs().first { it.profileUid == "a" }.copy(
            id = "$arm-$repeat",
            engineeringPlan = plan,
            engineeringRepeatIndex = repeat,
            engineeringContextVariant = arm,
            samples = fixture.cases.map { test ->
                fixture.sample(test, arm != "CURRENT").copy(
                    contextReceipt = benchmarkPrepareContext(descriptor(), plan, test.id, arm).receipt.copy(dispatched = true)
                )
            }
        )
    }

    private suspend fun assertFails(expected: String, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertEquals(expected, error?.message)
    }

    private fun descriptor(code: String = "fun mergeRevision(value: Int) = value\n"): JsonObject {
        val sources = sortedMapOf(
            "src/Pagination.kt" to code,
            "docs/architecture/repository-guide.md" to "# Pagination reference\n\n## Merge revisions\nInspect src/Pagination.kt and its mergeRevision function. Source observation only.\n"
        )
        sources["docs/architecture/feature-map.json"] = """{"schema_version":1,"features":[{"id":"pagination","title":"Pagination merge revisions","terms":["pagination","merge","revision"],"paths":["src/Pagination.kt"],"tests":[],"documents":["docs/architecture/repository-guide.md"],"commands":[],"invariants":[]}]}"""
        val files = JsonArray(
            sources.map { (path, text) ->
                buildJsonObject {
                    put("path", path)
                    val bytes = text.encodeToByteArray()
                    put("blobSha", MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".encodeToByteArray() + bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
                    put("content", text)
                }
            }
        )
        val inputs = buildJsonObject {
            put("schema", "benchmark.context-fixture.v1")
            put("sourceIdentityKind", "FROZEN_FIXTURE_MANIFEST")
            put("query", "Pagination merge revisions mergeRevision")
            put("files", files)
            put("declaredSourcePaths", JsonArray(listOf(JsonPrimitive("src/Pagination.kt"))))
            put("relevanceScope", "DECLARED_PUBLIC_SOURCE_COVERAGE_ONLY")
            put("sourceSha", benchmarkHash(BenchmarkPhoneGitHubContract.canonical(files)))
        }
        return buildJsonObject {
            put("prompt", "Public original source prompt")
            put("contextInputs", inputs)
            put("contextManifestDigest", benchmarkHash(BenchmarkPhoneGitHubContract.canonical(inputs)))
        }
    }
}
