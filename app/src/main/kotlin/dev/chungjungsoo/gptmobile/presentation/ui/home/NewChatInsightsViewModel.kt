package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardEntry
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardRepository
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.pricing.ModelPrice
import dev.chungjungsoo.gptmobile.data.pricing.ProfileBenchmarkResolver
import dev.chungjungsoo.gptmobile.data.pricing.ProviderPricingRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class ProfileInsight(val profile: PlatformV2, val score: ScoreboardEntry?, val community: Boolean, val price: ModelPrice?, val settings: AppFeatureSettings, val now: Long, val localRun: dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun? = null, val priceRefreshing: Boolean = false, val priceMessage: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewChatInsightsViewModel @Inject constructor(
    private val settings: SettingRepository,
    private val pricing: ProviderPricingRepository,
    private val benchmarks: BenchmarkStore,
    private val scoreboard: ScoreboardRepository
) : ViewModel() {
    val priceState = pricing.state
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }
    private val configuration = combine(settings.observePlatformV2s(), settings.observeFeatureSettings()) { profiles, features -> profiles to features }
    internal val insights = configuration.flatMapLatest { (profiles, features) ->
        combine(benchmarks.history, scoreboard.state, pricing.state, clock) { history, online, prices, now ->
            profiles.associate { profile ->
                val own = if (features.newChatScores) ProfileBenchmarkResolver.personal(profile, history) else null
                val community = if (own == null && features.newChatScores && features.newChatCommunity) ProfileBenchmarkResolver.choose(profile, online.entries) else null
                profile.uid to ProfileInsight(
                    profile,
                    own ?: community,
                    community != null,
                    if (features.newChatPrices) pricing.resolve(profile, features, prices.entries, now) else null,
                    features,
                    now,
                    if (features.newChatScores && profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM) history.filter { it.local && it.finished && !it.canceled && it.stoppedReason == null && it.profileUid == profile.uid && it.model == profile.model && it.mode in setOf(dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode.QUICK, dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode.FULL) }.maxByOrNull { it.startedAt } else null,
                    prices.refreshing,
                    prices.message
                )
            }
        }.onStart {
            if (features.newChatScores) benchmarks.load()
            if (features.newChatPrices) pricing.hydrate()
            if (features.newChatScores && features.newChatCommunity) scoreboard.hydrate()
            // Start independently: the picker never awaits networking or inference.
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyMap())

    suspend fun refreshWhileVisible() {
        configuration.collectLatestPricing()
    }
    private suspend fun kotlinx.coroutines.flow.Flow<Pair<List<PlatformV2>, AppFeatureSettings>>.collectLatestPricing() = collectLatest { (profiles, features) ->
        if (features.newChatScores && features.newChatCommunity) scoreboard.refresh()
        while (features.newChatPrices && features.newChatPriceRefresh) {
            pricing.refresh(profiles)
            delay(60_000)
        }
    }
    fun refreshNow() = viewModelScope.launch { pricing.refresh(settings.fetchPlatformV2s(), true) }
}
