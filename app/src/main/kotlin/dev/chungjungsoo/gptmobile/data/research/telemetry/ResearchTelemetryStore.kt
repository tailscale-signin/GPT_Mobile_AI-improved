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
    val schemaVersion: Int = 1
)

data class ResearchEngineRollup(val attempts: Int, val successes: Int, val failures: Int, val cached: Int, val skipped: Int, val empty: Int = 0, val canceled: Int = 0, val transportSuccesses: Int = 0, val transportFailures: Int = 0, val transportCanceled: Int = 0) {
    val successPercent: Double? get() = if (attempts == 0) null else successes * 100.0 / attempts
    val lowSample: Boolean get() = attempts < 20
}

/** Shared in-process index over immutable events. Statistics must never fail retrieval. */
class ResearchTelemetryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private class Index {
        var loaded = false
        val events = linkedMapOf<String, ResearchAttempt>()
        val rollups = mutableMapOf<Pair<String, Long>, ResearchEngineRollup>()
    }
    private val index = indexes.computeIfAbsent(directory.absolutePath) { Index() }

    private fun load() {
        if (index.loaded) return
        runCatching {
            directory.listFiles().orEmpty().filter { it.extension == "json" && it.length() <= 4096 }.sortedBy { it.lastModified() }.takeLast(MAX_EVENTS).forEach { file ->
                runCatching { json.decodeFromString<ResearchAttempt>(file.readText()) }.getOrNull()?.let { index.events[it.eventId] = it }
            }
        }
        index.loaded = true
    }

    fun record(event: ResearchAttempt) = synchronized(index) {
        runCatching {
            require(event.eventId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
            load()
            if (event.eventId in index.events) return@runCatching
            check(directory.mkdirs() || directory.isDirectory)
            val file = File(directory, "${event.eventId}.json")
            if (file.isFile) return@runCatching
            val temporary = File(directory, "${event.eventId}.tmp")
            val stored = event.copy(schemaVersion = 2)
            temporary.outputStream().use { it.write(json.encodeToString(stored).toByteArray()) }
            check(temporary.renameTo(file))
            index.events[event.eventId] = stored
            index.rollups.keys.removeAll { it.first == event.engineId }
            if (index.events.size > MAX_EVENTS) {
                val oldest = index.events.entries.first()
                File(directory, "${oldest.key}.json").delete()
                index.events.remove(oldest.key)
                index.rollups.keys.removeAll { it.first == oldest.value.engineId }
            }
        }
        Unit
    }

    fun rollup(engineId: String, since: Long = 0): ResearchEngineRollup = synchronized(index) {
        load()
        if (index.rollups.size >= 128) index.rollups.clear()
        index.rollups.getOrPut(engineId to since) {
            val events = index.events.values.filter { it.engineId == engineId && it.recordedAt >= since }
            fun legacyTransport(event: ResearchAttempt) = event.schemaVersion < 2 && event.operation in setOf("lookup_search", "read")
            val live = events.filter { !legacyTransport(it) && it.dispatched && !it.cacheHit && it.outcome in setOf("SUCCESS", "EMPTY", "FAILED", "INVALID_RESPONSE", "CANCELLED") }
            ResearchEngineRollup(live.size, live.count { it.outcome == "SUCCESS" }, live.count { it.outcome in setOf("FAILED", "INVALID_RESPONSE") }, events.count { it.cacheHit }, events.count { !it.dispatched && !it.cacheHit }, live.count { it.outcome == "EMPTY" }, live.count { it.outcome == "CANCELLED" }, events.count { it.outcome == "TRANSPORT_SUCCESS" || legacyTransport(it) && it.outcome == "SUCCESS" }, events.count { it.outcome == "TRANSPORT_FAILED" || legacyTransport(it) && it.outcome == "FAILED" }, events.count { it.outcome == "TRANSPORT_CANCELLED" || legacyTransport(it) && it.outcome == "CANCELLED" })
        }
    }

    fun writeExport(output: java.io.OutputStream) {
        val snapshot = synchronized(index) {
            load()
            index.events.values.toList()
        }
        output.bufferedWriter().use { writer ->
            writer.write("{\"schemaVersion\":1,\"events\":[")
            snapshot.forEachIndexed { index, event ->
                if (index > 0) writer.write(",")
                writer.write(json.encodeToString(event))
            }
            writer.write("]}")
        }
    }

    companion object {
        private const val MAX_EVENTS = 100_000
        private val indexes = java.util.concurrent.ConcurrentHashMap<String, Index>()
    }
}
