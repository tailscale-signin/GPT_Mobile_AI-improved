package dev.chungjungsoo.gptmobile.data.research.telemetry

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ResearchAttempt(
    val eventId: String,
    val engineId: String,
    val operation: String,
    val recordedAt: Long,
    val durationMs: Long = 0,
    val outcome: String,
    val httpCode: Int? = null,
    val dispatched: Boolean = true,
    val cacheHit: Boolean = false,
    val transportId: String = "native",
    val owner: String = "phone",
    val evidenceRecords: Int? = null
)

data class ResearchEngineRollup(
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val cached: Int,
    val skipped: Int,
    val usefulEvidenceRecords: Int? = null,
    val canceled: Int = 0,
    val medianLatencyMs: Long? = null,
    val p95LatencyMs: Long? = null,
    val lastSuccessAtMs: Long? = null
) {
    val successPercent: Double? get() = if (attempts == 0) null else successes * 100.0 / attempts
    val lowSample: Boolean get() = attempts < 20
}

/** Immutable, idempotent events. Resetting display history never touches provider cooldowns. */
class ResearchTelemetryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedEvents: List<ResearchAttempt>? = null
    private val cachedRollups = linkedMapOf<Pair<String, Long>, ResearchEngineRollup>()

    @Synchronized
    fun record(event: ResearchAttempt) {
        require(event.eventId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "${event.eventId}.json")
        if (file.isFile) return
        val files = directory.listFiles().orEmpty().filter { it.extension == "json" }
        if (files.size >= 5_000) {
            files.sortedBy { it.lastModified() }.take(files.size - 4_499).forEach { check(it.delete()) { "Could not prune expired research telemetry" } }
            cachedEvents = null
        }
        val temporary = File(directory, "${event.eventId}.tmp")
        temporary.outputStream().use { it.write(json.encodeToString(event).toByteArray()) }
        check(temporary.renameTo(file)) { "Could not checkpoint research telemetry" }
        cachedEvents = cachedEvents?.plus(event)
        cachedRollups.clear()
    }

    @Synchronized
    fun rollup(engineId: String, since: Long = 0): ResearchEngineRollup {
        val key = engineId to since
        cachedRollups[key]?.let { return it }
        val loaded = cachedEvents ?: directory.listFiles().orEmpty().asSequence().filter { it.extension == "json" && it.length() <= 4096 }
            .mapNotNull { runCatching { json.decodeFromString<ResearchAttempt>(it.readText()) }.getOrNull() }
            .distinctBy { it.eventId }.toList().also { cachedEvents = it }
        val events = loaded.filter { it.engineId == engineId && it.recordedAt >= since }
        val live = events.filter { it.dispatched && !it.cacheHit && it.outcome in setOf("SUCCESS", "EMPTY", "FAILED", "INVALID_RESPONSE") }
        val successes = live.count { it.outcome in setOf("SUCCESS", "EMPTY") }
        val times = live.map { it.durationMs }.sorted()
        val yield = live.mapNotNull { it.evidenceRecords?.takeIf { count -> count >= 0 } }
        val result = ResearchEngineRollup(
            live.size, successes, live.size - successes, events.count { it.cacheHit }, events.count { !it.dispatched },
            usefulEvidenceRecords = yield.takeIf { it.size == live.size && live.isNotEmpty() }?.sum(),
            canceled = events.count { it.outcome == "CANCELLED" },
            medianLatencyMs = times.takeIf { it.isNotEmpty() }?.get((times.size - 1) / 2),
            p95LatencyMs = times.takeIf { it.isNotEmpty() }?.get((times.size - 1) * 95 / 100),
            lastSuccessAtMs = live.filter { it.outcome in setOf("SUCCESS", "EMPTY") }.maxOfOrNull { it.recordedAt }
        )
        if (cachedRollups.size >= 128) cachedRollups.remove(cachedRollups.keys.first())
        cachedRollups[key] = result
        return result
    }
}
