package dev.chungjungsoo.gptmobile.data.amazon

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonHtmlParserTest {
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
        assertTrue(AmazonHtmlParser.search(html, AmazonSearchRequest("headphones", canada, maximum = BigDecimal("100")), at).products.isEmpty())
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

    private fun assertCode(code: AmazonReadError, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull() as? AmazonReadException
        assertEquals(code, error?.code)
    }
}
