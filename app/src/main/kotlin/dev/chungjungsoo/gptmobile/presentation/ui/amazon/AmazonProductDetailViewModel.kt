package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductMediaRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryProvider
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val media: AmazonProductMediaRepository,
    private val access: AmazonAccessPolicy
) : ViewModel() {
    private val current = MutableStateFlow(AmazonProductDetailState())
    val state = current.asStateFlow()
    private var lookup: Job? = null
    private var requestVersion = 0

    fun open(owner: String?, product: JsonObject, chatId: Int? = null) {
        val version = ++requestVersion
        lookup?.cancel()
        current.value = AmazonProductDetailState(product = product, loading = true, historyLoading = true, imageLoading = true)
        lookup = viewModelScope.launch {
            access.mediaChanges(owner).collectLatest { permitted ->
                if (!permitted) {
                    publish(version) { AmazonProductDetailState(product = product, notice = "Enable an Amazon plugin and remote tools for this profile to load product media.") }
                    return@collectLatest
                }
                publish(version) { AmazonProductDetailState(product = product, loading = true, historyLoading = true, imageLoading = true) }
                load(owner, chatId, product, version)
            }
        }
    }

    private suspend fun load(owner: String?, chatId: Int?, product: JsonObject, version: Int) = coroutineScope {
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
        launch {
            try {
                val photo = media.photo(owner, chatId, product)
                if (access.mediaAllowed(owner)) publish(version) { it.copy(image = photo.image, product = AmazonProducts.withDetails(it.product, photo.product)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The offer and chart remain available when a product has no accessible photo.
            } finally {
                publish(version) { it.copy(imageLoading = false) }
            }
        }
        launch {
            try {
                val details = media.details(owner, chatId, product)
                if (access.mediaAllowed(owner)) publish(version) { it.copy(product = AmazonProducts.withDetails(it.product, details)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(version) { it.copy(notice = "Additional product information is unavailable.") }
            } finally {
                publish(version) { it.copy(loading = false) }
            }
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
