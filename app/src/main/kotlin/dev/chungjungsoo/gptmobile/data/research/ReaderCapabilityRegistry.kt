package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reviewed legacy readers. Selection does not authorize execution: the resolved connection,
 * operation and captured schema still pass the existing live dispatch grant checks.
 * Crawler admission is intentionally absent until its trusted grant/receipt bridge is wired. */
object ReaderCapabilityRegistry {
    private val legacyUrlOperations = setOf(
        "read_url",
        "firecrawl_scrape",
        "tavily_extract",
        "web_fetch_exa",
        "crawling_exa",
        "scrape_as_markdown",
        "free_search__fetch",
        "free_search__read_doc"
    )

    fun legacyUrlReader(operation: String, schema: JsonObject): Boolean {
        if (operation !in legacyUrlOperations) return false
        val properties = schema["properties"] as? JsonObject ?: return false
        val urlKey = listOf("url", "urls").firstOrNull { it in properties } ?: return false
        val urlSchema = properties[urlKey] as? JsonObject ?: return false
        val type = (urlSchema["type"] as? JsonPrimitive)?.contentOrNull
        if (type != if (urlKey == "url") "string" else "array") return false
        if (urlKey == "urls" && ((urlSchema["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull != "string") return false
        val required = schema["required"]?.let { it as? JsonArray ?: return false }.orEmpty()
        return required.all { field ->
            val name = (field as? JsonPrimitive)?.contentOrNull ?: return@all false
            name == urlKey || (properties[name] as? JsonObject)?.containsKey("default") == true
        }
    }

    fun readsUrl(definition: AgentToolDefinition, originalName: String = definition.name): Boolean =
        AgentToolCapability.PAGE_READER in definition.capabilities || legacyUrlReader(originalName, definition.inputSchema)

    fun urlArguments(definition: AgentToolDefinition, originalName: String, url: String): JsonObject? {
        if (!readsUrl(definition, originalName)) return null
        val properties = definition.inputSchema["properties"] as? JsonObject ?: return null
        val key = listOf("url", "urls").firstOrNull { it in properties } ?: return null
        val arguments = properties.mapNotNull { (name, schema) -> (schema as? JsonObject)?.get("default")?.let { name to it } }.toMap().toMutableMap()
        arguments[key] = if (key == "urls") JsonArray(listOf(JsonPrimitive(url))) else JsonPrimitive(url)
        val result = JsonObject(arguments)
        return result.takeIf { dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(it, definition.inputSchema).isEmpty() }
    }

    fun inspectsEvidence(definition: AgentToolDefinition): Boolean = readsUrl(definition) || definition.name == "deep_research_read_paper"
}
