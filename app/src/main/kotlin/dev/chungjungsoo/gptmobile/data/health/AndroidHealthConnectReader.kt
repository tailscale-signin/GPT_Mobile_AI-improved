package dev.chungjungsoo.gptmobile.data.health

import android.app.KeyguardManager
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** No network client, background worker, database, file cache or diagnostic payload logging. */
@Singleton
class AndroidHealthConnectReader private constructor(
    private val sdkStatus: () -> Int,
    private val clientFactory: () -> HealthConnectClient,
    private val foregroundAllowed: suspend () -> Boolean
) : HealthDataReader {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { HealthConnectClient.getSdkStatus(context) },
        { HealthConnectClient.getOrCreate(context) },
        {
            withContext(Dispatchers.Main.immediate) {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
            }
        }
    )

    internal constructor(client: HealthConnectClient) : this({ HealthConnectClient.SDK_AVAILABLE }, { client }, { true })

    override fun availability(): String = when (sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "install_or_update_required"
        else -> "unavailable"
    }

    override suspend fun grantedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        if (availability() == "available") clientFactory().permissionController.getGrantedPermissions() else emptySet()
    }

    override suspend fun revokePermissions() = withContext(Dispatchers.IO) {
        if (availability() == "available") clientFactory().permissionController.revokeAllPermissions()
    }

    override suspend fun read(operation: String, query: HealthQuery): JsonObject = withContext(Dispatchers.IO) {
        withTimeout(20_000L) {
            require(operation in HealthConnectTools.operations) { "Unsupported health operation." }
            val available = availability()
            if (available != "available") {
                return@withTimeout buildJsonObject {
                    put("schema", HealthConnectTools.SCHEMA)
                    put("status", available)
                    put("action", "Open Smartwatch Health Connect settings to install or update Health Connect.")
                }
            }
            val permissions = grantedPermissions()
            if (operation == "status") {
                return@withTimeout buildJsonObject {
                    put("schema", HealthConnectTools.SCHEMA)
                    put("status", available)
                    put("read_only", true)
                    put("live_sensor_stream", false)
                    put("background_reads", false)
                    put("maximum_lookback_days", 30)
                    put(
                        "permissions",
                        buildJsonObject {
                            HealthMetric.entries.forEach { put(it.id, if (it.permission in permissions) "granted" else "not_granted") }
                        }
                    )
                    put("note", "Supported record types do not prove that a watch provides them. Sync the watch companion app to Health Connect.")
                }
            }
            if (!foregroundAllowed()) return@withTimeout foregroundRequired()
            val requested = HealthConnectTools.operations.getValue(operation)
            val results = requested.associate { metric ->
                val result = if (metric.permission !in permissions) {
                    state(metric, "permission_required")
                } else {
                    try {
                        readMetric(metric, query)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SecurityException) {
                        state(metric, "permission_or_foreground_access_required")
                    } catch (_: Exception) {
                        // Never put provider exception text (which may contain records) into a tool result.
                        state(metric, "temporarily_unavailable")
                    }
                }
                metric.id to result
            }.toMutableMap()
            // A revocation while another category was loading must not release earlier records.
            if (!foregroundAllowed()) return@withTimeout foregroundRequired()
            val stillGranted = grantedPermissions()
            requested.filter { it.permission !in stillGranted }.forEach { results[it.id] = state(it, "permission_required") }
            val statuses = results.values.map { (it["status"] as? JsonPrimitive)?.content }
            buildJsonObject {
                put("schema", HealthConnectTools.SCHEMA)
                put(
                    "status",
                    when {
                        statuses.all { it == "available" } -> "available"
                        statuses.all { it == "no_data" } -> "no_data"
                        statuses.all { it == "permission_required" } -> "permission_required"
                        statuses.none { it == "available" || it == "no_data" } -> "access_or_provider_error"
                        else -> "partial"
                    }
                )
                put("operation", operation)
                put("window_start", query.start.toString())
                put("window_end_exclusive", query.end.toString())
                put("time_zone", query.zone.id)
                put("queried_at", Instant.now().toString())
                put("last_source_sync_at", JsonNull)
                put("freshness", "Synced history, not live readings. Query time is not watch sync time.")
                put("interpretation", "Wellness information only, not a diagnosis. Null means unavailable, not zero. Activity and sleep totals use Health Connect aggregation; other types may include multiple sources. Narrow source_package to compare one source.")
                put("metrics", JsonObject(results))
            }
        }
    }

    private fun foregroundRequired() = buildJsonObject {
        put("schema", HealthConnectTools.SCHEMA)
        put("status", "foreground_required")
        put("action", "Keep GPT Mobile open and unlock your phone to read health data.")
    }

    private fun state(metric: HealthMetric, status: String) = buildJsonObject {
        put("label", metric.label)
        put("unit", metric.unit)
        put("status", status)
        put("value", JsonNull)
    }

    private fun origins(query: HealthQuery): Set<DataOrigin> = query.sourcePackage?.let { setOf(DataOrigin(it)) } ?: emptySet()
    private fun range(query: HealthQuery) = TimeRangeFilter.between(query.start, query.end)
    private fun JsonObjectBuilder.number(name: String, value: Number?) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)

    private suspend fun aggregate(
        metric: HealthMetric,
        query: HealthQuery,
        types: Set<AggregateMetric<*>>,
        values: JsonObjectBuilder.(AggregationResult) -> Unit
    ): JsonObject {
        val result = clientFactory().aggregate(AggregateRequest(metrics = types, timeRangeFilter = range(query), dataOriginFilter = origins(query)))
        val payload = buildJsonObject { values(result) }
        return buildJsonObject {
            put("label", metric.label)
            put("unit", metric.unit)
            put("status", if (payload.values.all { it == JsonNull }) "no_data" else "available")
            put("values", payload)
            put("method", "health_connect_aggregate")
            put("source_packages", JsonArray(result.dataOrigins.map { JsonPrimitive(it.packageName) }.sortedBy { it.content }))
        }
    }

    private suspend fun readMetric(metric: HealthMetric, q: HealthQuery): JsonObject = when (metric) {
        HealthMetric.STEPS -> aggregate(metric, q, setOf(StepsRecord.COUNT_TOTAL)) { number("total", it[StepsRecord.COUNT_TOTAL]) }
        HealthMetric.DISTANCE -> aggregate(metric, q, setOf(DistanceRecord.DISTANCE_TOTAL)) { number("total", it[DistanceRecord.DISTANCE_TOTAL]?.inMeters) }
        HealthMetric.ACTIVE_CALORIES -> aggregate(metric, q, setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)) { number("total", it[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories) }
        HealthMetric.TOTAL_CALORIES -> aggregate(metric, q, setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL)) { number("total", it[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories) }
        HealthMetric.HEART_RATE -> aggregate(metric, q, setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT)) {
            number("average", it[HeartRateRecord.BPM_AVG])
            number("minimum", it[HeartRateRecord.BPM_MIN])
            number("maximum", it[HeartRateRecord.BPM_MAX])
            number("measurements", it[HeartRateRecord.MEASUREMENTS_COUNT])
        }
        HealthMetric.RESTING_HEART_RATE -> aggregate(metric, q, setOf(RestingHeartRateRecord.BPM_AVG, RestingHeartRateRecord.BPM_MIN, RestingHeartRateRecord.BPM_MAX)) {
            number("average", it[RestingHeartRateRecord.BPM_AVG])
            number("minimum", it[RestingHeartRateRecord.BPM_MIN])
            number("maximum", it[RestingHeartRateRecord.BPM_MAX])
        }
        HealthMetric.HYDRATION -> aggregate(metric, q, setOf(HydrationRecord.VOLUME_TOTAL)) { number("total", it[HydrationRecord.VOLUME_TOTAL]?.inLiters) }
        HealthMetric.HRV -> samples(metric, q, HeartRateVariabilityRmssdRecord::class, { it.time }, { it.heartRateVariabilityMillis })
        HealthMetric.OXYGEN -> samples(metric, q, OxygenSaturationRecord::class, { it.time }, { it.percentage.value })
        HealthMetric.RESPIRATORY_RATE -> samples(metric, q, RespiratoryRateRecord::class, { it.time }, { it.rate })
        HealthMetric.WEIGHT -> samples(metric, q, WeightRecord::class, { it.time }, { it.weight.inKilograms })
        HealthMetric.SLEEP -> {
            val total = aggregate(metric, q, setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL)) { number("total", it[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMillis()?.div(60_000.0)) }
            val page = readBounded(SleepSessionRecord::class, q)
            JsonObject(
                total + mapOf(
                    "sessions" to JsonArray(
                        page.records.map { record ->
                            buildJsonObject {
                                put("start", record.startTime.toString())
                                put("end", record.endTime.toString())
                                put("session_span_minutes", Duration.between(record.startTime, record.endTime).toMillis() / 60_000.0)
                                put("source_package", record.metadata.dataOrigin.packageName)
                                put("note", "Session span is time in bed, not necessarily time asleep. Totals above are independently aggregated.")
                            }
                        }
                    ),
                    "sessions_complete" to JsonPrimitive(page.complete)
                )
            )
        }
        HealthMetric.WORKOUTS -> {
            val total = aggregate(metric, q, setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)) { number("total", it[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMillis()?.div(60_000.0)) }
            val page = readBounded(ExerciseSessionRecord::class, q)
            JsonObject(
                total + mapOf(
                    "sessions" to JsonArray(
                        page.records.map { record ->
                            buildJsonObject {
                                put("start", record.startTime.toString())
                                put("end", record.endTime.toString())
                                put("exercise_type_code", record.exerciseType)
                                put("session_span_minutes", Duration.between(record.startTime, record.endTime).toMillis() / 60_000.0)
                                put("source_package", record.metadata.dataOrigin.packageName)
                                put("routes_included", false)
                            }
                        }
                    ),
                    "sessions_complete" to JsonPrimitive(page.complete)
                )
            )
        }
    }

    private data class Page<T>(val records: List<T>, val complete: Boolean)

    private suspend fun <T : Record> readBounded(type: KClass<T>, q: HealthQuery): Page<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        val seenTokens = mutableSetOf<String>()
        var pages = 0
        do {
            val response = clientFactory().readRecords(
                ReadRecordsRequest(type, range(q), dataOriginFilter = origins(q), ascendingOrder = false, pageSize = q.limit + 1, pageToken = token)
            )
            records += response.records
            token = response.pageToken
            pages++
            if (token != null && !seenTokens.add(token)) return Page(records.take(q.limit), false)
        } while (token != null && records.size <= q.limit && pages < 4)
        return Page(records.take(q.limit), token == null && records.size <= q.limit)
    }

    private suspend fun <T : Record> samples(metric: HealthMetric, q: HealthQuery, type: KClass<T>, time: (T) -> Instant, value: (T) -> Double): JsonObject {
        val page = readBounded(type, q)
        val records = page.records.filter { time(it) >= q.start && time(it) < q.end && value(it).isFinite() }
        val values = records.map(value)
        val latest = records.maxByOrNull(time)
        return buildJsonObject {
            put("label", metric.label)
            put("unit", metric.unit)
            put("status", if (records.isEmpty()) "no_data" else "available")
            put(
                "values",
                buildJsonObject {
                    number("latest", latest?.let(value))
                    number("sample_average", values.takeIf { it.isNotEmpty() }?.average())
                    number("sample_minimum", values.minOrNull())
                    number("sample_maximum", values.maxOrNull())
                }
            )
            put("latest_measurement_at", latest?.let { JsonPrimitive(time(it).toString()) } ?: JsonNull)
            put("records_examined", records.size)
            put("records_complete", page.complete)
            put("method", "bounded_recent_records; sample statistics are not full-period population aggregates when incomplete")
            put("source_packages", JsonArray(records.map { it.metadata.dataOrigin.packageName }.distinct().sorted().map(::JsonPrimitive)))
        }
    }
}
