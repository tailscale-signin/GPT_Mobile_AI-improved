package dev.chungjungsoo.gptmobile.data.network.gateway

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class GatewayDiagnosticStatus(
    val captureState: String = "disabled",
    val protocolVersion: Int? = null,
    val retained: Int = 0,
    val pending: Int = 0,
    val evicted: Long = 0,
    val dropped: Long = 0,
    val rejected: Long = 0,
    val lastSyncAt: Long? = null,
    val storageError: String? = null,
    val sessionId: String? = null,
    val remoteCursor: Long = 0,
    val sourceAvailability: String = "not_observed",
    val routeDigest: String? = null,
    val incidents: Int = 0,
    val operationCount: Int = 0,
    val vendorLogAvailable: Boolean = false
)

internal data class GatewayDiagnosticPage(val events: List<JsonObject>, val nextOffset: Int?, val total: Int)

/**
 * A bounded offline mirror, separate from job/effect receipts. Call only on the diagnostics IO worker.
 * One atomic generation contains both the 5,000 retained events and nonrotating ACK/sequence state;
 * a failed write cannot acknowledge or reuse an identity from a different committed generation.
 */
internal class GatewayDiagnosticSpool(
    private val file: File,
    private val bootId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val write: (File, String, List<String>) -> Unit = ::writeDiagnosticGeneration
) {
    private data class State(
        val session: String,
        val origin: String,
        val created: Long,
        val sequences: MutableMap<String, Long> = linkedMapOf(),
        val acknowledged: MutableMap<String, Long> = linkedMapOf(),
        val gaps: MutableList<JsonObject> = mutableListOf(),
        val remoteGaps: MutableList<JsonObject> = mutableListOf(),
        val rows: MutableList<String> = mutableListOf(),
        var evicted: Long = 0,
        var queueDropped: Long = 0,
        var rejected: Long = 0,
        var remoteCursor: Long = 0,
        var lastSync: Long = 0
    ) {
        fun detached() = copy(sequences = sequences.toMutableMap(), acknowledged = acknowledged.toMutableMap(), gaps = gaps.toMutableList(), remoteGaps = remoteGaps.toMutableList(), rows = rows.toMutableList())
    }

    @Volatile private var state: State

    @Volatile private var storageError: String? = null

    init {
        require(GatewayDiagnosticEvent.hex32.matches(bootId))
        state = if (file.isFile) load() else State(randomId(), randomId(), now()).also(::persist)
    }

    // Header construction never waits for disk writes or a long export/status read.
    val sessionId: String get() = state.session
    val originId: String get() = state.origin
    val captureStartedAt: Long get() = state.created
    val cursor: Long get() = state.remoteCursor

    @Synchronized fun append(events: List<Pair<Long, JsonObject>>, queueDropped: Long = 0) = mutate { next ->
        checkCapture(next)
        if (bootId !in next.sequences && next.sequences.size >= 16) throw IOException("DIAGNOSTIC_SOURCE_LIMIT")
        next.queueDropped += queueDropped.coerceAtLeast(0)
        events.forEach { (occurredAt, fields) ->
            val sequence = (next.sequences[bootId] ?: 0) + 1
            val event = GatewayDiagnosticEvent.create(next.origin, bootId, sequence, occurredAt, fields)
            if (event == null) {
                next.rejected++
            } else {
                next.sequences[bootId] = sequence
                next.rows += event.toString()
                trim(next)
            }
        }
    }

    @Synchronized fun batch(): JsonObject {
        checkCapture(state)
        val pendingGaps = state.gaps.filter { (it.diagnosticLong("last") ?: 0) > (state.acknowledged[it.diagnosticText("origin_boot_id")] ?: 0) }.take(32)
        val selected = mutableListOf<JsonObject>()
        var bytes = JsonArray(pendingGaps).toString().toByteArray().size + 512
        for (line in state.rows) {
            val event = GatewayDiagnosticEvent.parse(line) ?: continue
            if (event.diagnosticText("origin_device_pseudonym") != state.origin) continue
            val acknowledged = state.acknowledged[event.diagnosticText("origin_boot_id")] ?: 0
            if ((event.diagnosticLong("origin_sequence") ?: 0) <= acknowledged) continue
            val size = GatewayDiagnosticEvent.bytes(event) + 1
            if (selected.size >= GatewayDiagnosticEvent.MAX_BATCH_EVENTS || bytes + size > GatewayDiagnosticEvent.MAX_BATCH_BYTES) break
            selected += event
            bytes += size
        }
        return buildJsonObject {
            put("schema_version", 2)
            put("session_id", state.session)
            put("capture_started_at_utc", state.created)
            put("events", JsonArray(selected))
            put("gaps", JsonArray(pendingGaps))
        }
    }

    @Synchronized fun acknowledge(receipt: JsonObject) = mutate { next ->
        if (receipt.diagnosticLong("schema_version") != 2L || receipt.diagnosticText("session_id") != next.session || receipt.diagnosticText("storage_state") != "committed") throw IOException("INVALID_DIAGNOSTIC_ACK")
        val watermarks = receipt["watermarks"] as? JsonArray ?: throw IOException("INVALID_DIAGNOSTIC_ACK")
        if (watermarks.size > 16) throw IOException("INVALID_DIAGNOSTIC_ACK")
        watermarks.filterIsInstance<JsonObject>().forEach { watermark ->
            if (watermark.diagnosticText("origin_device_pseudonym") != next.origin) return@forEach
            val boot = watermark.diagnosticText("origin_boot_id") ?: throw IOException("INVALID_DIAGNOSTIC_ACK")
            val maximum = next.sequences[boot] ?: throw IOException("INVALID_DIAGNOSTIC_ACK")
            val accounted = watermark.diagnosticLong("accounted_through")?.takeIf { it in 0..maximum } ?: throw IOException("INVALID_DIAGNOSTIC_ACK")
            next.acknowledged[boot] = maxOf(next.acknowledged[boot] ?: 0, accounted)
        }
        // Rejections are counts of observed responses, not a claim of independent defects.
        next.rejected += (receipt["rejected"] as? JsonArray)?.size ?: 0
        next.lastSync = now()
    }

    @Synchronized fun importPage(page: JsonObject) = mutate { next ->
        if (page.diagnosticLong("schema_version") != 2L || page.diagnosticText("session_id") != next.session) throw IOException("INVALID_DIAGNOSTIC_PAGE")
        val cursor = page.diagnosticLong("next_cursor") ?: throw IOException("INVALID_DIAGNOSTIC_CURSOR")
        if (cursor <= next.remoteCursor) return@mutate // A repeated/out-of-order page can never resurrect evicted rows.
        val maximum = page.diagnosticLong("last_ingest_sequence") ?: throw IOException("INVALID_DIAGNOSTIC_CURSOR")
        if (cursor > maximum) throw IOException("INVALID_DIAGNOSTIC_CURSOR")
        val rows = page["events"] as? JsonArray ?: throw IOException("INVALID_DIAGNOSTIC_PAGE")
        if (rows.size > GatewayDiagnosticEvent.MAX_BATCH_EVENTS) throw IOException("DIAGNOSTIC_BATCH_LIMIT")
        var priorCursor = next.remoteCursor
        val existing = next.rows.mapNotNull { GatewayDiagnosticEvent.parse(it)?.diagnosticText("event_id") }.toMutableSet()
        rows.forEach { raw ->
            val event = (raw as? JsonObject)?.let(GatewayDiagnosticEvent::sanitize) ?: throw IOException("INVALID_DIAGNOSTIC_EVENT")
            val receivedCursor = event.diagnosticLong("gateway_ingest_sequence") ?: throw IOException("INVALID_DIAGNOSTIC_CURSOR")
            if (receivedCursor <= next.remoteCursor) return@forEach
            if (receivedCursor <= priorCursor || receivedCursor > cursor) throw IOException("INVALID_DIAGNOSTIC_CURSOR")
            priorCursor = receivedCursor
            if (event.diagnosticText("origin_device_pseudonym") == next.origin) return@forEach
            if (existing.add(event.diagnosticText("event_id")!!)) next.rows += event.toString()
            trim(next)
        }
        val gap = page["cursor_gap"] as? JsonObject
        if (gap != null) {
            val first = gap.diagnosticLong("first") ?: throw IOException("INVALID_DIAGNOSTIC_GAP")
            val last = gap.diagnosticLong("last") ?: throw IOException("INVALID_DIAGNOSTIC_GAP")
            if (first < 1 || last < first || last > maximum) throw IOException("INVALID_DIAGNOSTIC_GAP")
            val previous = next.remoteGaps.lastOrNull()
            if (previous != null && (previous.diagnosticLong("last") ?: 0) + 1 >= first) {
                next.remoteGaps[next.remoteGaps.lastIndex] = buildJsonObject {
                    put("first", previous.diagnosticLong("first")!!)
                    put("last", maxOf(last, previous.diagnosticLong("last")!!))
                    put("reason", "EVICTED")
                }
            } else {
                if (next.remoteGaps.size >= 256) throw IOException("DIAGNOSTIC_GAP_LIMIT")
                next.remoteGaps += buildJsonObject {
                    put("first", first)
                    put("last", last)
                    put("reason", "EVICTED")
                }
            }
        }
        next.remoteCursor = cursor
        next.lastSync = now()
    }

    @Synchronized fun status(): GatewayDiagnosticStatus {
        val events = state.rows.mapNotNull(GatewayDiagnosticEvent::parse)
        val failures = events.filter { it.diagnosticText("severity") in setOf("warning", "error", "critical") }
        return GatewayDiagnosticStatus(
            captureState = if (storageError == null) "recording" else "storage_unavailable",
            retained = state.rows.size,
            pending = events.count { it.diagnosticText("origin_device_pseudonym") == state.origin && (it.diagnosticLong("origin_sequence") ?: 0) > (state.acknowledged[it.diagnosticText("origin_boot_id")] ?: 0) },
            evicted = state.evicted,
            dropped = state.queueDropped + state.gaps.sumOf { (it.diagnosticLong("last") ?: 0) - (it.diagnosticLong("first") ?: 0) + 1 },
            rejected = state.rejected,
            lastSyncAt = state.lastSync.takeIf { it > 0 },
            storageError = storageError,
            sessionId = state.session,
            remoteCursor = state.remoteCursor,
            sourceAvailability = if (events.none { it.diagnosticText("owner") == "gateway" }) {
                "not_observed"
            } else if (now() - state.lastSync > 30_000) {
                "disconnected_or_idle"
            } else {
                "observed_recently"
            },
            incidents = failures.map(::incidentKey).distinct().size,
            operationCount = events.mapNotNull { it.diagnosticText("operation_id") }.distinct().size
        )
    }

    @Synchronized fun page(offset: Int, limit: Int, failuresOnly: Boolean, traceId: String? = null, stages: Set<String> = emptySet()): GatewayDiagnosticPage {
        require(offset >= 0 && limit in 1..64)
        val matching = state.rows.asReversed().asSequence().mapNotNull(GatewayDiagnosticEvent::parse)
            .filter { !failuresOnly || it.diagnosticText("severity") in setOf("warning", "error", "critical") }
            .filter { traceId == null || it.diagnosticText("trace_id") == traceId }
            .filter { stages.isEmpty() || it.diagnosticText("stage") in stages }.toList()
        val selected = matching.drop(offset).take(limit)
        return GatewayDiagnosticPage(selected, (offset + selected.size).takeIf { it < matching.size }, matching.size)
    }

    fun export(destination: File): File {
        val snapshot = synchronized(this) { state.detached() }
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        var count = 0
        val parsed = snapshot.rows.mapNotNull(GatewayDiagnosticEvent::parse)
        destination.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry("events.jsonl"))
            parsed.forEach { event ->
                val bytes = (event.toString() + "\n").toByteArray(Charsets.UTF_8)
                digest.update(bytes)
                zip.write(bytes)
                byteCount += bytes.size
                count++
            }
            zip.closeEntry()
            val manifest = buildJsonObject {
                put("schema_version", 2)
                put("redaction_version", 1)
                put("snapshot_id", randomId())
                put("session_id", snapshot.session)
                put("capture_started_at_utc", snapshot.created)
                put("captured_at_utc", now())
                put("utc_timestamp_unit", "unix_milliseconds")
                put("event_limit", GatewayDiagnosticEvent.MAX_EVENTS)
                put("event_byte_limit", GatewayDiagnosticEvent.MAX_RECORD_BYTES)
                put("retained_count", count)
                put("evicted_count", snapshot.evicted)
                put("queue_dropped_count", snapshot.queueDropped)
                put("rejected_count", snapshot.rejected)
                put("offline_gaps", JsonArray(snapshot.gaps))
                put("remote_cursor_gaps", JsonArray(snapshot.remoteGaps))
                put("gateway_cursor", snapshot.remoteCursor)
                put("last_sync_at_utc", snapshot.lastSync.takeIf { it > 0 }?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                put("vendor_log_available", false)
                put("clock_uncertainty_ms", kotlinx.serialization.json.JsonNull)
                put("clock_ordering", "origin_sequence_and_causal_trace_only")
                put(
                    "source_versions",
                    buildJsonObject {
                        parsed.mapNotNull { it["bounded_attributes"] as? JsonObject }.forEach { attrs ->
                            listOf("app_revision", "gateway_revision", "app_version_code", "android_sdk").forEach { key -> attrs[key]?.let { put(key, it) } }
                        }
                    }
                )
                put(
                    "sources",
                    JsonArray(
                        parsed.groupBy { it.diagnosticText("origin_device_pseudonym") to it.diagnosticText("origin_boot_id") }.map { (key, events) ->
                            buildJsonObject {
                                put("origin_device_pseudonym", key.first)
                                put("origin_boot_id", key.second)
                                put("retained_count", events.size)
                                put("first_retained_sequence", events.minOf { it.diagnosticLong("origin_sequence")!! })
                                put("last_retained_sequence", events.maxOf { it.diagnosticLong("origin_sequence")!! })
                                if (key.first == snapshot.origin) put("accounted_through", snapshot.acknowledged[key.second] ?: 0)
                            }
                        }
                    )
                )
                put("missing_sources", JsonArray(listOf("android", "gateway").filter { owner -> parsed.none { it.diagnosticText("owner") == owner } }.map(::JsonPrimitive)))
                put("complete", false)
                put("coverage_note", "Bounded device mirror. The paired gateway export certifies committed combined coverage; unacknowledged events and absent vendor logs remain explicit.")
                put("capture_policy", "allowlisted_metadata_only; private_sessions_excluded; full_job_evidence_separate")
                put(
                    "files",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", "events.jsonl")
                                put("records", count)
                                put("bytes", byteCount)
                                put("sha256", digest.digest().diagnosticHex())
                            }
                        )
                    )
                )
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return destination
    }

    private fun trim(next: State) {
        while (next.rows.size > GatewayDiagnosticEvent.MAX_EVENTS) {
            val removed = GatewayDiagnosticEvent.parse(next.rows.removeAt(0))
            next.evicted++
            if (removed?.diagnosticText("origin_device_pseudonym") == next.origin) {
                val boot = removed.diagnosticText("origin_boot_id")!!
                val sequence = removed.diagnosticLong("origin_sequence")!!
                if (sequence > (next.acknowledged[boot] ?: 0)) gap(next, boot, sequence, sequence, "OFFLINE_EVICTED")
            }
        }
    }

    private fun gap(next: State, boot: String, first: Long, last: Long, reason: String) {
        val previous = next.gaps.lastOrNull()
        if (previous?.diagnosticText("origin_boot_id") == boot && previous.diagnosticText("reason") == reason && previous.diagnosticLong("last") == first - 1) {
            next.gaps[next.gaps.lastIndex] = JsonObject(previous + ("last" to JsonPrimitive(last)))
        } else {
            if (next.gaps.size >= 256) throw IOException("DIAGNOSTIC_GAP_LIMIT")
            next.gaps += buildJsonObject {
                put("origin_device_pseudonym", next.origin)
                put("origin_boot_id", boot)
                put("first", first)
                put("last", last)
                put("reason", reason)
            }
        }
    }

    private fun checkCapture(next: State) {
        if (now() - next.created > GatewayDiagnosticEvent.CAPTURE_DURATION_MILLIS || next.created > now() + 300_000) throw IOException("DIAGNOSTIC_CAPTURE_EXPIRED")
    }

    private fun mutate(change: (State) -> Unit) {
        val next = state.detached()
        try {
            change(next)
            persist(next)
            state = next
            storageError = null
        } catch (failure: IOException) {
            storageError = "DEBUG_STORAGE_UNAVAILABLE"
            throw IOException(storageError)
        }
    }

    private fun persist(next: State) {
        val header = buildJsonObject {
            put("schema_version", 2)
            put("session_id", next.session)
            put("origin", next.origin)
            put("created", next.created)
            put("sequences", JsonObject(next.sequences.mapValues { JsonPrimitive(it.value) }))
            put("acknowledged", JsonObject(next.acknowledged.mapValues { JsonPrimitive(it.value) }))
            put("gaps", JsonArray(next.gaps))
            put("remote_gaps", JsonArray(next.remoteGaps))
            put("evicted", next.evicted)
            put("queue_dropped", next.queueDropped)
            put("rejected", next.rejected)
            put("remote_cursor", next.remoteCursor)
            put("last_sync", next.lastSync)
        }.toString()
        if (header.toByteArray().size > 128 * 1024) throw IOException("DIAGNOSTIC_METADATA_LIMIT")
        write(file, header, next.rows)
    }

    private fun load(): State {
        if (file.length() > GatewayDiagnosticEvent.MAX_EVENTS * GatewayDiagnosticEvent.MAX_RECORD_BYTES.toLong() + 128 * 1024) throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
        BufferedInputStream(file.inputStream()).use { input ->
            val header = boundedDiagnosticLine(input, 128 * 1024)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
            val session = header.diagnosticText("session_id")?.takeIf(GatewayDiagnosticEvent.hex32::matches) ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
            val origin = header.diagnosticText("origin")?.takeIf(GatewayDiagnosticEvent.hex32::matches) ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
            val created = header.diagnosticLong("created") ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
            val result = State(session, origin, created)
            fun counters(name: String, target: MutableMap<String, Long>) {
                val values = header[name] as? JsonObject ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                if (values.size > 16) throw IOException("DIAGNOSTIC_SOURCE_LIMIT")
                values.forEach { (key, _) ->
                    if (!GatewayDiagnosticEvent.hex32.matches(key)) throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                    target[key] = values.diagnosticLong(key)?.takeIf { it in 0..(1L shl 53) } ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                }
            }
            counters("sequences", result.sequences)
            counters("acknowledged", result.acknowledged)
            listOf("gaps" to result.gaps, "remote_gaps" to result.remoteGaps).forEach { (name, target) ->
                val gaps = header[name] as? JsonArray ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                if (gaps.size > 256) throw IOException("DIAGNOSTIC_GAP_LIMIT")
                gaps.forEach { raw ->
                    val item = raw as? JsonObject ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                    val first = item.diagnosticLong("first") ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                    val last = item.diagnosticLong("last") ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                    val reason = item.diagnosticText("reason")?.takeIf { it in setOf("OFFLINE_EVICTED", "QUEUE_FULL", "RECORD_REJECTED", "STORAGE_UNAVAILABLE", "CAPTURE_INTERRUPTED", "EVICTED") } ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                    if (first < 1 || last < first) throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                    target += buildJsonObject {
                        put("first", first)
                        put("last", last)
                        put("reason", reason)
                        if (name == "gaps") {
                            val boot = item.diagnosticText("origin_boot_id")?.takeIf(GatewayDiagnosticEvent.hex32::matches) ?: throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                            put("origin_device_pseudonym", origin)
                            put("origin_boot_id", boot)
                        }
                    }
                }
            }
            result.evicted = header.diagnosticLong("evicted")?.coerceAtLeast(0) ?: 0
            result.queueDropped = header.diagnosticLong("queue_dropped")?.coerceAtLeast(0) ?: 0
            result.rejected = header.diagnosticLong("rejected")?.coerceAtLeast(0) ?: 0
            result.remoteCursor = header.diagnosticLong("remote_cursor")?.coerceAtLeast(0) ?: 0
            result.lastSync = header.diagnosticLong("last_sync")?.coerceAtLeast(0) ?: 0
            val identities = mutableSetOf<String>()
            while (true) {
                val line = boundedDiagnosticLine(input, GatewayDiagnosticEvent.MAX_RECORD_BYTES) ?: break
                val event = GatewayDiagnosticEvent.parse(line)
                if (event == null || !identities.add(event.diagnosticText("event_id")!!)) {
                    result.rejected++
                } else {
                    if (result.rows.size >= GatewayDiagnosticEvent.MAX_EVENTS) throw IOException("DIAGNOSTIC_SPOOL_CORRUPT")
                    result.rows += event.toString()
                }
            }
            // A damaged row never causes allocated-but-unacknowledged identity reuse or a stalled ACK.
            result.sequences.forEach { (boot, last) ->
                val present = result.rows.mapNotNull(GatewayDiagnosticEvent::parse)
                    .filter { it.diagnosticText("origin_device_pseudonym") == origin && it.diagnosticText("origin_boot_id") == boot }
                    .map { it.diagnosticLong("origin_sequence")!! to it.diagnosticLong("origin_sequence")!! }
                val covered = present + result.gaps.filter { it.diagnosticText("origin_boot_id") == boot }.map { it.diagnosticLong("first")!! to it.diagnosticLong("last")!! }
                var cursor = (result.acknowledged[boot] ?: 0) + 1
                covered.sortedBy { it.first }.forEach { (first, end) ->
                    if (first > cursor) gap(result, boot, cursor, minOf(last, first - 1), "CAPTURE_INTERRUPTED")
                    cursor = maxOf(cursor, end + 1)
                }
                if (cursor <= last) gap(result, boot, cursor, last, "CAPTURE_INTERRUPTED")
            }
            return result
        }
    }
}

private fun incidentKey(event: JsonObject): String = (event.diagnosticText("incident_id") ?: event.diagnosticText("operation_id") ?: event.diagnosticText("request_id") ?: event.diagnosticText("trace_id") ?: event.diagnosticText("event_id")).orEmpty()
private fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

private fun boundedDiagnosticLine(input: BufferedInputStream, limit: Int): String? {
    val buffer = ByteArrayOutputStream(minOf(limit, 2048))
    var oversized = false
    while (true) {
        val value = input.read()
        if (value < 0) {
            return if (buffer.size() == 0 && !oversized) {
                null
            } else if (oversized) {
                ""
            } else {
                buffer.toString("UTF-8")
            }
        }
        if (value == 10) return if (oversized) "" else buffer.toString("UTF-8")
        if (buffer.size() >= limit) {
            oversized = true
        } else if (!oversized) {
            buffer.write(value)
        }
    }
}

private fun writeDiagnosticGeneration(file: File, header: String, rows: List<String>) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write((header + "\n").toByteArray(Charsets.UTF_8))
            rows.forEach { output.write((it + "\n").toByteArray(Charsets.UTF_8)) }
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
