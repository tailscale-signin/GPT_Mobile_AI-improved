package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.PowerManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** An interrupted stage reruns its complete cohort; checkpoints never combine different campaigns. */
internal suspend fun voiceBenchRecognitionCpuCalibration(
    context: Context,
    registry: RecognitionModelRegistry,
    case: VoiceRecognitionBenchmarkCase,
    style: VoiceListeningStyle,
    active: () -> Boolean,
    interrupt: AtomicReference<(() -> Unit)?>,
    cleanupFailed: (Any) -> Unit,
    checkpoint: (List<VoiceBenchObservation>) -> Unit
): List<VoiceBenchObservation> {
    val pack = VoiceRecognitionModelCatalog.pack(case.configuration.model, case.configuration.compute)
    val counts = case.calibrationCandidates
    val reason = case.unavailableReason(pack)
        ?: "RECOGNITION_CPU_CANDIDATES_CHANGED".takeUnless { counts == VoiceRecognitionCpuCalibration.candidates() }
    if (reason != null || pack == null) return listOf(VoiceBenchObservation(case.identity.packId, VoiceBenchStatus.UNAVAILABLE, reason ?: "RECOGNITION_MODEL_PACK_UNAVAILABLE", recognition = case.identity))
    val cohort = UUID.randomUUID().toString()
    val completed = mutableListOf<VoiceBenchObservation>()
    for (threads in counts) {
        if (!active()) throw CancellationException("Voice benchmark stopped")
        val candidate = VoiceRecognitionBenchmarkCase.forPack(pack, threads)
        fun annotate(rows: List<VoiceBenchObservation>): List<VoiceBenchObservation> {
            val warmed = rows.any {
                it.metadata["recognitionWarmup"] == "true" &&
                    it.status == VoiceBenchStatus.MEASURED &&
                    it.metadata["recognitionActualProvider"] == "cpu" &&
                    it.metadata["recognitionExecutionVerified"] == "true"
            }
            return rows.map {
                it.copy(
                    fixture = "cpu-$threads/${it.fixture}",
                    metadata = it.metadata + mapOf(
                        "recognitionCalibrationCohort" to cohort,
                        "recognitionCalibrationWarmupComplete" to warmed.toString(),
                        "recognitionCalibrationScope" to "FIXED_SYNTHETIC_CPU_WORKLOAD_NOT_HUMAN_OR_BATTERY_QUALIFICATION"
                    )
                )
            }
        }
        val rows = voiceBenchRecognition(context, registry, candidate, style, active, interrupt, cleanupFailed, { checkpoint(completed + annotate(it)) }, calibrationWarmup = true)
        completed += annotate(rows)
        checkpoint(completed.toList())
        if (rows.any { it.status == VoiceBenchStatus.INTERRUPTED }) break
    }
    val samples = completed.filter { it.metadata["recognitionWarmup"] == "false" && "recognitionFixtureId" in it.metadata }
    val selected = VoiceRecognitionCpuCalibration.select(pack, counts, samples)
    if (!active()) throw CancellationException("Voice benchmark stopped")
    val thermal = runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()
    val cool = thermal in 0..1
    val saved = selected.threads != null && cool && VoiceRecognitionCpuCalibration.save(context, pack, counts, completed)
    val failure = selected.reason ?: when {
        !cool -> "RECOGNITION_CPU_THERMAL_EVIDENCE_UNQUALIFIED"
        !saved -> "RECOGNITION_CPU_RECOMMENDATION_NOT_SAVED"
        else -> null
    }
    completed += VoiceBenchObservation(
        "recognition-cpu-recommendation",
        if (saved) VoiceBenchStatus.MEASURED else VoiceBenchStatus.UNAVAILABLE,
        failure,
        metrics = buildMap {
            put("calibrationCandidates", counts.size.toDouble())
            if (saved) put("recommendedCpuThreads", requireNotNull(selected.threads).toDouble())
            selected.decodeBusyMs.forEach { (threads, ms) -> put("cpu${threads}DecodeBusyMs", ms) }
        },
        measurementScope = "COMPLETED_MATCHED_SYNTHETIC_CPU_COHORT_NOT_ACCELERATOR_ACOUSTIC_OR_BATTERY_QUALIFICATION",
        recognition = case.identity,
        metadata = mapOf("recognitionCalibrationCohort" to cohort, "recommendationPersisted" to saved.toString(), "selection" to "LOWEST_DECODE_BUSY_TIME_WITH_FEWER_THREADS_WITHIN_FIVE_PERCENT")
    )
    checkpoint(completed.toList())
    return completed
}
