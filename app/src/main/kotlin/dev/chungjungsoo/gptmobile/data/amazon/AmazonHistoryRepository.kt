package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class AmazonHistoryRepository internal constructor(private val database: ChatDatabaseV2, private val clock: Clock) {
    @Inject constructor(database: ChatDatabaseV2) : this(database, Clock.systemUTC())
    private val dao = database.amazonDao()
    val watches = dao.observeWatches()

    /** Runs inside the live request's permission observer. A revoked grant rolls back the transaction. */
    suspend fun record(owner: String, requestId: String, result: AmazonFetchResult, allowed: suspend () -> Boolean, chatId: Int? = null, requestedMarket: AmazonFreeMarket? = null) = database.withTransaction {
        require(owner.isNotBlank() && owner.length <= 200 && requestId.isNotBlank() && requestId.length <= 100)
        checkAllowed(allowed)
        if (chatId != null && database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary != false) return@withTransaction
        val now = clock.millis()
        for (product in result.products.take(10)) {
            if (requestedMarket != null && product.marketplace != requestedMarket) continue
            if (AmazonProducts.asin(product.asin) != product.asin || product.sourceType !in SOURCES || product.acquiredAt.toEpochMilli() !in 0..now + 300_000) continue
            val amount = product.amount?.takeIf { it >= BigDecimal.ZERO && it.scale() <= 2 && it < MAX_AMOUNT }
            val knownPrice = amount != null && product.currency == product.marketplace.currency
            dao.addCheckEvent(AmazonCheckEventEntity(UUID.randomUUID().toString(), owner, requestId, product.marketplace.domain, product.asin, product.acquiredAt.toEpochMilli(), if (knownPrice) "OBSERVED_INCOMPLETE_OFFER" else "PRICE_UNAVAILABLE"))
            if (!knownPrice) continue
            // Unknown seller/condition/variant/destination is a labelled listing series, never a strict offer key.
            val key = "${product.marketplace.domain}:${product.asin}:${product.currency}:base_item:${product.sourceType}:unknown_offer_context"
            dao.addObservation(
                AmazonObservationEntity(UUID.randomUUID().toString(), owner, requestId, key, product.marketplace.domain, product.asin, product.title.take(200), requireNotNull(amount).toPlainString(), requireNotNull(product.currency), product.sourceType, product.acquiredAt.toEpochMilli())
            )
        }
        result.errors.filter { it.asin != null }.take(10).forEach { error ->
            val asin = AmazonProducts.asin(error.asin.orEmpty()) ?: return@forEach
            val market = requestedMarket ?: result.products.firstOrNull { it.asin == asin }?.marketplace
            if (market != null) dao.addCheckEvent(AmazonCheckEventEntity(UUID.randomUUID().toString(), owner, requestId, market.domain, asin, now, error.code.name))
        }
        dao.pruneChecks(now - 30 * DAY)
        dao.pruneHistory(now - 365 * DAY)
        dao.trimHistory(50_000)
        checkAllowed(allowed)
    }

    suspend fun history(owner: String, market: AmazonFreeMarket, asin: String, limit: Int = 100): AmazonLocalHistory = history(owner, market.domain, asin, limit)

    suspend fun history(owner: String, marketplace: String, asin: String, limit: Int = 100): AmazonLocalHistory {
        require(AmazonProducts.marketplace(marketplace) == marketplace && AmazonProducts.asin(asin) == asin)
        return database.withTransaction {
            AmazonLocalHistory(dao.history(owner, marketplace, asin, limit.coerceIn(1, 100)), dao.historyCount(owner, marketplace, asin), dao.checkEvents(owner, marketplace, asin, 30))
        }
    }

    /** Provider snapshots stay in separate series from public pages and never establish a historical low. */
    suspend fun recordProvider(owner: String, requestId: String, products: List<JsonObject>, sourceType: String, allowed: suspend () -> Boolean, chatId: Int? = null) = database.withTransaction {
        require(owner.isNotBlank() && owner.length <= 200 && requestId.isNotBlank())
        require(sourceType in setOf("serpapi_search", "serpapi_product"))
        checkAllowed(allowed)
        if (chatId != null && database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary != false) return@withTransaction
        val now = clock.millis()
        for (product in products.take(10)) {
            val market = AmazonProducts.text(product, "marketplace")?.let(AmazonProducts::marketplace) ?: continue
            val asin = AmazonProducts.text(product, "asin")?.let(AmazonProducts::asin) ?: continue
            if (AmazonProducts.text(product, "url") != AmazonProducts.productUrl(market, asin)) continue
            val amount = AmazonProducts.text(product, "priceAmount")?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it.scale() <= 2 && it < MAX_AMOUNT } ?: continue
            val maximum = AmazonProducts.text(product, "priceMaxAmount")?.toBigDecimalOrNull()
            if (maximum != null && maximum.compareTo(amount) != 0) continue
            val currency = AmazonProducts.text(product, "currency")?.takeIf { Regex("[A-Z]{3}").matches(it) } ?: continue
            val timestamp = AmazonProducts.text(product, "observedAt", "retrievedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.takeIf { it in 0..now + 300_000 } ?: continue
            val offer = JsonArray(listOf("seller", "condition", "variant").map { kotlinx.serialization.json.JsonPrimitive(AmazonProducts.text(product, it).orEmpty()) }).toString()
            val key = "$market:$asin:$currency:base_item:$sourceType:$offer"
            dao.addObservation(AmazonObservationEntity(UUID.randomUUID().toString(), owner, requestId.take(100), key, market, asin, AmazonProducts.text(product, "title").orEmpty().take(200), amount.toPlainString(), currency, sourceType, timestamp))
        }
        dao.pruneHistory(now - 365 * DAY)
        dao.trimHistory(50_000)
        checkAllowed(allowed)
    }

    suspend fun listWatches(owner: String) = dao.watches(owner)

    suspend fun saveWatch(owner: String, market: AmazonFreeMarket, asin: String, target: String, allowed: suspend () -> Boolean, existingId: String? = null, expectedGeneration: Int? = null): AmazonWatchEntity = database.withTransaction {
        validateListing(market, asin)
        require(owner.isNotBlank() && owner.length <= 200)
        require(MONEY.matches(target) && target.toBigDecimal() > BigDecimal.ZERO) { "Enter a positive target with at most two decimal places." }
        checkAllowed(allowed)
        val existing = existingId?.let { requireNotNull(dao.watch(owner, it)) { "This watch is not owned by this profile." } }
        if (existing != null) {
            require(existing.generation == expectedGeneration) { "This watch changed. Reload it before editing." }
            require(existing.marketplace == market.domain && existing.asin == asin) { "Create a new watch to change the listing." }
        } else {
            require(dao.watches(owner).size < 20) { "This profile already has 20 watches. Delete one before saving another." }
        }
        val saved = (existing ?: AmazonWatchEntity(UUID.randomUUID().toString(), owner, market.domain, asin, target, market.currency, createdAt = clock.millis())).copy(
            targetAmount = target.toBigDecimal().toPlainString(),
            currency = market.currency,
            state = if (existing?.state == "PAUSED") "PAUSED" else "AWAITING_MATCHING_PRICE",
            generation = (existing?.generation ?: 0) + 1
        )
        dao.saveWatch(saved)
        checkAllowed(allowed)
        saved
    }

    suspend fun pauseWatch(owner: String, id: String, generation: Int, paused: Boolean) = database.withTransaction {
        val watch = requireNotNull(dao.watch(owner, id)) { "This watch is not owned by this profile." }
        require(watch.generation == generation) { "This watch changed. Reload it before editing." }
        dao.saveWatch(watch.copy(state = if (paused) "PAUSED" else "AWAITING_MATCHING_PRICE", generation = watch.generation + 1))
    }

    suspend fun deleteWatch(owner: String, id: String, generation: Int) {
        require(dao.deleteWatch(owner, id, generation) == 1) { "This watch changed or is not owned by this profile." }
    }

    /** Stale checks cannot overwrite a paused, edited, or deleted watch. No threshold event is emitted. */
    suspend fun finishCheck(watch: AmazonWatchEntity, requestId: String, result: AmazonFetchResult, allowed: suspend () -> Boolean) = database.withTransaction {
        checkAllowed(allowed)
        val current = dao.watch(watch.ownerProfileUid, watch.id) ?: return@withTransaction
        if (current.generation != watch.generation || current.state == "PAUSED") return@withTransaction
        val now = clock.millis()
        val product = result.products.firstOrNull { it.asin == watch.asin && it.marketplace.domain == watch.marketplace }
        val outcome = result.errors.firstOrNull { it.asin == watch.asin || it.asin == null }?.code?.name
            ?: if (product?.amount != null && product.currency == watch.currency) "OFFER_CONTEXT_INCOMPLETE" else "PRICE_UNAVAILABLE"
        dao.addCheckEvent(AmazonCheckEventEntity(UUID.randomUUID().toString(), watch.ownerProfileUid, requestId, watch.marketplace, watch.asin, now, outcome))
        dao.saveWatch(current.copy(state = "AWAITING_MATCHING_PRICE", lastAttemptAt = now, lastSuccessAt = product?.acquiredAt?.toEpochMilli() ?: current.lastSuccessAt, lastOutcome = outcome))
        checkAllowed(allowed)
    }

    suspend fun clearHistory(owner: String) = database.withTransaction {
        dao.clearHistory(owner)
        dao.clearChecks(owner)
    }

    private suspend fun checkAllowed(allowed: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!allowed()) throw AmazonReadException(AmazonReadError.PLUGIN_DISABLED, "Amazon access was revoked. No new history or watch change was saved.")
    }

    private fun validateListing(market: AmazonFreeMarket, asin: String) {
        require(AmazonProducts.asin(asin) == asin && AmazonFreeMarket.fromDomain(market.domain) != null) { "Enter a ten-character ASIN and a supported marketplace." }
    }

    companion object {
        val MONEY = Regex("[0-9]{1,9}(?:\\.[0-9]{1,2})?")
        private val MAX_AMOUNT = BigDecimal("1000000000")
        private const val DAY = 86_400_000L
        private val SOURCES = setOf("search_page", "product_page")
    }
}

data class AmazonLocalHistory(val observations: List<AmazonObservationEntity>, val retainedCount: Int, val checks: List<AmazonCheckEventEntity>) {
    fun toJson(market: AmazonFreeMarket, asin: String): JsonObject = buildJsonObject {
        put("schema", "amazon_history_v1")
        put("marketplace", market.domain)
        put("asin", asin)
        put("currency", market.currency)
        put("retainedObservationCount", retainedCount)
        put("returnedObservationCount", observations.size)
        put("truncated", retainedCount > observations.size)
        put("coverageType", "local_sampled_observations")
        checks.firstOrNull()?.let { check ->
            put(
                "lastCheck",
                buildJsonObject {
                    put("attemptedAt", Instant.ofEpochMilli(check.attemptedAt).toString())
                    put("outcome", check.outcome)
                }
            )
        }
        put("comparableOffer", false)
        put(
            "observations",
            JsonArray(
                observations.map { point ->
                    buildJsonObject {
                        put("seriesKey", point.seriesKey)
                        put("amount", point.amount)
                        put("currency", point.currency)
                        put("observedAt", Instant.ofEpochMilli(point.observedAt).toString())
                        put("sourceType", point.sourceType)
                        put("priceBasis", point.priceBasis)
                        put("contextQuality", point.contextQuality)
                    }
                }
            )
        )
        put("notice", "History starts with successful checks on this installation. Search and detail observations are separate incomplete-offer series. Gaps and unknown offer identity prevent historical-low or price-drop claims.")
    }
}
