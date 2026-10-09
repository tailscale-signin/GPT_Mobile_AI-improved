package dev.chungjungsoo.gptmobile.data.marketplace

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Android adapters are compiled into the APK; verified downloads install their registration. */
object NativeMarketplaceCatalog {
    val operations = linkedMapOf(
        "refuge" to mapOf("restrooms" to listOf("latitude", "longitude")),
        "toronto" to mapOf("datasets" to listOf("query"), "records" to listOf("resource_id")),
        "openstreetmap" to mapOf("geocode" to listOf("query"), "restrooms" to listOf("latitude", "longitude")),
        "nominatim" to mapOf("geocode" to listOf("query")),
        "overpass" to mapOf("restrooms" to listOf("latitude", "longitude")),
        "ticketmaster" to mapOf("events" to listOf("query", "location")),
        "tomtom" to mapOf("places" to listOf("query")),
        "yelp" to mapOf("businesses" to listOf("query", "location")),
        "eventbrite" to mapOf("organization_events" to listOf("organization_id")),
        "arcgis" to mapOf("geocode" to listOf("query")),
        "openrouteservice" to mapOf("walking_route" to listOf("latitude", "longitude", "end_latitude", "end_longitude")),
        "google-places" to mapOf("places" to listOf("query"), "nearby" to listOf("latitude", "longitude"), "details" to listOf("place_id")),
        "foursquare" to mapOf("places" to listOf("query", "location"))
    )
    val keyedProviders = setOf("ticketmaster", "tomtom", "yelp", "eventbrite", "arcgis", "openrouteservice", "google-places", "foursquare")
    val customEndpointProviders = setOf("nominatim", "overpass")
    fun requiresKey(entry: GitHubMarketplacePackage) = entry.provider in keyedProviders
    fun requiresEndpoint(entry: GitHubMarketplacePackage) = entry.provider in customEndpointProviders
    fun supports(entry: GitHubMarketplacePackage) = entry.provider in operations
    fun validKey(key: String) = key.isNotBlank() &&
        key.length <= 4096 &&
        key.all { it.code in 33..126 } &&
        !key.contains("YOUR_", true) &&
        !key.contains('<') &&
        !key.contains('{')

    fun validEndpoint(endpoint: String): Boolean = runCatching {
        val url = URI(endpoint.trim())
        val host = url.host.orEmpty().lowercase(java.util.Locale.ROOT)
        val shared = listOf("nominatim.openstreetmap.org", "overpass-api.de", "overpass.kumi.systems", "overpass.private.coffee")
        url.scheme == "https" &&
            host.isNotBlank() &&
            url.port in listOf(-1, 443) &&
            url.userInfo == null &&
            url.rawQuery == null &&
            url.fragment == null &&
            shared.none { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun definitions(entry: GitHubMarketplacePackage): List<AgentToolDefinition> = operations[entry.provider].orEmpty().map { (operation, fields) ->
        AgentToolDefinition(
            name = "${entry.preset.alias}__$operation",
            description = "${entry.preset.name}: read ${operation.replace('_', ' ')}. " +
                "One bounded provider request. Use supplied coordinates or location; do not guess the user's location. " +
                "Missing accessibility, fee and opening information is unknown. ${entry.serviceNotice}",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(fields.associateWith(::fieldSchema)))
                put("required", JsonArray(fields.map(::JsonPrimitive)))
                put("additionalProperties", false)
            }
        )
    }

    fun validate(provider: String, operation: String, arguments: JsonObject) {
        val fields = operations[provider]?.get(operation) ?: error("Unknown marketplace tool.")
        require(arguments.keys == fields.toSet()) { "Supply exactly the fields in the tool schema." }
        fields.forEach { name ->
            val value = arguments[name] as? JsonPrimitive ?: error("Invalid tool argument.")
            if (name.endsWith("latitude") || name.endsWith("longitude")) {
                val number = value.takeUnless { it.isString }?.doubleOrNull
                val bound = if (name.endsWith("latitude")) 90.0 else 180.0
                require(number != null && number.isFinite() && number in -bound..bound) { "Coordinates must be finite and in range." }
            } else {
                require(value.isString && value.content.isNotBlank() && value.content.length <= 250 && value.content.none { it.code < 32 }) { "Invalid text argument." }
                if (name == "organization_id") require(value.content.matches(Regex("[0-9]{1,30}"))) { "Organization ID must be numeric." }
                if (name == "resource_id") require(value.content.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid resource ID." }
            }
        }
    }

    private fun fieldSchema(name: String) = buildJsonObject {
        if (name.endsWith("latitude") || name.endsWith("longitude")) {
            val bound = if (name.endsWith("latitude")) 90 else 180
            put("type", "number")
            put("minimum", -bound)
            put("maximum", bound)
        } else {
            put("type", "string")
            put("minLength", 1)
            put(
                "maxLength",
                if (name == "organization_id") {
                    30
                } else if (name == "resource_id") {
                    80
                } else {
                    250
                }
            )
        }
    }
}
