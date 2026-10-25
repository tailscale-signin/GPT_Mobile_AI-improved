package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.health.HealthConnectTools
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
enum class PersonalDataCategory { MEMORY, HEALTH, COARSE_LOCATION, PRECISE_LOCATION, PRIVATE_CONTENT, UNCLASSIFIED }

/** Device-local authority bound to actual recipients and one tool, not a model-name allow list. */
@Serializable
data class PersonalDataGrant(
    val version: Int = 3,
    val profileUid: String,
    val destinationScope: String,
    val toolId: String,
    val recipients: Set<String>,
    val categories: Set<PersonalDataCategory>,
    val chatId: Int? = null,
    val issuedAt: Long,
    val expiresAt: Long,
    val rangeStart: Long? = null,
    val rangeEnd: Long? = null,
    val healthRangeDays: Int? = null,
    val downstreamRecipients: Set<String> = emptySet()
) {
    fun active(now: Long): Boolean = version == 3 &&
        issuedAt <= now &&
        now < expiresAt &&
        expiresAt - issuedAt in 1..(if (chatId == null) 30L else 1L) * 24 * 60 * 60_000L &&
        recipients.isNotEmpty() &&
        categories.isNotEmpty() &&
        ((rangeStart == null && rangeEnd == null) || (rangeStart != null && rangeEnd != null && rangeStart < rangeEnd)) &&
        (healthRangeDays == null || healthRangeDays in 1..30) &&
        (PersonalDataCategory.HEALTH !in categories || healthRangeDays != null || rangeStart != null)

    /** Calendar-day scopes match Health Connect parsing, including phone-zone DST transitions. */
    fun coversRange(start: Long?, end: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (rangeStart == null && healthRangeDays == null) return PersonalDataCategory.HEALTH !in categories
        if (start == null || end == null || start >= end || end > now) return false
        val rollingStart = healthRangeDays?.let { days ->
            runCatching { Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtMost(now - 1) }.getOrNull() ?: return false
        }
        return start >= maxOf(rangeStart ?: Long.MIN_VALUE, rollingStart ?: Long.MIN_VALUE) && end <= (rangeEnd ?: now)
    }
}

data class PersonalDataGrantOptions(val rememberForProfile: Boolean = false, val healthRangeDays: Int = 7, val preciseLocation: Boolean = false)

internal fun personalDataCategory(toolId: String): PersonalDataCategory {
    val name = toolId.substringAfterLast(':').substringAfterLast("__")
    return when {
        HealthConnectTools.isHealthTool(name) || name in setOf("health_query", "health_read_records") -> PersonalDataCategory.HEALTH
        name in setOf("memory", "read_memory", "search_memory", "memory_search", "memory_recall") -> PersonalDataCategory.MEMORY
        name == "device_location" -> PersonalDataCategory.PRECISE_LOCATION
        name in setOf("read_file", "read_file_slice", "github") || ':' in toolId -> PersonalDataCategory.PRIVATE_CONTENT
        else -> PersonalDataCategory.UNCLASSIFIED
    }
}

internal fun personalDataCategories(toolId: String): Set<PersonalDataCategory> = when (val category = personalDataCategory(toolId)) {
    PersonalDataCategory.PRECISE_LOCATION -> setOf(PersonalDataCategory.COARSE_LOCATION, PersonalDataCategory.PRECISE_LOCATION)
    else -> setOf(category)
}

internal const val LOCATION_MAP_RECIPIENT = "OpenStreetMap / Overpass"
