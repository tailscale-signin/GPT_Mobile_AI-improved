package dev.chungjungsoo.gptmobile.data.health

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Memory-only reuse for one resolver/run/profile. Never shared through the global tool broker. */
class RunScopedHealthReader(
    private val delegate: HealthDataReader,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) : HealthDataReader by delegate {
    private data class Key(val start: String, val end: String, val zone: String, val source: String?, val limit: Int)
    private data class Snapshot(val key: Key, val permissions: Set<String>, val time: Long, val value: JsonObject, val bytes: Int)
    private val mutex = Mutex()
    private val snapshots = ArrayDeque<Snapshot>()

    suspend fun invalidate() = mutex.withLock { snapshots.clear() }

    override suspend fun read(operation: String, query: HealthQuery): JsonObject = mutex.withLock {
        if (operation == "status") return@withLock delegate.read(operation, query)
        val permissions = delegate.grantedPermissions()
        if (!delegate.foregroundReadable()) {
            snapshots.clear()
            return@withLock delegate.read(operation, query)
        }
        val now = nowMillis()
        snapshots.removeAll { now - it.time !in 0..10_000 || it.permissions != permissions }
        val key = Key(query.start.toString(), query.end.toString(), query.zone.id, query.sourcePackage, query.limit)
        val metrics = HealthConnectTools.operations.getValue(operation)
        val cached = snapshots.lastOrNull { snapshot ->
            val values = snapshot.value["metrics"] as? JsonObject
            snapshot.key == key && values != null && metrics.all { it.id in values && it.permission in permissions }
        }
        if (cached != null && delegate.foregroundReadable() && delegate.grantedPermissions() == permissions) {
            val selected = JsonObject((cached.value.getValue("metrics") as JsonObject).filterKeys { key -> metrics.any { it.id == key } })
            val statuses = selected.values.map { (it as? JsonObject)?.get("status") as? JsonPrimitive }.map { it?.content }
            val status = when {
                statuses.all { it == "available" } -> "available"
                statuses.all { it == "no_data" } -> "no_data"
                else -> "partial"
            }
            // Preserve the original queried_at and freshness. Reuse is not a new watch sync.
            return@withLock JsonObject(
                cached.value + mapOf(
                    "operation" to JsonPrimitive(operation),
                    "status" to JsonPrimitive(status),
                    "metrics" to selected,
                    "snapshot_reused" to JsonPrimitive(true)
                )
            )
        }
        val value = delegate.read(operation, query)
        val values = value["metrics"] as? JsonObject
        val cacheable = values != null &&
            values.isNotEmpty() &&
            values.values.all {
                ((it as? JsonObject)?.get("status") as? JsonPrimitive)?.content in setOf("available", "no_data")
            }
        val bytes = if (cacheable) value.toString().length.toLong() * 2 else Long.MAX_VALUE
        if (cacheable && bytes <= 262_144 && delegate.foregroundReadable() && delegate.grantedPermissions() == permissions) {
            snapshots.addLast(Snapshot(key, permissions, nowMillis(), value, bytes.toInt()))
            while (snapshots.size > 8 || snapshots.sumOf { it.bytes } > 524_288) snapshots.removeFirst()
        } else {
            snapshots.clear()
        }
        value
    }

    override suspend fun revokePermissions() {
        invalidate()
        delegate.revokePermissions()
    }
}
