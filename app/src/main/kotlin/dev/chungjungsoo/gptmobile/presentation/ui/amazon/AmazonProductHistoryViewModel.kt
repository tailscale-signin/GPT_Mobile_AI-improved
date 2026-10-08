package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryProvider
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@HiltViewModel
class AmazonProductHistoryViewModel @Inject constructor(
    private val provider: AmazonPublicHistoryProvider,
    private val access: AmazonAccessPolicy
) : ViewModel() {
    private val lookups = mutableMapOf<String, Job>()

    /** Warm every result, including cards outside the visible LazyRow. The provider shares in-flight reads and limits concurrency. */
    fun preload(owner: String?, products: List<JsonObject>) {
        if (owner == null) return
        products.take(30).forEach { product ->
            val domain = AmazonProducts.text(product, "marketplace") ?: return@forEach
            val asin = AmazonProducts.text(product, "asin") ?: return@forEach
            if (AmazonProducts.productUrl(domain, asin) == null) return@forEach
            val key = "$owner/$domain/$asin"
            if (lookups[key]?.isActive == true) return@forEach
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (access.mediaAllowed(owner)) provider.fetch(domain, asin) { access.mediaAllowed(owner) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A missing public chart must never interrupt search results or the answer.
                } finally {
                    lookups.remove(key)
                }
            }
            lookups[key] = job
            job.start()
        }
    }
}
