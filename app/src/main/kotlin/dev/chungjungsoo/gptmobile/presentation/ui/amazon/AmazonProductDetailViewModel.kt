package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHtmlProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductImageProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductRequest
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadContext
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class AmazonProductDetailState(
    val product: JsonObject = JsonObject(emptyMap()),
    val publicHistory: AmazonPublicHistory? = null,
    val image: ByteArray? = null,
    val loading: Boolean = false,
    val historyLoading: Boolean = false,
    val imageLoading: Boolean = false,
    val notice: String? = null
)

@HiltViewModel
class AmazonProductDetailViewModel @Inject constructor(
    private val publicHistory: AmazonPublicHistoryProvider,
    private val images: AmazonProductImageProvider,
    private val native: AmazonHtmlProvider,
    private val access: AmazonAccessPolicy,
    private val settings: SettingRepository,
    private val tools: AgentToolResolver
) : ViewModel() {
    private val current = MutableStateFlow(AmazonProductDetailState())
    val state = current.asStateFlow()
    private var lookup: Job? = null
    private var requestVersion = 0

    fun open(owner: String?, product: JsonObject) {
        val version = ++requestVersion
        lookup?.cancel()
        current.value = AmazonProductDetailState(product = product, loading = true, historyLoading = true)
        lookup = viewModelScope.launch {
            access.mediaChanges(owner).collectLatest { permitted ->
                if (!permitted) {
                    publish(version) { AmazonProductDetailState(product = product, notice = "Enable an Amazon plugin and remote tools for this profile to load product media.") }
                    return@collectLatest
                }
                publish(version) { AmazonProductDetailState(product = product, loading = true, historyLoading = true) }
                load(owner, product, version)
            }
        }
    }

    private suspend fun load(owner: String?, product: JsonObject, version: Int) = coroutineScope {
        val domain = AmazonProducts.text(product, "marketplace").orEmpty()
        val asin = AmazonProducts.text(product, "asin").orEmpty()
        launch {
            try {
                val graph = publicHistory.fetch(domain, asin) { access.mediaAllowed(owner) }
                if (access.mediaAllowed(owner)) publish(version) { it.copy(publicHistory = graph) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The product remains usable even when neither public history provider has a chart.
            } finally {
                publish(version) { it.copy(historyLoading = false) }
            }
        }
        val initialImage = launch { loadImage(owner, product, version) }
        launch {
            try {
                val details = fetchDetails(owner, product, domain, asin)
                if (!access.mediaAllowed(owner)) return@launch
                publish(version) { it.copy(product = details) }
                if (AmazonProducts.text(details, "imageUrl") != AmazonProducts.text(product, "imageUrl")) {
                    initialImage.cancelAndJoin()
                    loadImage(owner, details, version)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(version) { it.copy(notice = "Additional product information is unavailable.") }
            } finally {
                publish(version) { it.copy(loading = false) }
            }
        }
    }

    private suspend fun fetchDetails(owner: String?, product: JsonObject, domain: String, asin: String): JsonObject {
        if (owner == null || !access.mediaAllowed(owner)) return product
        if (AmazonProducts.text(product, "sourceType") == "product_page") return product
        val market = AmazonFreeMarket.fromDomain(domain)
        val fetched = if (market != null && access.allowed(owner, network = true)) {
            try {
                val config = (settings.getFeatureSettings().pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings()).normalized()
                native.products(AmazonProductRequest(listOf(asin), market), AmazonReadContext(config.timeoutSeconds, config.amazonDailyRequests) { access.allowed(owner, network = true) }).products.firstOrNull()?.toJson()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (!access.mediaAllowed(owner)) return product
        val details = fetched ?: tools.amazonProductDetails(owner, domain, asin)
        return details?.let { AmazonProducts.withDetails(product, it) } ?: product
    }

    private suspend fun loadImage(owner: String?, product: JsonObject, version: Int) {
        val url = AmazonProducts.imageUrl(AmazonProducts.text(product, "imageUrl")) ?: return
        publish(version) { it.copy(imageLoading = true, image = null) }
        try {
            val image = images.fetch(url) { access.mediaAllowed(owner) }
            if (access.mediaAllowed(owner)) publish(version) { it.copy(image = image) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing images leave the title, price and history available.
        } finally {
            publish(version) { it.copy(imageLoading = false) }
        }
    }

    private fun publish(version: Int, transform: (AmazonProductDetailState) -> AmazonProductDetailState) {
        if (version == requestVersion) current.update(transform)
    }

    fun close() {
        requestVersion++
        lookup?.cancel()
        current.value = AmazonProductDetailState()
    }
}
