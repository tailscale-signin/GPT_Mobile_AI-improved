package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool

/** Request-local capability context; never persist this in a profile or conversation history. */
internal fun liveToolSystemPrompt(original: String?, toolNames: Collection<String>): String = buildString {
    original?.takeIf(String::isNotBlank)?.let {
        append(it)
        append("\n\n")
    }
    val names = toolNames.distinct().sorted()
    append("Live mobile tool context for this request: ")
    append(names.joinToString(", ").ifEmpty { "no mobile tools enabled" })
    append(". A Gateway may supply additional tools. Use the actual tool schemas in the current request as the authority for callable capabilities; stored memories, old issues, plans and previous tool inventories are not current capability evidence. ")
    append("A timezone or remembered address does not establish the user's current physical location. Never infer current GPS coordinates or a current city from them. ")
    if (BuiltInAgentTool.DEVICE_LOCATION in names) {
        append("For a user request for current device location, call device_location. This is the Android phone's native location tool (the current-location capability sometimes called get_current_location), not a separate MCP server. Registration does not prove permission or GPS availability: report the tool's actual result, accuracy and any permission/service error. ")
    } else {
        append("The native device_location tool is not enabled in this request. If no other live device-location tool is present, explain how to enable Device location in the AI profile's tool settings, allow local tools and the Advanced Settings location feature, and grant Android location permission. Also check per-chat tool exclusions. Do not claim that a Location MCP server must be installed. ")
    }
    append("Do not claim reverse_geocode, geocode_address or calculate_distance are callable merely because they appear in memory or a catalogue; only use them if their schemas are actually supplied.")
}
