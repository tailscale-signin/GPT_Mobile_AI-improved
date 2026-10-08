package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.AmazonNativeTool
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFetchResult
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHtmlProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonItemFailure
import dev.chungjungsoo.gptmobile.data.amazon.AmazonLocalHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadError
import dev.chungjungsoo.gptmobile.data.amazon.AmazonWatchEntity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AmazonDataState(
    val owner: String = "",
    val profiles: List<PlatformV2> = emptyList(),
    val watches: List<AmazonWatchEntity> = emptyList(),
    val features: AppFeatureSettings = AppFeatureSettings(),
    val history: AmazonLocalHistory? = null,
    val historyAsin: String = "",
    val historyMarket: AmazonFreeMarket = AmazonFreeMarket.CANADA,
    val busy: Boolean = false,
    val message: String? = null
) {
    val selectedProfile get() = profiles.firstOrNull { it.uid == owner }
    val canCheck get() = AmazonAccessPolicy.permits(features, selectedProfile, network = true)
    val canSave get() = AmazonAccessPolicy.permits(features, selectedProfile, network = false)
}

@HiltViewModel
class AmazonDataViewModel @Inject constructor(
    private val repository: AmazonHistoryRepository,
    private val access: AmazonAccessPolicy,
    private val provider: AmazonHtmlProvider,
    private val settings: SettingRepository
) : ViewModel() {
    private val edits = MutableStateFlow(AmazonDataState())
    val state = combine(edits, settings.observePlatformV2s(), settings.observeFeatureSettings(), repository.watches) { edit, profiles, features, watches ->
        val owner = edit.owner.ifBlank { profiles.firstOrNull()?.uid.orEmpty() }
        edit.copy(owner = owner, profiles = profiles, features = features, watches = watches.filter { it.ownerProfileUid == owner })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AmazonDataState())

    val orphanOwners = combine(settings.observePlatformV2s(), repository.watches) { profiles, watches ->
        watches.map { it.ownerProfileUid }.distinct().filter { owner -> profiles.none { it.uid == owner } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openListing(owner: String, market: AmazonFreeMarket, asin: String) = action {
        edits.update { it.copy(owner = owner, history = null, historyAsin = "") }
        val result = repository.history(owner, market, asin)
        if (edits.value.owner == owner) edits.update { it.copy(history = result, historyAsin = asin, historyMarket = market) }
    }

    fun selectOwner(owner: String) {
        edits.update { it.copy(owner = owner, history = null, historyAsin = "", message = null) }
    }

    fun loadHistory(market: AmazonFreeMarket, rawAsin: String) = action {
        val owner = state.value.owner
        require(owner.isNotBlank()) { "Choose an owning profile first." }
        val asin = AmazonProducts.asin(rawAsin.trim()) ?: error("Enter a ten-character ASIN.")
        require(rawAsin.trim().length == 10) { "Enter a ten-character ASIN." }
        val result = repository.history(owner, market, asin)
        if (state.value.owner == owner) edits.update { it.copy(owner = owner, history = result, historyAsin = asin, historyMarket = market) }
    }

    fun saveWatch(market: AmazonFreeMarket, rawAsin: String, target: String, existing: AmazonWatchEntity? = null, onSaved: () -> Unit) = action {
        val owner = state.value.owner
        require(owner.isNotBlank()) { "Choose an owning profile first." }
        val asin = AmazonProducts.asin(rawAsin.trim()) ?: error("Enter a ten-character ASIN.")
        require(rawAsin.trim().length == 10)
        repository.saveWatch(owner, market, asin, target.trim(), { access.allowed(owner, network = false) }, existing?.id, existing?.generation)
        edits.update { it.copy(message = "Saved on this device. Awaiting matching offer data; background checks and notifications are off.") }
        onSaved()
    }

    fun pause(watch: AmazonWatchEntity, paused: Boolean) = action {
        require(watch.ownerProfileUid == state.value.owner)
        if (!paused) require(access.allowed(watch.ownerProfileUid, network = false)) { "Enable this plugin and local tools for the owning profile before resuming." }
        repository.pauseWatch(watch.ownerProfileUid, watch.id, watch.generation, paused)
    }

    fun delete(watch: AmazonWatchEntity) = action {
        require(watch.ownerProfileUid == state.value.owner)
        repository.deleteWatch(watch.ownerProfileUid, watch.id, watch.generation)
        edits.update { it.copy(message = "Watch deleted. Its observations are still available in History.") }
    }

    fun clearHistory() = action {
        repository.clearHistory(state.value.owner)
        edits.update { it.copy(history = null, message = "This profile's observations and check outcomes were cleared. Watches and request usage were preserved.") }
    }

    fun check(watch: AmazonWatchEntity) = action {
        require(watch.ownerProfileUid == state.value.owner && watch.state != "PAUSED") { "Resume this watch before checking." }
        val owner = watch.ownerProfileUid
        val allowed: suspend () -> Boolean = { access.allowed(owner, network = true) }
        require(allowed()) { "Enable Amazon Research Free and remote tools for the owning profile." }
        val market = requireNotNull(AmazonFreeMarket.fromDomain(watch.marketplace))
        var recorded = false
        val tool = AmazonNativeTool(
            provider,
            true,
            { settings.getFeatureSettings().pluginExecution[ToolPluginId.AMAZON_FREE] ?: PluginExecutionSettings() },
            allowed,
            access.changes(owner, network = true),
            onFetched = { requestId, requestedMarket, result ->
                repository.record(owner, requestId, result, allowed, requestedMarket = requestedMarket)
                repository.finishCheck(watch, requestId, result, allowed)
                recorded = true
            }
        )
        val result = tool.execute(
            "manual-watch-${watch.id}",
            buildJsonObject {
                put("marketplace", market.domain)
                put("asins", JsonArray(listOf(JsonPrimitive(watch.asin))))
            }
        )
        if (!recorded && allowed()) {
            val json = (result.content as? ToolResultContent.Json)?.value as? JsonObject
            val error = (json?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject
            val code = error?.let { AmazonProducts.text(it, "code") }?.let { runCatching { AmazonReadError.valueOf(it) }.getOrNull() } ?: AmazonReadError.NETWORK_ERROR
            repository.finishCheck(watch, UUID.randomUUID().toString(), AmazonFetchResult(emptyList(), listOf(AmazonItemFailure(code, "Check failed", watch.asin)), 0), allowed)
        }
        if (state.value.owner == owner) {
            val local = repository.history(owner, market, watch.asin)
            edits.update { it.copy(owner = owner, history = local, historyAsin = watch.asin, historyMarket = market, message = if (result.isError) "Check failed. See the watch's last outcome; no price-drop decision was made." else "Check completed. Listing observations were saved where currency was confirmed; offer context is still incomplete.") }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (edits.value.busy) return
        edits.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                edits.update { it.copy(message = failure.message ?: "Check the supplied fields and current profile grants.") }
            } catch (failure: IllegalStateException) {
                edits.update { it.copy(message = failure.message ?: "Check the supplied fields.") }
            } catch (_: Exception) {
                edits.update { it.copy(message = "Amazon data could not be saved or loaded. No background work was started.") }
            } finally {
                edits.update { it.copy(busy = false) }
            }
        }
    }
}
