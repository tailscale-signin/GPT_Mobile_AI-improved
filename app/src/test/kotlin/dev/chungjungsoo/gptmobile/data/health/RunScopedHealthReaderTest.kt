package dev.chungjungsoo.gptmobile.data.health

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class RunScopedHealthReaderTest {
    private class Reader : HealthDataReader {
        var calls = 0
        var allowed = HealthMetric.entries.map { it.permission }.toSet()
        var foreground = true
        override fun availability() = "available"
        override suspend fun grantedPermissions() = allowed
        override suspend fun foregroundReadable() = foreground
        override suspend fun revokePermissions() {
            allowed = emptySet()
        }
        override suspend fun read(operation: String, query: HealthQuery): JsonObject {
            calls++
            return buildJsonObject {
                put("status", if (foreground) "available" else "foreground_required")
                put("queried_at", "original")
                put(
                    "metrics",
                    JsonObject(
                        HealthConnectTools.operations.getValue(operation).associate { metric ->
                            metric.id to buildJsonObject { put("status", if (foreground && metric.permission in allowed) "available" else "permission_required") }
                        }
                    )
                )
            }
        }
    }
    private val end = Instant.parse("2026-10-11T18:00:00Z")
    private val query = HealthQuery(end.minusSeconds(3600), end, ZoneOffset.UTC)

    @Test fun summaryCanServeExactCategoryRead() = runTest {
        val delegate = Reader()
        val reader = RunScopedHealthReader(delegate)
        reader.read("summary", query)
        val result = reader.read("sleep", query)
        assertEquals(1, delegate.calls)
        assertEquals(setOf("sleep"), (result["metrics"] as JsonObject).keys)
        assertEquals(JsonPrimitive("original"), result["queried_at"])
        assertEquals(JsonPrimitive(true), result["snapshot_reused"])
    }

    @Test fun permissionChangeAndBackgroundInvalidateReuse() = runTest {
        val delegate = Reader()
        val reader = RunScopedHealthReader(delegate)
        reader.read("summary", query)
        delegate.allowed = emptySet()
        reader.read("sleep", query)
        assertEquals(2, delegate.calls)
        delegate.foreground = false
        reader.read("sleep", query)
        assertEquals(3, delegate.calls)
    }

    @Test fun expiryAndDifferentWindowsNeverReuse() = runTest {
        var now = 0L
        val delegate = Reader()
        val reader = RunScopedHealthReader(delegate) { now }
        reader.read("summary", query)
        reader.read("sleep", query.copy(end = end.minusSeconds(1)))
        assertEquals(2, delegate.calls)
        now = 10_001
        reader.read("sleep", query)
        assertEquals(3, delegate.calls)
    }

    @Test fun profilesDoNotShareSnapshots() = runTest {
        val delegate = Reader()
        RunScopedHealthReader(delegate).read("summary", query)
        RunScopedHealthReader(delegate).read("sleep", query)
        assertEquals(2, delegate.calls)
    }
}
