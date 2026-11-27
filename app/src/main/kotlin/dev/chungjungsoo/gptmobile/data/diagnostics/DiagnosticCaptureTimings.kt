package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Scalar-only observations for one capture attempt; no payload, query or credential is retained. */
internal class DiagnosticCaptureTimings(private val nanoTime: () -> Long = System::nanoTime) {
    enum class Phase {
        QUERY_MATERIALIZATION,
        PROTECTED_HYDRATION,
        PROTECTED_AUTHORIZATION,
        PROTECTED_STORAGE_STAT,
        PROTECTED_READ_DECRYPT,
        PROTECTED_DIGEST,
        PROTECTED_SHAPE,
        PROTECTED_JSON_PARSE,
        PROTECTED_PROJECTION,
        PROTECTED_REDACTION,
        REDACTION,
        PAYLOAD_DEDUPLICATION,
        SERIALIZATION,
        WRITE,
        CHECKPOINT
    }

    private data class Sample(var calls: Long = 0, var nanos: Long = 0)
    private val samples = linkedMapOf<Phase, Sample>()
    private var redactionReuseHits = 0L
    private var bodyReferenceReuseHits = 0L
    private val verifiedShapes = linkedSetOf<Pair<Int, String>>()
    private var shapeReuseHits = 0L

    fun hasVerifiedShape(nodeBudget: Int, digest: String): Boolean {
        val hit = (nodeBudget to digest) in verifiedShapes
        if (hit) shapeReuseHits++
        return hit
    }

    fun retainVerifiedShape(nodeBudget: Int, digest: String) {
        verifiedShapes.add(nodeBudget to digest)
        if (verifiedShapes.size > 32) verifiedShapes.remove(verifiedShapes.first())
    }

    fun bodyReferenceReused(count: Int) {
        bodyReferenceReuseHits += count.coerceAtLeast(0)
    }

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
        put("scope", "Current stage attempt only; excludes earlier resumed parts. Query includes bounded cursor materialization; hydration includes its separately reported authorization, storage, decryption, digest, shape, parse, projection and redaction child phases. Parent and child timings overlap; they are elapsed observations, not CPU time or a stage sum.")
        put("redactionReuseHits", redactionReuseHits)
        put("payloadBodyReferenceReuseHits", bodyReferenceReuseHits)
        put("protectedShapeReuseHits", shapeReuseHits)
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
