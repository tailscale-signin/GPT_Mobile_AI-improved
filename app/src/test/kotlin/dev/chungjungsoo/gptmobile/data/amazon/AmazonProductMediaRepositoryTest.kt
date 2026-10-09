package dev.chungjungsoo.gptmobile.data.amazon

import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmazonProductMediaRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val images = mockk<AmazonProductImageProvider>()
    private val native = mockk<AmazonHtmlProvider>()
    private val access = mockk<AmazonAccessPolicy>()
    private val settings = mockk<SettingRepository>()
    private val tools = mockk<AgentToolResolver>()
    private val product = Json.parseToJsonElement("""{"asin":"B000000001","marketplace":"amazon.ca","title":"Headphones","price":"CAD 50"}""") as JsonObject
    private val url = "https://m.media-amazon.com/images/I/product.jpg"
    private val bytes = byteArrayOf(-1, -40, -1, 0)

    private fun repository(): AmazonProductMediaRepository {
        coEvery { access.mediaAllowed("owner") } returns true
        coEvery { access.allowed("owner", true) } returns true
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        coEvery { images.fetch(url, any()) } returns bytes
        coEvery { tools.amazonProductDetails(any(), any(), any()) } returns null
        return AmazonProductMediaRepository(AmazonProductMediaCache(temporary.newFolder(), { AmazonMediaConversation.ACTIVE }, images), images, native, access, settings, tools)
    }

    @Test fun preloadAndPopupShareThePhotoWithoutRepeatingACompletedDownload() = runBlocking {
        val repository = repository()
        val listing = Json.parseToJsonElement(product.toString().dropLast(1) + ",\"imageUrl\":\"$url\"}") as JsonObject
        assertNotNull(repository.photo("owner", 7, listing).image)
        assertNotNull(repository.photo("owner", 7, listing).image)
        coVerify(exactly = 1) { images.fetch(url, any()) }
        coVerify(exactly = 0) { native.products(any(), any()) }
    }

    @Test fun missingListingPhotoIsEnrichedOnceAndNeverReplacesTheSelectedOffer() = runBlocking {
        val repository = repository()
        val details = AmazonProductObservation("B000000001", AmazonFreeMarket.CANADA, "Other offer title", Instant.EPOCH, "product_page", price = "CAD 99", imageUrl = url, brand = "Acme")
        coEvery { native.products(any(), any()) } returns AmazonFetchResult(listOf(details))
        val warm = async { repository.photo("owner", 7, product) }
        val popup = async { repository.details("owner", 7, product) }
        val photo = warm.await()
        assertTrue(bytes.contentEquals(photo.image))
        assertEquals("CAD 50", AmazonProducts.text(photo.product, "price"))
        assertEquals("Headphones", AmazonProducts.text(photo.product, "title"))
        assertEquals("Acme", AmazonProducts.text(popup.await(), "brand"))
        coVerify(exactly = 1) { native.products(any(), any()) }
        coVerify(exactly = 1) { images.fetch(url, any()) }
    }

    @Test fun cachedDetailsWithoutAWorkingImageRefreshTheFailedGallery() = runBlocking {
        val repository = repository()
        val bad = "https://m.media-amazon.com/images/I/bad.jpg"
        coEvery { images.fetch(bad, any()) } returns null
        val stale = AmazonProductObservation("B000000001", AmazonFreeMarket.CANADA, "Headphones", Instant.EPOCH, "product_page", price = "CAD 99", imageUrl = bad)
        val fresh = stale.copy(imageUrl = url)
        coEvery { native.products(any(), any()) } returnsMany listOf(AmazonFetchResult(listOf(stale)), AmazonFetchResult(listOf(fresh)))
        repository.details("owner", 7, product)
        val photo = repository.photo("owner", 7, product)
        assertNotNull(photo.image)
        assertEquals("CAD 50", AmazonProducts.text(photo.product, "price"))
        coVerify(exactly = 2) { native.products(any(), any()) }
    }

    @Test fun disabledProfileDoesNotPreloadAnyRemoteMedia() = runBlocking {
        val repository = repository()
        coEvery { access.mediaAllowed("owner") } returns false
        assertEquals(product, repository.photo("owner", 7, product).product)
        assertEquals(product, repository.details("owner", 7, product))
        coVerify(exactly = 0) { images.fetch(any(), any()) }
        coVerify(exactly = 0) { native.products(any(), any()) }
        coVerify(exactly = 0) { tools.amazonProductDetails(any(), any(), any()) }
    }

    @Test fun unavailablePrimaryImageFallsBackToProviderGallery() = runBlocking {
        val repository = repository()
        val unavailable = "https://m.media-amazon.com/images/I/missing.jpg"
        coEvery { images.fetch(unavailable, any()) } returns null
        val listing = Json.parseToJsonElement(product.toString().dropLast(1) + ",\"imageUrl\":\"$unavailable\",\"images\":[{\"hiRes\":\"$url\"}]}") as JsonObject
        assertNotNull(repository.photo("owner", 7, listing).image)
        coVerify(exactly = 1) { images.fetch(unavailable, any()) }
        coVerify(exactly = 1) { images.fetch(url, any()) }
        coVerify(exactly = 0) { native.products(any(), any()) }
    }
}
