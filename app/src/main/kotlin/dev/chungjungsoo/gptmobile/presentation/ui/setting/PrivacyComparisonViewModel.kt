package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonFailure
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonResult
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonRunner
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonStatus
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Display values only: credentials and saved instructions never enter Compose state. */
internal data class PrivacyComparisonProfileChoice(
    val uid: String,
    val name: String,
    val model: String,
    val provider: ClientType
) {
    val onDevice: Boolean get() = provider == ClientType.LITERT_LM
}

internal data class PrivacyComparisonUiState(
    val profiles: List<PrivacyComparisonProfileChoice> = emptyList(),
    val selectedUid: String? = null,
    val running: Boolean = false,
    val cancelRequested: Boolean = false,
    val runProfile: PrivacyComparisonProfileChoice? = null,
    val result: PrivacyComparisonResult? = null
) {
    val selectedProfile: PrivacyComparisonProfileChoice? get() = profiles.singleOrNull { it.uid == selectedUid }
}

@HiltViewModel
class PrivacyComparisonViewModel internal constructor(
    profiles: Flow<List<PlatformV2>>,
    private val compare: suspend (PlatformV2, String, (PrivacyComparisonResult) -> Unit) -> PrivacyComparisonResult
) : ViewModel() {
    @Inject constructor(settings: SettingRepository, chats: ChatRepository) : this(
        settings.observePlatformV2s(),
        { profile, runId, onUpdate ->
            PrivacyComparisonRunner(
                openSession = { caseId, protection, requestId, deadlineMs ->
                    chats.openPrivacyComparisonSession(profile, caseId, protection, requestId, deadlineMs)
                }
            ).run(runId, onUpdate)
        }
    )

    private val mutableState = MutableStateFlow(PrivacyComparisonUiState())
    internal val state = mutableState.asStateFlow()
    private var savedProfiles: List<PlatformV2> = emptyList()
    private var activeProfile: PlatformV2? = null
    private var runJob: Job? = null
    private var cancellationReason: PrivacyComparisonFailure? = null

    init {
        viewModelScope.launch {
            profiles.collect { observed ->
                savedProfiles = observed.filter { it.enabled }
                val choices = savedProfiles.map { it.choice() }
                mutableState.update { current ->
                    current.copy(
                        profiles = choices,
                        selectedUid = current.selectedUid?.takeIf { uid -> choices.count { it.uid == uid } == 1 }
                    )
                }
                if (activeProfile?.let { active -> savedProfiles.count { it == active } != 1 } == true) {
                    stop(PrivacyComparisonFailure.PROFILE_CHANGED)
                }
            }
        }
    }

    internal fun selectProfile(uid: String) {
        if (mutableState.value.running || savedProfiles.count { it.uid == uid } != 1) return
        mutableState.update { it.copy(selectedUid = uid) }
    }

    internal fun runComparison() {
        if (mutableState.value.running || runJob != null) return
        val profile = savedProfiles.singleOrNull { it.uid == mutableState.value.selectedUid }?.copy() ?: return
        val runId = UUID.randomUUID().toString()
        activeProfile = profile
        cancellationReason = null
        mutableState.update {
            it.copy(running = true, cancelRequested = false, runProfile = profile.choice(forRun = true), result = PrivacyComparisonResult(runId))
        }
        // Assign before starting so an immediate completion cannot leave a stale active Job.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = compare(profile, runId) { update ->
                    if (update.runId == runId) {
                        mutableState.update { current ->
                            if (current.result?.runId == runId) current.copy(result = update) else current
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(result = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        result = (it.result ?: PrivacyComparisonResult(runId)).copy(
                            status = PrivacyComparisonStatus.INCOMPLETE,
                            failure = PrivacyComparisonFailure.INITIALIZATION_FAILED,
                            activeCaseId = null,
                            activeArm = null
                        )
                    )
                }
            }
        }
        runJob = job
        // Completion also runs if Cancel wins before the coroutine body starts.
        job.invokeOnCompletion { cause ->
            if (runJob === job) {
                if (cause is CancellationException) {
                    val reason = cancellationReason ?: PrivacyComparisonFailure.CANCELLED
                    mutableState.update {
                        it.copy(
                            result = (it.result ?: PrivacyComparisonResult(runId)).copy(
                                status = if (reason == PrivacyComparisonFailure.CANCELLED) PrivacyComparisonStatus.CANCELLED else PrivacyComparisonStatus.INCOMPLETE,
                                failure = reason,
                                activeCaseId = null,
                                activeArm = null
                            )
                        )
                    }
                }
                activeProfile = null
                runJob = null
                mutableState.update { it.copy(running = false, cancelRequested = false) }
            }
        }
        job.start()
    }

    internal fun cancel() = stop(PrivacyComparisonFailure.CANCELLED)

    private fun stop(reason: PrivacyComparisonFailure) {
        if (!mutableState.value.running) return
        if (cancellationReason == null) cancellationReason = reason
        mutableState.update { it.copy(cancelRequested = true) }
        runJob?.cancel()
    }
}

private fun PlatformV2.choice(forRun: Boolean = false): PrivacyComparisonProfileChoice {
    // Match benchmarkProfile's synchronous model normalization without parsing saved options in the UI.
    val displayedModel = if (forRun && compatibleType == ClientType.OPENROUTER) model.removeSuffix(":batch") else model
    return PrivacyComparisonProfileChoice(uid, name, displayedModel, compatibleType)
}
