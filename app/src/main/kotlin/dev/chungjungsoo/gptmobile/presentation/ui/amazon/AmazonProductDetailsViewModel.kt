package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonObservationEntity
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryChart
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class AmazonProductDetailsState(val product: JsonObject = JsonObject(emptyMap()), val observations: List<AmazonObservationEntity> = emptyList(), val loading: Boolean = false, val notice: String? = null, val publicChart: AmazonPublicHistoryChart? = null, val historyNotice: String? = null)

@HiltViewModel
class AmazonProductDetailsViewModel @Inject constructor(private val resolver: AgentToolResolver, private val history: AmazonHistoryRepository) : ViewModel() {
    private val current = MutableStateFlow(AmazonProductDetailsState())
    val state = current.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    fun open(product: JsonObject, owner: String?, chatId: Int?) {
        job?.cancel()
        val request = ++generation
        current.value = AmazonProductDetailsState(product, loading = owner != null)
        if (owner == null) return
        val market = AmazonProducts.text(product, "marketplace") ?: return
        val asin = AmazonProducts.text(product, "asin") ?: return
        job = viewModelScope.launch {
            try {
                val saved = history.history(owner, market, asin)
                if (generation == request) current.value = current.value.copy(observations = saved.observations)
                val result = coroutineScope {
                    val chart = async {
                        try {
                            val image = resolver.fetchPublicAmazonPriceHistoryChart(owner, market, asin)
                            if (generation == request) current.value = current.value.copy(publicChart = image)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (generation == request) current.value = current.value.copy(historyNotice = error.message ?: "Public price history is unavailable.")
                        }
                    }
                    val details = resolver.fetchAmazonProductDetails(owner, market, asin, chatId)
                    chart.await()
                    details
                }
                val payload = (result.content as? ToolResultContent.Json)?.value as? JsonObject
                val details = (payload?.get("products") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {
                    AmazonProducts.text(it, "marketplace") == market && AmazonProducts.text(it, "asin") == asin
                }
                if (generation != request) return@launch
                val sameOffer = details != null && listOf("seller", "condition", "variant").all { AmazonProducts.text(product, it) == AmazonProducts.text(details, it) }
                val refreshed = when {
                    details == null -> product
                    sameOffer -> AmazonProducts.mergeProducts(listOf(details, product)).first()
                    else -> JsonObject(product + details.filterKeys { it in setOf("description", "features", "imageUrl", "rating", "reviewCount") })
                }
                val observations = history.history(owner, market, asin).observations
                if (generation == request) current.value = current.value.copy(product = refreshed, observations = observations, loading = false, notice = if (result.isError || details == null) "Additional details are unavailable. Showing the saved listing and available price history." else null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == request) current.value = current.value.copy(loading = false, notice = "Could not refresh this listing. Saved product information remains available.")
            }
        }
    }

    fun close() {
        generation++
        job?.cancel()
    }
}
