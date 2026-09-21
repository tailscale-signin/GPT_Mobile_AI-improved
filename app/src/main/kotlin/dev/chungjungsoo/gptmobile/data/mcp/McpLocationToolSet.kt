package dev.chungjungsoo.gptmobile.data.mcp

/**
 * Built-in tool definitions for Location and Geocoding support.
 * Integrates native device GPS sensors, OpenStreetMap Nominatim reverse/forward geocoding,
 * and Haversine distance calculations. Preinstalled and enabled by default.
 */
object McpLocationToolSet {
    const val PRESET_ID = "device-location"
    const val PRESET_NAME = "Device Location & Geocoding"
    const val DEFAULT_LAUNCHER_PATH = "builtin://device_location"
    const val IS_PREINSTALLED = true

    /**
     * Set of built-in tools provided for location, geocoding, and distance calculations.
     */
    val tools: List<McpBuiltinTool> = listOf(
        McpBuiltinTool(
            name = "get_current_location",
            description = "Get current GPS coordinates (latitude, longitude, altitude, accuracy) from the device sensors."
        ),
        McpBuiltinTool(
            name = "reverse_geocode",
            description = "Convert latitude and longitude coordinates into a human-readable street address using OpenStreetMap Nominatim / native geocoding.",
            parameters = listOf(
                McpToolParameter(
                    name = "latitude",
                    type = "number",
                    description = "Latitude coordinate (-90 to 90)",
                    required = true
                ),
                McpToolParameter(
                    name = "longitude",
                    type = "number",
                    description = "Longitude coordinate (-180 to 180)",
                    required = true
                )
            )
        ),
        McpBuiltinTool(
            name = "geocode_address",
            description = "Convert a street address or location name into geographic coordinates (latitude and longitude).",
            parameters = listOf(
                McpToolParameter(
                    name = "address",
                    type = "string",
                    description = "Address or place name to geocode",
                    required = true
                )
            )
        ),
        McpBuiltinTool(
            name = "calculate_distance",
            description = "Calculate the great-circle distance between two geographic coordinates using the Haversine formula.",
            parameters = listOf(
                McpToolParameter(
                    name = "lat1",
                    type = "number",
                    description = "Latitude of starting location",
                    required = true
                ),
                McpToolParameter(
                    name = "lon1",
                    type = "number",
                    description = "Longitude of starting location",
                    required = true
                ),
                McpToolParameter(
                    name = "lat2",
                    type = "number",
                    description = "Latitude of destination location",
                    required = true
                ),
                McpToolParameter(
                    name = "lon2",
                    type = "number",
                    description = "Longitude of destination location",
                    required = true
                )
            )
        )
    )
}
