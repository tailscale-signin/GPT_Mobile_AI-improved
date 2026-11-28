package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.coroutines.CancellationException

/** The same sequencing and stop rules drive device runs and deterministic recovery tests. */
internal class VoiceBenchmarkCampaign(
    private val store: VoiceBenchmarkSuiteStore,
    private val changed: (VoiceBenchManifest) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(
        initial: VoiceBenchManifest,
        stages: List<VoiceBenchStage>,
        stopReason: () -> String?,
        ready: suspend () -> Boolean,
        measure: suspend (Int, VoiceBenchStage) -> VoiceBenchResult
    ) {
        var current = initial
        fun checkpoint(value: VoiceBenchManifest) {
            current = value
            store.save(current)
            changed(current)
        }
        var finish = "FINISHED"
        try {
            for ((index, stage) in stages.withIndex()) {
                stopReason()?.let { finish = it }
                if (finish != "FINISHED") break
                if (current.results[index].status != VoiceBenchStatus.PENDING) continue
                if (!ready()) {
                    finish = stopReason() ?: "THERMAL_LIMIT"
                    break
                }
                stopReason()?.let { finish = it }
                if (finish != "FINISHED") break
                checkpoint(current.update(index, VoiceBenchResult(stage.label, VoiceBenchStatus.RUNNING)))
                val result = measure(index, stage)
                checkpoint(current.update(index, result))
                if (result.status == VoiceBenchStatus.INTERRUPTED) {
                    finish = if (result.reason == "THERMAL_LIMIT") "THERMAL_LIMIT" else "INTERRUPTED"
                    break
                }
            }
            finish = stopReason() ?: finish
        } catch (cancelled: CancellationException) {
            finish = stopReason() ?: "INTERRUPTED"
            throw cancelled
        } catch (error: Throwable) {
            finish = "INTERRUPTED"
            throw error
        } finally {
            checkpoint(current.stopped(finish, now()))
        }
    }
}
