package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Normalize only known retail operations; a SerpApi Google request stays a web result. */
internal class AmazonMcpResultTool private constructor(
    private val delegate: AgentTool,
    private val provider: String,
    private val remoteName: String,
    private val settings: PluginExecutionSettings
) : AgentTool {
    override val definition = if (provider == "SerpApi") {
        delegate.definition.copy(
            description = delegate.definition.description + " For Amazon products use params.engine=amazon, params.k=<query>, " +
                "params.amazon_domain=${settings.amazonMarketplace}; for details use params.engine=amazon_product and params.asin=<ASIN>."
        )
    } else {
        delegate.definition
    }
    override val managesExecutionBudget = delegate.managesExecutionBudget

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val params = arguments["params"] as? JsonObject ?: arguments
        val engine = AmazonProducts.text(params, "engine")
        val amazon = if (provider == "SerpApi") engine in setOf("amazon", "amazon_product") else remoteName in BRIGHT_DATA_TOOLS
        val boundedArguments = if (amazon && provider == "SerpApi" && settings.amazonFreshPrices) {
            JsonObject(arguments + ("params" to JsonObject(params + ("no_cache" to kotlinx.serialization.json.JsonPrimitive(true)))))
        } else {
            arguments
        }
        val result = delegate.execute(callId, boundedArguments)
        if (!amazon || result.isError) return result
        val domain = AmazonProducts.text(params, "amazon_domain", "domain", "url")?.let { AmazonProducts.marketplace(it) ?: AmazonProducts.marketplaceFromUrl(it) }
            ?: "amazon.com".takeIf { provider == "SerpApi" && "amazon_domain" !in params }
            ?: return result
        val payload = when (val content = result.content) {
            is ToolResultContent.Json -> content.value
            is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) }.getOrNull()
            is ToolResultContent.ResourceLinks -> null
        } ?: return result
        val data = AmazonProducts.unwrap(payload)
        if ((data as? JsonObject)?.containsKey("error") == true) {
            return result.copy(content = ToolResultContent.Text("The Amazon provider could not complete this request. Check the connection and provider account."), isError = true, traceContent = null, retainedContent = null)
        }
        val products = AmazonProducts.normalize(payload, domain, provider, includeSponsored = settings.amazonIncludeSponsored, maxResults = settings.searchResults)
        val emptySearch = (data as? JsonObject)?.get("organic_results")?.let { it is kotlinx.serialization.json.JsonArray && it.isEmpty() } == true ||
            (data is kotlinx.serialization.json.JsonArray && data.isEmpty())
        val sponsoredOnly = !settings.amazonIncludeSponsored && AmazonProducts.normalize(payload, domain, provider, includeSponsored = true, maxResults = 1).isNotEmpty()
        if (products.isEmpty() && !emptySearch && !sponsoredOnly) {
            return result.copy(content = ToolResultContent.Text("The Amazon provider returned no supported product facts for this marketplace. No prices were verified."), isError = true, traceContent = null, retainedContent = null)
        }
        val normalized = ToolResultContent.Json(
            buildJsonObject {
                put("schema", AmazonProducts.SCHEMA)
                put("provider", provider)
                put("marketplace", domain)
                put("products", kotlinx.serialization.json.JsonArray(products))
                put("notice", "Prices and availability are provider observations and may change at checkout.")
            }
        )
        return result.copy(content = normalized, traceContent = normalized, retainedContent = null)
    }

    companion object {
        private val BRIGHT_DATA_TOOLS = setOf("web_data_amazon_product_search", "web_data_amazon_product")

        fun wrap(delegate: AgentTool, endpoint: String, remoteName: String, settings: PluginExecutionSettings = PluginExecutionSettings()): AgentTool {
            val host = runCatching { URI(endpoint).host }.getOrNull()
            val provider = when {
                host == "mcp.serpapi.com" && remoteName == "search" -> "SerpApi"
                host == "mcp.brightdata.com" && remoteName in BRIGHT_DATA_TOOLS -> "Bright Data"
                else -> return delegate
            }
            return AmazonMcpResultTool(delegate, provider, remoteName, settings.normalized())
        }
    }
}
