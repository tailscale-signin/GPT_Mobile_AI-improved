package dev.chungjungsoo.gptmobile.data.pricing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterModelRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PriceCatalogue(val schemaVersion: Int = 1, val revision: String, val entries: List<ModelPrice>)
data class PricingState(val entries: List<ModelPrice> = emptyList(), val refreshing: Boolean = false, val message: String? = null)

@Singleton
class ProviderPricingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val openRouter: OpenRouterModelRepository
) {
    private val preferences = context.getSharedPreferences("provider_pricing_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PricingState())
    val state = mutableState.asStateFlow()
    private var loaded = false

    suspend fun hydrate() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            val reviewed = runCatching { context.assets.open("pricing/catalog.v1.json").bufferedReader().use { json.decodeFromString<PriceCatalogue>(it.readText()) } }.getOrNull()
            val cached = preferences.getString("catalogue", null)?.takeIf { it.length <= 8_000_000 }?.let { runCatching { json.decodeFromString<PriceCatalogue>(it) }.getOrNull() }
            mutableState.value = PricingState(listOfNotNull(reviewed, cached).filter { it.schemaVersion == 1 }.flatMap { it.entries }.filter(ModelPrice::valid).distinctBy { it.provider to it.model })
            loaded = true
        }
    }

    suspend fun refresh(profiles: List<PlatformV2>, force: Boolean = false) {
        hydrate()
        if (profiles.none { billingProvider(it) == "OPENROUTER" }) {
            mutableState.value = mutableState.value.copy(message = "No API pricing source applies to these profiles. Reviewed and account rates retain their review dates.")
            return
        }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val existing = mutableState.value.entries.filter { it.provider == "OPENROUTER" }
                if (!force && existing.isNotEmpty() && existing.all { it.freshness(now) == PriceFreshness.FRESH }) return@withLock
                mutableState.value = mutableState.value.copy(refreshing = true, message = null)
                try {
                    val models = openRouter.fetchModels(force).getOrThrow()
                    val fresh = models.mapNotNull { model ->
                        val p = model.pricing ?: return@mapNotNull null
                        ModelPrice(
                            "OPENROUTER", model.id, perMillion(p.prompt) ?: return@mapNotNull null, perMillion(p.completion) ?: return@mapNotNull null,
                            source = PriceSource.PROVIDER_API, sourceUrl = "https://openrouter.ai/api/v1/models", validatedAt = openRouter.sourceValidatedAt,
                            cachedInputPerMillion = perMillion(p.inputCacheRead), routingReference = true, conditionalPricing = !p.overrides.isNullOrEmpty(),
                            extras = listOf("Cache write / 1M tokens" to perMillion(p.inputCacheWrite), "Request / request" to p.request, "Image / image" to p.image, "Web search / search" to p.webSearch).mapNotNull { (key, value) -> value?.takeIf { decimalRate(it) != null }?.let { key to it } }.toMap()
                        )
                    }.filter(ModelPrice::valid)
                    check(fresh.isNotEmpty()) { "No valid rates returned; last known prices retained." }
                    // Partial/parser omissions do not silently erase the last usable quote.
                    val merged = (fresh + existing).distinctBy { it.model }
                    check(preferences.edit().putString("catalogue", json.encodeToString(PriceCatalogue(revision = now.toString(), entries = merged))).commit()) { "Prices could not be saved." }
                    mutableState.value = PricingState(mutableState.value.entries.filterNot { it.provider == "OPENROUTER" } + merged, message = "OpenRouter prices checked. Route and account charges may differ.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.value = mutableState.value.copy(message = "Price refresh unavailable. Last known dates are unchanged.")
                } finally {
                    mutableState.value = mutableState.value.copy(refreshing = false)
                }
            }
        }
    }

    fun resolve(profile: PlatformV2, settings: AppFeatureSettings, entries: List<ModelPrice>, now: Long): ModelPrice? {
        val override = settings.manualPrices.lastOrNull { it.contextKey == priceContextKey(profile) && it.price.valid() && it.price.freshness(now) != PriceFreshness.EXPIRED }
        if (override != null) return override.price
        val provider = billingProvider(profile) ?: return null
        if (profile.batchMode || profile.model.endsWith(":batch")) return null
        return entries.firstOrNull { it.provider == provider && it.model == profile.model && it.valid() && it.freshness(now) != PriceFreshness.EXPIRED }
    }
}
