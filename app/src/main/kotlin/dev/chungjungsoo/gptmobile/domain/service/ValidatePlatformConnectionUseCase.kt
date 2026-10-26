package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ValidationResult(
    val isSuccess: Boolean,
    val statusCode: Int? = null,
    val message: String
)

@Singleton
class ValidatePlatformConnectionUseCase @Inject constructor(
    private val networkClient: NetworkClient
) {
    suspend fun validate(platform: PlatformV2): ValidationResult = withContext(Dispatchers.IO) {
        val client = networkClient()
        val url = platform.apiUrl.trimEnd('/')
        val token = platform.token.orEmpty()
        // This is an explicit user connection test, so admit one fresh endpoint check.
        dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.checkAgain(url)

        if (url.isBlank()) {
            return@withContext ValidationResult(
                isSuccess = false,
                message = "API URL cannot be empty."
            )
        }

        try {
            when (platform.compatibleType) {
                ClientType.OLLAMA -> {
                    // Test /api/tags
                    val endpoint = dev.chungjungsoo.gptmobile.data.network.LocalServerUrls.ollamaTags(url)
                    val response = client.get(endpoint)
                    if (response.status.isSuccess()) {
                        ValidationResult(isSuccess = true, statusCode = response.status.value, message = "Connection successful.")
                    } else {
                        val body = response.bodyAsText().take(300)
                        ValidationResult(
                            isSuccess = false,
                            statusCode = response.status.value,
                            message = "HTTP ${response.status.value}: $body"
                        )
                    }
                }
                ClientType.NVIDIA, ClientType.OPENAI, ClientType.GROQ -> {
                    // Test GET /models with Bearer auth
                    val endpoint = if (url.endsWith("/v1")) "$url/models" else "$url/v1/models"
                    val response = client.get(endpoint) {
                        if (token.isNotBlank()) {
                            header("Authorization", "Bearer $token")
                        }
                    }
                    if (response.status.isSuccess()) {
                        ValidationResult(isSuccess = true, statusCode = response.status.value, message = "Connection successful.")
                    } else {
                        val body = response.bodyAsText().take(300)
                        ValidationResult(
                            isSuccess = false,
                            statusCode = response.status.value,
                            message = "HTTP ${response.status.value}: $body"
                        )
                    }
                }
                ClientType.ANTHROPIC -> {
                    // Test Anthropic models endpoint
                    val endpoint = if (url.endsWith("/v1")) "$url/models" else "$url/v1/models"
                    val response = client.get(endpoint) {
                        if (token.isNotBlank()) {
                            header("x-api-key", token)
                            header("anthropic-version", "2023-06-01")
                        }
                    }
                    if (response.status.isSuccess()) {
                        ValidationResult(isSuccess = true, statusCode = response.status.value, message = "Connection successful.")
                    } else {
                        val body = response.bodyAsText().take(300)
                        ValidationResult(
                            isSuccess = false,
                            statusCode = response.status.value,
                            message = "HTTP ${response.status.value}: $body"
                        )
                    }
                }
                ClientType.GOOGLE -> {
                    // Test Google Gemini models listing
                    val endpoint = "$url/v1beta/models?key=$token"
                    val response = client.get(endpoint)
                    if (response.status.isSuccess()) {
                        ValidationResult(isSuccess = true, statusCode = response.status.value, message = "Connection successful.")
                    } else {
                        val body = response.bodyAsText().take(300)
                        ValidationResult(
                            isSuccess = false,
                            statusCode = response.status.value,
                            message = "HTTP ${response.status.value}: $body"
                        )
                    }
                }
                ClientType.CUSTOM, ClientType.LLAMA, ClientType.FREE -> {
                    when (val result = dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.validate(platform.compatibleType, platform.apiUrl, token)) {
                        is dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.ValidationResult.Success -> ValidationResult(true, message = result.message)
                        is dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.ValidationResult.Error -> ValidationResult(false, message = result.message)
                        else -> ValidationResult(false, message = "Validation did not complete")
                    }
                }
                else -> {
                    // Lightweight GET ping
                    val response = client.get(url) {
                        if (token.isNotBlank()) {
                            header("Authorization", "Bearer $token")
                        }
                    }
                    if (response.status.isSuccess()) {
                        ValidationResult(isSuccess = true, statusCode = response.status.value, message = "Host reachable.")
                    } else {
                        ValidationResult(
                            isSuccess = false,
                            statusCode = response.status.value,
                            message = "HTTP ${response.status.value}"
                        )
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: UnknownHostException) {
            ValidationResult(
                isSuccess = false,
                message = "Unable to resolve host: ${e.message ?: "Check URL & network connection."}"
            )
        } catch (e: Exception) {
            ValidationResult(
                isSuccess = false,
                message = e.localizedMessage ?: e.message ?: "Connection failed."
            )
        }
    }
}
