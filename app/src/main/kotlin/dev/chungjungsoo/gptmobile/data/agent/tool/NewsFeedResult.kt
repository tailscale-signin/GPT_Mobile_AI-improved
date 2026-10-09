package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonObject

/** Provider failures are retained even when an alternate feed succeeds. */
data class NewsFeedResult(
    val articles: List<JsonObject>,
    val diagnostics: List<JsonObject>,
    val fallbackUsed: Boolean = false
)
