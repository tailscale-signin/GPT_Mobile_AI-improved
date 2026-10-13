package dev.chungjungsoo.gptmobile.data.pricing

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

@Serializable
enum class PriceSource { PROVIDER_API, REVIEWED, ACCOUNT_OVERRIDE }
enum class PriceFreshness { FRESH, STALE, EXPIRED }

/** Exact strings at storage boundaries; no binary floating-point money. */
@Serializable
data class ModelPrice(
    val provider: String,
    val model: String,
    val inputPerMillion: String,
    val outputPerMillion: String,
    val currency: String = "USD",
    val source: PriceSource,
    val sourceUrl: String,
    val validatedAt: Long = 0,
    val reviewedOn: String? = null,
    val validUntil: Long? = null,
    val cachedInputPerMillion: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val adapterVersion: Int = 1,
    val routingReference: Boolean = false,
    val conditionalPricing: Boolean = false
) {
    fun freshness(now: Long): PriceFreshness {
        val observed = if (source == PriceSource.PROVIDER_API) validatedAt else reviewTime(reviewedOn) ?: return PriceFreshness.EXPIRED
        if (observed <= 0 || observed > now || validUntil?.let { now >= it } == true) return PriceFreshness.EXPIRED
        val age = now - observed
        val fresh = if (source == PriceSource.PROVIDER_API) 3_600_000L else 7 * 86_400_000L
        val expiry = if (source == PriceSource.PROVIDER_API) 72 * 3_600_000L else 30 * 86_400_000L
        return when {
            age <= fresh -> PriceFreshness.FRESH
            age <= expiry -> PriceFreshness.STALE
            else -> PriceFreshness.EXPIRED
        }
    }
    fun valid(): Boolean = provider.isNotBlank() &&
        model.isNotBlank() &&
        model.length <= 256 &&
        currency.matches(Regex("[A-Z]{3}")) &&
        decimalRate(inputPerMillion) != null &&
        decimalRate(outputPerMillion) != null &&
        (cachedInputPerMillion == null || decimalRate(cachedInputPerMillion) != null) &&
        extras.size <= 32 &&
        extras.all { (key, value) -> key.length in 1..120 && decimalRate(value) != null } &&
        sourceUrl.length <= 1000 &&
        (source != PriceSource.PROVIDER_API || validatedAt > 0) &&
        adapterVersion == 1
}

@Serializable
data class ManualPrice(val contextKey: String, val price: ModelPrice)

fun decimalRate(raw: String?): BigDecimal? = raw?.takeIf { it.length in 1..64 }?.toBigDecimalOrNull()
    ?.takeIf { it.signum() >= 0 && it.precision() <= 24 && it.scale() in -12..24 }
fun perMillion(raw: String?): String? = decimalRate(raw)?.multiply(BigDecimal("1000000"))?.stripTrailingZeros()?.toPlainString()
fun reviewTime(date: String?): Long? = runCatching { LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
fun formatRate(raw: String): String = decimalRate(raw)?.stripTrailingZeros()?.toPlainString() ?: "?"

/** Transport enums are not billing identities. Paths distinguish custom deployments. */
fun billingProvider(profile: PlatformV2): String? {
    if (profile.compatibleType == ClientType.LITERT_LM) return "ON_DEVICE"
    val defaults = mapOf(ClientType.OPENAI to "api.openai.com", ClientType.ANTHROPIC to "api.anthropic.com", ClientType.GOOGLE to "generativelanguage.googleapis.com", ClientType.GROQ to "api.groq.com", ClientType.OPENROUTER to "openrouter.ai", ClientType.NVIDIA to "integrate.api.nvidia.com")
    val defaultHost = defaults[profile.compatibleType]
    if (profile.apiUrl.isBlank()) return defaultHost?.let { profile.compatibleType.name }
    val uri = runCatching { URI(profile.apiUrl) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null) return null
    val allowedPaths = when (profile.compatibleType) {
        ClientType.OPENROUTER -> setOf("", "/", "/api/v1", "/api/v1/")
        ClientType.GROQ -> setOf("", "/", "/openai/v1", "/openai/v1/")
        ClientType.GOOGLE -> setOf("", "/", "/v1", "/v1/", "/v1beta", "/v1beta/")
        else -> setOf("", "/", "/v1", "/v1/")
    }
    return profile.compatibleType.name.takeIf { uri.host.equals(defaultHost, true) && uri.path in allowedPaths }
}

/** Local-only scope; no endpoint, credential, or contract note is published. */
fun priceContextKey(profile: PlatformV2): String = listOf(profile.providerConnectionUid?.takeIf(String::isNotBlank) ?: profile.uid, profile.apiUrl.trimEnd('/'), profile.compatibleType.name, profile.model, profile.batchMode.toString(), profile.openRouterRouting.orEmpty()).joinToString("\u0000")
