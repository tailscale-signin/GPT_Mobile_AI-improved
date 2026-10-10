package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun isCrawlerTool(name: String, description: String = ""): Boolean =
    name.lowercase() in setOf("free_search__fetch", "free_search__read_doc") ||
        Regex("(?i)(?:^|[ _-])(?:crawl(?:er|ing)?|scrape|scraper|scraping|read_url|fetch_url|read_web_page)(?:$|[ _-])").containsMatchIn(name) ||
        (
            Regex("(?i)\\b(?:crawl|scrape|fetch|read)\\b.*\\b(?:pages?|urls?|websites?)\\b").containsMatchIn(description) &&
                !Regex("(?i)\\bweb[ _-]*search\\b").containsMatchIn(name)
            )

internal fun isNamedWebSearch(name: String, description: String): Boolean =
    !isCrawlerTool(name) && Regex("(?i)web[ _-]*search|search[ _-]*(?:the[ _-]*)?web|internet[ _-]*search").containsMatchIn("$name $description")

internal fun ResolvedAgentTool.selectionId(): String = "${connectionUid.orEmpty()}:$realToolName"

/** Never guesses missing required arguments for arbitrary remote tools. */
internal fun crawlerArguments(definition: AgentToolDefinition, url: String): JsonObject? {
    val properties = definition.inputSchema["properties"] as? JsonObject ?: return null
    val key = listOf("url", "uri", "urls").firstOrNull { it in properties } ?: return null
    val values = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
    values[key] = if (key == "urls") JsonArray(listOf(JsonPrimitive(url))) else JsonPrimitive(url)
    val required = (definition.inputSchema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    for (field in required.filterNot { it == key }) {
        values[field] = (properties[field] as? JsonObject)?.get("default") ?: return null
    }
    // A crawl stays on this result page; the next search, not a crawler, expands scope.
    listOf("maxDepth", "max_depth", "depth").filter { it in properties }.forEach { values[it] = JsonPrimitive(0) }
    return JsonObject(values)
}
