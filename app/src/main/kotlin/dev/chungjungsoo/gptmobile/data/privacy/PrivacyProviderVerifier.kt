package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.BoundedResponseText
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Singleton
class PrivacyProviderVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    network: dev.chungjungsoo.gptmobile.data.network.NetworkClient,
    internal val authority: ProviderApprovalSource,
    private val router: OpenRouterAccountClient
) {
    internal data class Result(val decision: PrivacyProfileEligibility.Decision, val route: VerifiedPrivateRoute? = null)

    internal val preparation = dev.chungjungsoo.gptmobile.data.network.ModelRequestPreparation(network)
    internal val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(120, TimeUnit.SECONDS).build()

    private val metadataHttp = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    internal suspend fun alternatives(profiles: List<PlatformV2>, excludedUid: String): String {
        val available = profiles.filter { it.enabled && it.uid != excludedUid }
        val names = available.filter { it.compatibleType == ClientType.LITERT_LM }.take(3).map { it.name }.toMutableList()
        if (names.isEmpty()) {
            val recent = context.getSharedPreferences("privacy_route_checks", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            for (profile in available.filter { profile ->
                val record = recent.getString(identity(profile), null)?.split('|')
                record?.getOrNull(1) == "true" && record.firstOrNull()?.toLongOrNull()?.let { it in (now - 60000)..now } == true
            }.take(3)) {
                if (verify(profile).decision.eligible) names += profile.name
            }
        }
        return if (names.isEmpty()) "Open New Chat to recheck available profiles." else "Available profiles: " + names.joinToString() + "."
    }

    private fun identity(profile: PlatformV2) = OpenRouterIdentity.digest(listOf(profile.uid, profile.compatibleType.name, profile.apiUrl, profile.model, OpenRouterIdentity.digest(profile.token.orEmpty())).joinToString("\u0000"))

    internal fun approval(profile: PlatformV2, url: String, terms: String): ProviderApproval? = runCatching {
        authority.records().singleOrNull { it.covers(profile, url, terms, System.currentTimeMillis()) }
    }.getOrNull()

    internal suspend fun verify(profile: PlatformV2): Result = withContext(Dispatchers.IO) {
        val base = PrivacyProfileEligibility.evaluate(profile, true)
        val gateway = VerifiedPrivateRoute.gateway(profile)
        val remote = VerifiedPrivateRoute.providerEndpoint(profile)
        val result = try {
            when {
                profile.compatibleType == ClientType.LITERT_LM -> Result(base)
                gateway != null && !profile.batchMode -> {
                    val request = Request.Builder().url(gateway).apply {
                        profile.token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
                    }.build()
                    val metadata = readJson(metadataHttp, request)
                    if (metadata["protocol"] == JsonPrimitive("private-local-text-v1") &&
                        metadata["provider_kind"] == JsonPrimitive(profile.compatibleType.name) &&
                        (metadata["models"] as? JsonArray)?.contains(JsonPrimitive(profile.model)) == true
                    ) {
                        Result(PrivacyProfileEligibility.Decision(true, "Private Windows Gateway · local model verified"), VerifiedPrivateRoute(gateway, profile.compatibleType))
                    } else {
                        Result(base)
                    }
                }
                remote != null && base.policyUrl != null -> {
                    val proof = approval(profile, remote, base.policyUrl)
                    if (proof == null) {
                        Result(base)
                    } else {
                        val live = if (!matchesPublishedPolicy(proof)) {
                            false
                        } else if (profile.compatibleType == ClientType.OPENROUTER) {
                            val api = profile.apiUrl.trimEnd('/')
                            val key = profile.token.orEmpty()
                            val account = router.key(api, key)
                            check(!account.expired && !account.isManagement)
                            val catalog = router.objectResource(api, key, "endpoints/zdr")["data"] as? JsonArray
                            catalog != null && VerifiedPrivateRoute.strictCatalogMatch(catalog, proof) && strictGuardrail(proof)
                        } else {
                            true
                        }
                        if (live) {
                            Result(PrivacyProfileEligibility.Decision(true, "Verified for stateless text · " + proof.upstream, base.policyUrl), VerifiedPrivateRoute(remote, profile.compatibleType, proof))
                        } else {
                            Result(PrivacyProfileEligibility.Decision(false, "The published policy or provider route no longer matches the approval.", base.policyUrl))
                        }
                    }
                }
                else -> Result(base)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result(PrivacyProfileEligibility.Decision(false, "Could not verify this route. Choose an on-device profile or recheck the connection.", base.policyUrl))
        }
        // Audit only. A previous receipt is never consulted to authorize a request.
        context.getSharedPreferences("privacy_route_checks", Context.MODE_PRIVATE).edit()
            .putString(identity(profile), listOf(System.currentTimeMillis(), result.decision.eligible, result.decision.policyUrl.orEmpty()).joinToString("|")).apply()
        result
    }

    private suspend fun strictGuardrail(proof: ProviderApproval): Boolean {
        val id = proof.guardrailId?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")) } ?: return false
        val hash = proof.providerKeyHash?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return false
        val key = authority.managementKey() ?: return false
        val base = "https://openrouter.ai/api/v1/guardrails/$id"
        suspend fun get(url: String) = readJson(metadataHttp, Request.Builder().url(url).header("Authorization", "Bearer $key").build())
        val group = when (proof.model.substringBefore('/')) {
            "google", "openai", "anthropic" -> proof.model.substringBefore('/')
            "x-ai" -> "xai"
            else -> "other"
        }
        fun strict(rule: JsonObject) = rule["id"] == JsonPrimitive(id) &&
            rule["enforce_zdr_$group"] == JsonPrimitive(true) &&
            rule["allow_safety_retention_google"] == JsonPrimitive(false) &&
            rule["allow_safety_retention_other"] == JsonPrimitive(false)
        val rule = get(base)["data"] as? JsonObject ?: return false
        if (rule["id"] != JsonPrimitive(id)) return false
        var assigned = false
        var offset = 0
        while (offset < 10000) {
            val page = get("$base/assignments/keys?limit=100&offset=$offset")
            val rows = page["data"] as? JsonArray ?: return false
            if (rows.filterIsInstance<JsonObject>().any { it["key_hash"] == JsonPrimitive(hash) && it["guardrail_id"] == JsonPrimitive(id) }) {
                assigned = true
                break
            }
            offset += rows.size
            val count = (page["total_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return false
            if (rows.isEmpty() || offset >= count) return false
        }
        if (!assigned) return false
        if (strict(rule)) return true
        if (!proof.allowGuardrailTightening || !authority.mayUpdateGuardrail()) return false
        val update = JsonObject(
            mapOf(
                "enforce_zdr_$group" to JsonPrimitive(true),
                "allow_safety_retention_google" to JsonPrimitive(false),
                "allow_safety_retention_other" to JsonPrimitive(false)
            )
        )
        readJson(
            http,
            Request.Builder().url(base).header("Authorization", "Bearer $key")
                .patch(update.toString().toRequestBody("application/json".toMediaType())).build()
        )
        return (get(base)["data"] as? JsonObject)?.let(::strict) == true
    }

    private suspend fun matchesPublishedPolicy(proof: ProviderApproval): Boolean {
        var url = proof.policyDocument
        repeat(4) {
            if (!proof.permitsDocument(url)) return false
            val request = Request.Builder().url(url).header("Accept", "text/markdown, text/plain, text/html")
                .header("Cache-Control", "no-cache").build()
            val observed = fetch(metadataHttp, request) { response ->
                if (response.isRedirect) {
                    val location = response.header("Location") ?: return@fetch null
                    response.request.url.resolve(location)?.toString()
                } else {
                    if (!response.isSuccessful) return@fetch null
                    val body = response.body ?: return@fetch null
                    val text = body.byteStream().use { BoundedResponseText.read(it, 4 * 1024 * 1024) }
                    OpenRouterIdentity.digest(text)
                }
            } ?: return false
            if (!observed.startsWith("https://")) return observed == proof.policySha256
            url = observed
        }
        return false
    }

    internal suspend fun readJson(client: OkHttpClient, request: Request): JsonObject = fetch(client, request) { response ->
        check(response.isSuccessful) { "The verified endpoint rejected this request (HTTP " + response.code + ")." }
        val body = requireNotNull(response.body)
        Json.parseToJsonElement(body.byteStream().use { BoundedResponseText.read(it, 2 * 1024 * 1024) }) as JsonObject
    }

    private suspend fun <T> fetch(client: OkHttpClient, request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { waiting ->
        val call = client.newCall(request)
        waiting.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                waiting.resumeWithException(IOException("Privacy verification or private inference is unavailable."))
            }

            override fun onResponse(call: Call, response: Response) {
                waiting.resumeWith(runCatching { response.use(read) })
            }
        })
    }
}
