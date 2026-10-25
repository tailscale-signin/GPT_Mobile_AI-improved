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
    val policy: String = "benchmark-tool-dimensions-v1"
)
