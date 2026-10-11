package dev.chungjungsoo.gptmobile.data.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.units.Mass
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHealthConnectReaderTest {
    private val client = mockk<HealthConnectClient>()
    private val permissions = mockk<PermissionController>()
    private val now = Instant.parse("2026-10-11T19:00:00Z")
    private val query = HealthQuery(now.minusSeconds(3600), now, ZoneId.of("UTC"))

    init { every { client.permissionController } returns permissions }

    @Test fun deniedCategoriesNeverReachTheProvider() = runBlocking {
        coEvery { permissions.getGrantedPermissions() } returns emptySet()
        val result = AndroidHealthConnectReader(client).read("summary", query)
        assertEquals(JsonPrimitive("permission_required"), result["status"])
        assertTrue(result.getValue("metrics").jsonObject.values.all { it.jsonObject["value"] == JsonNull })
        coVerify(exactly = 0) { client.aggregate(any()) }
    }

    @Test fun activityUsesHealthConnectAggregationWithSourceFilterRatherThanSummingRawSteps() = runBlocking {
        coEvery { permissions.getGrantedPermissions() } returns setOf(HealthMetric.STEPS.permission)
        val aggregate = mockk<AggregationResult>()
        every { aggregate[StepsRecord.COUNT_TOTAL] } returns 1234L
        every { aggregate.dataOrigins } returns setOf(DataOrigin("com.example.health"))
        val request = slot<AggregateRequest>()
        coEvery { client.aggregate(capture(request)) } returns aggregate
        val result = AndroidHealthConnectReader(client).read("activity", query.copy(sourcePackage = "com.example.health"))
        assertEquals(setOf(StepsRecord.COUNT_TOTAL), request.captured.metrics)
        assertEquals(setOf(DataOrigin("com.example.health")), request.captured.dataOriginFilter)
        assertEquals(JsonPrimitive(1234L), result.getValue("metrics").jsonObject.getValue("steps").jsonObject.getValue("values").jsonObject["total"])
        assertEquals(JsonPrimitive("partial"), result["status"])
        assertEquals(JsonNull, result["last_source_sync_at"])
        coVerify(exactly = 1) { client.aggregate(any()) }
    }

    @Test fun absenceIsNullNotZero() = runBlocking {
        coEvery { permissions.getGrantedPermissions() } returns setOf(HealthMetric.STEPS.permission)
        val aggregate = mockk<AggregationResult>()
        every { aggregate[StepsRecord.COUNT_TOTAL] } returns null
        every { aggregate.dataOrigins } returns emptySet()
        coEvery { client.aggregate(any()) } returns aggregate
        val metric = AndroidHealthConnectReader(client).read("activity", query).getValue("metrics").jsonObject.getValue("steps").jsonObject
        assertEquals(JsonPrimitive("no_data"), metric["status"])
        assertEquals(JsonNull, metric.getValue("values").jsonObject["total"])
    }

    @Test fun revocationAfterReadingRemovesEarlierMeasurements() = runBlocking {
        coEvery { permissions.getGrantedPermissions() } returnsMany listOf(setOf(HealthMetric.STEPS.permission), emptySet())
        val aggregate = mockk<AggregationResult>()
        every { aggregate[StepsRecord.COUNT_TOTAL] } returns 987654L
        every { aggregate.dataOrigins } returns setOf(DataOrigin("com.example.health"))
        coEvery { client.aggregate(any()) } returns aggregate
        val result = AndroidHealthConnectReader(client).read("activity", query)
        assertEquals(JsonPrimitive("permission_required"), result["status"])
        assertFalse(result.toString().contains("987654"))
    }

    @Test fun recentRecordLimitsAreHonestAndBounded() = runBlocking {
        coEvery { permissions.getGrantedPermissions() } returns setOf(HealthMetric.WEIGHT.permission)
        fun weight(seconds: Long, kilograms: Double): WeightRecord = mockk {
            every { time } returns now.minusSeconds(seconds)
            every { weight } returns Mass.kilograms(kilograms)
            every { metadata } returns mockk { every { dataOrigin } returns DataOrigin("com.example.health") }
        }
        val response = mockk<ReadRecordsResponse<WeightRecord>>()
        every { response.records } returns listOf(weight(10, 70.0), weight(20, 71.0), weight(30, 72.0))
        every { response.pageToken } returns "more"
        val request = slot<ReadRecordsRequest<WeightRecord>>()
        coEvery { client.readRecords(capture(request)) } returns response
        val metric = AndroidHealthConnectReader(client).read("body", query.copy(limit = 2)).getValue("metrics").jsonObject.getValue("weight").jsonObject
        assertEquals(3, request.captured.pageSize)
        assertEquals(JsonPrimitive(false), metric["records_complete"])
        assertEquals(JsonPrimitive(2), metric["records_examined"])
        assertEquals(JsonPrimitive(70.0), metric.getValue("values").jsonObject["latest"])
        coVerify(exactly = 1) { client.readRecords(any<ReadRecordsRequest<WeightRecord>>()) }
    }

    @Test fun statusReadsPermissionsNotHealthMeasurements() = runBlocking {
        coEvery { permissions.getGrantedPermissions() } returns setOf(HealthMetric.SLEEP.permission)
        val result = AndroidHealthConnectReader(client).read("status", query)
        assertEquals(JsonPrimitive(false), result["live_sensor_stream"])
        assertEquals(JsonPrimitive(false), result["background_reads"])
        coVerify(exactly = 0) { client.aggregate(any()) }
    }

    @Test fun cancellationPropagatesAndProviderErrorsAreSanitized() = runBlocking {
        coEvery { permissions.getGrantedPermissions() } returns setOf(HealthMetric.STEPS.permission)
        coEvery { client.aggregate(any()) } throws IllegalStateException("private record 987654")
        val result = AndroidHealthConnectReader(client).read("activity", query)
        assertFalse(result.toString().contains("987654"))
        coEvery { client.aggregate(any()) } throws CancellationException()
        assertTrue(runCatching { AndroidHealthConnectReader(client).read("activity", query) }.exceptionOrNull() is CancellationException)
    }
}
