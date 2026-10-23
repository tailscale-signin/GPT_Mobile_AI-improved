package dev.chungjungsoo.gptmobile.presentation.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(private val settingRepositoryProvider: javax.inject.Provider<SettingRepository>, private val savedState: androidx.lifecycle.SavedStateHandle) : ViewModel() {

    sealed class SplashEvent {
        data object OpenIntro : SplashEvent()
        data object OpenHome : SplashEvent()
        data object OpenMigrate : SplashEvent()
    }

    private val _isReady: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()

    private val _event: MutableSharedFlow<SplashEvent> = MutableSharedFlow(replay = 1)
    val event: SharedFlow<SplashEvent> = _event.asSharedFlow()

    private var startupJob: kotlinx.coroutines.Job? = null
    private val navigation = NotificationNavigationQueue(savedState)
    val pendingNavigation = navigation.pending
    fun enqueueNavigation(route: String, identity: String = java.util.UUID.randomUUID().toString()) = navigation.enqueue(route, identity)
    fun consumeNavigation(event: String) = navigation.consume(event)

    init {
        retryStartup()
    }
    fun retryStartup() {
        if (startupJob?.isActive == true) return
        _startupError.value = null
        startupJob = viewModelScope.launch {
            try {
                StartupRecoveryGate.retry()
                StartupRecoveryGate.await()
                val settingRepository = settingRepositoryProvider.get()
                val platforms = settingRepository.fetchPlatforms()
                val platformV2s = settingRepository.fetchPlatformV2s()
                when {
                    platforms.all { !it.enabled && it.token == null } && platformV2s.isEmpty() -> sendSplashEvent(SplashEvent.OpenIntro)
                    platformV2s.isEmpty() -> sendSplashEvent(SplashEvent.OpenMigrate)
                    else -> sendSplashEvent(SplashEvent.OpenHome)
                }
                setAsReady()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _startupError.value = dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact("${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(2000)}")
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Startup", "RECOVERY_FAILED · ${failure.javaClass.simpleName}", "E")
            }
        }
    }

    private suspend fun sendSplashEvent(event: SplashEvent) {
        _event.emit(event)
    }

    private fun setAsReady() {
        _isReady.update { true }
    }
}
