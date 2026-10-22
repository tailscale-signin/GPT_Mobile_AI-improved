package dev.chungjungsoo.gptmobile.data.openrouter.account

import dev.chungjungsoo.gptmobile.data.network.BoundedResponseText
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Deliberately separate from inference logging. Only approved GET resources; redirects never carry credentials. */
@Singleton
class OpenRouterAccountClient internal constructor(private val connectionFactory: (URL) -> HttpURLConnection) {
    @Inject constructor() : this({ it.openConnection() as HttpURLConnection })
    suspend fun key(endpoint: String, credential: String): OpenRouterKeySnapshot = OpenRouterKeySnapshot.parse(get(endpoint, credential, "key"))

    suspend fun get(endpoint: String, credential: String, resource: String): String = withContext(Dispatchers.IO) {
        val base = OpenRouterIdentity.endpoint(endpoint)
        val key = OpenRouterIdentity.credential(credential)
        require(
            resource in setOf("key", "models/user", "providers", "credits", "activity", "endpoints/zdr") ||
                resource.matches(Regex("models/[A-Za-z0-9_.%:-]+/[A-Za-z0-9_.%:-]+/endpoints")) ||
                resource.matches(Regex("generation\\?id=[A-Za-z0-9_.%:-]+"))
        )
        val connection = connectionFactory(URL("$base/$resource")).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw OpenRouterAccountException(
                    when (status) {
                        401 -> AccountFailure.INVALID
                        402 -> AccountFailure.BILLING
                        403 -> AccountFailure.FORBIDDEN
                        429 -> AccountFailure.RATE_LIMITED
                        else -> AccountFailure.NETWORK
                    },
                    retryAfterSeconds(connection.getHeaderField("Retry-After")) ?: if (status == 429) 60 else null
                )
            }
            ensureActive()
            val body = connection.inputStream.use { BoundedResponseText.read(it, if (resource == "models/user") 8 * 1024 * 1024 else 2 * 1024 * 1024) }
            ensureActive()
            body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun objectResource(endpoint: String, credential: String, resource: String): JsonObject =
        (Json.parseToJsonElement(get(endpoint, credential, resource)) as? JsonObject)
            ?: throw OpenRouterAccountException(AccountFailure.MALFORMED)
}

internal fun retryAfterSeconds(value: String?, nowSeconds: Long = System.currentTimeMillis() / 1000): Long? =
    value?.trim()?.let { header ->
        header.toLongOrNull()?.takeIf { it >= 0 } ?: runCatching {
            (java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - nowSeconds).coerceAtLeast(0)
        }.getOrNull()
    }
