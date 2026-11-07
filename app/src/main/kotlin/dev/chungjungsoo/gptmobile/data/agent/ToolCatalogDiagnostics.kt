package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Routine deferral is an inventory summary. Required-tool failures retain their warning receipts. */
internal object ToolCatalogDiagnostics {
    private val window = Window()

    fun recordOmissions(stage: String, omissions: Map<String, String>, schemaAllowance: Long? = null, inventoryNames: Collection<String>? = null) {
        synchronized(window) {
            if (!AppLogRecorder.enabled.value || AppLogRecorder.privateSessionActive) {
                window.clear()
                return
            }
            window.observe(stage, omissions, schemaAllowance, inventoryNames)?.let { AppLogRecorder.record("ToolCatalog", it) }
        }
    }

    fun clear() = window.clear()

    /** Full retained details belong in the bounded export, not repeated lines in the recent-log ring. */
    fun snapshot(): List<JsonObject> = synchronized(window) {
        val available = AppLogRecorder.enabled.value && !AppLogRecorder.privateSessionActive
        if (!available) window.clear()
        listOf(
            buildJsonObject {
                put("kind", "tool_catalog_snapshot_scope")
                put("availability", if (available) "RETAINED_PROCESS_SNAPSHOTS" else "TRACKING_DISABLED_OR_PRIVATE")
                put("scope", "Latest observed input inventory and omissions per admission stage in this process. These are configuration observations, not task execution receipts or test coverage.")
                put("maxStages", 16)
                put("maxToolNamesPerStage", 512)
            }
        ) + window.snapshot()
    }

    internal class Window(private val maxStages: Int = 16, private val maxTools: Int = 512, private val now: () -> Long = System::currentTimeMillis) {
        private data class Seen(
            val fingerprint: String,
            val omissions: Map<String, String>,
            val omittedCount: Int,
            val reasons: Map<String, Int>,
            val reasonCount: Int,
            val inventory: List<String>?,
            val inventoryCount: Int?,
            val schemaAllowance: Long?,
            val observedAtMillis: Long,
            val repetitions: Long,
            val bounded: Boolean
        )
        private val seen = linkedMapOf<String, Seen>()
        private var evictions = 0L

        init {
            require(maxStages in 1..16 && maxTools in 1..512)
        }

        @Synchronized
        fun observe(stage: String, omissions: Map<String, String>, schemaAllowance: Long? = null, inventoryNames: Collection<String>? = null): String? {
            val key = stage.replace('\n', ' ').replace('\r', ' ').take(96)
            val ordered = omissions.toSortedMap()
            val inventory = inventoryNames?.toSortedSet()
            val fingerprint = researchHash(ordered.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\nINVENTORY\n" + inventory?.joinToString("\n"))
            val prior = seen[key]
            if (prior == null && ordered.isEmpty() && inventory == null) return null
            val repetitions = if (prior?.fingerprint == fingerprint) (prior.repetitions + 1).coerceAtLeast(prior.repetitions) else 0
            val details = ordered.entries.asSequence().filter { it.key.length <= 256 && it.value.length <= 128 }.take(maxTools).associate { it.toPair() }
            val inventoryDetails = inventory?.asSequence()?.filter { it.length <= 256 }?.take(maxTools)?.toList()
            val reasons = ordered.values.groupingBy { it }.eachCount().toSortedMap()
            val reasonDetails = reasons.entries.asSequence().filter { it.key.length <= 128 }.take(64).associate { it.toPair() }
            val bounded = details.size < ordered.size || inventoryDetails?.size != inventory?.size || reasons.size > reasonDetails.size
            if (seen.size >= maxStages && key !in seen) {
                seen.remove(seen.keys.first())
                evictions++
            }
            seen.remove(key)
            seen[key] = Seen(fingerprint, details, ordered.size, reasonDetails, reasons.size, inventoryDetails, inventory?.size, schemaAllowance, now(), repetitions, bounded)
            if (repetitions != 0L && repetitions % 32 != 0L) return null
            val added = details.keys - prior?.omissions.orEmpty().keys
            val removed = prior?.omissions.orEmpty().keys - details.keys
            val changed = details.keys.count { it in prior?.omissions.orEmpty() && prior?.omissions?.get(it) != details[it] }
            val digest = prior?.fingerprint ?: "none"
            return summarizeOmissions(key, omissions, schemaAllowance) +
                " · inventoryDigest=$fingerprint · priorDigest=$digest · detailAdded=${added.size} · detailRemoved=${removed.size} · detailReasonChanged=$changed · diffCoverage=${if (bounded || prior?.bounded == true) "BOUNDED_DETAIL" else "FULL_STAGE"} · repeatedRounds=$repetitions"
        }

        @Synchronized
        fun snapshot(): List<JsonObject> = seen.map { (stage, row) ->
            buildJsonObject {
                put("kind", "tool_catalog_stage")
                put("stage", stage)
                put("observedAtMillis", row.observedAtMillis)
                put("inventoryDigest", row.fingerprint)
                put("inventoryNames", row.inventory?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                put("inventoryCount", row.inventoryCount)
                put("inventoryScope", if (row.inventory != null) "STAGE_INPUT" else "NOT_CAPTURED")
                put("omittedCount", row.omittedCount)
                put("omissions", JsonObject(row.omissions.mapValues { JsonPrimitive(it.value) }))
                put("omissionReasons", JsonObject(row.reasons.mapValues { JsonPrimitive(it.value) }))
                put("omissionReasonCount", row.reasonCount)
                put("schemaAllowance", row.schemaAllowance)
                put("repeatedRounds", row.repetitions)
                put("detailsTruncated", row.bounded)
                put("stagesEvicted", evictions)
            }
        }

        @Synchronized
        fun clear() {
            seen.clear()
            evictions = 0
        }
    }

    internal fun summarizeOmissions(stage: String, omissions: Map<String, String>, schemaAllowance: Long? = null): String {
        val ordered = omissions.toSortedMap()
        val counts = ordered.values.groupingBy { it }.eachCount().toSortedMap()
        val sample = ordered.keys.take(8).map { it.replace('\n', ' ').replace('\r', ' ').take(96) }
        val digest = researchHash(ordered.entries.joinToString("\n") { "${it.key}:${it.value}" })
        val summary = counts.entries.take(8).associate { it.key.replace('\n', ' ').replace('\r', ' ').take(96) to it.value }
        return "TOOL_OMISSIONS · stage=$stage · omitted=${ordered.size} · reasons=$summary · reasonCategories=${counts.size} · sampleNames=$sample · detailNamesSampled=${ordered.size > sample.size} · omissionDigest=$digest · schemaAllowance=${schemaAllowance ?: "not measured"}"
    }
}
