package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class DeviceLocationTool @Inject constructor(
    private val locationProvider: DeviceLocationProvider
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = BuiltInAgentTool.DEVICE_LOCATION,
        description = "Get current location / GPS coordinates from the Android phone (get_current_location capability). Use this for where-am-I requests, not timezone or memory inference. Returns latitude, longitude, accuracy and altitude when Android permission and location services allow it.",
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
                content = ToolResultContent.Text("Android location permission is not granted. Enable Device location in this AI profile and grant the app location permission in Android settings. No separate MCP server is required."),
                isError = true
            )
        }

        val location = locationProvider.getCurrentLocation()
            ?: return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("No recent device location fix is available. Check Android location services and try again. Do not substitute a timezone or remembered address for a current location."),
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
