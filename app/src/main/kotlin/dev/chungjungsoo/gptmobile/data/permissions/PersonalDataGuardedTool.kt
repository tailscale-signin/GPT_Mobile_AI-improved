package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.health.AuthorizedHealthQuery
import dev.chungjungsoo.gptmobile.data.health.HealthQuery
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.time.Clock
import kotlin.math.round
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Recheck before preparation, execution and release; range and precision are executable authority. */
internal class PersonalDataGuardedTool(
    private val child: AgentTool,
    private val permitted: suspend () -> Boolean,
    private val grantLookup: (suspend () -> PersonalDataGrant?)?,
    private val clock: Clock = Clock.systemDefaultZone()
) : AgentTool by child,
    dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool,
    dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool {
    constructor(child: AgentTool, permitted: suspend () -> Boolean) : this(child, permitted, null)

    override val executionOwner = (child as? dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool)?.executionOwner ?: dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner.CLIENT
    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = PersonalDataGuardedTool(child.withChildBudget(bind), permitted, grantLookup, clock)

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()

    override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
        check(permitted()) { "PERSONAL_DATA_PERMISSION_REQUIRED: Review this tool's data sharing permission." }
        val grant = currentGrant()
        val query = grant?.takeIf { PersonalDataCategory.HEALTH in it.categories }?.let {
            val status = child.definition.name.substringAfterLast("__") == "health_connect_status"
            if (status) require(arguments.isEmpty()) { "Health status accepts no arguments." }
            HealthQuery.parse(arguments, clock.instant(), clock.zone).also { query ->
                check(it.coversRange(query.start.toEpochMilli(), query.end.toEpochMilli(), clock.millis(), clock.zone)) { "PERSONAL_DATA_RANGE_REQUIRED: This health query exceeds the selected sharing range. Choose a narrower date range or review permission." }
            }
        }
        if (grant != null && personalDataCategory(grant.toolId) == PersonalDataCategory.PRECISE_LOCATION) {
            require(arguments.keys.all { it in setOf("nearby", "place_name", "radius_meters") }) { "Unknown device-location argument." }
            check("nearby" !in arguments || LOCATION_MAP_RECIPIENT in grant.downstreamRecipients && PersonalDataCategory.PRECISE_LOCATION in grant.categories) {
                "PERSONAL_DATA_MAP_PERMISSION_REQUIRED: Nearby lookup sends coordinates to OpenStreetMap / Overpass. Grant that map service and precise location separately before using nearby search."
            }
        }
        val scopedArguments = if (query != null && !child.definition.name.endsWith("health_connect_status")) {
            JsonObject(arguments - "days" + mapOf("start_time" to JsonPrimitive(query.start.toString()), "end_time" to JsonPrimitive(query.end.toString())))
        } else {
            arguments
        }
        val execute = (child as? dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool)?.prepareExecution(callId, scopedArguments) ?: suspend { child.execute(callId, scopedArguments) }
        suspend fun stillAuthorized() {
            check(permitted()) { "PERSONAL_DATA_PERMISSION_REVOKED: The prepared tool or its result was not released." }
            if (grant != null) {
                check(currentGrant() == grant) { "PERSONAL_DATA_PERMISSION_REVOKED: Sharing scope changed after this tool was prepared." }
                check(query == null || grant.coversRange(query.start.toEpochMilli(), query.end.toEpochMilli(), clock.millis(), clock.zone)) { "PERSONAL_DATA_RANGE_REVOKED: The health data is outside the current sharing range." }
            }
        }
        return {
            stillAuthorized()
            val result = if (query != null && !child.definition.name.endsWith("health_connect_status")) {
                withContext(AuthorizedHealthQuery(scopedArguments, query)) { execute() }
            } else {
                execute()
            }
            stillAuthorized()
            if (grant == null) {
                result
            } else {
                val protected = if (PersonalDataCategory.COARSE_LOCATION in grant.categories && PersonalDataCategory.PRECISE_LOCATION !in grant.categories) coarseLocation(result) else result
                val provenance = PersonalDataProvenance(
                    sourceToolId = grant.toolId,
                    sourceProfileUid = grant.profileUid,
                    sourceRef = researchHash(callId),
                    categories = grant.categories,
                    sourceRecipients = grant.recipients,
                    retentionChatId = grant.chatId,
                    rangeStart = query?.start?.toEpochMilli(),
                    rangeEnd = query?.end?.toEpochMilli()
                )
                protected.copy(personalData = (protected.personalData + provenance).distinct())
            }
        }
    }

    private suspend fun currentGrant(): PersonalDataGrant? {
        val lookup = grantLookup ?: return null
        return checkNotNull(lookup()?.takeIf { it.active(clock.millis()) }) { "PERSONAL_DATA_PERMISSION_REQUIRED: Choose this tool's sharing category and data range." }
    }

    private fun coarseLocation(result: AgentToolResult): AgentToolResult {
        if (result.isError) {
            val safe = ToolResultContent.Text("Device location could not be read. No precise location was released.")
            return result.copy(content = safe, retainedContent = safe, traceContent = safe, measurement = null, media = emptyList(), assistMetadata = emptyList(), optionalAssistKey = null)
        }
        val body = checkNotNull((result.content as? ToolResultContent.Json)?.value as? JsonObject) { "PERSONAL_DATA_PRECISION_UNVERIFIED: A precise location result could not be reduced safely." }
        val latitude = (body["latitude"] as? JsonPrimitive)?.doubleOrNull
        val longitude = (body["longitude"] as? JsonPrimitive)?.doubleOrNull
        check(latitude != null && latitude.isFinite() && latitude in -90.0..90.0 && longitude != null && longitude.isFinite() && longitude in -180.0..180.0) { "PERSONAL_DATA_PRECISION_UNVERIFIED: A precise location result could not be reduced safely." }
        val coarse = ToolResultContent.Json(
            buildJsonObject {
                put("latitude", round(latitude * 10) / 10)
                put("longitude", round(longitude * 10) / 10)
                put("granularity", "approximate area; coordinates rounded to a 0.1-degree grid")
                body["timestamp"]?.let { put("timestamp", it) }
                put("freshness", "observation at the returned timestamp; saved context is not a new location fix")
            }
        )
        return result.copy(
            content = coarse,
            retainedContent = coarse,
            traceContent = ToolResultContent.Text("Approximate device location; raw coordinates omitted from the stored tool trace."),
            measurement = null,
            media = emptyList(),
            assistMetadata = emptyList(),
            optionalAssistKey = null
        )
    }
}
