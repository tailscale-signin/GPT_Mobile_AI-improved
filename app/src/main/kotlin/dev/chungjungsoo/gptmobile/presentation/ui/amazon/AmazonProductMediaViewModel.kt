package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductMediaRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@HiltViewModel
class AmazonProductMediaViewModel @Inject constructor(private val media: AmazonProductMediaRepository) : ViewModel() {
    private val lookups = mutableMapOf<List<Any?>, Job>()

    fun preload(owner: String?, chatId: Int?, products: List<JsonObject>) {
        if (owner == null) return
        products.take(30).forEach { product ->
            val key = listOf(owner, chatId, AmazonProducts.text(product, "marketplace"), AmazonProducts.text(product, "asin"), AmazonProducts.text(product, "variant"), AmazonProducts.productImageUrl(product))
            if (lookups[key]?.isActive == true) return@forEach
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    media.photo(owner, chatId, product)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Media is optional; a failed preload must not interrupt the answer.
                } finally {
                    lookups.remove(key)
                }
            }
            lookups[key] = job
            job.start()
        }
    }
}
