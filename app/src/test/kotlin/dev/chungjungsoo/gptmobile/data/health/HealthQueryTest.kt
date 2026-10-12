package dev.chungjungsoo.gptmobile.data.health

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthQueryTest {
    @Test
    fun thirtyCalendarDaysAcrossFallBackAreClippedToTheHistoryLimit() {
        val now = Instant.parse("2026-11-20T04:30:00Z")
        val query = HealthQuery.parse(buildJsonObject { put("days", 30) }, now, ZoneId.of("America/Toronto"))
        assertEquals(now.minus(java.time.Duration.ofDays(30)), query.start)
        assertEquals(now, query.end)
    }

    private val now = Instant.parse("2026-10-11T19:00:00Z")
    private val zone = ZoneId.of("America/Toronto")

    @Test fun queryKeepsTheValidationInstantForDelayedThirtyDayReads() {
        val query = HealthQuery.parse(buildJsonObject { put("days", 30) }, now, zone)
        assertEquals(now, query.requestedAt)
        assertTrue(query.start >= query.requestedAt.minus(java.time.Duration.ofDays(30)))
    }

    @Test fun defaultsUsePhoneCalendarDayNotUtcDay() {
        val query = HealthQuery.parse(buildJsonObject {}, now, zone)
        assertEquals(Instant.parse("2026-10-11T04:00:00Z"), query.start)
        assertEquals(now, query.end)
        assertEquals(50, query.limit)
    }

    @Test fun springDstBoundaryUsesCalendarDays() {
        val query = HealthQuery.parse(buildJsonObject { put("days", 2) }, Instant.parse("2026-03-09T12:00:00Z"), zone)
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), query.start)
    }

    @Test fun fallDstBoundaryUsesCalendarDays() {
        val query = HealthQuery.parse(buildJsonObject { put("days", 2) }, Instant.parse("2026-11-02T12:00:00Z"), zone)
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), query.start)
    }

    @Test fun midnightDefaultHasAValidNonFutureWindow() {
        val midnight = Instant.parse("2026-10-11T04:00:00Z")
        val query = HealthQuery.parse(buildJsonObject {}, midnight, zone)
        assertTrue(query.start < query.end)
        assertEquals(midnight, query.end)
    }

    @Test fun explicitOffsetsAndSourceArePreserved() {
        val query = parse("""{"start_time":"2026-10-10T09:00:00-04:00","end_time":"2026-10-11T09:00:00-04:00","source_package":"com.vendor.health","limit":100}""")
        assertEquals(Instant.parse("2026-10-10T13:00:00Z"), query.start)
        assertEquals(100, query.limit)
        assertEquals("com.vendor.health", query.sourcePackage)
    }

    @Test fun rejectsOversizedMalformedAmbiguousAndFutureRequests() {
        listOf(
            """{"days":0}""", """{"days":31}""", """{"days":"7"}""", """{"days":1.5}""",
            """{"limit":0}""", """{"limit":101}""", """{"limit":null}""", """{"write":true}""",
            """{"source_package":"https://example.org"}""", """{"source_package":"../file"}""",
            """{"start_time":"2026-10-10T00:00:00Z"}""",
            """{"start_time":"2026-10-10T00:00:00","end_time":"2026-10-11T00:00:00Z"}""",
            """{"start_time":"2026-09-01T00:00:00Z","end_time":"2026-10-11T00:00:00Z"}""",
            """{"start_time":"2026-10-10T00:00:00Z","end_time":"2026-10-12T00:00:00Z"}""",
            """{"start_time":"2026-10-11T00:00:00Z","end_time":"2026-10-10T00:00:00Z"}""",
            """{"days":7,"start_time":"2026-10-10T00:00:00Z","end_time":"2026-10-11T00:00:00Z"}"""
        ).forEach { input -> assertTrue(input, runCatching { parse(input) }.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test fun newAndRestoredSettingsRequireIndependentGlobalAndProfileOptIn() {
        val initial = AppFeatureSettings()
        val id = ToolPluginId.HEALTH_CONNECT
        assertFalse(initial.isToolPluginEnabled(id))
        assertFalse(initial.isToolPluginEnabledForProfile("p", id))
        val global = initial.withToolPluginEnabled(id, true)
        assertFalse(global.isToolPluginEnabledForProfile("p", id))
        val granted = global.withProfileToolPluginEnabled("p", id, true)
        assertTrue(granted.isToolPluginEnabledForProfile("p", id))
        assertFalse(granted.isToolPluginEnabledForProfile("other", id))
        assertFalse(granted.withToolPluginEnabled(id, false).isToolPluginEnabledForProfile("p", id))
    }

    @Test fun nineUniqueReadOnlyOperationsUseOnlyThirteenDeclaredReadPermissions() {
        assertEquals(9, HealthConnectTools.operations.size)
        assertEquals(13, HealthMetric.entries.size)
        assertTrue(HealthMetric.entries.all { it.permission.startsWith("android.permission.health.READ_") })
        assertTrue(HealthConnectTools.isHealthTool("client__health_connect_sleep"))
        assertFalse(HealthConnectTools.isHealthTool("web_search"))
    }

    private fun parse(input: String) = HealthQuery.parse(Json.parseToJsonElement(input).jsonObject, now, zone)
}
