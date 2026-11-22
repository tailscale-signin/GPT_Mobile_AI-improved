package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Guided personal setup. Only the plugin confirmation is supplied by the account owner. */
internal class OpenRouterPrivacySetup(
    private val authority: ProviderApprovalSource,
    private val transport: suspend (Request, () -> Unit) -> JsonObject,
    private val currentPolicyDigest: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    class SetupException(val userMessage: String, internal val rejected: Boolean = false) : Exception(userMessage)

    data class SavedStatus(val managed: Boolean, val summary: String?)

    suspend fun savedStatus(profile: PlatformV2?): SavedStatus = friendly {
        val managed = authority.managedSetup()
        SavedStatus(managed, if (managed) null else profile?.let(authority::personalSetupSummary))
    }

    class Preview internal constructor(
        val workspaceName: String,
        val promptLoggingOff: Boolean,
        val dataCollectionOff: Boolean,
        val broadcastOff: Boolean,
        val guardrailReady: Boolean,
        internal val sessionId: String,
        val policyReviewRequired: Boolean = false
    ) {
        override fun toString() = "OpenRouter privacy setup preview"
    }

    private class Session(
        val profileIdentity: String,
        val management: String,
        val revision: String,
        val binding: OpenRouterWorkspacePrivacy.KeyBinding,
        val expiresAt: Long,
        val active: AtomicBoolean = AtomicBoolean(true)
    )

    private data class PolicyPlan(
        val assignment: OpenRouterPrivacyPolicy.Assignment,
        val memberAssignment: JsonObject?,
        val sourcePolicy: JsonObject?,
        val config: JsonObject,
        val memberCreator: String?
    )

    private data class ConfiguredPolicy(val id: String, val inherited: Boolean = false)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val writes = Mutex()

    suspend fun inspect(profile: PlatformV2, managementKey: String): Preview {
        var prepared: Preview? = null
        try {
            return friendly { inspectAccount(profile, managementKey).also { prepared = it } }
        } catch (cancelled: CancellationException) {
            // Cancellation can win while an IO result is being returned to the caller.
            // A preview that never reached the UI must not retain an active setup session.
            prepared?.let(::discard)
            throw cancelled
        }
    }

    private suspend fun inspectAccount(profile: PlatformV2, managementKey: String): Preview {
        requireProfile(profile)
        val revision = authority.personalRevision()
        if (!authority.personalAllowed(revision)) throw SetupException("This device uses organization or signed verification. Manage that setup under Advanced.")
        val key = managementKey.trim().ifEmpty { authority.managementKey().orEmpty() }
        if (key.isEmpty()) throw SetupException("Add an OpenRouter management key once. A read/write key for this workspace enables automatic setup.")
        OpenRouterIdentity.credential(key)
        val hash = OpenRouterIdentity.digest(profile.token.orEmpty())
        fun authorized() {
            check(authority.personalAllowed(revision))
        }
        val binding = OpenRouterWorkspacePrivacy.parseKey(get("keys/$hash", key, ::authorized), hash, now())
            ?: throw SetupException("The management key could not verify this profile's active key and workspace. Use a management key from the same account.")
        val workspace = workspace(binding, key, ::authorized)
        val session = Session(identity(profile), key, revision, binding, now() + 600_000)
        // Assignment reads can materialize the provider's existing assignment representation.
        // OpenRouter requires the read/write management key requested by this guided setup.
        val observed = plan(session, ::authorized)
        val ready = observed.sourcePolicy?.let { rule ->
            OpenRouterPrivacyPolicy.strict(observed.config) &&
                (observed.assignment.policies.isEmpty() || OpenRouterPrivacyPolicy.directlyProtects(rule, hash))
        } == true
        val canCreate = observed.sourcePolicy == null &&
            observed.memberCreator == null &&
            !observed.assignment.optedOut &&
            observed.assignment.policies.isEmpty() &&
            observed.assignment.defaults.isEmpty()
        currentCoroutineContext().ensureActive()
        authorized()
        sessions.entries.removeIf { (_, session) ->
            val expired = session.expiresAt <= now()
            if (expired) session.active.set(false)
            expired
        }
        if (sessions.size >= 4) throw SetupException("Close the other privacy setup and check this account again.")
        val id = UUID.randomUUID().toString()
        sessions[id] = session
        return Preview(workspace.name, !workspace.promptLogging, !workspace.dataCollection, !workspace.broadcast, ready, id, policyReviewRequired = !ready && !canCreate)
    }

    fun discard(preview: Preview) {
        sessions.remove(preview.sessionId)?.active?.set(false)
    }

    suspend fun apply(profile: PlatformV2, preview: Preview, pluginsConfirmed: Boolean): String = friendly {
        if (!pluginsConfirmed) throw SetupException("Review this workspace's default plugins in OpenRouter and confirm that they are off.")
        val session = sessions[preview.sessionId] ?: throw SetupException("Check this account again before applying settings.")
        try {
            writes.withLock {
                fun authorized() {
                    check(session.active.get() && session.expiresAt > now() && session.profileIdentity == identity(profile))
                    check(authority.personalAllowed(session.revision))
                }
                authorized()
                bindingStillMatches(session, ::authorized)
                reconcilePending(session, ::authorized)
                val initialPlan = plan(session, ::authorized)
                val existingPolicy = reusablePolicy(initialPlan, session.binding.hash)
                val policyDigest = currentPolicyDigest()
                if (!policyDigest.matches(Regex("[0-9a-f]{64}"))) throw SetupException("The current OpenRouter privacy policy could not be checked. Try again when connected.")

                if (!workspace(session.binding, session.management, ::authorized).strict) {
                    val body = OpenRouterWorkspacePrivacy.strictPatch
                    val pending = journal("workspace", session, body)
                    mutate("PATCH", "workspaces/${session.binding.workspaceId}", body, pending, session, ::authorized)
                    if (!workspace(session.binding, session.management, ::authorized).strict) throw unknown()
                    authority.confirmSetupMutation(pending.toString())
                }

                val target = existingPolicy ?: assignDedicatedPolicy(session, initialPlan, ::authorized)
                bindingStillMatches(session, ::authorized)
                if (!workspace(session.binding, session.management, ::authorized).strict || !policyStillApplies(session, target, ::authorized)) {
                    throw SetupException("OpenRouter settings changed during setup. Check the account again; it has not been marked ready.")
                }
                authorized()
                val timestamp = now()
                val proof = ProviderApproval(
                    endpoint = profile.apiUrl.trimEnd('/') + "/chat/completions",
                    model = "*",
                    credentialSha256 = session.binding.hash,
                    upstream = "*",
                    policyUrl = PrivacyProfileEligibility.OPENROUTER_TERMS,
                    verifiedAtMillis = timestamp,
                    expiresAtMillis = timestamp + 86_400_000,
                    feature = "stateless-text-v1",
                    retentionTier = "zdr",
                    noTraining = true,
                    loggingDisabled = true,
                    externalToolsDisabled = true,
                    providerDefaultsDisabled = true,
                    safetyRetentionExcluded = true,
                    providerKeyHash = session.binding.hash,
                    policySha256 = policyDigest,
                    policyDocument = POLICY_DOCUMENT,
                    scope = "openrouter-personal-v1",
                    personalWorkspaceId = session.binding.workspaceId,
                    setupId = UUID.randomUUID().toString(),
                    modelAvailabilityPolicyId = target.id,
                    personalPolicyAssignment = if (target.inherited) "inherited" else "direct"
                )
                authority.savePersonal(proof, session.management, session.revision)
                "Account setup saved. Logging, collection, Broadcast and strict ZDR were checked automatically. Default plugins are confirmed by you for 24 hours. Your models are checked automatically when you return to New Chat."
            }
        } finally {
            discard(preview)
        }
    }

    /** Fresh API evidence remains necessary after setup; a saved confirmation is not account telemetry. */
    suspend fun verifyPersonal(profile: PlatformV2, proof: ProviderApproval): Boolean = friendly {
        if (proof.scope != "openrouter-personal-v1" || proof.providerKeyHash != OpenRouterIdentity.digest(profile.token.orEmpty())) return@friendly false
        val management = authority.managementKey() ?: return@friendly false
        val hash = proof.credentialSha256
        fun authorized() {
            check(authority.records().contains(proof))
            check(proof.covers(profile, profile.apiUrl.trimEnd('/') + "/chat/completions", PrivacyProfileEligibility.OPENROUTER_TERMS, now()))
        }
        val binding = OpenRouterWorkspacePrivacy.parseKey(get("keys/$hash", management, ::authorized), hash, now()) ?: return@friendly false
        if (binding.workspaceId != proof.personalWorkspaceId || !workspace(binding, management, ::authorized).strict) return@friendly false
        val id = proof.modelAvailabilityPolicyId ?: return@friendly false
        val session = Session(identity(profile), management, "", binding, now() + 600_000)
        policyStillApplies(session, ConfiguredPolicy(id, proof.personalPolicyAssignment == "inherited"), ::authorized)
    }

    private suspend fun policyStillApplies(session: Session, target: ConfiguredPolicy, authorized: () -> Unit): Boolean =
        if (target.inherited) {
            val current = plan(session, authorized)
            current.sourcePolicy?.get("id") == JsonPrimitive(target.id) && OpenRouterPrivacyPolicy.strict(current.config)
        } else {
            OpenRouterPrivacyPolicy.directlyProtects(policy(target.id, session.binding, session.management, authorized), session.binding.hash)
        }

    private fun reusablePolicy(initial: PolicyPlan, keyHash: String): ConfiguredPolicy? {
        val direct = initial.assignment.policies.singleOrNull()
        if (direct != null && initial.sourcePolicy?.let { OpenRouterPrivacyPolicy.directlyProtects(it, keyHash) } == true) return ConfiguredPolicy(direct)
        initial.sourcePolicy?.let { source ->
            if (direct == null && OpenRouterPrivacyPolicy.strict(initial.config)) return ConfiguredPolicy(text(source, "id") ?: throw malformed(), inherited = true)
            throw SetupException("Your existing model-access policy needs ZDR for all model groups and both Safety Retention options off. Review that policy in OpenRouter Guardrails, then check again. Its other restrictions have been preserved.")
        }
        // Assigning a new policy overrides member/default inheritance. The API cannot atomically
        // compare a source policy's contents, so never clone or replace existing restrictions.
        if (initial.memberCreator != null || initial.assignment.optedOut || initial.assignment.policies.isNotEmpty() || initial.assignment.defaults.isNotEmpty()) {
            throw SetupException("This key inherits account policy settings. Configure strict ZDR in OpenRouter Guardrails, then check again. Automatic setup will not replace inherited restrictions.")
        }
        return null
    }

    private suspend fun assignDedicatedPolicy(session: Session, initial: PolicyPlan, authorized: () -> Unit): ConfiguredPolicy {
        val binding = session.binding
        val desired = OpenRouterPrivacyPolicy.strictFields
        // Deterministic, credential-free name lets a confirmed but unassigned creation be reused after restart.
        val name = "GPT Mobile Privacy " + OpenRouterIdentity.digest(binding.hash + desired.toString()).take(32)
        val candidates = policies(binding, session.management, authorized).filter { it["name"] == JsonPrimitive(name) }
            .map { row -> policy(text(row, "id") ?: throw malformed(), binding, session.management, authorized) }
            .filter { it["config"] == desired && it["is_workspace_default"] == JsonPrimitive(false) }
        if (candidates.size > 1) throw SetupException("More than one matching privacy policy was found. Review OpenRouter Guardrails before trying again.")
        val id = candidates.singleOrNull()?.let { text(it, "id") } ?: run {
            val create = JsonObject(
                mapOf(
                    "name" to JsonPrimitive(name),
                    "policy_type" to JsonPrimitive("model_availability"),
                    "workspace_id" to JsonPrimitive(binding.workspaceId),
                    "is_workspace_default" to JsonPrimitive(false),
                    "config" to desired
                )
            )
            val pending = journal("create", session, create)
            val response = mutate("POST", POLICY_PATH, create, pending, session, authorized)
            val created = OpenRouterPrivacyPolicy.policy(response) ?: throw unknown()
            val createdId = text(created, "id") ?: throw unknown()
            val confirmed = policy(createdId, binding, session.management, authorized)
            if (confirmed["name"] != JsonPrimitive(name) || confirmed["config"] != desired || confirmed["is_workspace_default"] != JsonPrimitive(false)) throw unknown()
            authority.confirmSetupMutation(pending.toString())
            createdId
        }
        // CAS covers a policy/default assigned after this observation. There is no mutable
        // inherited configuration to copy or detach on this automatic-creation path.
        if (plan(session, authorized) != initial) throw SetupException("The current model restrictions changed. Check the account again to review the updated settings.")
        bindingStillMatches(session, authorized)
        if (policy(id, binding, session.management, authorized)["config"] != desired) throw SetupException("The proposed privacy policy changed. Check the account again.")
        val update = OpenRouterPrivacyPolicy.assignmentUpdate(binding.workspaceId, id, initial.assignment)
        val pending = journal("assign", session, update, desired)
        mutate("PUT", assignmentPath(binding.hash), update, pending, session, authorized)
        val observed = assignment(binding.hash, session, authorized)
        if (observed.optedOut ||
            observed.policies != listOf(id) ||
            policy(id, binding, session.management, authorized).let { it["config"] != desired || !OpenRouterPrivacyPolicy.directlyProtects(it, binding.hash) }
        ) {
            throw unknown()
        }
        authority.confirmSetupMutation(pending.toString())
        return ConfiguredPolicy(id)
    }

    private suspend fun plan(session: Session, authorized: () -> Unit): PolicyPlan {
        val keyAssignment = assignment(session.binding.hash, session, authorized)
        var member: JsonObject? = null
        var memberCreator: String? = null
        var selected = keyAssignment.selected
        if (keyAssignment.policies.isEmpty() && !keyAssignment.optedOut) {
            val keyData = get("keys/${session.binding.hash}", session.management, authorized)["data"] as? JsonObject ?: throw malformed()
            // Personal/service keys explicitly report no member creator and follow the workspace default.
            // Missing or malformed identity is not evidence of that absence.
            if (keyData["creator_user_id"] != JsonNull) {
                val creator = text(keyData, "creator_user_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                    ?: throw SetupException("OpenRouter did not identify this key's inherited policy. Review its model-access policy in OpenRouter and check again.")
                memberCreator = creator
                val observed = OpenRouterPrivacyPolicy.assignment(
                    get("guardrails/policies/assignments/members/$creator?workspace_id=${session.binding.workspaceId}&policy_type=model_availability", session.management, authorized)
                ) ?: throw malformed()
                member = observed.raw
                selected = if (observed.optedOut) null else observed.selected ?: keyAssignment.selected
            }
        }
        val source = selected?.let { policy(it, session.binding, session.management, authorized) }
        val config = source?.get("config") as? JsonObject ?: JsonObject(emptyMap())
        return PolicyPlan(keyAssignment, member, source, config, memberCreator)
    }

    private suspend fun reconcilePending(session: Session, authorized: () -> Unit) {
        val raw = authority.pendingSetupMutation() ?: return
        val pending = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: throw unknown()
        if (pending["version"] != JsonPrimitive(1) ||
            pending["origin"] != JsonPrimitive(ORIGIN) ||
            pending["key_hash"] != JsonPrimitive(session.binding.hash) ||
            pending["workspace_id"] != JsonPrimitive(session.binding.workspaceId)
        ) {
            throw SetupException("An earlier account setup is still awaiting confirmation. Recheck the original account before applying another setup.")
        }
        val body = pending["body"] as? JsonObject ?: throw unknown()
        val confirmed = when (text(pending, "kind")) {
            "workspace" -> body == OpenRouterWorkspacePrivacy.strictPatch && workspace(session.binding, session.management, authorized).strict
            "create" -> {
                val matching = policies(session.binding, session.management, authorized).filter { it["name"] == body["name"] }
                    .map { policy(text(it, "id") ?: throw malformed(), session.binding, session.management, authorized) }
                    .filter { it["config"] == body["config"] && it["is_workspace_default"] == JsonPrimitive(false) }
                matching.size == 1
            }
            "assign" -> {
                if (body["workspace_id"] != JsonPrimitive(session.binding.workspaceId) || body["policy_type"] != JsonPrimitive("model_availability") || body["assigned"] != JsonPrimitive(true)) throw unknown()
                val id = text(body, "policy_id") ?: throw unknown()
                val current = assignment(session.binding.hash, session, authorized)
                val rule = policy(id, session.binding, session.management, authorized)
                !current.optedOut &&
                    current.policies == listOf(id) &&
                    rule["config"] == pending["config"] &&
                    OpenRouterPrivacyPolicy.directlyProtects(rule, session.binding.hash)
            }
            else -> false
        }
        if (!confirmed) throw unknown()
        authority.confirmSetupMutation(raw)
    }

    private suspend fun mutate(method: String, path: String, body: JsonObject, pending: JsonObject, session: Session, authorized: () -> Unit): JsonObject {
        bindingStillMatches(session, authorized)
        val raw = pending.toString()
        if (!authority.beginSetupMutation(raw)) throw unknown()
        var crossedBoundary = false
        try {
            val request = request(path, session.management).method(method, body.toString().toRequestBody("application/json".toMediaType())).build()
            return transport(request) {
                authorized()
                check(authority.pendingSetupMutation() == raw)
                crossedBoundary = true
            }
        } catch (error: Exception) {
            if (!crossedBoundary || (error as? SetupException)?.rejected == true) authority.confirmSetupMutation(raw)
            if (error is CancellationException) throw error
            if (crossedBoundary && (error as? SetupException)?.rejected != true) throw unknown()
            throw error
        }
    }

    private fun journal(kind: String, session: Session, body: JsonObject, config: JsonObject? = null) = JsonObject(
        mapOf(
            "version" to JsonPrimitive(1),
            "origin" to JsonPrimitive(ORIGIN),
            "kind" to JsonPrimitive(kind),
            "key_hash" to JsonPrimitive(session.binding.hash),
            "workspace_id" to JsonPrimitive(session.binding.workspaceId),
            "body" to body
        ) + if (config == null) emptyMap() else mapOf("config" to config)
    )

    private suspend fun bindingStillMatches(session: Session, authorized: () -> Unit) {
        val observed = OpenRouterWorkspacePrivacy.parseKey(get("keys/${session.binding.hash}", session.management, authorized), session.binding.hash, now())
        if (observed != session.binding) throw SetupException("The selected API key or workspace changed. Check the account again.")
    }

    private suspend fun workspace(binding: OpenRouterWorkspacePrivacy.KeyBinding, key: String, authorized: () -> Unit) =
        OpenRouterWorkspacePrivacy.parseWorkspace(get("workspaces/${binding.workspaceId}", key, authorized), binding.workspaceId)
            ?: throw SetupException("OpenRouter did not return all three workspace privacy controls. No missing setting is treated as off.")

    private suspend fun assignment(hash: String, session: Session, authorized: () -> Unit) = OpenRouterPrivacyPolicy.assignment(
        get(assignmentPath(hash) + "?workspace_id=${session.binding.workspaceId}&policy_type=model_availability", session.management, authorized)
    ) ?: throw malformed()

    private suspend fun policies(binding: OpenRouterWorkspacePrivacy.KeyBinding, key: String, authorized: () -> Unit): List<JsonObject> {
        val root = get("$POLICY_PATH?workspace_id=${binding.workspaceId}", key, authorized)
        val rows = root["data"] as? JsonArray ?: throw malformed()
        val count = (root["total_count"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull() ?: throw malformed()
        if (rows.size != count || rows.size > 500) throw SetupException("The complete privacy-policy list could not be checked. Review this workspace in OpenRouter and try again.")
        return rows.map { row -> OpenRouterPrivacyPolicy.policy(JsonObject(mapOf("data" to row))) ?: throw malformed() }
    }

    private suspend fun policy(id: String, binding: OpenRouterWorkspacePrivacy.KeyBinding, key: String, authorized: () -> Unit): JsonObject {
        if (!id.matches(Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"))) throw malformed()
        return OpenRouterPrivacyPolicy.policy(get("$POLICY_PATH/$id?workspace_id=${binding.workspaceId}", key, authorized), id) ?: throw malformed()
    }

    private suspend fun get(path: String, key: String, authorized: () -> Unit) = transport(request(path, key).get().build(), authorized)

    private fun request(path: String, key: String) = Request.Builder().url("$ORIGIN/$path")
        .header("Authorization", "Bearer $key").header("Accept", "application/json").header("Cache-Control", "no-cache")

    private fun requireProfile(profile: PlatformV2) {
        if (profile.compatibleType != ClientType.OPENROUTER ||
            profile.apiUrl.trimEnd('/') !in OpenRouterIdentity.endpoints ||
            profile.token.isNullOrBlank() ||
            profile.token.any { it.isWhitespace() || it.isISOControl() || it == ',' || it == ';' }
        ) {
            throw SetupException("Choose a saved OpenRouter profile with an official endpoint and one valid inference key.")
        }
    }

    private fun identity(profile: PlatformV2) = OpenRouterIdentity.digest(listOf(profile.uid, profile.apiUrl.trimEnd('/'), profile.token.orEmpty()).joinToString("\u0000"))
    private fun assignmentPath(hash: String) = "guardrails/policies/assignments/api-keys/$hash"
    private fun text(data: JsonObject, name: String) = OpenRouterPrivacyPolicy.nullableText(data, name)
    private fun malformed() = SetupException("OpenRouter returned incomplete privacy-policy information. Check again later; no model has been marked ready.")
    private fun unknown() = SetupException("An earlier privacy change has not been confirmed. Check this account again to read its outcome. The app will not repeat an uncertain change.")

    private suspend fun <T> friendly(block: suspend () -> T): T = try {
        // RestrictionsManager and SharedPreferences may synchronously access disk even
        // before a network request or after a response. Keep every setup entry point off UI.
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: SetupException) {
        throw known
    } catch (_: Exception) {
        throw SetupException("Privacy setup could not finish or its authorization changed. Check the account and management key, then try again.")
    }

    companion object {
        const val ORIGIN = "https://openrouter.ai/api/v1"
        const val POLICY_DOCUMENT = "https://openrouter.ai/docs/guides/features/zdr.md"
        private const val POLICY_PATH = "guardrails/policies/model-availability"
    }
}
