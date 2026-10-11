package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardRepository
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardSubmission
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardSubmissionStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ScoreboardShareState(val accounts: List<ToolConnection> = emptyList(), val busy: Boolean = false, val message: String? = null, val issue: Long? = null, val status: ScoreboardSubmissionStatus.State? = null)

@HiltViewModel
class ScoreboardViewModel @Inject constructor(store: BenchmarkStore, private val repository: ScoreboardRepository) : ViewModel() {
    val submissions = store.history.map { runs -> runs.filter { it.finished } }.distinctUntilChanged()
        .map { history -> history.mapNotNull(BenchmarkScoreboard::fromRun) }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val local = submissions.map(BenchmarkScoreboard::best).flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val online = repository.state
    private val mutableShare = MutableStateFlow(ScoreboardShareState())
    val share = mutableShare.asStateFlow()
    private var refreshing = false
    private var submissionAccount: String? = null

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                repository.refresh()
            } finally {
                refreshing = false
            }
        }
    }

    fun prepareShare() {
        if (mutableShare.value.busy) return
        mutableShare.value = ScoreboardShareState(busy = true)
        viewModelScope.launch {
            try {
                mutableShare.value = ScoreboardShareState(accounts = repository.sharingAccounts())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableShare.value = ScoreboardShareState(message = "Could not load GitHub connections. Reopen sharing to retry.")
            }
        }
    }

    fun publish(data: ScoreboardSubmission, account: String) {
        if (mutableShare.value.busy || mutableShare.value.issue != null) return
        submissionAccount = account
        mutableShare.value = mutableShare.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val issue = repository.submit(data, account)
                mutableShare.value = mutableShare.value.copy(busy = false, issue = issue, status = ScoreboardSubmissionStatus.State.PENDING, message = "Submitted as #$issue. Waiting for validation.")
                repeat(6) { attempt ->
                    delay(if (attempt == 0) 2_000 else 5_000)
                    val status = checkSubmissionStatus(issue, account)
                    if (status != null && status.state != ScoreboardSubmissionStatus.State.PENDING) return@launch
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableShare.value = mutableShare.value.copy(busy = false, message = error.message?.take(240) ?: "Could not share this result.")
            }
        }
    }

    fun checkSubmissionStatus() {
        val current = mutableShare.value
        val issue = current.issue ?: return
        val account = submissionAccount ?: return
        viewModelScope.launch { checkSubmissionStatus(issue, account) }
    }

    private suspend fun checkSubmissionStatus(issue: Long, account: String): ScoreboardSubmissionStatus? {
        return try {
            val status = repository.submissionStatus(issue, account)
            mutableShare.value = mutableShare.value.copy(status = status.state, message = status.message)
            status
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableShare.value = mutableShare.value.copy(message = "Could not check validation status. Try again shortly.")
            null
        }
    }
}
