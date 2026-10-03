package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkRating
import dev.chungjungsoo.gptmobile.data.benchmark.delegationBenchmarkRating
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LocalToolsViewModel @Inject constructor(
    private val settings: SettingRepository,
    private val benchmarkStore: BenchmarkStore
) : ViewModel() {
    val delegation = settings.observeFeatureSettings().map { it.delegation.normalized() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelDelegationSettings())
    val tokenBudget = settings.observeFeatureSettings().map { it.tokenBudget.normalized() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings())
    fun updateBudget(transform: (dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings) -> dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings) {
        viewModelScope.launch {
            val latest = settings.getFeatureSettings()
            settings.updateFeatureSettings(latest.copy(tokenBudget = transform(latest.tokenBudget).normalized()))
        }
    }
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val modelScores = kotlinx.coroutines.flow.combine(benchmarkStore.history, profiles) { history, profileList ->
        profileList.mapNotNull { profile ->
            val delegationRuns = history
                .filter { run ->
                    run.mode == BenchmarkMode.DELEGATION &&
                        run.finished &&
                        !run.canceled &&
                        run.samples.any { sample -> sample.delegation?.workerUid == profile.uid }
                }
                .sortedByDescending { it.startedAt }
                .take(5)
            val delegationScore = delegationBenchmarkRating(delegationRuns).score
            val standardMode = if (history.any { it.profileUid == profile.uid && it.mode == BenchmarkMode.FULL }) {
                BenchmarkMode.FULL
            } else {
                BenchmarkMode.QUICK
            }
            val standardRuns = history
                .filter { it.profileUid == profile.uid && it.mode == standardMode && it.finished && !it.canceled }
                .sortedByDescending { it.startedAt }
                .take(5)
            val standardScore = benchmarkRating(standardRuns, profile.compatibleType == ClientType.LITERT_LM).score
            (delegationScore ?: standardScore)?.let { profile.uid to it }
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { benchmarkStore.load() }
        }
    }

    fun resetDelegationDefaults() {
        update { current ->
            ModelDelegationSettings(
                enabled = current.enabled,
                targetProfileUid = current.targetProfileUid,
                localPlatformsOnly = current.localPlatformsOnly,
                allowRemoteWorkers = current.allowRemoteWorkers
            )
        }
    }

    fun update(transform: (ModelDelegationSettings) -> ModelDelegationSettings) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val latest = settings.getFeatureSettings()
                settings.updateFeatureSettings(latest.copy(delegation = transform(latest.delegation).normalized()))
                _error.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _error.value = "Could not save delegation settings. Try again."
            } finally {
                _busy.value = false
            }
        }
    }
}
