package dev.chungjungsoo.gptmobile.data.mcp

import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool

/** Metadata for the native Android tool resolved by AgentToolResolver, subject to profile opt-in. */
object McpLocationToolSet {
    const val PRESET_ID = "device-location"
    const val PRESET_NAME = "Device Location"
    const val DEFAULT_LAUNCHER_PATH = "builtin://device_location"
    const val IS_PREINSTALLED = true

    val tools: List<McpBuiltinTool> = listOf(
        McpBuiltinTool(
            name = BuiltInAgentTool.DEVICE_LOCATION,
            description = "Get the Android phone's current coordinates, accuracy and altitude. Enable Device location for the AI profile and grant Android location permission."
        )
    )
}
