package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Adapts the pinned upstream's text products without changing history, watch or link responses. */
internal class AmazonJanNaftaTool(
    private val delegate: AgentTool,
    private val remoteName: String,
    settings: PluginExecutionSettings
) : AgentTool {
    private val settings = settings.normalized()
    override val definition = delegate.definition.copy(description = "$MARKER ${delegate.definition.description} Prices may come from the host cache. Price watches are checked on demand on the host, not background phone alerts.")
    override val managesExecutionBudget = delegate.managesExecutionBudget

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        var bounded = arguments
        if (remoteName != "list_price_watches" && remoteName != "remove_price_watch" && remoteName != "compare_marketplaces" && "marketplace" !in bounded) {
            MARKETS.entries.firstOrNull { it.value == settings.amazonMarketplace }?.key?.let { code -> bounded = JsonObject(bounded + ("marketplace" to JsonPrimitive(code))) }
        }
        if (remoteName in setOf("search_products", "get_deals")) {
            val limit = (bounded["limit"] as? JsonPrimitive)?.intOrNull ?: settings.searchResults
            bounded = JsonObject(bounded + ("limit" to JsonPrimitive(limit.coerceIn(1, 40).coerceAtMost(settings.searchResults.coerceIn(1, 40)))))
        }
        val result = delegate.execute(callId, bounded)
        if (result.isError || remoteName !in setOf("search_products", "get_product", "get_deals")) return result
        val text = (result.content as? ToolResultContent.Text)?.text ?: return result
        val requestedDomain = AmazonProducts.text(bounded, "marketplace")?.let(MARKETS::get)
        val blocks = if (remoteName == "get_product") listOf(text) else numberedProducts.findAll(text).map { it.groupValues[1] }.toList()
        val products = blocks.take(40).mapNotNull { block ->
            val lines = block.lines()
            val title = lines.firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val link = buyLink.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val domain = AmazonProducts.marketplaceFromUrl(link) ?: return@mapNotNull null
            if (requestedDomain != null && domain != requestedDomain) return@mapNotNull null
            val id = runCatching { asinPath.find(URI(link).path.orEmpty())?.groupValues?.get(1)?.let(AmazonProducts::asin) }.getOrNull() ?: return@mapNotNull null
            val quotedAsin = quotedAsin.find(block)?.groupValues?.get(1)?.let(AmazonProducts::asin)
            if (quotedAsin != null && quotedAsin != id) return@mapNotNull null
            val price = priceLine.find(lines.getOrElse(1) { "" }.trim())
            val rating = ratingLine.find(block)
            val item = buildJsonObject {
                put("asin", id)
                put("title", title)
                put("link", link)
                price?.groupValues?.get(1)?.takeIf(String::isNotEmpty)?.let { put("extracted_price", it.replace(",", "")) }
                price?.groupValues?.get(2)?.let { put("currency", it) }
                rating?.groupValues?.get(1)?.toDoubleOrNull()?.let { put("rating", it) }
                rating?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull()?.let { put("reviews", it) }
                if (block.contains("✓Prime")) put("prime", true)
                lines.firstOrNull { it.startsWith("Availability: ") }?.removePrefix("Availability: ")?.let { put("availability", it) }
            }
            AmazonProducts.normalize(item, domain, "Jan Nafta Amazon MCP", maxResults = 1).firstOrNull()?.let { product ->
                val affiliate = AmazonProducts.affiliateUrl(link, domain, id)
                if (affiliate != null) JsonObject(product + ("affiliateUrl" to JsonPrimitive(affiliate))) else product
            }
        }.take(settings.searchResults)
        if (products.isEmpty()) return result
        val content = ToolResultContent.Json(
            buildJsonObject {
                put("schema", AmazonProducts.SCHEMA)
                put("provider", "Jan Nafta Amazon MCP")
                put("products", JsonArray(products))
                put("sourceText", text)
                put("notice", "Prices may come from the host cache and may change at checkout. Tagged links support the host's configured Amazon Associate.")
            }
        )
        return result.copy(content = content, traceContent = content, retainedContent = null)
    }

    companion object {
        const val MARKER = "Amazon shopping service (JanNafta/amazon-mcp)."
        val TOOL_NAMES = setOf("search_products", "get_product", "get_price_history", "get_deals", "get_buy_link", "compare_marketplaces", "add_price_watch", "list_price_watches", "remove_price_watch")
        private val MARKETS = mapOf("US" to "amazon.com", "CA" to "amazon.ca", "UK" to "amazon.co.uk", "DE" to "amazon.de", "FR" to "amazon.fr", "IT" to "amazon.it", "ES" to "amazon.es", "JP" to "amazon.co.jp", "MX" to "amazon.com.mx", "IN" to "amazon.in", "BR" to "amazon.com.br", "AU" to "amazon.com.au")
        private val numberedProducts = Regex("(?ms)^\\d+\\. (.+?)(?=^\\d+\\. |\\z)")
        private val buyLink = Regex("(?m)^\\s*Buy \\(affiliate\\): (https://\\S+)$")
        private val asinPath = Regex("/(?:dp|gp/product)/([A-Z0-9]{10})(?:/|$)", RegexOption.IGNORE_CASE)
        private val quotedAsin = Regex("ASIN: ([A-Z0-9]{10})", RegexOption.IGNORE_CASE)
        private val priceLine = Regex("^(?:([0-9][0-9,]*\\.[0-9]{2})|—) ([A-Z]{3})(?:\\s|$)")
        private val ratingLine = Regex("★([0-9]+(?:\\.[0-9]+)?)(?:\\s*\\(([0-9,]+)(?: ratings)?\\))?")

        fun recognizes(connection: ToolConnection, toolNames: Set<String>): Boolean =
            ToolServiceCatalog.forConnection(connection).id == ToolPluginId.AMAZON_SEARCH && toolNames.containsAll(TOOL_NAMES)
    }
}
