package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Scalar-only observations for one capture attempt; no payload, query or credential is retained. */
internal class DiagnosticCaptureTimings(private val nanoTime: () -> Long = System::nanoTime) {
    enum class Phase { QUERY_MATERIALIZATION, PROTECTED_HYDRATION, REDACTION, SERIALIZATION, WRITE, CHECKPOINT }

    private data class Sample(var calls: Long = 0, var nanos: Long = 0)
    private val samples = linkedMapOf<Phase, Sample>()
    private var redactionReuseHits = 0L

    fun redactionReused() {
        redactionReuseHits++
    }

    fun <T> measure(phase: Phase, action: () -> T): T {
        val start = nanoTime()
        try {
            return action()
        } finally {
            retain(phase, start)
        }
    }

    suspend fun <T> measureSuspending(phase: Phase, action: suspend () -> T): T {
        val start = nanoTime()
        try {
            return action()
        } finally {
            retain(phase, start)
        }
    }

    private fun retain(phase: Phase, start: Long) {
        val sample = samples.getOrPut(phase) { Sample() }
        sample.calls++
        sample.nanos += (nanoTime() - start).coerceAtLeast(0)
    }

    fun json(): JsonObject = buildJsonObject {
        put("scope", "Current stage attempt only; excludes earlier resumed parts. Query includes bounded cursor materialization; hydration includes protected projection. Timings are elapsed observations, not CPU time or a complete stage sum.")
        put("redactionReuseHits", redactionReuseHits)
        put(
            "phases",
            buildJsonObject {
                Phase.entries.forEach { phase ->
                    val sample = samples[phase]
                    put(
                        phase.name,
                        buildJsonObject {
                            put("calls", sample?.calls ?: 0)
                            put("durationNanos", sample?.nanos ?: 0)
                        }
                    )
                }
            }
        )
    }
}
