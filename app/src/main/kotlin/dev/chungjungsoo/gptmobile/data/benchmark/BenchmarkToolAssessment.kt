package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkToolAssessment(
    val toolSelectionCorrect: Boolean?,
    val argumentsCorrect: Boolean?,
    val executionSuccessful: Boolean?,
    val resultUsed: Boolean?,
    val finalFormatCompliant: Boolean?,
    val progressProtocolCompliant: Boolean?,
    val expectedRetryObserved: Boolean?,
    val callLimitRespected: Boolean?,
    val attemptedCalls: Int,
    val successfulCalls: Int,
    val expectedErrorCalls: Int,
    val policy: String = "benchmark-tool-dimensions-v1",
    // Optional so persisted benchmark exports from earlier app builds remain readable.
    val argumentEvidence: BenchmarkToolArgumentEvidence? = null,
    /** Never conflate a fixture pass with a live repository or Copilot run. */
    val evidenceClass: String = "OFFLINE_TOOL_CONTRACT",
    val workflowCompleted: Boolean? = null
)

/** Field identity only. Never persists model-proposed argument values or private strings. */
@Serializable
data class BenchmarkToolArgumentEvidence(
    val expectedToolSelected: Boolean,
    val schemaValid: Boolean,
    val exactArguments: Boolean,
    val observedFieldNames: List<String>,
    val missingFieldNames: List<String>,
    val additionalFieldNames: List<String>,
    val changedFieldNames: List<String>,
    val policy: String = "benchmark-argument-evidence-v1"
)
