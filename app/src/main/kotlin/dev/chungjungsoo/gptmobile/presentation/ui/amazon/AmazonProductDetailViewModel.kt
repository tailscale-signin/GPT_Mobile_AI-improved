package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHtmlProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonLocalHistory
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class AmazonProductDetailState(
    val product: JsonObject = JsonObject(emptyMap()),
    val publicHistory: AmazonPublicHistory? = null,
    val localHistory: AmazonLocalHistory? = null,
    val loading: Boolean = false,
    val notice: String? = null
)

@HiltViewModel
class AmazonProductDetailViewModel @Inject constructor(
    private val publicHistory: AmazonPublicHistoryProvider,
    private val localHistory: AmazonHistoryRepository,
    private val native: AmazonHtmlProvider,
    private val access: AmazonAccessPolicy,
    private val settings: SettingRepository,
    private val tools: AgentToolResolver
) : ViewModel() {
    private val current = MutableStateFlow(AmazonProductDetailState())
    val state = current.asStateFlow()
    private var lookup: Job? = null

    fun open(owner: String?, product: JsonObject) {
        lookup?.cancel()
        current.value = AmazonProductDetailState(product = product, loading = true)
        lookup = viewModelScope.launch {
            val domain = AmazonProducts.text(product, "marketplace").orEmpty()
            val asin = AmazonProducts.text(product, "asin").orEmpty()
            try {
                val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == owner }
                suspend fun allowed(): Boolean {
                    val features = settings.getFeatureSettings()
                    val live = settings.fetchPlatformV2s().firstOrNull { it.uid == owner }
                    return live?.enabled == true &&
                        !live.disableAllTools &&
                        !live.disableRemoteTools &&
                        (features.isToolPluginEnabledForProfile(live.uid, ToolPluginId.AMAZON_FREE) || features.isToolPluginEnabledForProfile(live.uid, ToolPluginId.AMAZON_SEARCH))
                }
                val market = AmazonFreeMarket.fromDomain(domain)
                val local = if (owner != null && profile != null && !profile.disableLocalTools && market != null) localHistory.history(owner, market, asin) else null
                if (!allowed()) {
                    current.value = AmazonProductDetailState(product, localHistory = local, notice = "Enable Amazon and remote tools for this profile to load public price history.")
                    return@launch
                }
                val result = coroutineScope {
                    val graph = async { publicHistory.fetch(domain, asin, ::allowed) }
                    val details = async {
                        if (owner == null) return@async product
                        try {
                            if (market == null || !access.allowed(owner, network = true)) {
                                return@async tools.amazonProductDetails(owner, domain, asin)?.let { JsonObject(product + it) } ?: product
                            }
                            val fetched = try {
                                val config = (settings.getFeatureSettings().pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings()).normalized()
                                native.products(AmazonProductRequest(listOf(asin), market), AmazonReadContext(config.timeoutSeconds, config.amazonDailyRequests) { access.allowed(owner, network = true) })
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (!access.allowed(owner, network = true)) return@async product
                            val full = fetched?.products?.firstOrNull()?.toJson() ?: tools.amazonProductDetails(owner, domain, asin)
                            full?.let { JsonObject(product + it) } ?: product
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            product
                        }
                    }
                    AmazonProductDetailState(details.await(), graph.await(), local)
                }
                if (allowed()) current.value = result else current.value = AmazonProductDetailState(product, notice = "Amazon access was disabled during this lookup.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current.value = AmazonProductDetailState(product, notice = "Additional product information is unavailable. You can still open the listing on Amazon.")
            }
        }
    }

    fun close() {
        lookup?.cancel()
    }
}
