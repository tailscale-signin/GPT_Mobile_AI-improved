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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
            "Creates listing cards and photo galleries. Indexed fallback results do not confirm prices, dates or availability. Booking opens Airbnb.",
        Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["search","details"],"default":"search"},"location":{"type":"string","maxLength":200},"id":{"type":"string","pattern":"^[0-9]{1,30}$"},"checkin":{"type":"string"},"checkout":{"type":"string"},"adults":{"type":"integer","minimum":1,"maximum":50},"children":{"type":"integer","minimum":0,"maximum":50},"infants":{"type":"integer","minimum":0,"maximum":50},"pets":{"type":"integer","minimum":0,"maximum":20}},"additionalProperties":false}""") as JsonObject
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        validate(arguments)
        check(allowed()) { "Airbnb is disabled for this profile." }
        val action = text(arguments, "action") ?: "search"
        var fallback = false
        val listings = try {
            client.listings(action, arguments)
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
        val payload = JsonObject(
            AirbnbListings.json(listings.map { it.copy(provider = if (fallback) "Public web index" else "Airbnb public page") }, "Android public browsing") + buildJsonObject {
                put("indexedFallback", fallback)
                put("notice", if (fallback) "Public indexed listings. Dates, fees, prices and availability are unconfirmed; open listing details or Airbnb to verify." else "Public page observations only. Missing prices, fees and review text remain unknown. Confirm availability and final total on Airbnb.")
                if (listings.isEmpty()) put("error", "No public listings were returned. Try another location or open Airbnb.")
            }
        )
        AgentToolResult(callId, ToolResultContent.Json(payload), listings.isEmpty())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AgentToolResult(callId, ToolResultContent.Text("Airbnb public browsing unavailable. Check the profile toggle, location, dates and connectivity. No sign-up or API key is required. A blocked page cannot supply live listing details."), true)
    }

    internal fun validate(arguments: JsonObject) {
        require(arguments.keys.all { it in setOf("action", "location", "id", "checkin", "checkout", "adults", "children", "infants", "pets") })
        val action = text(arguments, "action") ?: "search"
        require(action in setOf("search", "details"))
        if (action == "search") require(text(arguments, "location")?.let { it.isNotBlank() && it.length <= 200 && it.none { char -> char.code < 32 } } == true)
        if (action == "details") require(text(arguments, "id")?.matches(Regex("[0-9]{1,30}")) == true)
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
