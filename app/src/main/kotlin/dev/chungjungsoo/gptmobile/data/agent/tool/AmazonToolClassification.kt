package dev.chungjungsoo.gptmobile.data.agent.tool

private val AMAZON_MARKET = Regex("(?i)\\bamazon\\b")
private val AMAZON_ASIN = Regex("(?i)\\bB0[A-Z0-9]{8}\\b")
private val RETAIL_INTENT = Regex("(?i)\\b(?:product|products|shopping|shop|buy|purchase|price|prices|cost|under|deal|deals|asin|stock|reviews?|find|search|compare|available|availability|watch(?:es)?|watchlist|track(?:ing)?)\\b")
private val NON_RETAIL_AMAZON = Regex("(?i)\\b(?:aws|bedrock|ec2)\\b|amazon\\s+(?:web services|s3|lambda|river|rainforest|prime video|music)")

internal fun isAmazonShoppingTask(task: String): Boolean =
    !NON_RETAIL_AMAZON.containsMatchIn(task) &&
        (AMAZON_ASIN.containsMatchIn(task) || (AMAZON_MARKET.containsMatchIn(task) && RETAIL_INTENT.containsMatchIn(task)))

internal fun ResolvedAgentTool.isAmazonProductTool(): Boolean =
    realToolName in setOf(AmazonSearchTool.SEARCH, AmazonSearchTool.GET_PRODUCTS, "amazon_get_price_history", "web_data_amazon_product_search", "web_data_amazon_product") ||
        tool.definition.description.contains("For Amazon products use params.engine=amazon") ||
        tool.definition.description.contains(AmazonJanNaftaTool.MARKER)
