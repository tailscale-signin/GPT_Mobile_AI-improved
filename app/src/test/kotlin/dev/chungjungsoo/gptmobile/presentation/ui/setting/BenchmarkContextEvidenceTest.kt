package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkContextExperiment
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkContextReceipt
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringLane
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringTestFixtures
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkContextEvidenceTest {
    @Test fun runTitlesKeepTheFrozenArmAndOrdinaryProfileNames() {
        val run = contextEvidenceRun()
        assertEquals("Retained profile · TASK", benchmarkEngineeringRunTitle(run))
        assertEquals("Retained profile · CURRENT", benchmarkEngineeringRunTitle(run.copy(engineeringContextVariant = "CURRENT")))
        assertEquals("Retained profile · Unknown arm", benchmarkEngineeringRunTitle(run.copy(engineeringContextVariant = "private-untrusted-value")))
        assertEquals("Retained profile", benchmarkEngineeringRunTitle(run.copy(engineeringPlan = null, engineeringContextVariant = null)))
    }

    @Test fun frozenBudgetUsesTheAcceptedExperimentInsteadOfTheCurrentDefault() {
        val accepted = requireNotNull(contextEvidenceRun().engineeringPlan?.contextExperiment)
        assertEquals("8192 bytes per task", benchmarkContextBudget(accepted))
        assertEquals("12000 bytes per task", benchmarkContextBudget(BenchmarkContextExperiment()))
        assertEquals("Unknown", benchmarkContextBudget(accepted.copy(budgetBytes = -1)))
    }

    @Test fun receiptShowsIdentitiesObservedFlagsAndAQualifiedEstimate() {
        val lines = benchmarkContextReceiptDetails(contextEvidenceReceipt())
        assertTrue(lines.contains("Receipt arm: TASK · revision verified: Yes · dispatched: No"))
        assertTrue(lines.contains("Manifest aaaaaaaaaaaa · source bbbbbbbbbbbb (Frozen public fixture)"))
        assertTrue(lines.contains("Reference cccccccccccc · prompt dddddddddddd · abbreviated identities"))
        assertTrue(lines.contains("Supplement bytes: 642 · estimated tokens: 214 (UTF-8 size estimate)"))
        assertTrue(lines.contains("Preparation: cold 12 ms / warm 3 ms · Local selector and source-cache probes"))
        assertTrue(lines.contains("Source reads: cold 3 / warm 0 · warm cache hits: 3"))
        assertFalse(lines.any { "measured tokens" in it })
    }

    @Test fun missingReceiptDoesNotTurnAbsentMeasurementsIntoZeroOrNo() {
        val lines = benchmarkContextReceiptDetails(null)
        assertTrue(lines.contains("Context receipt: Not recorded"))
        assertTrue(lines.contains("Receipt arm: Unknown arm · revision verified: Unknown · dispatched: Unknown"))
        assertTrue(lines.contains("Supplement bytes: Unknown · estimated tokens: Unknown"))
        assertTrue(lines.contains("Preparation: cold Unknown / warm Unknown · Unknown"))
        assertFalse(lines.any { "0" in it || "verified: No" in it || "dispatched: No" in it })
    }

    @Test fun observedBaselineZerosRemainDistinctFromMissingEvidence() {
        val lines = benchmarkContextReceiptDetails(contextEvidenceReceipt().copy(variant = "CURRENT", contextBytes = 0, estimatedContextTokens = 0, coldPreparationMs = 0, warmPreparationMs = 0, revisionVerified = false))
        assertTrue(lines.contains("Receipt arm: CURRENT · revision verified: No · dispatched: No"))
        assertTrue(lines.contains("Supplement bytes: 0 · estimated tokens: 0 (UTF-8 size estimate)"))
        assertTrue(lines.contains("Preparation: cold 0 ms / warm 0 ms · Local selector and source-cache probes"))
    }

    @Test fun untrustedReceiptStringsAndNegativeMeasurementsAreNotDisplayed() {
        val privateValue = "private prompt and source /customer/secret.kt"
        val lines = benchmarkContextReceiptDetails(
            contextEvidenceReceipt().copy(
                variant = privateValue, manifestDigest = privateValue, sourceSha = privateValue,
                sourceIdentityKind = privateValue, referenceDigest = privateValue, promptDigest = privateValue,
                tokenMeasurement = privateValue, preparationScope = privateValue,
                contextBytes = -1, estimatedContextTokens = -1, coldPreparationMs = -1, warmPreparationMs = -1,
                coldSourceReads = -1, warmSourceReads = -1, warmCacheHits = -1
            )
        )
        assertFalse(lines.any { privateValue in it || "secret.kt" in it || "-1" in it })
        assertTrue(lines.contains("Manifest Unknown · source Unknown (Unknown)"))
        assertTrue(lines.contains("Supplement bytes: Unknown · estimated tokens: Unknown"))
        assertTrue(lines.contains("Source reads: cold Unknown / warm Unknown · warm cache hits: Unknown"))
    }

    @Test fun anUnsupportedTokenMeasurementCannotBecomeAMeasuredTokenClaim() {
        val lines = benchmarkContextReceiptDetails(contextEvidenceReceipt().copy(tokenMeasurement = "MEASURED_SELECTED_TOKENIZER"))
        assertTrue(lines.contains("Supplement bytes: 642 · estimated tokens: Unknown"))
    }
}

internal fun contextEvidenceReceipt() = BenchmarkContextReceipt(
    variant = "TASK", manifestDigest = "a".repeat(64), sourceSha = "b".repeat(64),
    referenceDigest = "c".repeat(64), indexDigest = "b".repeat(64), promptDigest = "d".repeat(64),
    selectedPathCount = 3, selectedSourceCount = 3, declaredSourceCount = 5, selectedTestCount = 1,
    contextBytes = 642, estimatedContextTokens = 214, coldPreparationMs = 12, warmPreparationMs = 3,
    coldSourceReads = 3, warmSourceReads = 0, warmCacheHits = 3, repeatedSourceReads = 0,
    retrievalMode = "TASK_LEXICAL", revisionVerified = true, dispatched = false
)

internal fun contextEvidenceRun(): BenchmarkRun {
    val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.EXECUTABLE_CODING, families = 1, repeats = 1)
    val plan = fixture.plan.copy(contextExperiment = BenchmarkContextExperiment(listOf("CURRENT", "TASK"), budgetBytes = 8192))
    return fixture.runs().first().let { run ->
        run.copy(profileName = "Retained profile", engineeringPlan = plan, engineeringContextVariant = "TASK", samples = run.samples.map { it.copy(contextReceipt = contextEvidenceReceipt()) })
    }
}
