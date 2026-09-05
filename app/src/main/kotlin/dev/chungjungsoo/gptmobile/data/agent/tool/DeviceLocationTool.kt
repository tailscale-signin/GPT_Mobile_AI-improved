package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceLocationTool @Inject constructor(
    private val locationProvider: DeviceLocationProvider
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = BuiltInAgentTool.DEVICE_LOCATION,
        description = "Returns the user's current device location (latitude, longitude, accuracy, and altitude) when permission is granted and location services are enabled.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        if (!locationProvider.hasPermission()) {
            return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("Location permission is not granted on this device."),
                isError = true
            )
        }

        val location = locationProvider.getCurrentLocation()
            ?: return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("Unable to determine device location at this time."),
                isError = true
            )

        val responseJson = buildJsonObject {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            location.accuracy?.let { put("accuracy_meters", it.toDouble()) }
            location.altitude?.let { put("altitude_meters", it) }
            put("timestamp", location.timestamp)
            location.provider?.let { put("provider", it) }
        }

        return AgentToolResult(
            callId = callId,
            content = ToolResultContent.Json(responseJson),
            isError = false
        )
    }
}
