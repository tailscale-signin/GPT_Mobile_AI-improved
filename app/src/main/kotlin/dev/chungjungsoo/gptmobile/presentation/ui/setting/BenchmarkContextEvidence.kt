package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkContextExperiment
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkContextReceipt
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun

internal fun benchmarkEngineeringRunTitle(run: BenchmarkRun): String = run.profileName +
    if (run.engineeringPlan?.contextExperiment != null || run.engineeringContextVariant != null) {
        " · ${benchmarkContextArm(run.engineeringContextVariant)}"
    } else {
        ""
    }

internal fun benchmarkContextArm(variant: String?): String = variant?.takeIf { it in BenchmarkContextExperiment.VARIANTS } ?: "Unknown arm"

internal fun benchmarkContextBudget(experiment: BenchmarkContextExperiment): String =
    experiment.takeIf { it.valid() }?.let { "${it.budgetBytes} bytes per task" } ?: "Unknown"

/** Only typed observations and valid digest abbreviations belong in the context card. */
internal fun benchmarkContextReceiptDetails(receipt: BenchmarkContextReceipt?): List<String> {
    fun identity(value: String?): String = value?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.take(12) ?: "Unknown"
    fun count(value: Int?): String = value?.takeIf { it >= 0 }?.toString() ?: "Unknown"
    fun milliseconds(value: Long?): String = value?.takeIf { it >= 0 }?.let { "$it ms" } ?: "Unknown"
    fun observed(value: Boolean?): String = when (value) {
        true -> "Yes"
        false -> "No"
        null -> "Unknown"
    }
    val tokens = receipt?.estimatedContextTokens?.takeIf {
        it >= 0 && receipt.tokenMeasurement == "UTF8_BYTES_DIVIDED_BY_THREE_ESTIMATE"
    }?.let { "$it (UTF-8 size estimate)" } ?: "Unknown"
    val preparation = when (receipt?.preparationScope) {
        "LOCAL_SELECTOR_COLD_AND_WARM_CACHE_PROBES_NOT_MODEL_OR_NETWORK_COLD_START" -> "Local selector and source-cache probes"
        else -> "Unknown"
    }
    val sourceKind = when (receipt?.sourceIdentityKind) {
        "FROZEN_FIXTURE_MANIFEST" -> "Frozen public fixture"
        else -> "Unknown"
    }
    return buildList {
        if (receipt == null) add("Context receipt: Not recorded")
        add("Receipt arm: ${benchmarkContextArm(receipt?.variant)} · revision verified: ${observed(receipt?.revisionVerified)} · dispatched: ${observed(receipt?.dispatched)}")
        add("Manifest ${identity(receipt?.manifestDigest)} · source ${identity(receipt?.sourceSha)} ($sourceKind)")
        add("Reference ${identity(receipt?.referenceDigest)} · prompt ${identity(receipt?.promptDigest)} · abbreviated identities")
        add("Supplement bytes: ${count(receipt?.contextBytes)} · estimated tokens: $tokens")
        add("Preparation: cold ${milliseconds(receipt?.coldPreparationMs)} / warm ${milliseconds(receipt?.warmPreparationMs)} · $preparation")
        add("Source reads: cold ${count(receipt?.coldSourceReads)} / warm ${count(receipt?.warmSourceReads)} · warm cache hits: ${count(receipt?.warmCacheHits)}")
    }
}
