package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.nvidia.NvidiaEndpoints
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
        if (clientType == ClientType.FREE) return@withContext validateFreeProvider(apiUrl)
        val keyFreeGateway = apiKey.isBlank() && clientType in setOf(ClientType.LLAMA, ClientType.CUSTOM) && apiUrl.isNotBlank()
        if (apiKey.isBlank() && !keyFreeGateway && clientType != ClientType.OLLAMA && clientType != ClientType.LITERT_LM) {
            return@withContext ValidationResult.Error("API key cannot be empty")
        }

        val testUrl = when (clientType) {
            ClientType.OPENAI -> "https://api.openai.com/v1/models"
            ClientType.ANTHROPIC -> "https://api.anthropic.com/v1/models"
            ClientType.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            ClientType.GROQ -> "https://api.groq.com/openai/v1/models"
            ClientType.OPENROUTER -> "https://openrouter.ai/api/v1/auth/key"
            ClientType.LLAMA -> {
                val base = apiUrl.trim().trimEnd('/')
                if (base.isNotEmpty()) "$base/models" else "https://api.llama.com/v1/models"
            }
            ClientType.OLLAMA -> {
                if (apiUrl.isNotBlank()) LocalServerUrls.ollamaTags(apiUrl) else "http://localhost:11434/api/tags"
            }
            ClientType.NVIDIA -> {
                val base = runCatching { NvidiaEndpoints.normalize(apiUrl) }.getOrElse {
                    return@withContext ValidationResult.Error("Invalid NVIDIA API URL.")
                }
                "$base/models"
            }
            ClientType.FREE, ClientType.CUSTOM -> {
                val base = apiUrl.trim().trimEnd('/')
                if (base.isNotEmpty()) "${LocalServerUrls.openAiBase(base)}/models" else return@withContext ValidationResult.Error("Enter a server URL")
            }
            ClientType.LITERT_LM -> return@withContext ValidationResult.Success("Local model does not require key validation")
        }

        try {
            val url = URL(testUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GPTMobile/1.0")
                when (clientType) {
                    ClientType.NVIDIA, ClientType.OPENAI, ClientType.GROQ, ClientType.LLAMA, ClientType.CUSTOM -> {
                        if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    ClientType.ANTHROPIC -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    ClientType.OPENROUTER -> {
                        if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    ClientType.GOOGLE, ClientType.OLLAMA, ClientType.LITERT_LM, ClientType.FREE -> {}
                }
            }

            try {
                val code = connection.responseCode
                if (code in 200..299) {
                    if (clientType in setOf(ClientType.CUSTOM, ClientType.LLAMA, ClientType.OLLAMA)) {
                        val bytes = connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() <= 2_000_000) {
                                val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                                if (count < 0) break
                                if (count > 0) output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(bytes.size <= 2_000_000) { "Model catalog is too large" }
                        val root = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as? kotlinx.serialization.json.JsonObject
                        require(root?.get(if (clientType == ClientType.OLLAMA) "models" else "data") is kotlinx.serialization.json.JsonArray) { "The server did not return a compatible model catalog." }
                    }
                    ValidationResult.Success(if (keyFreeGateway) "Anonymous server connection succeeded (HTTP $code)" else "Connection successful (HTTP $code)")
                } else {
                    val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    when (code) {
                        401 -> ValidationResult.Error("Unauthorized (401): Invalid API Key")
                        403 -> ValidationResult.Error("Forbidden (403): Key lacks permissions or is disabled")
                        429 -> ValidationResult.Error("Rate Limited (429): Quota exhausted or rate limit hit")
                        else -> ValidationResult.Error("Server returned HTTP $code: ${errorMsg?.take(100) ?: "Check key and configuration"}")
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ValidationResult.Error("Connection error: ${e.localizedMessage ?: "Unable to reach server"}")
        }
    }
    private suspend fun validateFreeProvider(apiUrl: String): ValidationResult {
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(apiUrl)
            ?: return ValidationResult.Error("Choose a Free service first.")
        if (!provider.isAvailable) return ValidationResult.Error("This service requires provider approval for this build.")
        dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.refresh(provider, true)
        val status = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.value[provider]
        return if (status?.reachable == true) ValidationResult.Success(status.message) else ValidationResult.Error(status?.message ?: "Service unavailable")
    }
}
