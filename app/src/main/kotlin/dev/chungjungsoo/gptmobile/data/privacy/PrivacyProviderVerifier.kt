package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.BoundedResponseText
import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountException
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProfileEligibility.Reason
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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
    private val router: OpenRouterAccountClient,
    private val routerSettings: dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
) {
    internal data class Result(val decision: PrivacyProfileEligibility.Decision, val route: VerifiedPrivateRoute? = null)

    internal val preparation = dev.chungjungsoo.gptmobile.data.network.ModelRequestPreparation(network)
    internal val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(120, TimeUnit.SECONDS).build()

    // Dispatch-boundary account checks must not queue behind the inference calls awaiting them.
    private val metadataHttp = http.newBuilder().dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
        .callTimeout(8, TimeUnit.SECONDS).build()
    internal val accountSetup = OpenRouterPrivacySetup(authority, ::accountRequest, ::currentOpenRouterPolicyDigest)

    private suspend fun accountRequest(request: Request, authorized: () -> Unit): JsonObject {
        val originalContext = currentCoroutineContext()
        val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
        val client = metadataHttp.newBuilder().addNetworkInterceptor { chain ->
            if (!attempted.compareAndSet(false, true) || chain.call().isCanceled()) throw IOException("Account check interrupted.")
            originalContext.ensureActive()
            try {
                authorized()
            } catch (_: Exception) {
                throw IOException("Account setup authorization changed.")
            }
            chain.proceed(chain.request())
        }.build()
        return fetch(client, request) { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "The management key was rejected. Check its value and expiration."
                    403 -> "This management key lacks access. Automatic setup needs read/write access to this profile's workspace."
                    404 -> "This account key, workspace or policy could not be found. Check that the management key belongs to the same account."
                    409 -> "The account policy changed during setup. Check the account again before applying the updated settings."
                    429 -> "OpenRouter temporarily limited account checks. Wait and try again; Privacy Mode stays on."
                    else -> "OpenRouter could not confirm this account operation. Check the account again."
                }
                throw OpenRouterPrivacySetup.SetupException(message, rejected = response.code in setOf(400, 401, 403, 404, 409, 422, 429))
            }
            val body = response.body ?: throw OpenRouterPrivacySetup.SetupException("OpenRouter returned an empty account response.")
            Json.parseToJsonElement(body.byteStream().use { BoundedResponseText.read(it, 2 * 1024 * 1024) }) as JsonObject
        }
    }

    private suspend fun currentOpenRouterPolicyDigest(): String {
        var url = OpenRouterPrivacySetup.POLICY_DOCUMENT
        repeat(4) {
            val parsed = java.net.URI(url)
            check(parsed.scheme == "https" && parsed.host == "openrouter.ai" && parsed.port in setOf(-1, 443) && parsed.userInfo == null && parsed.fragment == null && parsed.path.startsWith("/docs/"))
            val request = Request.Builder().url(url)
                .header("Accept", "text/markdown, text/plain, text/html").header("Cache-Control", "no-cache").build()
            val observed = fetch(metadataHttp, request) { response ->
                if (response.isRedirect) {
                    requireNotNull(response.request.url.resolve(requireNotNull(response.header("Location")))).toString()
                } else {
                    check(response.isSuccessful)
                    OpenRouterIdentity.digest(requireNotNull(response.body).byteStream().use { BoundedResponseText.read(it, 4 * 1024 * 1024) })
                }
            }
            if (!observed.startsWith("https://")) return observed
            url = observed
        }
        throw OpenRouterPrivacySetup.SetupException("The current OpenRouter privacy policy could not be reached.")
    }

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
                    val proof = requireApproval(profile, remote, base.policyUrl)
                    if (!matchesPublishedPolicy(proof)) throw VerificationBlocked(Reason.PUBLISHED_POLICY_CHANGED)
                    if (profile.compatibleType == ClientType.OPENROUTER) {
                        verifyOpenRouter(profile, remote, proof)
                    } else {
                        Result(PrivacyProfileEligibility.Decision(true, "Verified for stateless text · " + proof.upstream, base.policyUrl), VerifiedPrivateRoute(remote, profile.compatibleType, proof))
                    }
                }
                else -> Result(base)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (blocked: VerificationBlocked) {
            Result(blocked.reason.decision(base.policyUrl))
        } catch (error: OpenRouterAccountException) {
            val reason = when (error.category) {
                AccountFailure.INVALID, AccountFailure.EXPIRED, AccountFailure.ROLE -> Reason.KEY_UNAVAILABLE
                AccountFailure.RATE_LIMITED -> Reason.VERIFICATION_RATE_LIMITED
                AccountFailure.MALFORMED -> Reason.CATALOG_MALFORMED
                else -> Reason.VERIFICATION_UNAVAILABLE
            }
            Result(reason.decision(base.policyUrl))
        } catch (_: Exception) {
            Result(Reason.VERIFICATION_UNAVAILABLE.decision(base.policyUrl))
        }
        // Audit only. A previous receipt is never consulted to authorize a request.
        context.getSharedPreferences("privacy_route_checks", Context.MODE_PRIVATE).edit()
            .putString(identity(profile), listOf(System.currentTimeMillis(), result.decision.eligible, result.decision.policyUrl.orEmpty(), result.decision.status.name, result.decision.code.orEmpty()).joinToString("|")).apply()
        result
    }

    private class VerificationBlocked(val reason: Reason) : Exception()

    private data class RoutingSnapshot(val options: JsonObject, val revision: String)

    private suspend fun routing(profile: PlatformV2): RoutingSnapshot {
        val settings = profile.providerConnectionUid?.let { routerSettings.loadSettings(it) }
        val defaults = settings?.routing?.normalized()?.let { Json.encodeToString(it) }
        val base = PrivateOpenRouterRouting.options(defaults) ?: throw VerificationBlocked(Reason.ROUTING_UNSUPPORTED)
        val override = PrivateOpenRouterRouting.options(profile.openRouterRouting) ?: throw VerificationBlocked(Reason.ROUTING_UNSUPPORTED)
        val resolved = PrivateOpenRouterRouting.mergeOptions(base, override) ?: throw VerificationBlocked(Reason.PROVIDER_RESTRICTIONS)
        val revision = OpenRouterIdentity.digest(listOf(profile.providerConnectionUid.orEmpty(), settings?.revision?.toString().orEmpty(), defaults.orEmpty(), profile.openRouterRouting.orEmpty()).joinToString("\u0000"))
        return RoutingSnapshot(resolved, revision)
    }

    internal suspend fun routingUnchanged(profile: PlatformV2, route: VerifiedPrivateRoute): Boolean =
        route.kind != ClientType.OPENROUTER || route.routingRevision == routing(profile).revision

    internal suspend fun accountUnchanged(profile: PlatformV2, route: VerifiedPrivateRoute): Boolean =
        route.approval?.takeIf { it.scope == "openrouter-personal-v1" }?.let { accountSetup.verifyPersonal(profile, it) } ?: true

    private fun requireApproval(profile: PlatformV2, url: String, terms: String): ProviderApproval {
        val records = try {
            authority.records()
        } catch (_: Exception) {
            throw VerificationBlocked(Reason.ACCOUNT_APPROVAL_INVALID)
        }
        val now = System.currentTimeMillis()
        val matching = records.filter { it.covers(profile, url, terms, now) }
        if (matching.size > 1) throw VerificationBlocked(Reason.ACCOUNT_APPROVAL_AMBIGUOUS)
        matching.singleOrNull()?.let { return it }
        val sameAccount = records.filter { it.endpoint == url && it.credentialSha256 == OpenRouterIdentity.digest(profile.token.orEmpty()) }
        val reason = when {
            records.isEmpty() -> Reason.ACCOUNT_PRIVACY_UNVERIFIED
            sameAccount.isNotEmpty() && sameAccount.all { it.expiresAtMillis <= now } -> Reason.ACCOUNT_APPROVAL_EXPIRED
            else -> Reason.ACCOUNT_APPROVAL_INVALID
        }
        throw VerificationBlocked(reason)
    }

    private suspend fun verifyOpenRouter(profile: PlatformV2, url: String, proof: ProviderApproval): Result {
        val api = profile.apiUrl.trimEnd('/')
        val key = profile.token.orEmpty()
        val account = router.key(api, key)
        if (account.expired || account.isManagement) throw VerificationBlocked(Reason.KEY_UNAVAILABLE)
        val personal = proof.scope == "openrouter-personal-v1"
        if (personal && !accountSetup.verifyPersonal(profile, proof)) throw VerificationBlocked(Reason.ACCOUNT_PRIVACY_UNVERIFIED)
        val accountScope = proof.scope in setOf("openrouter-account-v1", "openrouter-personal-v1")
        var metadata = router.objectResource(api, key, "model/" + profile.model)
        val alias = PrivateOpenRouterRouting.aliasTarget(metadata, profile.model)
        if (alias != null) {
            if (!accountScope || profile.model.endsWith(":free") != alias.endsWith(":free")) throw VerificationBlocked(Reason.MODEL_RESOLUTION_UNVERIFIED)
            metadata = router.objectResource(api, key, "model/" + alias)
        }
        val target = PrivateOpenRouterRouting.concreteTarget(metadata, alias ?: profile.model, accountScope && alias == null)
            ?: throw VerificationBlocked(Reason.MODEL_RESOLUTION_UNVERIFIED)
        if (alias != null && target.model != alias) throw VerificationBlocked(Reason.MODEL_RESOLUTION_UNVERIFIED)
        val catalog = router.objectResource(api, key, "endpoints/zdr")["data"] as? JsonArray
            ?: throw VerificationBlocked(Reason.CATALOG_MALFORMED)
        val inventory = router.objectResource(api, key, "models/" + target.model + "/endpoints")
        val routing = routing(profile)
        val options = routing.options
        val selected = PrivateOpenRouterRouting.select(catalog, inventory, target.model, if (accountScope) null else proof.upstream, options, target.canonical)
        selected.failure?.let { throw VerificationBlocked(Reason.valueOf(it.name)) }
        val upstream = selected.upstream ?: throw VerificationBlocked(Reason.CATALOG_MALFORMED)
        if (!personal && !strictGuardrail(profile, url, proof, target.model)) throw VerificationBlocked(Reason.GUARDRAIL_UNVERIFIED)
        // Store a concrete model and selector in this ephemeral route; later alias rollover cannot broaden it.
        return Result(
            PrivacyProfileEligibility.Decision(true, "Verified private text · " + target.model + " · " + upstream + if (personal) " · default plugins confirmed by you" else "", proof.policyUrl, code = "STRICT_ZDR_VERIFIED"),
            VerifiedPrivateRoute(url, profile.compatibleType, proof, upstream, options, target.model, routing.revision)
        )
    }

    private suspend fun strictGuardrail(profile: PlatformV2, url: String, proof: ProviderApproval, model: String): Boolean {
        val id = proof.guardrailId?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")) } ?: return false
        val hash = proof.providerKeyHash?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return false
        val key = authority.managementKey() ?: throw VerificationBlocked(Reason.MANAGEMENT_KEY_MISSING)
        val base = "https://openrouter.ai/api/v1/guardrails/$id"
        suspend fun get(url: String) = readJson(metadataHttp, Request.Builder().url(url).header("Authorization", "Bearer $key").build())
        val group = when (model.substringBefore('/')) {
            "google", "openai", "anthropic" -> model.substringBefore('/')
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
            if (rows.isEmpty() || offset >= count) throw VerificationBlocked(Reason.GUARDRAIL_ASSIGNMENT_UNVERIFIED)
        }
        if (!assigned) throw VerificationBlocked(Reason.GUARDRAIL_ASSIGNMENT_UNVERIFIED)
        val update = JsonObject(
            mapOf(
                "enforce_zdr_$group" to JsonPrimitive(true),
                "allow_safety_retention_google" to JsonPrimitive(false),
                "allow_safety_retention_other" to JsonPrimitive(false)
            )
        )
        val updateIdentity = PrivateOpenRouterRouting.guardrailUpdateIdentity(id, hash, update)
        if (strict(rule)) {
            authority.confirmGuardrailUpdate(updateIdentity)
            return true
        }
        if (!proof.allowGuardrailTightening || !authority.mayUpdateGuardrail()) return false
        if (approval(profile, url, proof.policyUrl) != proof) throw VerificationBlocked(Reason.ACCOUNT_APPROVAL_INVALID)
        if (!authority.beginGuardrailUpdate(updateIdentity)) throw VerificationBlocked(Reason.GUARDRAIL_UPDATE_UNCONFIRMED)
        try {
            readJson(
                guardrailUpdateClient(profile, url, proof),
                Request.Builder().url(base).header("Authorization", "Bearer $key")
                    .patch(update.toString().toRequestBody("application/json".toMediaType())).build()
            )
            if ((get(base)["data"] as? JsonObject)?.let(::strict) != true) throw VerificationBlocked(Reason.GUARDRAIL_UPDATE_UNCONFIRMED)
            authority.confirmGuardrailUpdate(updateIdentity)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable intent survives a lost PATCH response. A subsequent read must confirm it.
            throw VerificationBlocked(Reason.GUARDRAIL_UPDATE_UNCONFIRMED)
        }
    }

    internal fun guardrailUpdateClient(profile: PlatformV2, url: String, proof: ProviderApproval): OkHttpClient {
        val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
        return http.newBuilder().addNetworkInterceptor { chain ->
            if (!attempted.compareAndSet(false, true) || chain.call().isCanceled() || !proof.allowGuardrailTightening || !authority.mayUpdateGuardrail() || approval(profile, url, proof.policyUrl) != proof) {
                throw IOException("Guardrail authorization changed before dispatch.")
            }
            chain.proceed(chain.request())
        }.build()
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
