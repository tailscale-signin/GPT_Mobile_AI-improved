package dev.chungjungsoo.gptmobile.data.amazon

import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject

/** Shares preload and popup reads, retaining the selected offer while filling in product facts. */
@Singleton
class AmazonProductMediaRepository @Inject constructor(
    private val cache: AmazonProductMediaCache,
    private val images: AmazonProductImageProvider,
    private val native: AmazonHtmlProvider,
    private val access: AmazonAccessPolicy,
    private val settings: SettingRepository,
    private val tools: AgentToolResolver
) {
    private val photoLocks = Array(32) { Mutex() }
    private val detailLocks = Array(32) { Mutex() }
    private val detailRequests = Semaphore(2)

    suspend fun photo(owner: String?, chatId: Int?, product: JsonObject): AmazonProductMedia = photoLocks[slot(owner, chatId, product)].withLock {
        if (!allowed(owner, chatId)) return@withLock AmazonProductMedia(product)
        val retained = cache.load(chatId, product)
        if (retained?.image != null) return@withLock retained
        var enriched = retained?.product ?: product
        var image = readImage(owner, chatId, enriched)
        var detailed = retained?.detailed == true
        if (image == null && allowed(owner, chatId)) {
            enriched = details(owner, chatId, enriched, refreshImage = true)
            detailed = true
            image = readImage(owner, chatId, enriched)
        }
        if (!allowed(owner, chatId)) return@withLock AmazonProductMedia(product)
        AmazonProductMedia(enriched, image, detailed).also { cache.save(chatId, it) }
    }

    suspend fun details(owner: String?, chatId: Int?, product: JsonObject, refreshImage: Boolean = false): JsonObject = detailLocks[slot(owner, chatId, product)].withLock {
        if (owner == null || !allowed(owner, chatId)) return@withLock product
        val retained = cache.load(chatId, product)
        val untriedGallery = retained?.let { saved -> AmazonProducts.productImageUrls(saved.product).any { it !in AmazonProducts.productImageUrls(product) } } == true
        if (retained?.detailed == true && (!refreshImage || retained.image != null || untriedGallery)) return@withLock retained.product
        if (!refreshImage && AmazonProducts.text(product, "sourceType") == "product_page") return@withLock product
        val domain = AmazonProducts.text(product, "marketplace") ?: return@withLock product
        val asin = AmazonProducts.text(product, "asin") ?: return@withLock product
        val fetched = detailRequests.withPermit {
            if (!allowed(owner, chatId)) return@withPermit null
            val market = AmazonFreeMarket.fromDomain(domain)
            val nativeProduct = if (market != null && (refreshImage || access.allowed(owner, network = true))) {
                try {
                    val config = (settings.getFeatureSettings().pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings()).normalized()
                    native.products(
                        AmazonProductRequest(listOf(asin), market),
                        AmazonReadContext(config.timeoutSeconds, config.amazonDailyRequests) { allowed(owner, chatId) && (refreshImage || access.allowed(owner, network = true)) }
                    ).products.firstOrNull()?.toJson()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            if (!allowed(owner, chatId)) null else nativeProduct ?: tools.amazonProductDetails(owner, domain, asin)
        }
        if (!allowed(owner, chatId)) return@withLock product
        val enriched = fetched?.let { AmazonProducts.withDetails(retained?.product ?: product, it) } ?: retained?.product ?: product
        cache.save(chatId, AmazonProductMedia(enriched, detailed = true))
        enriched
    }

    private suspend fun readImage(owner: String?, chatId: Int?, product: JsonObject): ByteArray? {
        for (url in AmazonProducts.productImageUrls(product).take(4)) {
            val image = images.fetch(url) { allowed(owner, chatId) }
            if (image != null) return image
        }
        return null
    }

    private suspend fun allowed(owner: String?, chatId: Int?) = access.mediaAllowed(owner) && cache.available(chatId)

    private fun slot(owner: String?, chatId: Int?, product: JsonObject) =
        (listOf(owner, chatId, AmazonProducts.text(product, "marketplace"), AmazonProducts.text(product, "asin"), AmazonProducts.text(product, "variant")).hashCode() and Int.MAX_VALUE) % photoLocks.size
}
