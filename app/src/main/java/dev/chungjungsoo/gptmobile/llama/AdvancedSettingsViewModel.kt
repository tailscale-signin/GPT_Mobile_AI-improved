package dev.chungjungsoo.gptmobile.llama

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.launch

@Parcelize
data class RouterModel(
    val id: String,
    val name: String,
    val type: String,
    val available: Boolean,
    val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val ctxSize: Int = 8192,
    val ngl: Int = 35,
    val threads: Int = 8,
    val status: String = "unloaded"
) : Parcelable

class AdvancedSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settings = MutableLiveData<AdvancedSettings>()
    val settings: LiveData<AdvancedSettings> = _settings

    private val _models = MutableLiveData<List<RouterModel>>()
    val models: LiveData<List<RouterModel>> = _models

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _saved = MutableLiveData<Boolean>()
    val saved: LiveData<Boolean> = _saved

    init {
        loadSettings()
        loadModels()
    }

    fun loadSettings() {
        viewModelScope.launch {
            val prefs = getApplication<Application>().getSharedPreferences("llama_settings", Context.MODE_PRIVATE)
            val json = prefs.getString("advanced_settings", null) ?: "{}"
            try {
                val gson = Gson()
                val type = object : TypeToken<AdvancedSettings>() {}.type
                val parsed = gson.fromJson(json, type)
                _settings.value = parsed ?: AdvancedSettings()
            } catch (e: Exception) {
                _error.value = "Failed to parse settings: ${e.message}"
                _settings.value = AdvancedSettings()
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val response = fetchModels()
                _models.value = response
            } catch (e: Exception) {
                _error.value = "Failed to load models: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun fetchModels(): List<RouterModel> {
        return listOf(
            RouterModel("llama3", "Llama 3 8B", "chat", true),
            RouterModel("mistral", "Mistral 7B", "chat", true),
            RouterModel("codellama", "Code Llama", "code", true),
            RouterModel("gemma", "Gemma 7B", "chat", true)
        )
    }

    fun updateSettings(settings: AdvancedSettings) {
        _settings.value = settings
        saveSettings(settings)
    }

    private fun saveSettings(settings: AdvancedSettings) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val prefs = getApplication<Application>().getSharedPreferences("llama_settings", Context.MODE_PRIVATE)
                prefs.edit().putString("advanced_settings", settings.toString()).apply()
                _saved.value = true
                _loading.value = false
            } catch (e: Exception) {
                _error.value = "Failed to save settings: ${e.message}"
                _loading.value = false
            }
        }
    }

    fun resetToDefaults() {
        _settings.value = AdvancedSettings()
        saveSettings(AdvancedSettings())
    }

    fun validateSettings(): List<String> {
        val errors = mutableListOf<String>()
        if (settings.value?.serverUrl?.isEmpty() == true) {
            errors.add("Server URL cannot be empty")
        }
        if (settings.value?.temperature?.let { it < 0f || it > 2f } == true) {
            errors.add("Temperature must be between 0 and 2")
        }
        return errors
    }
}