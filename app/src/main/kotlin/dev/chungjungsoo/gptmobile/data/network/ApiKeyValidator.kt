package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight probe validator for AI Platform API keys.
 * Tests connection and authentication against provider endpoints.
 */
object ApiKeyValidator {

    sealed class ValidationResult {
        data object Idle : ValidationResult()
        data object Validating : ValidationResult()
        data class Success(val message: String) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    suspend fun validate(clientType: ClientType, apiUrl: String, apiKey: String): ValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ValidationResult.Error("API key cannot be empty")
        }

        val testUrl = when (clientType) {
            ClientType.OPENAI -> "https://api.openai.com/v1/models"
            ClientType.ANTHROPIC -> "https://api.anthropic.com/v1/models"
            ClientType.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            ClientType.GROQ -> "https://api.groq.com/openai/v1/models"
            ClientType.OPENROUTER -> "https://openrouter.ai/api/v1/auth/key"
            ClientType.OLLAMA -> {
                val base = apiUrl.trim().trimEnd('/')
                if (base.isNotEmpty()) "$base/api/tags" else "http://localhost:11434/api/tags"
            }
            ClientType.CUSTOM -> {
                val base = apiUrl.trim().trimEnd('/')
                if (base.isNotEmpty()) "$base/models" else return@withContext ValidationResult.Success("Custom URL accepted")
            }
            ClientType.LITERT_LM -> return@withContext ValidationResult.Success("Local model does not require key validation")
        }

        try {
            val url = URL(testUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GPTMobile/1.0")
                when (clientType) {
                    ClientType.OPENAI, ClientType.GROQ, ClientType.CUSTOM -> {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    ClientType.ANTHROPIC -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    ClientType.OPENROUTER -> {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    ClientType.GOOGLE, ClientType.OLLAMA, ClientType.LITERT_LM -> {}
                }
            }

            val code = connection.responseCode
            if (code in 200..299) {
                ValidationResult.Success("API Key is valid and active (HTTP $code)")
            } else {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() }
                when (code) {
                    401 -> ValidationResult.Error("Unauthorized (401): Invalid API Key")
                    403 -> ValidationResult.Error("Forbidden (403): Key lacks permissions or is disabled")
                    429 -> ValidationResult.Error("Rate Limited (429): Quota exhausted or rate limit hit")
                    else -> ValidationResult.Error("Server returned HTTP $code: ${errorMsg?.take(100) ?: "Check key and configuration"}")
                }
            }
        } catch (e: Exception) {
            ValidationResult.Error("Connection error: ${e.localizedMessage ?: "Unable to reach server"}")
        }
    }
}
