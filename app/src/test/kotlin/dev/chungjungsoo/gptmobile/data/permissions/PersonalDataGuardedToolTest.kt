package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolMedia
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.HealthConnectTool
import dev.chungjungsoo.gptmobile.data.health.HealthDataReader
import dev.chungjungsoo.gptmobile.data.health.HealthQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDataGuardedToolTest {
    private val instant = Instant.parse("2026-10-24T15:00:00Z")
    private val clock = Clock.fixed(instant, ZoneId.of("America/Toronto"))
    private fun grant(tool: String, categories: Set<PersonalDataCategory> = personalDataCategories(tool), days: Int? = null) = PersonalDataGrant(profileUid = "profile", destinationScope = "scope", toolId = tool, recipients = setOf("OVHcloud"), categories = categories, chatId = 1, issuedAt = clock.millis() - 1000, expiresAt = clock.millis() + 60_000, healthRangeDays = days)

    @Test fun revocationAfterPreparationPreventsDispatchAndPreservesExecutionOwnership() = runTest {
        var granted = true
        var calls = 0
        val child = object : PreparableAgentTool, OwnedAgentTool {
            override val definition = AgentToolDefinition("private_read", "Read private data", JsonObject(emptyMap()))
            override val executionOwner = AgentToolExecutionOwner.GATEWAY
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()
            override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult = {
                calls++
                AgentToolResult(callId, ToolResultContent.Text("Private value"), false)
            }
        }
        val guarded = PersonalDataGuardedTool(child) { granted }
        assertEquals(AgentToolExecutionOwner.GATEWAY, guarded.executionOwner)
        val prepared = guarded.prepareExecution("call", JsonObject(emptyMap()))
        granted = false
        assertTrue(runCatching { prepared() }.exceptionOrNull()?.message.orEmpty().contains("PERMISSION_REVOKED"))
        assertEquals(0, calls)
        granted = true
        assertEquals("Private value", (guarded.execute("next", JsonObject(emptyMap())).content as ToolResultContent.Text).text)
        assertEquals(1, calls)
    }

    @Test fun healthRangeIsValidatedBeforePreparationAndActualBoundsBecomeProvenance() = runTest {
        var prepared = 0
        var calls = 0
        var sent = JsonObject(emptyMap())
        var permission = grant("health_connect_sleep", days = 7)
        val child = object : PreparableAgentTool {
            override val definition = AgentToolDefinition("health_connect_sleep", "Sleep records", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = prepareExecution(callId, arguments).invoke()
            override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
                prepared++
                sent = arguments
                return {
                    calls++
                    AgentToolResult(callId, ToolResultContent.Text("Health data"), false)
                }
            }
        }
        val tool = PersonalDataGuardedTool(child, { true }, { permission }, clock)
        assertTrue(runCatching { tool.execute("wide", buildJsonObject { put("days", 30) }) }.exceptionOrNull()?.message.orEmpty().contains("RANGE_REQUIRED"))
        assertEquals(0, prepared)
        val action = tool.prepareExecution("scoped", buildJsonObject { put("days", 7) })
        assertFalse("days" in sent)
        assertEquals(instant, Instant.parse(sent.getValue("end_time").jsonPrimitive.content))
        val admitted = permission
        permission = permission.copy(healthRangeDays = 1)
        assertTrue(runCatching { action() }.isFailure)
        assertEquals(0, calls)
        permission = admitted
        val result = tool.execute("fresh", buildJsonObject { put("days", 7) })
        val provenance = result.personalData.single()
        assertEquals(Instant.parse(sent.getValue("start_time").jsonPrimitive.content).toEpochMilli(), provenance.rangeStart)
        assertEquals(instant.toEpochMilli(), provenance.rangeEnd)
        assertEquals(setOf(PersonalDataCategory.HEALTH), provenance.categories)
        assertEquals(1, calls)
    }

    @Test fun frozenThirtyDayQuerySurvivesTheChildsLaterClockAcrossDaylightSaving() = runTest {
        val admittedAt = Instant.parse("2026-11-20T04:30:00Z")
        val zone = ZoneId.of("America/Toronto")
        val admittedClock = Clock.fixed(admittedAt, zone)
        var observed: HealthQuery? = null
        val reader = object : HealthDataReader {
            override fun availability() = "available"
            override suspend fun grantedPermissions() = emptySet<String>()
            override suspend fun revokePermissions() = Unit
            override suspend fun read(operation: String, query: HealthQuery): JsonObject {
                observed = query
                return buildJsonObject { put("status", "available") }
            }
        }
        val permission = grant("health_connect_sleep", days = 30).copy(issuedAt = admittedAt.toEpochMilli() - 1000, expiresAt = admittedAt.toEpochMilli() + 60_000)
        val child = HealthConnectTool("sleep", reader, { true }, { true }, Clock.offset(admittedClock, java.time.Duration.ofSeconds(1)))
        val result = PersonalDataGuardedTool(child, { true }, { permission }, admittedClock).execute("thirty", buildJsonObject { put("days", 30) })
        assertFalse(result.isError)
        assertEquals(admittedAt, observed?.requestedAt)
        assertEquals(admittedAt.minus(java.time.Duration.ofDays(30)), observed?.start)
        assertEquals(observed?.start?.toEpochMilli(), result.personalData.single().rangeStart)
    }

    @Test fun coarseLocationDoesNotReleasePreciseValuesInAnErrorOrRetainedTrace() = runTest {
        val child = object : AgentTool {
            override val definition = AgentToolDefinition("device_location", "Location", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                val raw = ToolResultContent.Text("Location 43.65321,-79.38318 failed")
                return AgentToolResult(callId, raw, true, retainedContent = raw, traceContent = raw, assistMetadata = listOf(buildJsonObject { put("precise", "43.65321") }))
            }
        }
        val permission = grant("device_location", setOf(PersonalDataCategory.COARSE_LOCATION))
        val result = PersonalDataGuardedTool(child, { true }, { permission }, clock).execute("failed", JsonObject(emptyMap()))
        assertTrue(result.isError)
        assertFalse(result.toString().contains("43.65321"))
        assertEquals(result.content, result.retainedContent)
        assertEquals(result.content, result.traceContent)
        assertEquals(setOf(PersonalDataCategory.COARSE_LOCATION), result.personalData.single().categories)
    }

    @Test fun coarseLocationRemovesEveryPreciseRepresentationAndNearbyNeedsItsOwnRecipient() = runTest {
        var calls = 0
        val precise = ToolResultContent.Json(
            buildJsonObject {
                put("latitude", 43.65321)
                put("longitude", -79.38318)
                put("altitude_meters", 123.456)
                put("timestamp", clock.millis())
            }
        )
        val child = object : AgentTool {
            override val definition = AgentToolDefinition("device_location", "Location", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls++
                return AgentToolResult(callId, precise, false, retainedContent = precise, assistMetadata = listOf(buildJsonObject { put("precise", "secret") }), media = listOf(AgentToolMedia("raw-location", "text/plain", 10, "hash")))
            }
        }
        var permission = grant("device_location", setOf(PersonalDataCategory.COARSE_LOCATION))
        val tool = PersonalDataGuardedTool(child, { true }, { permission }, clock)
        assertTrue(runCatching { tool.execute("nearby", buildJsonObject { put("nearby", "pharmacy") }) }.exceptionOrNull()?.message.orEmpty().contains("MAP_PERMISSION_REQUIRED"))
        assertEquals(0, calls)
        val result = tool.execute("coarse", JsonObject(emptyMap()))
        val value = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(43.7, value.getValue("latitude").jsonPrimitive.double, 0.0001)
        assertEquals(-79.4, value.getValue("longitude").jsonPrimitive.double, 0.0001)
        assertFalse("altitude_meters" in value)
        assertEquals(result.content, result.retainedContent)
        assertTrue(result.media.isEmpty() && result.assistMetadata.isEmpty())
        assertEquals(setOf(PersonalDataCategory.COARSE_LOCATION), result.personalData.single().categories)
        permission = grant("device_location")
        assertTrue(runCatching { tool.execute("map-without-recipient", buildJsonObject { put("nearby", "pharmacy") }) }.isFailure)
        permission = permission.copy(downstreamRecipients = setOf(LOCATION_MAP_RECIPIENT))
        assertFalse(tool.execute("explicit-map", buildJsonObject { put("nearby", "pharmacy") }).isError)
        assertEquals(2, calls)
    }
}
