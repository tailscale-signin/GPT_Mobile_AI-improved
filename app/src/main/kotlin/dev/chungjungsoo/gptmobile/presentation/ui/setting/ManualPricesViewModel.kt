package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.pricing.ManualPrice
import dev.chungjungsoo.gptmobile.data.pricing.ModelPrice
import dev.chungjungsoo.gptmobile.data.pricing.PriceSource
import dev.chungjungsoo.gptmobile.data.pricing.ProviderPricingRepository
import dev.chungjungsoo.gptmobile.data.pricing.billingProvider
import dev.chungjungsoo.gptmobile.data.pricing.priceContextKey
import dev.chungjungsoo.gptmobile.data.pricing.reviewTime
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ManualPricesViewModel @Inject constructor(private val repository: SettingRepository, private val pricing: ProviderPricingRepository) : ViewModel() {
    val profiles = repository.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val settings = repository.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AppFeatureSettings())
    val status = MutableStateFlow<String?>(null)
    val pricingState = pricing.state
    fun refresh() = viewModelScope.launch { pricing.refresh(repository.fetchPlatformV2s(), true) }
    fun remove(profile: PlatformV2) = viewModelScope.launch { repository.mutateFeatureSettings { current -> current.copy(manualPrices = current.manualPrices.filterNot { it.contextKey == priceContextKey(profile) }) } }
    fun save(profile: PlatformV2, input: String, output: String, currency: String, source: String, reviewed: String, expiry: String) = viewModelScope.launch {
        val date = reviewTime(reviewed.trim())
        val end = reviewTime(expiry.trim())
        val price = ModelPrice(billingProvider(profile) ?: "CUSTOM", profile.model, input.trim(), output.trim(), currency.trim().uppercase(), PriceSource.ACCOUNT_OVERRIDE, source.trim(), reviewedOn = reviewed.trim(), validUntil = end)
        if (!price.valid() || price.freshness(System.currentTimeMillis()) == dev.chungjungsoo.gptmobile.data.pricing.PriceFreshness.EXPIRED || date == null || date > System.currentTimeMillis() || end == null || end <= System.currentTimeMillis() || end <= date || source.isBlank() || source.length > 1000) {
            status.value = "Enter nonnegative decimal rates, a 3-letter currency, a source, a valid review date and a future expiry date (YYYY-MM-DD)."
            return@launch
        }
        repository.mutateFeatureSettings { current -> current.copy(manualPrices = current.manualPrices.filterNot { it.contextKey == priceContextKey(profile) } + ManualPrice(priceContextKey(profile), price)) }
        status.value = "Account rate saved for this exact profile context."
    }
}
