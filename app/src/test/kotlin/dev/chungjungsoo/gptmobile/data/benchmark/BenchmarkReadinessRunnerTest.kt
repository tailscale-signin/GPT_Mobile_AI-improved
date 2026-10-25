package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReadinessRunnerTest {
    private val policy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1
    private val plan = listOf(BenchmarkIntelligenceFixtures.batches(0).first())
    private fun metadata(local: Boolean): BenchmarkReadinessReceipt {
        val required = if (local) setOf("configuration", "model_header", "initialization", "tokenizer", "memory") else setOf("configuration")
        val checks = listOf("configuration", "model_header", "initialization", "tokenizer", "memory").map { id ->
            BenchmarkReadinessCheck(
                id,
                when {
                    id !in required -> BenchmarkReadinessStatus.NOT_REQUIRED
                    id in setOf("configuration", "model_header") -> BenchmarkReadinessStatus.PASSED
                    else -> BenchmarkReadinessStatus.UNKNOWN
                }
            )
        }
        return BenchmarkReadinessReceipt(1, null, checks, required, policy)
    }
    private fun cleanup(safe: Boolean = true) = RuntimeCleanupReceipt("probe", requiredActions = setOf("close_conversation"), obligationsObserved = true, nativeClosureConfirmed = true.takeIf { safe }, conversationClosed = true, engineHealthy = true.takeIf { safe })
    private fun passed(test: BenchmarkCase, key: String?, local: Boolean = true): BenchmarkSample {
        val configuration = GenerationConfigReceipt(test.id, 1, "engine", null, 256, measuredPromptTokens = 10, structuredOutputCompatibilityKey = key, observedAtElapsedMs = 1)
        val execution = RuntimeExecutionReceipt(runtimeRequestId = test.id, nativeEntered = true, nativeReturned = true, terminalKind = "eos", promptTokens = 10, sampledTokens = 10, configuration = configuration)
        val answer = test.expected ?: "READY"
        return BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED, outputCharacters = answer.length, canonicalAnswer = answer, terminalReceived = true, requestDispatched = true, runtimeExecutions = if (local) listOf(execution) else emptyList(), runtimeCleanup = cleanup().takeIf { local })
    }
    private fun failed(test: BenchmarkCase, code: String, safe: Boolean = true) = BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.ERROR, canonicalAnswer = "{\"Math\":", failureCategory = code, failure = BenchmarkFailure(code, "generation", "Retained primary failure"), runtimeCleanup = cleanup(safe))

    @Test fun threeNativeProbesRequireOneActualLoadedKeyAndCompletedReceipt() = runTest {
        val saved = mutableListOf<BenchmarkReadinessReceipt>()
        var calls = 0
        val result = BenchmarkReadinessRunner({ _, _ -> metadata(true) }, { test, context, retained ->
            assertTrue(context.readinessProbe)
            assertEquals(BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1, context.structuredOutputMode)
            assertEquals(90_000L, context.requestDeadlineMs)
            calls++
            passed(test, "loaded-key").also { retained(it) }
        }, { saved += it }, now = { 10 }).run(plan, policy, true, false)
        assertEquals(3, calls)
        assertTrue(result.ready)
        assertFalse(result.nativePrimaryFailureRetained)
        assertTrue(saved.filter { it.finishedAt == null }.all { !it.ready })
        assertEquals(3, result.probes.size)
        assertTrue(result.probes.all { it.sample?.canonicalAnswer == plan.single().expected })
    }

    @Test fun nativeUnsupportedRetainsFailureAndVerifiesThreeSeparatePromptProbesForEachSchema() = runTest {
        val second = BenchmarkCase("generic", "Generic JSON", "json", "Return ready", "{\"status\":\"ready\"}", responseSchemaJson = "{\"type\":\"object\"}")
        var calls = 0
        val result = BenchmarkReadinessRunner({ _, _ -> metadata(true) }, { test, context, retained ->
            calls++
            val sample = if (context.structuredOutputMode == BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1) failed(test, "STRUCTURED_OUTPUT_MODE_UNSUPPORTED") else passed(test, "loaded-${benchmarkHash(test.responseSchemaJson!!)}")
            sample.also { retained(it) }
        }, {}).run(plan + second, policy, true, false)
        assertEquals(8, calls)
        assertTrue(result.ready)
        assertTrue(result.nativePrimaryFailureRetained)
        assertEquals(2, result.probes.count { it.sample?.outcome == BenchmarkOutcome.ERROR })
        assertEquals(6, result.probes.count { it.passed })
        assertEquals(2, result.verifiedStructuredCompatibilityKeys.size)
        assertTrue(result.schemaModes.values.all { it == BenchmarkStructuredOutputMode.PROMPT_JSON_VERIFIED_V1 })
        assertEquals(setOf(benchmarkHash(plan.single().responseSchemaJson!!), benchmarkHash(second.responseSchemaJson!!)), result.verifiedStructuredCompatibilityKeys.keys)
        assertEquals("{\"Math\":", result.probes.first().sample?.canonicalAnswer)
    }

    @Test fun unknownNativeFailureOrUnsafeCleanupDoesNotAuthorizeFallback() = runTest {
        for ((code, safe) in listOf("NATIVE_EXECUTION_FAILED" to true, "STRUCTURED_OUTPUT_MALFORMED" to false)) {
            var calls = 0
            val result = BenchmarkReadinessRunner({ _, _ -> metadata(true) }, { test, _, retained ->
                calls++
                failed(test, code, safe).also { retained(it) }
            }, {}).run(plan, policy, true, false)
            assertEquals(1, calls)
            assertFalse(result.ready)
            assertTrue(result.verifiedStructuredCompatibilityKeys.isEmpty())
        }
    }

    @Test fun changingLoadedIdentityCannotBecomeVerifiedThroughThreeIndividuallyPassingProbes() = runTest {
        var calls = 0
        val result = BenchmarkReadinessRunner({ _, _ -> metadata(true) }, { test, _, retained -> passed(test, "engine-${calls++}").also { retained(it) } }, {}).run(plan, policy, true, false)
        assertEquals(3, calls)
        assertFalse(result.ready)
        assertTrue(result.probes.all { it.passed })
        assertTrue(result.verifiedStructuredCompatibilityKeys.isEmpty())
    }

    @Test fun remoteMetadataDoesNotRequireALocalHeaderAndMalformedPrefixIsNeverRepaired() = runTest {
        val good = BenchmarkReadinessRunner({ _, _ -> metadata(false) }, { test, _, retained -> passed(test, null, false).also { retained(it) } }, {}).run(plan, policy, false, false)
        assertTrue(good.ready)
        assertEquals(3, good.probes.size)
        val raw = "{\"{\"Math\":\"138\"}"
        var calls = 0
        val bad = BenchmarkReadinessRunner({ _, _ -> metadata(false) }, { test, _, retained ->
            calls++
            passed(test, null, false).copy(canonicalAnswer = raw).also { retained(it) }
        }, {}).run(plan, policy, false, false)
        assertFalse(bad.ready)
        assertEquals(1, calls)
        assertEquals(raw, bad.probes.single().sample?.canonicalAnswer)
    }

    @Test fun interruptedProbeCommitsItsPartialAndCleanupBeforeCancellationEscapes() = runTest {
        val saved = mutableListOf<BenchmarkReadinessReceipt>()
        val runner = BenchmarkReadinessRunner({ _, _ -> metadata(true) }, { test, _, retained ->
            retained(failed(test, "USER_STOP").copy(outcome = BenchmarkOutcome.CANCELED, canonicalAnswer = "{\"partial\":"))
            throw CancellationException("user stopped during probe")
        }, { saved += it })
        var canceled = false
        try {
            runner.run(plan, policy, true, false)
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
        val last = saved.last()
        assertNull(last.finishedAt)
        assertFalse(last.ready)
        assertEquals("INTERRUPTED", last.probes.single().state)
        assertEquals("{\"partial\":", last.probes.single().sample?.canonicalAnswer)
        assertEquals(true, last.probes.single().sample?.runtimeCleanup?.safeForSuccessor)
    }
}
