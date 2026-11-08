package dev.chungjungsoo.gptmobile.data.network.gateway

import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal data class GatewayDashboardDay(val day: String, val outputTokens: Long?, val missingMeasurements: Long?)

internal data class GatewayDashboardDevice(
    val address: String,
    val agent: String,
    val state: String,
    val lastSeen: Double?,
    val activeRequests: Long?,
    val requests: Long?
)

internal data class GatewayDashboardActivity(
    val id: String,
    val kind: String,
    val title: String,
    val status: String,
    val updated: Double?,
    val detail: String,
    val branch: String
)

internal data class GatewayDashboardSnapshot(
    val instanceId: String,
    val capturedAt: Double?,
    val outputTokens: Long?,
    val tokensPerSecond: Double?,
    val days: List<GatewayDashboardDay>,
    val unmeasuredRounds: Long?,
    val openJobs: Long?,
    val activeWorkers: Long?,
    val maxWorkers: Long?,
    val draining: Boolean?,
    val devices: List<GatewayDashboardDevice>?,
    val activities: List<GatewayDashboardActivity>?,
    val historyAvailable: Boolean,
    val uptime: Double?,
    val requests: Long?,
    val errors: Long?,
    val receivedBytes: Long?,
    val sentBytes: Long?
)

internal data class GatewayDashboardObservation(val snapshot: GatewayDashboardSnapshot, val receivedAtMillis: Long)

/** A responding gateway can be alive while its selected model or durable store is not ready. */
internal data class GatewayReadinessObservation(
    val instanceId: String,
    val ready: Boolean,
    val generationReady: Boolean?,
    val selectedModelAvailable: Boolean?,
    val draining: Boolean,
    val receivedAtMillis: Long,
    val availableWorkers: Long? = null,
    val canAccept: Boolean? = null,
    val canQueue: Boolean? = null,
    val modelLoading: Boolean? = null,
    val observedAtMillis: Long? = null,
    val observationAgeMillis: Long? = null,
    val receivedAtMonotonicMillis: Long? = null
)

internal fun parseGatewayReadiness(value: JsonObject, status: Int, model: String?, receivedAtMillis: Long, receivedAtMonotonicMillis: Long? = null): GatewayReadinessObservation {
    require((value["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == 1) { "Unsupported gateway readiness." }
    val instance = (value["instanceId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    require(instance != null && instance.matches(Regex("[a-f0-9]{32}"))) { "Gateway readiness identity is unavailable." }
    val ready = (value["ready"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Gateway readiness was not measured.")
    require(status == if (ready) 200 else 503) { "Gateway readiness response contradicts its status." }
    val capabilities = value["capabilities"] as? JsonObject ?: error("Gateway readiness capabilities are unavailable.")
    if (!model.isNullOrBlank()) {
        val digest = (capabilities["selectedModelDigest"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        require(digest == dev.chungjungsoo.gptmobile.data.research.researchHash(model)) { "Gateway readiness belongs to another model." }
    }
    val generation = (capabilities["generationReady"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    val available = (capabilities["modelAvailable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    require(!ready || generation == true && (model.isNullOrBlank() || available == true)) { "Gateway readiness lacks model evidence." }
    val capacity = ((value["components"] as? JsonObject)?.get("workerCapacity") as? JsonObject)
    fun JsonObject.optionalCount(key: String): Long? = if (key !in this) null else count(key) ?: error("Invalid gateway readiness measurement.")
    fun JsonObject.optionalFlag(key: String): Boolean? = if (key !in this) null else (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Invalid gateway readiness measurement.")
    val observedAt = capabilities.optionalCount("observedAtMs")
    val reportedAge = capabilities.optionalCount("observationAgeMs")
    value.optionalCount("serverTimeMs")
    require((observedAt == null) == (reportedAge == null)) { "Gateway readiness freshness evidence is incomplete." }
    // Original probe age is monotonic on the server; its wall clock is metadata.
    // Validate the optional server timestamp without comparing clocks across hosts.
    return GatewayReadinessObservation(
        instance,
        ready,
        generation,
        available,
        (value["service"] as? JsonObject)?.flag("draining") == true,
        receivedAtMillis,
        capacity?.optionalCount("availableWorkers"),
        capabilities.optionalFlag("canAccept"),
        capabilities.optionalFlag("canQueue"),
        capabilities.optionalFlag("modelLoading"),
        observedAt,
        reportedAge,
        receivedAtMonotonicMillis
    )
}

/** Missing, invalid, and explicitly unavailable measurements remain unknown. */
internal fun parseGatewayDashboard(value: JsonObject): GatewayDashboardSnapshot {
    require((value["schemaVersion"] as? JsonPrimitive)?.intOrNull == 1 && value.text("schema") == "gptmobile.gateway.dashboard.v1") { "Unsupported gateway dashboard." }
    require(value.text("scope") == "admitted_identity") { "Gateway dashboard scope is unavailable." }
    val instance = value.text("instanceId").also { require(it.matches(Regex("[a-fA-F0-9]{32}"))) { "Gateway dashboard identity is unavailable." } }
    val availability = value["availability"] as? JsonObject ?: JsonObject(emptyMap())
    val usage = (value["usage"] as? JsonObject)?.takeIf { availability.flag("usage") && it.flag("available") && it.text("scope") == "this_installation" }
    val service = (value["service"] as? JsonObject)?.takeIf { availability.flag("service") && value.text("serviceScope") == "this_installation" }
    val days = usage?.objects("days", 7).orEmpty().mapNotNull { row ->
        val day = row.text("day")
        if (runCatching { LocalDate.parse(day) }.isFailure) return@mapNotNull null
        GatewayDashboardDay(day, row.count("outputTokens"), row.count("missingMeasurements"))
    }.distinctBy { it.day }.sortedBy { it.day }
    val devices = if (availability.flag("devices") && value["devices"] is JsonArray) {
        value.objects("devices", 128).map { row ->
            GatewayDashboardDevice(row.text("address", 80), row.text("agent", 100), row.text("state", 20), row.number("lastSeen"), row.count("activeRequests"), row.count("requests"))
        }.sortedByDescending { it.lastSeen ?: 0.0 }
    } else {
        null
    }
    val history = availability.flag("history") && value["jobs"] is JsonArray && value["engineering"] is JsonArray
    val liveActivity = availability.flag("activities") && value["activities"] is JsonArray
    val activity = if (liveActivity || history) {
        val rows = (if (liveActivity) value.objects("activities", 300) else emptyList()) +
            (if (history) value.objects("jobs", 100) + value.objects("engineering", 100) else emptyList())
        rows.mapNotNull { row ->
            val id = row.text("id", 160).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GatewayDashboardActivity(id, row.text("kind", 30), row.text("title", 160), row.text("status", 40), row.number("updated") ?: row.number("started"), row.text("detail", 240), row.text("branch", 160))
        }.distinctBy { "${it.kind}:${it.id}" }.sortedByDescending { it.updated ?: 0.0 }.take(300)
    } else {
        null
    }
    return GatewayDashboardSnapshot(
        instance,
        value.number("capturedAt"),
        usage?.count("outputTokens"),
        usage?.number("tokensPerSecond"),
        days,
        usage?.count("unmeasuredRounds"),
        value.count("openJobs").takeIf { availability.flag("openJobs") },
        service?.count("activeWorkers"),
        service?.count("maxWorkers"),
        (service?.get("draining") as? JsonPrimitive)?.booleanOrNull,
        devices,
        activity,
        history,
        value.number("uptime"),
        value.count("requests"),
        value.count("errors"),
        value.count("receivedBytes"),
        value.count("sentBytes")
    )
}

private fun JsonObject.objects(key: String, maximum: Int) = (this[key] as? JsonArray).orEmpty().take(maximum).filterIsInstance<JsonObject>()
private fun JsonObject.text(key: String, maximum: Int = 100) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().filterNot(Char::isISOControl).take(maximum)
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
private fun JsonObject.count(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
