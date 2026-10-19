package dev.chungjungsoo.gptmobile.data.universalmarketplace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalMarketplaceTest {
    private fun request(raw: String = """{"query":"graphics card","city":"Toronto","max_price_minor":80000,"radius_km":50}""") = MarketplaceContract.decode(Json.parseToJsonElement(raw).jsonObject)

    @Test fun noKeysOrGatewayProducesFourActionsAndZeroListings() = runBlocking {
        val result = UniversalMarketplaceCoordinator().search(request(), UniversalMarketplaceSettings(), { true })
        assertEquals("links_only", result.outcome)
        assertTrue(result.listings.isEmpty() && result.discoveries.isEmpty() && result.coverage.automaticallyQueried.isEmpty())
        assertEquals(4, result.searchActions.size)
        assertEquals(request().providers.toSet(), result.providerStatuses.map { it.provider }.toSet())
        assertTrue(result.searchActions.all { it.pendingFilters["radius_km"] == "50" && it.pendingFilters["maximum_price"] == "CAD 800.00" })
        assertTrue(result.searchActions.all { MarketplaceContract.safeUrl(it.provider, it.url) })
        assertEquals(result, MarketplaceContract.json.decodeFromString(MarketplaceSearchResult.serializer(), MarketplaceContract.json.encodeToString(MarketplaceSearchResult.serializer(), result)))
    }

    @Test fun strictArgumentsRejectUnsafeTypesUnknownFieldsAndInvalidCursors() {
        listOf(
            """{"query":""}""", """{"query":"x","endpoint":"https://evil.example"}""",
            """{"query":"x","max_price_minor":-1}""", """{"query":"x","max_price_minor":"80000"}""",
            """{"query":"x","max_price_minor":9223372036854775808}""", """{"query":"x","max_price_minor":1.5}""",
            """{"query":"x","providers":["unknown"]}""", """{"query":"x","providers":[]}""",
            """{"query":"x","country":"UK"}""", """{"query":"x","radius_km":1001}""",
            """{"query":"x","cursor":"another-query"}""", """{"query":"x","max_results":101}""",
            """{"query":"x\ny"}"""
        ).forEach { assertTrue(it, runCatching { request(it) }.isFailure) }
    }

    @Test fun disabledSourcesAndUnsupportedRegionsRemainExplicit() = runBlocking {
        val result = UniversalMarketplaceCoordinator().search(request().copy(country = "US"), UniversalMarketplaceSettings(enabledSources = listOf("ebay", "kijiji")), { true })
        assertEquals(1, result.searchActions.size)
        assertEquals("unsupported_region", result.providerStatuses.single { it.provider == "kijiji" }.state)
        assertEquals("disabled", result.providerStatuses.single { it.provider == "facebook" }.state)
        val unavailable = UniversalMarketplaceCoordinator().search(request(), UniversalMarketplaceSettings(enabledSources = emptyList()), { true })
        assertEquals("unavailable", unavailable.outcome)
        assertTrue(unavailable.searchActions.isEmpty())
    }

    @Test fun unknownCityDoesNotSilentlyBecomeTorontoOrAUsLocation() = runBlocking {
        val result = UniversalMarketplaceCoordinator().search(request().copy(city = "Small town"), UniversalMarketplaceSettings(), { true })
        val action = result.searchActions.single { it.provider == "craigslist" }
        assertEquals("https://www.craigslist.org/about/sites", action.url)
        assertEquals("Small town", action.pendingFilters["city"])
        assertEquals("graphics card", action.pendingFilters["query"])
    }

    @Test fun permissionIsCheckedBeforeEachSourceAndDeliveryAndCancellationPropagates() = runBlocking {
        var checks = 0
        assertTrue(runCatching { UniversalMarketplaceCoordinator().search(request(), UniversalMarketplaceSettings(), { ++checks < 3 }) }.isFailure)
        assertEquals(3, checks)
        val failure = runCatching { UniversalMarketplaceCoordinator().search(request(), UniversalMarketplaceSettings(), { throw CancellationException("stop") }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun moneyNeverTreatsUnknownShippingAsFreeOrAddsDifferentCurrencies() {
        val item = MarketplaceMoney(45000, "CAD")
        assertEquals(null, item.plus(null))
        assertEquals(null, item.plus(MarketplaceMoney(500, "USD")))
        assertEquals(MarketplaceMoney(45500, "CAD"), item.plus(MarketplaceMoney(500, "CAD")))
        assertEquals(null, MarketplaceMoney(Long.MAX_VALUE, "CAD").plus(MarketplaceMoney(1, "CAD")))
    }

    @Test fun queryIdentityIsStableAndNavigationRejectsUnexpectedOrigins() {
        val first = request().copy(providers = listOf("ebay", "facebook", "ebay"))
        assertEquals(first.fingerprint(), first.copy(providers = listOf("facebook", "ebay")).fingerprint())
        assertFalse(first.fingerprint() == first.copy(currency = "USD").fingerprint())
        listOf("javascript:alert(1)", "https://www.ebay.ca.evil.example/", "https://secret@www.ebay.ca/", "http://www.ebay.ca/", "https://www.ebay.ca:8080/").forEach { assertFalse(MarketplaceContract.safeUrl("ebay", it)) }
    }

    @Test fun responseValidationRejectsContradictoryCountsCoverageAndUnsafeActions() = runBlocking {
        val valid = UniversalMarketplaceCoordinator().search(request(), UniversalMarketplaceSettings(), { true })
        val invalid = listOf(
            valid.copy(outcome = "results"),
            valid.copy(searchActions = emptyList()),
            valid.copy(providerStatuses = valid.providerStatuses + valid.providerStatuses.first()),
            valid.copy(providerStatuses = valid.providerStatuses.map { it.copy(count = 1) }),
            valid.copy(coverage = valid.coverage.copy(exhaustive = true)),
            valid.copy(coverage = valid.coverage.copy(automaticallyQueried = listOf("ebay"))),
            valid.copy(searchActions = valid.searchActions.map { it.copy(url = "https://evil.example/") }),
            valid.copy(nextCursor = "unavailable-page"),
            valid.copy(capabilities = valid.capabilities.map { it.copy(monitoringAllowed = true) })
        )
        invalid.forEach { assertTrue(runCatching { it.validated() }.isFailure) }
        assertTrue(valid.capabilities.all { !it.monitoringAllowed && !it.pagination && !it.authenticationRequired })
    }
}
