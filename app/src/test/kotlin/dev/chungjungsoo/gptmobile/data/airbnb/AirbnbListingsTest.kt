package dev.chungjungsoo.gptmobile.data.airbnb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirbnbListingsTest {
    private fun parse(value: String) = Json.parseToJsonElement(value)
    private val arguments = parse("""{"checkin":"2026-11-01","checkout":"2026-11-05","adults":2}""") as JsonObject

    @Test fun coordinatesMustComeFromTheSameObject() {
        val paired = AirbnbListings.normalize(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"123","title":"Stay","location":{"coordinate":{"latitude":43.1,"longitude":-79.1}}}""")).single()
        assertEquals(43.1, paired.latitude!!, 0.0)
        assertEquals(-79.1, paired.longitude!!, 0.0)
        val unrelated = AirbnbListings.normalize(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"123","title":"Stay","a":{"latitude":43.1},"b":{"longitude":-79.1}}""")).single()
        assertEquals(null, unrelated.latitude)
        assertEquals(null, unrelated.longitude)
    }

    @Test fun openSourceSearchRetainsGalleryDatesAndExplicitFeeLinesWhileDeduplicating() {
        val payload = parse(
            """{"searchResults":[
            {"id":"123","url":"https://www.airbnb.com/rooms/123","demandStayListing":{"id":"TGlzdGluZzoxMjM="},"structuredContent":{"primaryLine":{"body":"Lake cottage"}},"structuredDisplayPrice":{"primaryLine":{"accessibilityLabel":"CAD 600 total"},"priceDetails":[{"description":"Cleaning fee","priceString":"CAD 50"}]},"photos":["https://a0.muscache.com/im/pictures/a.jpg"]},
            {"id":"123","name":"Lake cottage","photos":["https://a0.muscache.com/im/pictures/b.jpg"]}
        ]}"""
        )
        val listing = AirbnbListings.normalize(payload, arguments).single()
        assertEquals("123", listing.id)
        assertEquals("Lake cottage", listing.title)
        assertEquals(2, listing.photos.size)
        assertEquals(4, listing.nights)
        assertEquals(2, listing.adults)
        assertEquals("CAD 600 total", listing.totalPrice)
        assertEquals(listOf("Cleaning fee: CAD 50"), listing.feeLines)
        assertEquals(0, listing.reviewAnalysis.sampleSize)
    }

    @Test fun detailSectionsEnrichSearchWithoutInventingPriceOrSentiment() {
        val payload = parse(
            """{"listingUrl":"https://www.airbnb.com/rooms/123","details":[
            {"id":"TITLE_DEFAULT","title":"Lake cottage"},
            {"id":"DESCRIPTION_DEFAULT","htmlText":"<p>Quiet retreat.</p>"},
            {"id":"GPTMOBILE_MEDIA","photos":["https://a0.muscache.com/im/pictures/a.jpg"],"reviews":[{"comments":"Great location but dirty sheets and hidden fees."},{"comments":"Clean room, no bed bugs."}]}
        ]}"""
        )
        val listing = AirbnbListings.normalize(payload, arguments).single()
        assertEquals("Quiet retreat.", listing.description)
        assertEquals(2, listing.reviewAnalysis.sampleSize)
        assertEquals(2, listing.reviewAnalysis.positiveSignals)
        assertEquals(1, listing.reviewAnalysis.negativeSignals)
        assertTrue(listing.reviewAnalysis.redFlags.any { it.startsWith("Unexpected fees") })
        assertFalse(listing.reviewAnalysis.redFlags.any { it.startsWith("Pests") })
        assertNull(listing.price)
        assertTrue(listing.feeLines.isEmpty())
    }

    @Test fun serializedCardsCannotInjectExternalLinksOrInvalidCoordinates() {
        val listing = AirbnbListing("123", "Cottage", "https://www.airbnb.com/rooms/123", photos = listOf("https://evil.example/photo.jpg", "https://a0.muscache.com/im/pictures/a.jpg"), latitude = 100.0, checkin = "bad date")
        val retained = AirbnbListings.normalize(AirbnbListings.json(listOf(listing))).single()
        assertEquals(1, retained.photos.size)
        assertNull(retained.latitude)
        assertNull(retained.checkin)
        assertTrue(AirbnbListings.normalize(AirbnbListings.json(listOf(listing.copy(url = "https://airbnb.com.evil.example/rooms/123")))).isEmpty())
        assertNull(AirbnbListings.photoUrl("https://user@a0.muscache.com/im/pictures/a.jpg"))
    }

    @Test fun differentTravelDatesAreNotDeduplicatedIntoOneOffer() {
        val listing = AirbnbListing("123", "Cottage", "https://www.airbnb.com/rooms/123", checkin = "2026-11-01", checkout = "2026-11-05", totalPrice = "CAD 600")
        assertEquals(2, AirbnbListings.normalize(AirbnbListings.json(listOf(listing, listing.copy(checkin = "2026-12-01", checkout = "2026-12-05", totalPrice = "CAD 800")))).size)
    }
}
