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
    val owner: String = "phone"
)

data class ResearchEngineRollup(val attempts: Int, val successes: Int, val failures: Int, val cached: Int, val skipped: Int) {
    val successPercent: Double? get() = if (attempts == 0) null else successes * 100.0 / attempts
    val lowSample: Boolean get() = attempts < 20
}

/** Immutable, idempotent events. Resetting display history never touches provider cooldowns. */
class ResearchTelemetryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(event: ResearchAttempt) {
        require(event.eventId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "${event.eventId}.json")
        if (file.isFile) return
        val temporary = File(directory, "${event.eventId}.tmp")
        temporary.outputStream().use { it.write(json.encodeToString(event).toByteArray()) }
        check(temporary.renameTo(file)) { "Could not checkpoint research telemetry" }
    }

    @Synchronized
    fun rollup(engineId: String, since: Long = 0): ResearchEngineRollup {
        val events = directory.listFiles().orEmpty().asSequence().filter { it.extension == "json" && it.length() <= 4096 }
            .mapNotNull { runCatching { json.decodeFromString<ResearchAttempt>(it.readText()) }.getOrNull() }
            .filter { it.engineId == engineId && it.recordedAt >= since }.distinctBy { it.eventId }.toList()
        val live = events.filter { it.dispatched && !it.cacheHit && it.outcome in setOf("SUCCESS", "EMPTY", "FAILED", "INVALID_RESPONSE") }
        val successes = live.count { it.outcome in setOf("SUCCESS", "EMPTY") }
        return ResearchEngineRollup(live.size, successes, live.size - successes, events.count { it.cacheHit }, events.count { !it.dispatched })
    }
}
