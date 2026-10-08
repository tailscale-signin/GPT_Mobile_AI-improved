package dev.chungjungsoo.gptmobile.data.amazon

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonHtmlParserTest {
    @Test
    fun productDetailsKeepImageBrandAvailabilityAndSpecifications() {
        val html = fixture("product-ca").replace(
            "</body>",
            """
            <img id="landingImage" src="https://m.media-amazon.com/images/I/small.jpg" data-old-hires="https://m.media-amazon.com/images/I/large.jpg">
            <div id="availability"><span>In stock</span></div>
            <a id="sellerProfileTriggerId">Acme Store</a>
            <div id="productOverview_feature_div"><table><tr><td>Brand</td><td>Acme</td></tr><tr><td>Colour</td><td>Blue</td></tr></table></div>
            <table id="productDetails_techSpec_section_1"><tr><th>Item Weight</th><td>200 g</td></tr></table>
            <div id="productDescription">Comfortable headphones</div>
            <div id="feature-bullets"><ul><li><span class="a-list-item">Noise cancelling</span></li></ul></div>
            </body>
            """.trimIndent()
        )
        val item = AmazonHtmlParser.product(html, "B000000001", canada, at).products.single()
        assertEquals("Acme", item.brand)
        assertEquals("In stock", item.availability)
        assertEquals("Acme Store", item.seller)
        assertEquals("https://m.media-amazon.com/images/I/large.jpg", item.imageUrl)
        assertEquals(mapOf("Brand" to "Acme", "Colour" to "Blue", "Item Weight" to "200 g"), item.specifications)
        assertEquals("Comfortable headphones", item.description)
        assertEquals(listOf("Noise cancelling"), item.features)
        assertTrue(item.toJson().containsKey("specifications"))
    }

    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val canada = AmazonFreeMarket.CANADA
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/amazon/$name.html")).readText()

    @Test
    fun searchKeepsVerifiedFactsAndDoesNotPromoteReferencePrices() {
        val result = AmazonHtmlParser.search(fixture("search-ca"), AmazonSearchRequest("headphones", canada), at)
        assertEquals(listOf("B000000001", "B000000002"), result.products.map { it.asin })
        val item = result.products.first()
        assertEquals(BigDecimal("89.99"), item.amount)
        assertEquals("CAD", item.currency)
        assertEquals(1234, item.reviewCount)
        assertEquals(4.5, item.rating)
        assertEquals(at, item.acquiredAt)
        assertFalse(item.toJson().containsKey("affiliateUrl"))
        assertEquals("search_page", AmazonProducts.text(item.toJson(), "sourceType"))
        assertEquals(AmazonReadError.PRICE_UNAVAILABLE, result.errors.single().code)
    }

    @Test
    fun sponsoredDuplicateDoesNotHideTheVerifiedOrganicResult() {
        val html = """
            <div data-component-type="s-search-result" data-asin="B000000001">
                <h2><a href="/dp/B000000001">Sponsored headphones</a></h2>
                <span class="puis-sponsored-label-text">Sponsored</span>
                <span class="a-price"><span class="a-offscreen">CAD 19.99</span></span>
            </div>
            <div data-component-type="s-search-result" data-asin="B000000001" data-sponsored="false">
                <h2><a href="/dp/B000000001">Organic headphones</a></h2>
                <span class="a-price"><span class="a-offscreen">CAD 89.99</span></span>
            </div>
        """.trimIndent()
        val result = AmazonHtmlParser.search(html, AmazonSearchRequest("headphones", canada), at)
        assertEquals("Organic headphones", result.products.single().title)
        assertEquals(BigDecimal("89.99"), result.products.single().amount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun localPageFiltersExcludeUnknownPricesAndSortOnlyRetrievedFacts() {
        val result = AmazonHtmlParser.search(fixture("search-ca"), AmazonSearchRequest("headphones", canada, includeSponsored = true, sort = "price_asc", maximum = BigDecimal("100")), at)
        assertEquals(listOf("B000000003", "B000000001"), result.products.map { it.asin })
        assertEquals(1, result.pagesFetched)
    }

    @Test
    fun bareDollarWithoutCurrencyContextNeverBecomesAFilterableAmount() {
        val html = fixture("search-ca").replace("<a id=\"icp-touch-link-cop\">CAD - Canadian Dollar</a>", "").replace("CDN$", "$")
        val result = AmazonHtmlParser.search(html, AmazonSearchRequest("headphones", canada), at)
        assertNull(result.products.first().amount)
        assertNull(result.products.first().currency)
        assertEquals("$89.99", result.products.first().price)
        assertTrue(result.errors.all { it.code == AmazonReadError.PRICE_UNAVAILABLE })
        val filtered = AmazonHtmlParser.search(html, AmazonSearchRequest("headphones", canada, maximum = BigDecimal("100")), at)
        assertTrue(filtered.products.isEmpty())
        assertEquals(result.products.map { it.asin }, filtered.unverifiedProducts.map { it.asin })
        assertTrue(filtered.hasProducts)
        assertEquals("partial", AmazonProducts.text(filtered.toJson("filter", canada), "status"))
    }

    @Test
    fun changedCurrencyAndUntrustedImageAreNotSilentlyRelabeled() {
        val html = fixture("search-ca").replace("CDN$89.99", "US$89.99").replace("https://m.media-amazon.com/images/I/synthetic.jpg", "https://evil.example/item.jpg")
        val item = AmazonHtmlParser.search(html, AmazonSearchRequest("headphones", canada), at).products.first()
        assertNull(item.amount)
        assertEquals("USD", item.currency)
        assertNull(item.imageUrl)
    }

    @Test
    fun productIdentityAndCorePriceAreRequired() {
        val item = AmazonHtmlParser.product(fixture("product-ca"), "B000000001", canada, at).products.single()
        assertEquals(BigDecimal("89.99"), item.amount)
        assertEquals("product_page", item.sourceType)
        assertCode(AmazonReadError.PARSE_CHANGED) { AmazonHtmlParser.product(fixture("product-ca"), "B000000099", canada, at) }
        val noCorePrice = fixture("product-ca").replace("corePriceDisplay_desktop_feature_div", "irrelevant-price")
        assertNull(AmazonHtmlParser.product(noCorePrice, "B000000001", canada, at).products.single().amount)
    }

    @Test
    fun wrongMarketplaceProductLinkCannotPassNormalization() {
        val html = fixture("search-ca").replace("href=\"/dp/B000000001\"", "href=\"https://www.amazon.com/dp/B000000001\"")
        val result = AmazonHtmlParser.search(html, AmazonSearchRequest("headphones", canada), at)
        assertFalse(result.products.any { it.asin == "B000000001" })
    }

    @Test
    fun robotCheckAndUnknownLayoutDoNotMasqueradeAsEmptySearch() {
        assertCode(AmazonReadError.CHALLENGE_REQUIRED) { AmazonHtmlParser.search("<title>Robot Check</title><form action='/errors/validateCaptcha'></form>", AmazonSearchRequest("headphones", canada), at) }
        assertCode(AmazonReadError.PARSE_CHANGED) { AmazonHtmlParser.search("<h1>Something went wrong</h1>", AmazonSearchRequest("headphones", canada), at) }
        assertTrue(AmazonHtmlParser.search("<div class='s-no-results'>No results for this query</div>", AmazonSearchRequest("headphones", canada), at).products.isEmpty())
    }

    @Test
    fun mobileCardsWithSplitPricesAndCurrencyMetadataRemainFilterable() {
        val html = """
            <div class="s-result-item" data-asin="B000000001" data-csa-c-currency="CAD">
                <a href="/dp/B000000001"><h2 aria-label="Mobile headphones"></h2></a>
                <span class="a-price" data-a-strike="true"><span class="a-offscreen">$199.99</span></span>
                <span class="a-price"><span class="a-price-symbol">$</span><span class="a-price-whole">89.</span><span class="a-price-fraction">09</span></span>
            </div>
        """.trimIndent()
        val result = AmazonHtmlParser.search(html, AmazonSearchRequest("headphones", canada, maximum = BigDecimal("100")), at)
        val product = result.products.single()
        assertEquals("Mobile headphones", product.title)
        assertEquals("$89.09", product.price)
        assertEquals(BigDecimal("89.09"), product.amount)
        assertEquals("CAD", product.currency)
        assertTrue(result.unverifiedProducts.isEmpty())
    }

    @Test
    fun unverifiedDiscoveryNeverIncludesAConfirmedOverBudgetListing() {
        val result = AmazonHtmlParser.search(fixture("search-ca"), AmazonSearchRequest("headphones", canada, maximum = BigDecimal("20")), at)
        assertTrue(result.products.isEmpty())
        assertEquals(listOf("B000000002"), result.unverifiedProducts.map { it.asin })
        assertEquals(AmazonReadError.PRICE_UNAVAILABLE, result.errors.single().code)
    }

    @Test
    fun mobileProductCorePriceUsesTheSameVerifiedPriceParser() {
        val html = fixture("product-ca").replace("corePriceDisplay_desktop_feature_div", "corePriceDisplay_mobile_feature_div")
            .replace("<span class=\"a-offscreen\">CDN$89.99</span>", "<span class=\"a-price-symbol\">CDN$</span><span class=\"a-price-whole\">89.</span><span class=\"a-price-fraction\">99</span>")
        val item = AmazonHtmlParser.product(html, "B000000001", canada, at).products.single()
        assertEquals(BigDecimal("89.99"), item.amount)
        assertEquals("CDN$89.99", item.price)
    }

    @Test
    fun sponsoredRedirectsRequireTheMatchingMarketAndAsin() {
        val request = AmazonSearchRequest("headphones", canada, includeSponsored = true)
        val html = fixture("search-ca").replace("href=\"/dp/B000000001\"", "href=\"/sspa/click?url=%2Fdp%2FB000000001\"")
        val result = AmazonHtmlParser.search(html, request, at)
        assertTrue(result.products.first().sponsored == true)
        assertFalse(AmazonHtmlParser.search(html, request.copy(includeSponsored = false), at).products.any { it.asin == "B000000001" })
        for (target in listOf("https%3A%2F%2Fevil.example%2Fdp%2FB000000001", "%2Fdp%2FB000000099", "https%3A%2F%2Fwww.amazon.com%2Fdp%2FB000000001")) {
            assertFalse(AmazonHtmlParser.search(html.replace("%2Fdp%2FB000000001", target), request, at).products.any { it.asin == "B000000001" })
        }
    }

    private fun assertCode(code: AmazonReadError, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull() as? AmazonReadException
        assertEquals(code, error?.code)
    }
}
