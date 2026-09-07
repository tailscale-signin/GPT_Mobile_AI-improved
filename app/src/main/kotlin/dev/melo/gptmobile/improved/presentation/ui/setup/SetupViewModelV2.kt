package dev.melo.gptmobile.improved.presentation.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.model.ClientType
import dev.melo.gptmobile.improved.data.model.LocalModelCatalog
import dev.melo.gptmobile.improved.data.model.LocalModelCatalogEntry
import dev.melo.gptmobile.improved.data.repository.LocalModelRepository
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import dev.melo.gptmobile.improved.presentation.ui.localmodel.HuggingFaceAuthClient
import dev.melo.gptmobile.improved.presentation.ui.localmodel.LocalModelDownloadDialog
import dev.melo.gptmobile.improved.presentation.ui.localmodel.LocalModelDownloadState
import dev.melo.gptmobile.improved.presentation.ui.localmodel.LocalModelDownloadViewModelDelegate
import dev.melo.gptmobile.improved.presentation.ui.setting.LocalModelListItem
import dev.melo.gptmobile.improved.presentation.ui.setting.toLocalModelStatus
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SetupViewModelV2 @Inject constructor(
    private val settingRepository: SettingRepository,
    private val localModelRepository: LocalModelRepository,
    private val huggingFaceAuthClient: HuggingFaceAuthClient
) : ViewModel() {

    private val localModelDelegate = LocalModelDownloadViewModelDelegate(
        localModelRepository = localModelRepository,
        huggingFaceAuthClient = huggingFaceAuthClient,
        scope = viewModelScope
    )

    // Platforms list
    private val _platforms = MutableStateFlow<List<PlatformV2>>(emptyList())
    val platforms: StateFlow<List<PlatformV2>> = _platforms.asStateFlow()

    private val _activePlatformId = MutableStateFlow<String?>(null)
    val activePlatformId: StateFlow<String?> = _activePlatformId.asStateFlow()

    // Wizard state
    private val _wizardStep = MutableStateFlow(0)
    val wizardStep: StateFlow<Int> = _wizardStep.asStateFlow()

    private val _selectedClientType = MutableStateFlow<ClientType?>(null)
    val selectedClientType: StateFlow<ClientType?> = _selectedClientType.asStateFlow()

    private val _platformName = MutableStateFlow("")
    val platformName: StateFlow<String> = _platformName.asStateFlow()

    private val _apiUrl = MutableStateFlow("")
    val apiUrl: StateFlow<String> = _apiUrl.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow("")
    val model: StateFlow<String> = _model.asStateFlow()

    // Download state for LiteRT models
    val localModelDownloadState: StateFlow<LocalModelDownloadState> = localModelDelegate.state

    // Available catalog local models for LiteRT selection
    val catalogLocalModels: StateFlow<List<LocalModelListItem>> = combine(
        localModelRepository.installedModels,
        localModelRepository.downloadProgress
    ) { installed, progresses ->
        LocalModelCatalog.entries.map { entry ->
            LocalModelListItem(
                entry = entry,
                status = toLocalModelStatus(
                    entry = entry,
                    installedModels = installed,
                    downloadProgresses = progresses
                ),
                installed = installed.any { it.catalogEntryId == entry.id }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Installed LiteRT models (for legacy/direct model picker)
    val localModels: StateFlow<List<LocalModelCatalogEntry>> = localModelRepository.installedModels
        .combine(catalogLocalModels) { installed, catalog ->
            installed.mapNotNull { model ->
                catalog.firstOrNull { it.entry.id == model.catalogEntryId }?.entry
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Events
    private val _messageFlow = MutableSharedFlow<String>()
    val messageFlow: SharedFlow<String> = _messageFlow.asSharedFlow()

    val canProceed: StateFlow<Boolean> = combine(
        _wizardStep,
        _selectedClientType,
        _platformName,
        _apiUrl,
        _apiKey,
        _model
    ) { step, clientType, name, url, key, mdl ->
        when (step) {
            WIZARD_STEP_BASICS -> {
                val hasName = name.isNotBlank()
                val hasValidUrl = clientType == ClientType.LITERT_LM || url.isNotBlank()
                hasName && hasValidUrl
            }
            WIZARD_STEP_API_KEY -> {
                // LiteRT LM and self-hosted don't necessarily require an API key
                clientType == ClientType.LITERT_LM || clientType?.isSelfHosted == true || key.isNotBlank()
            }
            WIZARD_STEP_MODEL -> {
                mdl.isNotBlank()
            }
            else -> false
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isWaitingForDownload: StateFlow<Boolean> = combine(
        _selectedClientType,
        _model,
        localModelRepository.installedModels
    ) { clientType, selectedModel, installed ->
        clientType == ClientType.LITERT_LM &&
            selectedModel.isNotBlank() &&
            installed.none { it.catalogEntryId == selectedModel }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        loadPlatforms()
        viewModelScope.launch {
            localModelDelegate.events.collect { message ->
                _messageFlow.emit(message)
            }
        }
    }

    private fun loadPlatforms() {
        viewModelScope.launch {
            val list = settingRepository.fetchPlatformV2s()
            _platforms.value = list
            if (_activePlatformId.value == null) {
                _activePlatformId.value = list.firstOrNull { it.enabled }?.uid ?: list.firstOrNull()?.uid
            }
        }
    }

    fun selectClientType(clientType: ClientType) {
        _selectedClientType.value = clientType
        // Set default values based on client type
        _platformName.value = getDefaultPlatformName(clientType)
        _apiUrl.value = getDefaultApiUrl(clientType)
        _model.value = getDefaultModel(clientType)
        _apiKey.value = ""
        _wizardStep.value = 0
    }

    fun updatePlatformName(name: String) {
        _platformName.value = name
    }

    fun updateApiUrl(url: String) {
        _apiUrl.value = url
    }

    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    fun updateModel(model: String) {
        _model.value = model
    }

    fun selectLocalModel(modelId: String) {
        _model.value = modelId
    }

    fun wizardTotalSteps(): Int = if (_selectedClientType.value == ClientType.LITERT_LM) 2 else 3

    fun wizardDisplayStep(): Int = when {
        _selectedClientType.value == ClientType.LITERT_LM && _wizardStep.value == WIZARD_STEP_MODEL -> 1
        else -> _wizardStep.value
    }

    fun nextWizardStep() {
        val current = _wizardStep.value
        if (_selectedClientType.value == ClientType.LITERT_LM && current == WIZARD_STEP_BASICS) {
            _wizardStep.value = WIZARD_STEP_MODEL
        } else if (current < WIZARD_STEP_MODEL) {
            _wizardStep.value = current + 1
        }
    }

    fun previousWizardStep() {
        val current = _wizardStep.value
        if (_selectedClientType.value == ClientType.LITERT_LM && current == WIZARD_STEP_MODEL) {
            _wizardStep.value = WIZARD_STEP_BASICS
        } else if (current > 0) {
            _wizardStep.value = current - 1
        }
    }

    fun resetWizard() {
        _wizardStep.value = 0
        _selectedClientType.value = null
        _platformName.value = ""
        _apiUrl.value = ""
        _apiKey.value = ""
        _model.value = ""
    }

    fun savePlatform() {
        val clientType = _selectedClientType.value ?: return
        val name = _platformName.value.trim()
        val url = if (clientType == ClientType.LITERT_LM) "" else _apiUrl.value.trim()
        val key = if (clientType == ClientType.LITERT_LM) "" else _apiKey.value.trim()
        val mdl = _model.value.trim()

        viewModelScope.launch {
            val uid = UUID.randomUUID().toString()
            val platform = PlatformV2(
                name = name,
                uid = uid,
                compatibleType = clientType,
                apiUrl = url,
                token = key.takeIf { it.isNotBlank() },
                model = mdl,
                enabled = true
            )
            settingRepository.addPlatformV2(platform)
            _activePlatformId.value = uid
            loadPlatforms()
            resetWizard()
        }
    }

    fun deletePlatform(platformId: Int) {
        viewModelScope.launch {
            val platform = _platforms.value.firstOrNull { it.id == platformId }
            if (platform != null) {
                settingRepository.deletePlatformV2(platform)
                loadPlatforms()
            }
        }
    }

    fun setActivePlatform(platformUid: String) {
        viewModelScope.launch {
            _activePlatformId.value = platformUid
            val updated = _platforms.value.map {
                it.copy(enabled = it.uid == platformUid)
            }
            updated.forEach { settingRepository.updatePlatformV2(it) }
            _platforms.value = updated
        }
    }

    // LiteRT Download Methods
    fun confirmRamWarning() = localModelDelegate.confirmRamWarning()
    fun confirmMeteredDownload() = localModelDelegate.confirmMeteredDownload()
    fun dismissDownloadDialog() = localModelDelegate.dismissDialog()
    fun startHuggingFaceSignIn() = localModelDelegate.startHuggingFaceSignIn()
    fun onAuthActivityResult(resultCode: Int, data: android.content.Intent?) = localModelDelegate.onAuthActivityResult(resultCode, data)
    fun onLicenseTabClosed() = localModelDelegate.onLicenseTabClosed()
    fun retryAfterLicense() = localModelDelegate.retryAfterLicense()
    fun openAccessTokenDialog() = localModelDelegate.openAccessTokenDialog()
    fun saveHuggingFaceAccessToken(token: String) = localModelDelegate.saveHuggingFaceAccessToken(token)

    private fun getDefaultPlatformName(clientType: ClientType): String = when (clientType) {
        ClientType.OPENAI -> "OpenAI"
        ClientType.ANTHROPIC -> "Anthropic"
        ClientType.GOOGLE -> "Google AI"
        ClientType.GROQ -> "Groq"
        ClientType.OLLAMA -> "Ollama (Local)"
        ClientType.OPENAI_COMPATIBLE -> "OpenAI Compatible"
        ClientType.LM_STUDIO -> "LM Studio"
        ClientType.VLLM -> "vLLM"
        ClientType.OPENROUTER -> "OpenRouter"
        ClientType.CUSTOM -> "Custom OpenAI"
        ClientType.LITERT_LM -> "On-Device AI"
    }

    private fun getDefaultApiUrl(clientType: ClientType): String = when (clientType) {
        ClientType.OPENAI -> "https://api.openai.com/v1/"
        ClientType.ANTHROPIC -> "https://api.anthropic.com/v1/"
        ClientType.GOOGLE -> "https://generativelanguage.googleapis.com/"
        ClientType.GROQ -> "https://api.groq.com/openai/v1/"
        ClientType.OLLAMA -> "http://localhost:11434/v1/"
        ClientType.OPENAI_COMPATIBLE -> "http://localhost:8000/v1/"
        ClientType.LM_STUDIO -> "http://localhost:1234/v1/"
        ClientType.VLLM -> "http://localhost:8000/v1/"
        ClientType.OPENROUTER -> "https://openrouter.ai/api/v1/"
        ClientType.CUSTOM -> "https://api.openai.com/v1/"
        ClientType.LITERT_LM -> ""
    }

    private fun getDefaultModel(clientType: ClientType): String = when (clientType) {
        ClientType.OPENAI -> "gpt-4o"
        ClientType.ANTHROPIC -> "claude-3-5-sonnet-latest"
        ClientType.GOOGLE -> "gemini-2.5-flash"
        ClientType.GROQ -> "llama-3.3-70b-versatile"
        ClientType.OLLAMA -> "llama3.2"
        ClientType.OPENAI_COMPATIBLE -> "default"
        ClientType.LM_STUDIO -> "default"
        ClientType.VLLM -> "default"
        ClientType.OPENROUTER -> "meta-llama/llama-3.3-70b-instruct"
        ClientType.CUSTOM -> "gpt-4o"
        ClientType.LITERT_LM -> LocalModelCatalog.DEFAULT_MODEL_ID
    }

    companion object {
        const val WIZARD_STEP_BASICS = 0
        const val WIZARD_STEP_API_KEY = 1
        const val WIZARD_STEP_MODEL = 2
    }
}
