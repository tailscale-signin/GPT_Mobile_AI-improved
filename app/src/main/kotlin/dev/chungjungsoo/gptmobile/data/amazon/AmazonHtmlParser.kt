package dev.chungjungsoo.gptmobile.data.amazon

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
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
        val cards = document.select("[data-component-type=s-search-result][data-asin], .s-result-item[data-asin]").take(100)
        if (cards.isEmpty()) {
            val empty = document.selectFirst(".s-no-results") != null ||
                document.select(".s-main-slot .s-no-outline").any { it.text().startsWith("No results for", true) }
            if (empty) return AmazonFetchResult(emptyList())
            throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon did not return a recognized product-results page.")
        }
        val verified = cards.mapNotNull { card ->
            val id = AmazonProducts.asin(card.attr("data-asin")) ?: return@mapNotNull null
            val title = firstText(card, "h2 a span", "h2 span", "h2", "[data-cy=title-recipe] .a-text-normal")
                ?: card.selectFirst("h2[aria-label]")?.attr("aria-label")?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val link = card.selectFirst("a:has(h2)") ?: card.selectFirst("h2 a") ?: card.selectFirst("a[href*=/dp/]")
            if (link != null && !matchingLink(link.attr("href"), request.marketplace, id)) return@mapNotNull null
            val sponsored = when {
                card.selectFirst(".puis-sponsored-label-text, .s-sponsored-label-info-icon, [data-component-type=sp-sponsored-result], a[href*=/sspa/click]") != null -> true
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
        val unverified = if (request.minimum != null || request.maximum != null) {
            products.filter { it.amount == null || it.currency != request.marketplace.currency }
                .take((request.maxResults - sorted.size).coerceAtLeast(0))
        } else {
            emptyList()
        }
        return AmazonFetchResult(sorted, priceFailures(sorted + unverified), unverifiedProducts = unverified)
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
        val priceScope = document.selectFirst("#corePriceDisplay_desktop_feature_div, #corePrice_feature_div, #corePrice_desktop, #corePriceDisplay_mobile_feature_div, #corePrice_mobile_feature_div")
        val specifications = document.select("#productOverview_feature_div tr, #productDetails_techSpec_section_1 tr, #productDetails_detailBullets_sections1 tr")
            .mapNotNull { row ->
                val label = row.selectFirst("th, td:first-child")?.text()?.replace("\u200e", "")?.trim()?.trimEnd(':')?.take(80)
                val value = row.selectFirst("td:last-child")?.text()?.trim()?.take(500)
                if (label.isNullOrBlank() || value.isNullOrBlank() || label == value) null else label to value
            }.distinctBy { it.first.lowercase(Locale.ROOT) }.take(24).toMap()
        val item = observation(document, asin, title, market, acquiredAt, "product_page", currencyContext(document, market), null, priceScope).copy(
            description = document.selectFirst("#productDescription")?.text()?.takeIf { it.isNotBlank() }?.take(4000),
            features = document.select("#feature-bullets li span.a-list-item").map { it.text().trim().take(500) }.filter { it.isNotBlank() }.distinct().take(12),
            brand = specifications.entries.firstOrNull { it.key.equals("Brand", true) || it.key.equals("Manufacturer", true) }?.value,
            availability = firstText(document, "#availability span", "#availabilityInsideBuyBox_feature_div span"),
            seller = firstText(document, "#sellerProfileTriggerId", "#merchantInfoFeature_feature_div #sellerProfileTriggerId"),
            specifications = specifications
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
        val raw = priceScope?.let(::displayPrice)
            ?: if (sourceType == "product_page") firstText(element, "#priceblock_ourprice", "#priceblock_dealprice", "#price_inside_buybox") else null
        val explicitCurrency = raw?.let(::explicitCurrency)
        val currency = explicitCurrency ?: priceScope?.let(::currencyMetadata) ?: contextCurrency
        // Ambiguous bare dollars retain their display text, without a numeric amount usable for alerts/filters.
        val amount = if (currency == market.currency) raw?.let(::decimalPrice) else null
        val ratingText = firstText(element, "#acrPopover .a-icon-alt", "i.a-icon-star-small .a-icon-alt", "i.a-icon-star .a-icon-alt")
        val rating = ratingText?.let { Regex("^([0-5](?:\\.[0-9]+)?) out of 5 stars$", RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?.takeIf { it.isFinite() && it in 0.0..5.0 }
        val countText = firstText(element, "#acrCustomerReviewText", "a[href*=customerReviews] .s-underline-text", "a[aria-label*=ratings] span")
        val count = countText?.let { Regex("^([0-9][0-9,]*)(?: (?:ratings|reviews))?$", RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
        val image = element.selectFirst("img.s-image, #landingImage, #imgBlkFront")?.let { image ->
            AmazonProducts.imageUrl(image.attr("data-old-hires")) ?: AmazonProducts.imageUrl(image.attr("src"))
        }
        return AmazonProductObservation(
            asin, market, title.take(300), acquiredAt, sourceType,
            price = raw?.take(80), amount = amount, currency = currency,
            rating = rating, reviewCount = count, imageUrl = image, sponsored = sponsored,
            prime = true.takeIf { element.selectFirst("i.a-icon-prime, [aria-label=\"Amazon Prime\"]") != null }
        )
    }

    private fun currencyContext(document: Element, market: AmazonFreeMarket): String? {
        currencyMetadata(document)?.let { return it }
        val selector = document.selectFirst("#icp-touch-link-cop")?.text().orEmpty()
        return Regex("\\b${market.currency}\\b").find(selector)?.let { market.currency }
    }

    private fun currencyMetadata(element: Element): String? {
        val values = element.select("meta[property=product:price:currency], [itemprop=priceCurrency], [data-csa-c-currency], [data-currency-code]")
            .map { it.attr("content").ifBlank { it.attr("data-csa-c-currency") }.ifBlank { it.attr("data-currency-code") }.ifBlank { it.text() }.trim().uppercase(Locale.ROOT) }
            .filter { it in setOf("CAD", "USD") }.distinct()
        return values.singleOrNull()
    }

    private fun displayPrice(element: Element): String? {
        // Mobile cards can split the visible price without supplying an a-offscreen span.
        val price = element.select(".a-price:not(.a-text-price):not([data-a-strike=true])").firstOrNull() ?: return null
        firstText(price, ".a-offscreen")?.let { return it }
        val whole = firstText(price, ".a-price-whole")?.trimEnd('.') ?: return null
        val fraction = firstText(price, ".a-price-fraction")
        if (!Regex("[0-9][0-9,]*").matches(whole) || (fraction != null && !Regex("[0-9]{2}").matches(fraction))) return null
        return firstText(price, ".a-price-symbol").orEmpty() + whole + if (fraction != null) ".$fraction" else ""
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
        var uri = URI("https://www.${market.domain}").resolve(raw)
        if (AmazonProducts.marketplaceFromUrl(uri.toString()) != market.domain) return@runCatching false
        if (uri.path == "/sspa/click") {
            val targets = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                .filter { it.first() == "url" }
            if (targets.size != 1) return@runCatching false
            uri = uri.resolve(URLDecoder.decode(targets.single().getOrElse(1) { "" }, "UTF-8"))
        }
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
