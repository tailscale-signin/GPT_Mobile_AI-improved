package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PluginExecutionSettings(
    val timeoutSeconds: Int = 45,
    val maxOutputCharacters: Int = 32000,
    val searchResults: Int = 10,
    val decimalPlaces: Int = 8,
    val timeZone: String = "",
    val nearbyPlaces: Boolean = true,
    val nearbyRadiusMeters: Int = 1500,
    val includePageLinks: Boolean = true,
    val fileExcerptLines: Int = 200,
    val githubCacheSeconds: Int = 15
) {
    fun normalized() = copy(
        nearbyRadiusMeters = nearbyRadiusMeters.coerceIn(100, 5000),
        fileExcerptLines = fileExcerptLines.coerceIn(10, 2000),
        timeoutSeconds = timeoutSeconds.coerceIn(5, 120),
        maxOutputCharacters = maxOutputCharacters.coerceIn(1000, 128000),
        searchResults = searchResults.coerceIn(1, 10),
        decimalPlaces = decimalPlaces.coerceIn(0, 15),
        githubCacheSeconds = githubCacheSeconds.coerceIn(0, 120)
    )
}
