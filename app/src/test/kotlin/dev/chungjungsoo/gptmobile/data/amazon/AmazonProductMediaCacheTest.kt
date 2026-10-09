package dev.chungjungsoo.gptmobile.data.amazon

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmazonProductMediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val images = mockk<AmazonProductImageProvider>()
    private val product = Json.parseToJsonElement("""{"asin":"B000000001","marketplace":"amazon.ca","title":"Headphones","price":"CAD 50","imageUrl":"https://m.media-amazon.com/images/I/product.jpg"}""") as JsonObject
    private val bytes = byteArrayOf(-1, -40, -1, 0)

    @Test fun photoSurvivesReopeningAndKeepsTheCurrentOfferUntilConversationIsArchived() = runBlocking {
        val root = temporary.newFolder()
        var available = AmazonMediaConversation.ACTIVE
        coEvery { images.evict(any()) } returns Unit
        val cache = AmazonProductMediaCache(root, { available }, images)
        cache.save(7, AmazonProductMedia(product, bytes, detailed = true))
        val reopened = AmazonProductMediaCache(root, { available }, images)
        val newOffer = Json.parseToJsonElement(product.toString().replace("CAD 50", "CAD 40")) as JsonObject
        val loaded = reopened.load(7, newOffer)
        assertNotNull(loaded)
        assertTrue(bytes.contentEquals(loaded?.image))
        assertEquals("CAD 40", AmazonProducts.text(requireNotNull(loaded).product, "price"))
        available = AmazonMediaConversation.UNAVAILABLE
        reopened.clearConversation(7)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        coVerify { images.evict(listOf("https://m.media-amazon.com/images/I/product.jpg")) }
        reopened.save(7, AmazonProductMedia(product, bytes))
        assertNull(reopened.load(7, product))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun temporaryConversationsNeverPersistProductPhotos() = runBlocking {
        val root = temporary.newFolder()
        val cache = AmazonProductMediaCache(root, { AmazonMediaConversation.TEMPORARY }, images)
        assertTrue(cache.available(7))
        cache.save(7, AmazonProductMedia(product, bytes))
        assertNull(cache.load(7, product))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun metadataUpdatesPreservePreloadedPhotoAndDistinctVariantsStaySeparate() = runBlocking {
        val cache = AmazonProductMediaCache(temporary.newFolder(), { AmazonMediaConversation.ACTIVE }, images)
        cache.save(7, AmazonProductMedia(product, bytes))
        val details = Json.parseToJsonElement(product.toString().dropLast(1) + ",\"brand\":\"Acme\"}") as JsonObject
        cache.save(7, AmazonProductMedia(details, detailed = true))
        val loaded = requireNotNull(cache.load(7, product))
        assertTrue(bytes.contentEquals(loaded.image))
        assertEquals("Acme", AmazonProducts.text(loaded.product, "brand"))
        assertTrue(loaded.detailed)
        val variant = Json.parseToJsonElement(product.toString().dropLast(1) + ",\"variant\":\"Blue\"}") as JsonObject
        assertNull(cache.load(7, variant))
    }

    @Test fun replacementImageMetadataDoesNotDiscardTheWorkingPhotoBeforeDownload() = runBlocking {
        val cache = AmazonProductMediaCache(temporary.newFolder(), { AmazonMediaConversation.ACTIVE }, images)
        cache.save(7, AmazonProductMedia(product, bytes))
        val updated = Json.parseToJsonElement(product.toString().replace("product.jpg", "replacement.jpg")) as JsonObject
        cache.save(7, AmazonProductMedia(updated, detailed = true))
        assertTrue(bytes.contentEquals(cache.load(7, updated)?.image))
        assertEquals("https://m.media-amazon.com/images/I/replacement.jpg", cache.load(7, updated)?.product?.let(AmazonProducts::productImageUrl))
    }
}
