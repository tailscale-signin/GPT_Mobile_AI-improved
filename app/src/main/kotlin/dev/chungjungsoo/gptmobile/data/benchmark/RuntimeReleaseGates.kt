package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionMode
import kotlinx.serialization.Serializable

/** Device-harness evidence, independent of a quick benchmark or library-presence probe. */
@Serializable
data class RuntimeReleaseEvidence(
    val backendConfirmed: Boolean = false,
    val controlledTurns: Int = 0,
    val completedTurns: Int = 0,
    val nativeCrashes: Int = 0,
    val anrs: Int = 0,
    val cancellationScenarios: Int = 0,
    val workerDeathScenarios: Int = 0,
    val memoryScenarios: Int = 0,
    val noToolReplay: Boolean = false,
    val losslessStream: Boolean = false,
    val mtpFixturesPass: Boolean = false,
    val qualityEquivalent: Boolean = false,
    val persistencePass: Boolean = false,
    val sustainedDecodeRatio: Double? = null,
    val pairedConfidenceLower: Double? = null,
    val firstVisibleP95Ratio: Double? = null,
    val endToEndRatio: Double? = null
)

object RuntimeReleaseGates {
    fun failures(evidence: RuntimeReleaseEvidence, mode: LocalExecutionMode): List<String> = buildList {
        if (!evidence.backendConfirmed) add("G1 Backend execution not confirmed")
        if (evidence.controlledTurns < 500 || evidence.completedTurns > evidence.controlledTurns || evidence.completedTurns < evidence.controlledTurns * .99 || evidence.nativeCrashes != 0 || evidence.anrs != 0) add("G2 Stability evidence incomplete")
        if (evidence.cancellationScenarios < 50 || evidence.workerDeathScenarios < 10 || evidence.memoryScenarios < 20 || !evidence.noToolReplay) add("G3 Recovery evidence incomplete")
        if (!evidence.losslessStream) add("G4 Lossless ordered streaming unverified")
        if (!evidence.mtpFixturesPass) add("G5 MTP/workflow fixtures unverified")
        if (mode != LocalExecutionMode.SINGLE) {
            val helper = mode == LocalExecutionMode.GPU_VISION_NPU_TEXT || mode == LocalExecutionMode.BOUNDED_HELPER
            if (helper) {
                if (evidence.endToEndRatio?.let { it.isFinite() && it < 1 } != true || evidence.sustainedDecodeRatio?.let { it.isFinite() && it >= .95 } != true) add("G6 Intended workflow gain unverified")
            } else if (evidence.sustainedDecodeRatio?.let { it.isFinite() && it >= 1.15 } != true || evidence.pairedConfidenceLower?.let { it.isFinite() && it > 1 } != true) {
                add("G6 Sustained speed gain unverified")
            }
        }
        if (!evidence.qualityEquivalent || evidence.firstVisibleP95Ratio?.let { it.isFinite() && it <= 1.10 && it > 0 } != true) add("G7 Quality/latency regression checks incomplete")
        if (!evidence.persistencePass) add("G8 Migration/restore checks incomplete")
    }
}
