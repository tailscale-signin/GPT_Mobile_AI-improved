package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only measurements supplied by the runtime are reported; absent counters stay unavailable. */
data class DiagnosticExportMemory(
    val capturedAtMillis: Long,
    val javaMaxBytes: Long,
    val javaCommittedBytes: Long,
    val javaUsedBytes: Long,
    val javaHeadroomBytes: Long,
    val nativeAllocatedBytes: Long? = null,
    val gcCount: Long? = null,
    val gcTimeMillis: Long? = null,
    val allocatedBytes: Long? = null,
    val freedBytes: Long? = null,
    val nativeGcSampledAtMillis: Long? = null
) {
    fun json(): JsonObject = buildJsonObject {
        put("capturedAtMillis", capturedAtMillis)
        put("javaMaxBytes", javaMaxBytes)
        put("javaCommittedBytes", javaCommittedBytes)
        put("javaUsedBytes", javaUsedBytes)
        put("javaHeadroomBytes", javaHeadroomBytes)
        put("nativeAllocatedBytes", nativeAllocatedBytes?.let(::JsonPrimitive) ?: JsonNull)
        put("gcCount", gcCount?.let(::JsonPrimitive) ?: JsonNull)
        put("gcTimeMillis", gcTimeMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("allocatedBytes", allocatedBytes?.let(::JsonPrimitive) ?: JsonNull)
        put("freedBytes", freedBytes?.let(::JsonPrimitive) ?: JsonNull)
        put("nativeGcSampledAtMillis", nativeGcSampledAtMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("unavailableMeasurement", "null means unavailable; native allocated heap is not total native/process RSS")
    }

    companion object {
        fun sample(): DiagnosticExportMemory {
            val runtime = Runtime.getRuntime()
            val committed = runtime.totalMemory()
            val used = (committed - runtime.freeMemory()).coerceAtLeast(0)
            val maximum = runtime.maxMemory()
            return DiagnosticExportMemory(System.currentTimeMillis(), maximum, committed, used, (maximum - used).coerceAtLeast(0))
        }
    }
}
