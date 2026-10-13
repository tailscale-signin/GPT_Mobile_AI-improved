package dev.chungjungsoo.gptmobile.data.openrouter.account

import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.Instant
import java.util.Currency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Account amounts remain decimal strings: no conversion through Double. */
data class OpenRouterKeySnapshot(val data: JsonObject, val fetchedAt: Long = System.currentTimeMillis()) {
    val isManagement: Boolean get() = data.flag("is_management_key") == true || data.flag("is_provisioning_key") == true
    val expired: Boolean get() = data.text("expires_at")?.let { Instant.parse(it).isBefore(Instant.now()) } ?: false
    val remaining: String? get() = (data["effective_budget"] as? JsonObject)?.money("remaining_usd") ?: data.money("limit_remaining")
    val exhausted: Boolean get() = remaining?.toBigDecimalOrNull()?.signum()?.let { it <= 0 } == true

    companion object {
        fun parse(body: String): OpenRouterKeySnapshot {
            val data = (Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
                ?: throw OpenRouterAccountException(AccountFailure.MALFORMED)
            if (data.text("label").isNullOrBlank() ||
                data.flag("is_free_tier") == null ||
                data.flag("is_management_key") == null ||
                data.money("usage") == null
            ) {
                throw OpenRouterAccountException(AccountFailure.MALFORMED)
            }
            data.text("expires_at")?.let { runCatching { Instant.parse(it) }.getOrElse { throw OpenRouterAccountException(AccountFailure.MALFORMED) } }
            return OpenRouterKeySnapshot(data)
        }
    }
}

enum class AccountFailure { INVALID, EXPIRED, ROLE, FORBIDDEN, BILLING, RATE_LIMITED, NETWORK, MALFORMED, ENDPOINT }
class OpenRouterAccountException(val category: AccountFailure, val retryAfterSeconds: Long? = null) : IllegalStateException("OpenRouter account request: ${category.name}")

enum class OpenRouterTab { SETUP, ROUTING, ACCOUNT }

data class OpenRouterAccess(val verifiedRevision: String? = null) {
    fun unlocked(revision: String?): Boolean = revision != null && revision == verifiedRevision
    fun select(requested: OpenRouterTab, revision: String?): OpenRouterTab =
        if (requested == OpenRouterTab.SETUP || unlocked(revision)) requested else OpenRouterTab.SETUP
}

object OpenRouterIdentity {
    val endpoints = listOf("https://openrouter.ai/api/v1", "https://eu.openrouter.ai/api/v1", "https://us.openrouter.ai/api/v1")
    fun endpoint(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (normalized !in endpoints || uri == null || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw OpenRouterAccountException(AccountFailure.ENDPOINT)
        }
        return normalized
    }
    fun credential(value: String): String = value.trim().also { key ->
        require(key.isNotEmpty() && key.length <= 4096 && key.none { it.isWhitespace() || it.isISOControl() || it == ',' })
    }
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun revision(connection: String, endpoint: String, key: String): String = digest("$connection\u0000$endpoint\u0000$key")
}

fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
fun JsonObject.money(name: String): String? = get(name).decimal(allowNegative = name in setOf("remaining_usd", "limit_remaining"))
fun JsonElement?.decimal(allowNegative: Boolean = false): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    ?.takeIf { it.length <= 96 }?.toBigDecimalOrNull()?.takeIf { (allowNegative || it.signum() >= 0) && it.scale() in -18..24 && it.precision() <= 40 }
    ?.stripTrailingZeros()?.toPlainString()
fun usd(value: String?): String = value?.toBigDecimalOrNull()?.let { amount ->
    NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance("USD")
        maximumFractionDigits = 8
    }.format(amount) + " USD"
} ?: "—"
fun perMillion(value: String?): String? = value?.let { JsonPrimitive(it).decimal() }?.toBigDecimalOrNull()
    ?.multiply(BigDecimal(1000000))?.stripTrailingZeros()?.toPlainString()
fun JsonObject.rows(): List<Pair<String, String>> = entries.mapNotNull { (name, value) ->
    when (value) {
        is JsonPrimitive -> value.contentOrNull?.let { name to it }
        is JsonArray -> name to value.joinToString { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        else -> null
    }
}
