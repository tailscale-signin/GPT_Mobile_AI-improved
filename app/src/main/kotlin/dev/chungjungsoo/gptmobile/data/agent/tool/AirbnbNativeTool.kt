package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Device-side public browsing; no MCP host, account, cookies or API key. */
internal class AirbnbNativeTool(
    private val client: PublicAirbnbClient,
    private val allowed: suspend () -> Boolean,
    private val search: suspend (String) -> JsonObject?
) : AgentTool {
    override val definition = AgentToolDefinition(
        "airbnb",
        "Airbnb public listing search and details, running in the app without an OpenBnB account. " +
            "Use action=search with location, or action=details with a numeric listing id. Dates and guests are optional. " +
            "For nearby search supply observed origin_latitude, origin_longitude and radius_km. Distance is verified only for listings with coordinates; say nearest among verified returned listings. Creates listing cards and photo galleries. Indexed fallback results do not confirm prices, dates or availability. Booking opens Airbnb.",
        Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["search","details"],"default":"search"},"location":{"type":"string","maxLength":200},"id":{"type":"string","pattern":"^[0-9]{1,30}$"},"checkin":{"type":"string"},"checkout":{"type":"string"},"adults":{"type":"integer","minimum":1,"maximum":50},"children":{"type":"integer","minimum":0,"maximum":50},"infants":{"type":"integer","minimum":0,"maximum":50},"pets":{"type":"integer","minimum":0,"maximum":20},"origin_latitude":{"type":"number","minimum":-90,"maximum":90},"origin_longitude":{"type":"number","minimum":-180,"maximum":180},"radius_km":{"type":"number","minimum":0.1,"maximum":500}},"additionalProperties":false}""") as JsonObject
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        // Optional blank/null fields are commonly emitted by tool-calling models.
        // Remove them before date parsing rather than misreporting a network failure.
        val cleaned = JsonObject(
            arguments.filterNot { (key, value) ->
                key in setOf("action", "id", "checkin", "checkout", "adults", "children", "infants", "pets") &&
                    (value == JsonNull || (value is JsonPrimitive && value.isString && value.content.isBlank()))
            }
        )
        return executeValidated(callId, cleaned)
    }

    private suspend fun executeValidated(callId: String, arguments: JsonObject): AgentToolResult {
        return try {
            try {
                validate(arguments)
            } catch (_: Exception) {
                return AgentToolResult(callId, ToolResultContent.Text("Invalid Airbnb arguments. Search requires a nonempty location; details requires a numeric id. Omit unused dates or supply both checkin and checkout as YYYY-MM-DD with checkout after checkin. Guest counts must be integers. Use only fields in the tool schema."), true)
            }
            if (!allowed()) return AgentToolResult(callId, ToolResultContent.Text("Airbnb is disabled for this profile. Enable the installed Airbnb plugin and its AI profile toggle before searching."), true)
            val action = text(arguments, "action") ?: "search"
            var fallback = false
            val listings = try {
                client.listings(action, arguments).also { check(it.isNotEmpty()) { "No public listing data." } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (action == "details") throw error
                check(allowed()) { "Airbnb was disabled." }
                fallback = true
                val results = search("site:airbnb.com/rooms/ ${text(arguments, "location")}")?.get("results") as? JsonArray
                val candidates = results.orEmpty().filterIsInstance<JsonObject>().mapNotNull { result ->
                    val url = AirbnbListings.listingUrl(text(result, "url")) ?: return@mapNotNull null
                    buildJsonObject {
                        put("id", url.substringAfterLast('/'))
                        put("url", url)
                        put("title", text(result, "title") ?: "Airbnb listing")
                        text(result, "snippet")?.let { put("description", it) }
                    }
                }
                AirbnbListings.normalize(JsonObject(mapOf("searchResults" to JsonArray(candidates))), arguments)
            }
            check(allowed()) { "Airbnb was disabled." }
            val originLat = (arguments["origin_latitude"] as? JsonPrimitive)?.doubleOrNull
            val originLon = (arguments["origin_longitude"] as? JsonPrimitive)?.doubleOrNull
            val validatedListings = if (originLat != null && originLon != null) {
                dev.chungjungsoo.gptmobile.data.airbnb.AirbnbGeography.filter(listings, originLat, originLon, (arguments["radius_km"] as? JsonPrimitive)?.doubleOrNull ?: 25.0)
            } else {
                listings
            }
            val payload = JsonObject(
                AirbnbListings.json(validatedListings.map { it.copy(observedAtEpochMillis = System.currentTimeMillis(), provider = if (fallback) "Public web index" else "Airbnb public page") }, "Android public browsing") + buildJsonObject {
                    put("indexedFallback", fallback)
                    put("geographyQualification", "Nearest among returned listings with verified coordinates only. Listings without coordinates have unverified distance; search coverage is incomplete.")
                    put("notice", if (fallback) "Public indexed listings. Dates, fees, prices and availability are unconfirmed; open listing details or Airbnb to verify." else "Public page observations only. Missing prices, fees and review text remain unknown. Confirm availability and final total on Airbnb.")
                    if (validatedListings.isEmpty()) put("error", "No public listings were returned. Try another location or open Airbnb.")
                }
            )
            AgentToolResult(callId, ToolResultContent.Json(payload), validatedListings.isEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Airbnb", "Public lookup failed · category=${error.javaClass.simpleName}", "W")
            AgentToolResult(callId, ToolResultContent.Text("Airbnb public browsing unavailable. Check the profile toggle, location, dates and connectivity. No sign-up or API key is required. A blocked page cannot supply live listing details."), true)
        }
    }

    internal fun validate(arguments: JsonObject) {
        require(arguments.keys.all { it in setOf("action", "location", "id", "checkin", "checkout", "adults", "children", "infants", "pets", "origin_latitude", "origin_longitude", "radius_km") })
        for (key in listOf("action", "location", "id", "checkin", "checkout")) {
            arguments[key]?.let { require(it is JsonPrimitive && it.isString) }
        }
        val action = text(arguments, "action") ?: "search"
        require(action in setOf("search", "details"))
        if (action == "search") require(text(arguments, "location")?.let { it.isNotBlank() && it.length <= 200 && it.none { char -> char.code < 32 } } == true)
        if (action == "details") require(text(arguments, "id")?.matches(Regex("[0-9]{1,30}")) == true)
        val lat = (arguments["origin_latitude"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        val lon = (arguments["origin_longitude"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        require((arguments["origin_latitude"] == null) == (arguments["origin_longitude"] == null))
        if (arguments["origin_latitude"] != null) require(lat != null && lat.isFinite() && lat in -90.0..90.0 && lon != null && lon.isFinite() && lon in -180.0..180.0)
        arguments["radius_km"]?.let { value ->
            val radius = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            require(lat != null && radius != null && radius.isFinite() && radius in 0.1..500.0)
        }
        val checkin = text(arguments, "checkin")?.let(LocalDate::parse)
        val checkout = text(arguments, "checkout")?.let(LocalDate::parse)
        require((checkin == null) == (checkout == null)) { "Supply both travel dates." }
        if (checkin != null && checkout != null) require(checkout.isAfter(checkin) && checkout <= checkin.plusDays(365))
        for (key in listOf("adults", "children", "infants", "pets")) {
            arguments[key]?.let { value ->
                val primitive = value as? JsonPrimitive
                val count = primitive?.takeUnless { it.isString }?.intOrNull
                require(count != null && count in (if (key == "adults") 1 else 0)..(if (key == "pets") 20 else 50))
            }
        }
    }

    private fun text(arguments: JsonObject, key: String): String? = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
