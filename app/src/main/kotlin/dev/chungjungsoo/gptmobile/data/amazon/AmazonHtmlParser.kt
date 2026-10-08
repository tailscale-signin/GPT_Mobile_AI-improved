package dev.chungjungsoo.gptmobile.data.amazon

import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Selected extraction patterns adapted from JanNafta/amazon-mcp; see assets/licenses/amazon-mcp.txt. */
object AmazonHtmlParser {
    /** Called inside the request reservation so a blocked response stops queued requests before I/O. */
    fun checkForChallenge(html: String) = rejectChallenge(Jsoup.parse(html))

    fun search(html: String, request: AmazonSearchRequest, acquiredAt: Instant): AmazonFetchResult {
        val document = Jsoup.parse(html)
        rejectChallenge(document)
        val currency = currencyContext(document, request.marketplace)
        val cards = document.select("div[data-component-type=s-search-result][data-asin]").take(100)
        if (cards.isEmpty()) {
            val empty = document.selectFirst(".s-no-results") != null ||
                document.select(".s-main-slot .s-no-outline").any { it.text().startsWith("No results for", true) }
            if (empty) return AmazonFetchResult(emptyList())
            throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon did not return a recognized product-results page.")
        }
        val verified = cards.mapNotNull { card ->
            val id = AmazonProducts.asin(card.attr("data-asin")) ?: return@mapNotNull null
            val title = firstText(card, "h2 a span", "h2 span", "[data-cy=title-recipe] span", "h2") ?: return@mapNotNull null
            val link = card.selectFirst("a:has(h2)") ?: card.selectFirst("h2 a") ?: card.selectFirst("a[href*=/dp/]")
            if (link != null && !matchingLink(link.attr("href"), request.marketplace, id)) return@mapNotNull null
            val sponsored = when {
                card.selectFirst(".puis-sponsored-label-text, .s-sponsored-label-info-icon, [data-component-type=sp-sponsored-result]") != null -> true
                card.attr("data-sponsored") == "false" -> false
                else -> null
            }
            observation(card, id, title, request.marketplace, acquiredAt, "search_page", currency, sponsored)
        }
        if (verified.isEmpty()) {
            throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon product cards could not be verified in this page layout.")
        }
        // A page full of verified sponsored products is a valid filtered empty result.
        val products = verified.filter { request.includeSponsored || it.sponsored != true }.distinctBy { it.asin }
        val filtered = products.filter { item ->
            if (request.minimum == null && request.maximum == null) {
                true
            } else {
                item.currency == request.marketplace.currency &&
                    item.amount != null &&
                    (request.minimum == null || item.amount >= request.minimum) &&
                    (request.maximum == null || item.amount <= request.maximum)
            }
        }
        val sorted = when (request.sort) {
            "price_asc" -> filtered.sortedWith(compareBy<AmazonProductObservation> { it.amount == null || it.currency != request.marketplace.currency }.thenBy { it.amount })
            "price_desc" -> filtered.sortedWith(compareBy<AmazonProductObservation> { it.amount == null || it.currency != request.marketplace.currency }.thenByDescending { it.amount })
            "rating" -> filtered.sortedByDescending { it.rating ?: -1.0 }
            else -> filtered
        }.take(request.maxResults)
        return AmazonFetchResult(sorted, priceFailures(sorted))
    }

    fun product(html: String, asin: String, market: AmazonFreeMarket, acquiredAt: Instant): AmazonFetchResult {
        val document = Jsoup.parse(html)
        rejectChallenge(document)
        val pageAsin = document.selectFirst("input[name=ASIN]")?.attr("value")?.let(AmazonProducts::asin)
            ?: document.selectFirst("#dp[data-asin]")?.attr("data-asin")?.let(AmazonProducts::asin)
        val canonical = document.selectFirst("link[rel=canonical]")?.attr("href")
        if ((pageAsin != null && pageAsin != asin) ||
            (canonical != null && !matchingLink(canonical, market, asin)) ||
            (pageAsin == null && canonical == null)
        ) {
            throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon did not confirm the requested product identity.")
        }
        val title = firstText(document, "#productTitle", "h1#title span")
            ?: throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon did not return a recognized product-details page.")
        // Restrict price selection to the product's core price, never recommendations or struck-out list prices.
        val priceScope = document.selectFirst("#corePriceDisplay_desktop_feature_div, #corePrice_feature_div, #corePrice_desktop")
        val item = observation(document, asin, title, market, acquiredAt, "product_page", currencyContext(document, market), null, priceScope).copy(
            description = firstText(document, "#productDescription")?.take(4000),
            features = document.select("#feature-bullets li span.a-list-item").map { it.text().trim() }.filter { it.isNotBlank() }.take(10).map { it.take(500) }
        )
        return AmazonFetchResult(listOf(item), priceFailures(listOf(item)))
    }

    private fun observation(
        element: Element,
        asin: String,
        title: String,
        market: AmazonFreeMarket,
        acquiredAt: Instant,
        sourceType: String,
        contextCurrency: String?,
        sponsored: Boolean?,
        priceScope: Element? = element
    ): AmazonProductObservation {
        val raw = priceScope?.let { firstText(it, ".a-price:not(.a-text-price) .a-offscreen") }
            ?: if (sourceType == "product_page") firstText(element, "#priceblock_ourprice", "#priceblock_dealprice", "#price_inside_buybox") else null
        val explicitCurrency = raw?.let(::explicitCurrency)
        val currency = explicitCurrency ?: contextCurrency
        // Ambiguous bare dollars retain their display text, without a numeric amount usable for alerts/filters.
        val amount = if (currency == market.currency) raw?.let(::decimalPrice) else null
        val ratingText = firstText(element, "#acrPopover .a-icon-alt", "i.a-icon-star-small .a-icon-alt", "i.a-icon-star .a-icon-alt")
        val rating = ratingText?.let { Regex("^([0-5](?:\\.[0-9]+)?) out of 5 stars$", RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?.takeIf { it.isFinite() && it in 0.0..5.0 }
        val countText = firstText(element, "#acrCustomerReviewText", "a[href*=customerReviews] .s-underline-text", "a[aria-label*=ratings] span")
        val count = countText?.let { Regex("^([0-9][0-9,]*)(?: (?:ratings|reviews))?$", RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
        val image = element.selectFirst("img.s-image, #landingImage, #imgBlkFront")?.attr("src")?.let(AmazonProducts::imageUrl)
        return AmazonProductObservation(
            asin, market, title.take(300), acquiredAt, sourceType,
            price = raw?.take(80), amount = amount, currency = currency,
            rating = rating, reviewCount = count, imageUrl = image, sponsored = sponsored,
            prime = true.takeIf { element.selectFirst("i.a-icon-prime, [aria-label=\"Amazon Prime\"]") != null }
        )
    }

    private fun currencyContext(document: Element, market: AmazonFreeMarket): String? {
        val metadata = document.select("meta[property=product:price:currency], meta[itemprop=priceCurrency]")
            .map { it.attr("content").uppercase(Locale.ROOT) }.distinct()
        if (metadata.size == 1) return metadata.single().takeIf { it in setOf("CAD", "USD") }
        val selector = document.selectFirst("#icp-touch-link-cop")?.text().orEmpty()
        return Regex("\\b${market.currency}\\b").find(selector)?.let { market.currency }
    }

    private fun explicitCurrency(raw: String): String? = when {
        Regex("(?:\\bCAD\\b|CDN[$]|CA[$]|C[$])", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "CAD"
        Regex("(?:\\bUSD\\b|US[$])", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "USD"
        else -> null
    }

    private fun decimalPrice(raw: String): BigDecimal? {
        val amount = raw.replace(Regex("(?i)(CAD|USD|CDN|CA|US|C)?[$]|\\b(?:CAD|USD)\\b"), "").trim()
        if (!Regex("(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,9})(?:\\.[0-9]{2})?").matches(amount)) return null
        return amount.replace(",", "").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("1000000000") }
    }

    private fun matchingLink(raw: String, market: AmazonFreeMarket, asin: String): Boolean = runCatching {
        val uri = URI("https://www.${market.domain}").resolve(raw)
        AmazonProducts.marketplaceFromUrl(uri.toString()) == market.domain &&
            Regex("/(?:dp|gp/product)/${Regex.escape(asin)}(?:/|$)").containsMatchIn(uri.path.orEmpty())
    }.getOrDefault(false)

    private fun rejectChallenge(document: Element) {
        if (document.selectFirst("form[action*=validateCaptcha], input#captchacharacters, #auth-captcha-image") != null ||
            document.selectFirst("title")?.text()?.let { it.contains("Robot Check", true) || it.contains("Amazon CAPTCHA", true) || it.contains("Amazon Sign-In", true) } == true
        ) {
            throw AmazonReadException(AmazonReadError.CHALLENGE_REQUIRED, "Amazon blocked this lookup. Public-page access is temporarily unavailable.")
        }
    }

    private fun priceFailures(items: List<AmazonProductObservation>): List<AmazonItemFailure> = items.filter { it.amount == null || it.currency == null }.map {
        AmazonItemFailure(AmazonReadError.PRICE_UNAVAILABLE, "A price in a confirmed currency was not available; other product facts are retained.", it.asin)
    }

    private fun firstText(element: Element, vararg selectors: String): String? = selectors.firstNotNullOfOrNull { selector ->
        element.selectFirst(selector)?.text()?.trim()?.takeIf(String::isNotBlank)
    }
}
