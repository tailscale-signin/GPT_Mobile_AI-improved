package dev.chungjungsoo.gptmobile.data.airbnb

import java.net.URI
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import org.jsoup.Jsoup

@Serializable
data class AirbnbListing(
    val id: String,
    val title: String,
    val url: String,
    val photos: List<String> = emptyList(),
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rating: String? = null,
    val description: String? = null,
    val amenities: List<String> = emptyList(),
    val houseRules: List<String> = emptyList(),
    val price: String? = null,
    val totalPrice: String? = null,
    val priceLines: List<String> = emptyList(),
    val feeLines: List<String> = emptyList(),
    val checkin: String? = null,
    val checkout: String? = null,
    val adults: Int? = null,
    val children: Int? = null,
    val infants: Int? = null,
    val pets: Int? = null,
    val nights: Int? = null,
    val reviewAnalysis: AirbnbReviewAnalysis = AirbnbReviewAnalysis(),
    val provider: String = "OpenBnB"
)

@Serializable
data class AirbnbReviewAnalysis(
    val sampleSize: Int = 0,
    val positiveSignals: Int = 0,
    val negativeSignals: Int = 0,
    val redFlags: List<String> = emptyList(),
    val summary: String = "No review text supplied; sentiment and red flags are unknown."
)

/** Normalizes hosted and open-source results without inventing missing prices, photos, fees or reviews. */
object AirbnbListings {
    const val SCHEMA = "gptmobile.airbnb.v1"
    private val json = Json { ignoreUnknownKeys = true }

    fun listingUrl(value: String?): String? = runCatching {
        val uri = URI(value.orEmpty())
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1)
        require(uri.host in setOf("airbnb.com", "www.airbnb.com", "airbnb.ca", "www.airbnb.ca"))
        val id = Regex("^/rooms/([0-9]{1,30})/?$").matchEntire(uri.path)?.groupValues?.get(1) ?: return null
        "https://www.airbnb.com/rooms/$id"
    }.getOrNull()

    fun photoUrl(value: String?): String? = runCatching {
        val uri = URI(value.orEmpty())
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null && value!!.length <= 4096)
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        require(host == "muscache.com" || host.endsWith(".muscache.com"))
        uri.toASCIIString()
    }.getOrNull()

    fun unwrap(value: JsonElement, depth: Int = 0): JsonElement {
        if (depth >= 8) return value
        if (value is JsonPrimitive && value.isString && value.content.length <= 1_500_000) {
            return runCatching { unwrap(Json.parseToJsonElement(value.content), depth + 1) }.getOrDefault(value)
        }
        if (value is JsonArray && value.size == 1) return unwrap(value.first(), depth + 1)
        if (value is JsonObject) {
            for (key in listOf("structuredContent", "content", "result", "text", "data")) {
                if (key in value && value.keys.none { it in setOf("listings", "searchResults", "listingUrl", "details", "url", "id") }) return unwrap(value.getValue(key), depth + 1)
            }
        }
        return value
    }

    private fun objects(value: JsonElement, depth: Int = 0): List<JsonObject> {
        if (depth > 9) return emptyList()
        return when (value) {
            is JsonObject -> listOf(value) + value.values.take(80).flatMap { objects(it, depth + 1) }
            is JsonArray -> value.take(100).flatMap { objects(it, depth + 1) }
            else -> emptyList()
        }.take(3000)
    }

    fun normalize(payload: JsonElement, arguments: JsonObject = JsonObject(emptyMap())): List<AirbnbListing> {
        val raw = unwrap(payload)
        val root = raw as? JsonObject
        if (root?.get("schema") == JsonPrimitive(SCHEMA)) {
            return (root["listings"] as? JsonArray).orEmpty().take(100).mapNotNull {
                runCatching { json.decodeFromJsonElement(AirbnbListing.serializer(), it) }.getOrNull()?.let(::validated)
            }.groupBy(::stayKey).values.map { it.reduce(::merge) }.take(30)
        }
        val all = objects(raw)
        val candidates = all.filter { obj -> id(obj) != null && (text(obj, "title", "name") != null || "demandStayListing" in obj || "structuredContent" in obj || "structuredDisplayPrice" in obj) }
        val detailId = text(arguments, "id", "listing_id")?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            ?: listingUrl(root?.let { text(it, "listingUrl") })?.substringAfterLast('/')
        val selected = if (candidates.isNotEmpty()) {
            candidates
        } else if (detailId != null && root != null) {
            listOf(root)
        } else {
            emptyList()
        }
        return selected.take(100).mapNotNull { item ->
            val listingId = id(item) ?: detailId ?: return@mapNotNull null
            val descendants = objects(item)
            fun nested(vararg keys: String) = descendants.firstNotNullOfOrNull { text(it, *keys) }
            fun section(name: String): List<JsonObject> = descendants.filter { text(it, "id", "sectionId")?.contains(name, true) == true }.flatMap(::objects)
            val structured = item["structuredContent"] as? JsonObject
            val primary = structured?.get("primaryLine") as? JsonObject
            val title = text(item, "title", "name") ?: primary?.let { text(it, "body") } ?: section("TITLE").firstNotNullOfOrNull { text(it, "title") } ?: "Airbnb listing $listingId"
            fun guests(key: String, range: IntRange) = (text(arguments, key) ?: text(item, key))?.toIntOrNull()?.takeIf { it in range }
            val prices = (item["structuredDisplayPrice"] as? JsonObject)?.let(::objects).orEmpty()
            val priceLines = prices.mapNotNull { text(it, "accessibilityLabel", "priceString") }.distinct().take(15)
            val fees = prices.mapNotNull { obj ->
                val label = text(obj, "description") ?: return@mapNotNull null
                val value = text(obj, "priceString") ?: return@mapNotNull null
                "$label: $value"
            }.distinct().take(15)
            val reviewTexts = descendants.flatMap { obj ->
                (obj["reviews"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { text(it, "comments", "text", "review", "content") }
            }.distinct().take(50)
            val checkin = date(text(arguments, "checkin") ?: text(item, "checkin"))
            val checkout = date(text(arguments, "checkout") ?: text(item, "checkout"))
            val nights = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(checkin), LocalDate.parse(checkout)).toInt().takeIf { it in 1..365 } }.getOrNull()
            val latitude = descendants.firstNotNullOfOrNull { number(it, "latitude", "lat") }?.takeIf { it in -90.0..90.0 }
            val longitude = descendants.firstNotNullOfOrNull { number(it, "longitude", "lng", "lon") }?.takeIf { it in -180.0..180.0 }
            val photos = descendants.flatMap { obj ->
                listOf("picture", "image", "imageUrl", "image_url", "baseUrl", "src").mapNotNull { photoUrl(text(obj, it)) } +
                    listOf("photos", "images", "pictures", "contextualPictures").flatMap { key ->
                        (obj[key] as? JsonArray).orEmpty().mapNotNull {
                            photoUrl((it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.let { image -> text(image, "url", "picture", "src") })
                        }
                    }
            }.distinct().take(20)
            val locationSection = section("LOCATION").firstOrNull()
            val description = section("DESCRIPTION").firstNotNullOfOrNull { text(it, "htmlText", "description") } ?: text(item, "description")
            AirbnbListing(
                id = listingId, title = title, url = "https://www.airbnb.com/rooms/$listingId", photos = photos,
                location = text(item, "location", "address", "city") ?: locationSection?.let { text(it, "subtitle", "title") },
                latitude = latitude, longitude = longitude, rating = nested("avgRatingA11yLabel", "rating", "avgRating"),
                description = description?.let { Jsoup.parse(it).text().take(6000) },
                amenities = (
                    section("AMENITIES").mapNotNull { text(it, "title") } + descendants.flatMap { obj ->
                        (obj["seeAllAmenitiesGroups"] as? JsonObject)?.entries?.map { "${it.key}: ${(it.value as? JsonPrimitive)?.contentOrNull ?: it.value.toString()}" }.orEmpty() +
                            (obj["amenities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    }
                    ).distinct().take(60),
                houseRules = (section("POLICIES").mapNotNull { text(it, "title") } + (item["houseRules"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }).distinct().take(30),
                price = text(item, "price", "priceDisplay", "nightlyPrice") ?: priceLines.firstOrNull(),
                totalPrice = text(item, "totalPrice", "total_price") ?: priceLines.firstOrNull { it.contains("total", true) },
                priceLines = priceLines, feeLines = fees, checkin = checkin, checkout = checkout,
                adults = guests("adults", 1..50), children = guests("children", 0..50), infants = guests("infants", 0..50), pets = guests("pets", 0..20), nights = nights, reviewAnalysis = analyzeReviews(reviewTexts)
            )
        }.groupBy(::stayKey).values.map { group -> group.reduce(::merge) }.take(30)
    }

    fun merge(base: AirbnbListing, details: AirbnbListing): AirbnbListing {
        if (base.id != details.id) return base
        return details.copy(
            title = details.title.takeUnless { it == "Airbnb listing ${base.id}" } ?: base.title,
            photos = (details.photos + base.photos).distinct().take(20), location = details.location ?: base.location,
            latitude = details.latitude ?: base.latitude, longitude = details.longitude ?: base.longitude,
            rating = details.rating ?: base.rating, description = details.description ?: base.description,
            amenities = (details.amenities + base.amenities).distinct(), houseRules = (details.houseRules + base.houseRules).distinct(),
            price = details.price ?: base.price, totalPrice = details.totalPrice ?: base.totalPrice,
            priceLines = details.priceLines.ifEmpty { base.priceLines }, feeLines = details.feeLines.ifEmpty { base.feeLines },
            checkin = details.checkin ?: base.checkin, checkout = details.checkout ?: base.checkout,
            nights = details.nights ?: base.nights, adults = details.adults ?: base.adults,
            children = details.children ?: base.children, infants = details.infants ?: base.infants, pets = details.pets ?: base.pets,
            reviewAnalysis = details.reviewAnalysis.takeIf { it.sampleSize > 0 } ?: base.reviewAnalysis
        )
    }

    fun analyzeReviews(reviews: List<String>): AirbnbReviewAnalysis {
        if (reviews.isEmpty()) return AirbnbReviewAnalysis()
        val positive = Regex("(?i)\\b(clean|excellent|wonderful|comfortable|helpful|beautiful|great)\\b")
        val negative = Regex("(?i)\\b(dirty|noisy|unsafe|broken|rude|mould|mold|bugs|scam)\\b")
        val patterns = mapOf(
            "Pests" to Regex("(?i)\\b(bed ?bugs|cockroaches|infestation)\\b"),
            "Safety" to Regex("(?i)\\b(unsafe|break[ -]?in|stolen|hidden camera)\\b"),
            "Unexpected fees" to Regex("(?i)\\b(hidden fees?|unexpected charges?|extra charges?)\\b"),
            "Off-platform payment" to Regex("(?i)\\b(wire transfer|off[ -]platform payment|pay outside airbnb)\\b"),
            "Cleanliness" to Regex("(?i)\\b(dirty|mould|mold)\\b"),
            "Listing mismatch" to Regex("(?i)\\b(not as (?:described|pictured)|misleading|inaccurate listing)\\b")
        )
        val samples = reviews.take(50).map { Jsoup.parse(it).text().take(2000) }
        fun signals(text: String, pattern: Regex) = pattern.findAll(text).filter { hit ->
            val prefix = text.substring((hit.range.first - 15).coerceAtLeast(0), hit.range.first)
            !Regex("(?i)\\b(no|not|never|without)\\s+(?:(?:bed|any|very|really)\\s+)?$").containsMatchIn(prefix)
        }
        val flags = patterns.mapNotNull { (label, pattern) ->
            val evidence = samples.firstNotNullOfOrNull { text -> signals(text, pattern).firstOrNull()?.let { text to it.range } } ?: return@mapNotNull null
            val start = (evidence.second.first - 90).coerceAtLeast(0)
            val end = (evidence.second.last + 120).coerceAtMost(evidence.first.length)
            "$label: ${evidence.first.substring(start, end)}"
        }
        val plus = samples.count { signals(it, positive).any() }
        val minus = samples.count { signals(it, negative).any() }
        return AirbnbReviewAnalysis(samples.size, plus, minus, flags, "${samples.size} supplied reviews: $plus with positive signals, $minus with negative signals. English keyword analysis; signals require review and are not a safety verdict.")
    }

    fun json(listings: List<AirbnbListing>, provider: String = "OpenBnB"): JsonObject = JsonObject(
        mapOf(
            "schema" to JsonPrimitive(SCHEMA),
            "provider" to JsonPrimitive(provider),
            "listings" to JsonArray(listings.map { json.encodeToJsonElement(AirbnbListing.serializer(), it) }),
            "notice" to JsonPrimitive("Provider observations only. Missing fees, reviews and photos remain unknown. Confirm availability and final total on Airbnb.")
        )
    )

    private fun id(obj: JsonObject): String? = text(obj, "id", "listing_id", "listingId")?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
        ?: listingUrl(text(obj, "url", "listingUrl"))?.substringAfterLast('/')
    fun stayKey(listing: AirbnbListing): List<Any?> = listOf(listing.id, listing.checkin, listing.checkout, listing.adults, listing.children, listing.infants, listing.pets)
    private fun date(value: String?): String? = value?.takeIf { it.length == 10 && runCatching { LocalDate.parse(it) }.isSuccess }
    private fun validated(listing: AirbnbListing): AirbnbListing? {
        if (!listing.id.matches(Regex("[0-9]{1,30}")) || listingUrl(listing.url)?.substringAfterLast('/') != listing.id) return null
        return listing.copy(
            url = "https://www.airbnb.com/rooms/${listing.id}", title = listing.title.take(300),
            photos = listing.photos.mapNotNull(::photoUrl).distinct().take(20),
            latitude = listing.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 },
            longitude = listing.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 },
            checkin = date(listing.checkin), checkout = date(listing.checkout),
            adults = listing.adults?.takeIf { it in 1..50 }, nights = listing.nights?.takeIf { it in 1..365 },
            children = listing.children?.takeIf { it in 0..50 }, infants = listing.infants?.takeIf { it in 0..50 }, pets = listing.pets?.takeIf { it in 0..20 },
            description = listing.description?.take(6000), amenities = listing.amenities.take(60), houseRules = listing.houseRules.take(30),
            priceLines = listing.priceLines.take(15), feeLines = listing.feeLines.take(15)
        )
    }
    private fun text(obj: JsonObject, vararg keys: String) = keys.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }?.take(8000) }
    private fun number(obj: JsonObject, vararg keys: String) = keys.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) }
}
