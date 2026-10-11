package dev.chungjungsoo.gptmobile.data.health

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Only wellness records: never medical records, routes, reproductive data or writes. */
enum class HealthMetric(val label: String, val unit: String, val permissionSuffix: String) {
    STEPS("Steps", "steps", "STEPS"),
    DISTANCE("Distance", "m", "DISTANCE"),
    ACTIVE_CALORIES("Active calories", "kcal", "ACTIVE_CALORIES_BURNED"),
    TOTAL_CALORIES("Total calories", "kcal", "TOTAL_CALORIES_BURNED"),
    HEART_RATE("Heart rate", "bpm", "HEART_RATE"),
    RESTING_HEART_RATE("Resting heart rate", "bpm", "RESTING_HEART_RATE"),
    HRV("Heart rate variability (RMSSD)", "ms", "HEART_RATE_VARIABILITY"),
    SLEEP("Sleep", "min", "SLEEP"),
    WORKOUTS("Workouts", "min", "EXERCISE"),
    OXYGEN("Oxygen saturation", "%", "OXYGEN_SATURATION"),
    RESPIRATORY_RATE("Respiratory rate", "breaths/min", "RESPIRATORY_RATE"),
    WEIGHT("Weight", "kg", "WEIGHT"),
    HYDRATION("Hydration", "L", "HYDRATION");

    val permission: String get() = "android.permission.health.READ_$permissionSuffix"
    val id: String get() = name.lowercase(java.util.Locale.ROOT)
}

object HealthConnectTools {
    const val PREFIX = "health_connect_"
    const val SCHEMA = "gptmobile.health.v1"
    const val REDACTED = "Health Connect result omitted from stored tool traces. Request fresh data with current consent."
    val operations = linkedMapOf(
        "status" to emptySet(),
        "summary" to HealthMetric.entries.toSet(),
        "activity" to setOf(HealthMetric.STEPS, HealthMetric.DISTANCE, HealthMetric.ACTIVE_CALORIES, HealthMetric.TOTAL_CALORIES),
        "heart_rate" to setOf(HealthMetric.HEART_RATE, HealthMetric.RESTING_HEART_RATE, HealthMetric.HRV),
        "sleep" to setOf(HealthMetric.SLEEP),
        "workouts" to setOf(HealthMetric.WORKOUTS),
        "vitals" to setOf(HealthMetric.OXYGEN, HealthMetric.RESPIRATORY_RATE),
        "body" to setOf(HealthMetric.WEIGHT),
        "hydration" to setOf(HealthMetric.HYDRATION)
    )
    fun isHealthTool(name: String): Boolean = name.substringAfterLast("__").startsWith(PREFIX)
}

data class HealthQuery(
    val start: Instant,
    val end: Instant,
    val zone: ZoneId,
    val sourcePackage: String? = null,
    val limit: Int = 50
) {
    companion object {
        private val allowed = setOf("days", "start_time", "end_time", "source_package", "limit")

        fun parse(arguments: JsonObject, now: Instant, zone: ZoneId): HealthQuery {
            require(arguments.keys.all { it in allowed }) { "Unknown argument. Use days or start_time/end_time, source_package and limit." }
            fun integer(key: String, default: Int): Int {
                val value = arguments[key] ?: return default
                val primitive = value as? JsonPrimitive
                require(primitive != null && !primitive.isString && primitive.intOrNull != null) { "$key must be an integer." }
                return requireNotNull(primitive.intOrNull)
            }
            fun text(key: String): String? {
                val value = arguments[key] ?: return null
                require(value is JsonPrimitive && value.isString) { "$key must be a string." }
                return value.content
            }
            val days = integer("days", 1)
            val limit = integer("limit", 50)
            require(days in 1..30) { "days must be between 1 and 30." }
            require(limit in 1..100) { "limit must be between 1 and 100." }
            val startText = text("start_time")
            val endText = text("end_time")
            require((startText == null) == (endText == null)) { "Provide both start_time and end_time." }
            require(startText == null || "days" !in arguments) { "Use days OR explicit timestamps, not both." }
            fun timestamp(value: String): Instant = try {
                java.time.OffsetDateTime.parse(value).toInstant()
            } catch (_: java.time.DateTimeException) {
                throw IllegalArgumentException("Timestamps must be ISO-8601 with Z or an explicit UTC offset.")
            }
            val end = endText?.let(::timestamp) ?: now
            val start = startText?.let(::timestamp)
                ?: now.atZone(zone).toLocalDate().minusDays(days - 1L).atStartOfDay(zone).toInstant()
                    .let { if (it == now) now.minusNanos(1) else it }
            require(start < end) { "start_time must be before end_time." }
            require(end <= now) { "Future records cannot be requested." }
            // Deliberately conservative: this plugin does not request extended-history permission.
            require(start >= now.minus(Duration.ofDays(30))) { "This plugin reads only the most recent 30 days." }
            val source = text("source_package")
            require(source == null || source.length <= 200 && source.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "source_package must be an Android package name." }
            return HealthQuery(start, end, zone, source, limit)
        }
    }
}

interface HealthDataReader {
    fun availability(): String
    suspend fun grantedPermissions(): Set<String>
    suspend fun read(operation: String, query: HealthQuery): JsonObject
    suspend fun revokePermissions()
}
