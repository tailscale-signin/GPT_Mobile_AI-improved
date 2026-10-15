package dev.chungjungsoo.gptmobile.data.agent.tool

/** Location is query text, never a network endpoint or country-code alias. */
internal fun newsLocationQuery(action: String, query: String?, location: String?): String {
    val place = location?.trim()?.takeIf { it.isNotEmpty() }
    require(location == null || place != null && place.length <= 150) { "location must be a city or region of 1–150 characters." }
    val text = query.orEmpty().trim()
    return when {
        place == null -> text
        text.isBlank() -> place
        text.contains(place, ignoreCase = true) -> text
        action in setOf("search", "location") -> "$text $place"
        else -> text
    }.also { require(it.length <= 250) { "Combined news query and location exceed 250 characters." } }
}
