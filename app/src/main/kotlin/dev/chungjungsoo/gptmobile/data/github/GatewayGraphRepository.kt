package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Graph requests use an explicitly selected private gateway and its existing GitHub account. */
@Singleton
class GatewayGraphRepository @Inject constructor(
    private val settings: SettingRepository,
    private val connections: ToolConnectionRepository,
    private val client: GatewayEngineeringClient,
    private val state: GitHubRepositoryStateStore
) {
    private val operations = Mutex()

    /** Read-only admission; inference and engineering connections retain separate authority. */
    suspend fun readiness(context: GitHubConversationContext): String? = try {
        client.requireGraphReady(admission(context, null).gateway.engineeringConfig())
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalStateException) {
        failure.message ?: "The selected gateway repository graph service is unavailable."
    } catch (_: Exception) {
        "The selected gateway repository graph readiness check could not complete. Check its connection and engineering service status."
    }

    suspend fun execute(context: GitHubConversationContext, arguments: JsonObject): JsonObject {
        val response = executeForMap(context, arguments, refreshReadiness = false, forModel = true)
        // This observation belongs to the local checkpoint, not the gateway payload.
        // A successful status read need not acknowledge an earlier index request.
        return JsonObject(
            response.result + (
                "index_checkpoint" to buildJsonObject {
                    put("index_outcome_unknown", response.indexOutcomeUnknown)
                    response.indexAttemptHash?.let { put("graph_index_attempt_hash", it) }
                    response.confirmedIndexAttemptHash?.let { put("confirmed_index_attempt_hash", it) }
                    if (response.indexOutcomeUnknown) put("repair_action", "CHECK_SAVED_INDEX")
                }
                )
        )
    }

    internal suspend fun workspaceCheck(context: GitHubConversationContext, arguments: JsonObject): JsonObject {
        val admitted = admission(context, null, requirePersistentMemory = false)
        val key = GatewayGraphSharing.key(admitted.profile, context.connectionUid, context.repository.fullName, admitted.gateway.uid)
        fun verifyConsent() {
            check(!dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore.active() && admitted.preferences.workspaceCheckConsent == key) { "Authorize workspace checks for this model and repository." }
        }
        verifyConsent()
        require(arguments.keys.all { it in setOf("action", "request_id", "check_index", "changes") })
        val config = admitted.gateway.engineeringConfig()
        val identity = client.capabilities(config)
        val frozen = config.withGatewayIdentity(GatewayScopeIdentity(requireNotNull(identity.gatewayId), requireNotNull(identity.principalId)))
        val bound = buildJsonObject {
            arguments.forEach { (name, value) -> put(name, value) }
            put("repository", context.repository.fullName)
            put("branch", context.ref)
            put("head_sha", context.commitSha)
            put("profile_binding", key)
        }
        val result = client.profileCheck(frozen, bound)
        check(admission(context, admitted.gateway.uid, requirePersistentMemory = false) == admitted) { "The workspace connection or permissions changed." }
        verifyConsent()
        require(result["profile_binding"] == JsonPrimitive(key) && result["head_sha"] == JsonPrimitive(context.commitSha) && result["repository"] == JsonPrimitive(context.repository.fullName)) { "The check receipt belongs to a different profile or revision." }
        return result
    }

    internal suspend fun executeForMap(
        context: GitHubConversationContext,
        arguments: JsonObject,
        expectedGatewayProfileUid: String? = null,
        refreshReadiness: Boolean = true,
        forModel: Boolean = false
    ): GatewayGraphResponse = operations.withLock {
        var gateway: PlatformV2? = null
        var checkpoint: GatewayGraphIndexCheckpoint? = null
        var attempt: String? = null
        var observedCheckpoint: GatewayGraphIndexCheckpoint.Observation? = null
        var dispatched = false
        try {
            val admitted = admission(context, expectedGatewayProfileUid)
            fun requireSharing() {
                if (forModel && !GatewayGraphSharing.allowed(admitted.profile, admitted.preferences, context)) {
                    throw GatewayGraphAdmissionException("GRAPH_SOURCE_SHARING_REQUIRED", "Enable repository map sharing for this repository and AI profile in GitHub settings.")
                }
            }
            requireSharing()
            gateway = admitted.gateway
            val config = admitted.gateway.engineeringConfig()
            val capability = if (refreshReadiness) client.refreshCapabilities(config) else client.capabilities(config)
            val principal = capability.requireGraphReadiness()
            val frozenConfig = capability.gatewayId?.let { config.withGatewayIdentity(GatewayScopeIdentity(it, principal)) } ?: config
            val scope = GitHubRepositoryStateStore.digest(
                buildJsonObject {
                    put("kind", "gateway-graph-index-v1")
                    // Different AI profiles or aliases still share this remote index.
                    if (capability.gatewayId == null) put("endpoint", admitted.gateway.apiUrl.trim().trimEnd('/'))
                    put("gateway_id", capability.gatewayId)
                    put("principal", principal)
                    put("repository", "${context.owner}/${context.repo}".lowercase(java.util.Locale.ROOT))
                    put("branch", context.ref)
                    put("commit_sha", context.commitSha.lowercase(java.util.Locale.ROOT))
                }.toString()
            )
            val retained = GatewayGraphIndexCheckpoint(state, scope)
            checkpoint = retained
            observedCheckpoint = retained.observation()
            val expectedAttempt = arguments.text("expected_index_attempt_hash")
            val previouslyConfirmed = expectedAttempt?.takeIf { retained.previouslyConfirmed(it) }
            if (expectedAttempt != null) {
                require(arguments["action"] == JsonPrimitive("status") && (expectedAttempt == observedCheckpoint.attemptHash || previouslyConfirmed != null)) {
                    "The selected Gateway and revision do not own this pending index attempt."
                }
            }
            var payload = boundRequest(context, JsonObject(arguments - setOf("expected_index_attempt_hash", "confirm_sync_index_request_id", "sync_index_request_id")))
            val action = payload.text("action") ?: error("Select a repository graph operation.")
            val syncReceipts = capability.codeGraph?.syncIndexReceipts == true
            if (action == "status" && syncReceipts) {
                retained.pendingRequestId()?.let { id -> payload = JsonObject(payload + ("confirm_sync_index_request_id" to JsonPrimitive(id))) }
            }
            val jobAction = payload.text("job_action")
            val cancelJob = action == "index" && jobAction == "cancel"
            val controlJob = action == "index" && jobAction in setOf("cancel", "pause", "resume")
            val durableJobs = capability.codeGraph?.durableIndexJobs == true
            if (action == "index" && jobAction != null && !durableJobs) throw GatewayGraphAdmissionException("GRAPH_BACKGROUND_UNSUPPORTED", "Update this gateway to control saved index jobs.", "UPDATE_SELECTED_GATEWAY_GRAPH_PROTOCOL")
            // A model's index request means preparing this revision, not holding
            // one tool call open through every remote source read. Use the saved
            // job protocol by default when this host advertises it; an explicit
            // synchronous request and legacy gateways keep their bounded batches.
            if (forModel && action == "index" && durableJobs && "background" !in payload && "job_action" !in payload) {
                payload = JsonObject(payload + ("background" to JsonPrimitive(true)))
            }
            if (payload["background"] == JsonPrimitive(true) && !durableJobs) payload = JsonObject(payload - "background")
            // Job controls use the saved-job protocol even when the caller did
            // not repeat background=true. The gateway routes job_action that way.
            val background = action == "index" && (payload["background"] == JsonPrimitive(true) || jobAction != null)
            val modelAccess = when {
                !GatewayGraphSharing.allowed(admitted.profile, admitted.preferences, context) -> "SHARING_DISABLED"
                GitHubAutonomousEngineeringPolicy.supportsSavedJobs(admitted.profile) -> "PRIVATE_PROFILE_ELIGIBLE"
                else -> "REMOTE_PROFILE_AUTHORIZED"
            }
            var previousStatus: JsonObject? = null
            verifyAdmission(context, expectedGatewayProfileUid, admitted)
            if (controlJob) {
                if (!durableJobs) throw GatewayGraphAdmissionException("GRAPH_BACKGROUND_UNSUPPORTED", "Update this gateway to control saved index jobs.", "UPDATE_SELECTED_GATEWAY_GRAPH_PROTOCOL")
                val saved = client.graph(frozenConfig, boundRequest(context, buildJsonObject { put("action", "status") }))
                previousStatus = saved
                verifyResult(context, saved)
                val job = saved["index_job"] as? JsonObject
                if (payload.text("job_id").isNullOrBlank() || payload.text("job_id") != job?.text("id")) {
                    throw GatewayGraphIntegrityException("GRAPH_JOB_CHANGED", "The saved indexing job changed. Refresh its status before changing it.")
                }
                val jobGeneration = (payload["job_generation"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (jobGeneration == null || jobGeneration < 1 || payload["job_generation"] != job?.get("generation")) throw GatewayGraphIntegrityException("GRAPH_JOB_CHANGED", "The saved indexing job was replaced. Refresh its status before controlling the current job.")
                verifyAdmission(context, expectedGatewayProfileUid, admitted)
            }
            if (action == "index" && !cancelJob) {
                observedCheckpoint = retained.observation()
                if (observedCheckpoint.unresolved) {
                    throw GatewayGraphAdmissionException("GRAPH_INDEX_OUTCOME_UNKNOWN", "The previous index request is unconfirmed. Check the saved index on this gateway before continuing.", "CHECK_SAVED_INDEX")
                }
            }
            // A retained cursor is a server-side precondition. A fresh build first reads
            // the existing snapshot, so reopening the map never restarts saved work.
            if (action == "index" && !controlJob && payload.text("index_cursor").isNullOrBlank()) {
                val saved = client.graph(frozenConfig, boundRequest(context, buildJsonObject { put("action", "status") }))
                previousStatus = saved
                verifyResult(context, saved)
                verifyAdmission(context, expectedGatewayProfileUid, admitted)
                if (saved["has_more"] == JsonPrimitive(false) && arguments["retry_failed"] != JsonPrimitive(true)) {
                    verifyAdmission(context, expectedGatewayProfileUid, admitted)
                    requireSharing()
                    return@withLock GatewayGraphResponse(saved, "status", admitted.gateway.uid, admitted.gateway.name, durableIndexJobs = durableJobs, modelAccess = modelAccess)
                }
                if (saved["has_more"] == JsonPrimitive(true) && arguments["retry_failed"] != JsonPrimitive(true) && !background) {
                    val cursor = saved.text("index_cursor")?.takeIf(String::isNotBlank)
                        ?: error("The saved index has no continuation cursor. Check its status before continuing.")
                    payload = JsonObject(payload + ("index_cursor" to JsonPrimitive(cursor)))
                }
            }
            if (action == "index" && !cancelJob) {
                attempt = retained.begin(payload.text("index_cursor"), previousStatus, requestIdentityRequired = background || syncReceipts)
                if (background || syncReceipts) {
                    val requestField = if (background) "request_id" else "sync_index_request_id"
                    payload = JsonObject(payload + (requestField to JsonPrimitive(attempt)))
                }
            }
            // Persistence and capability reads can suspend. Revalidate before the POST,
            // not only after a stale configuration could have changed host state.
            verifyAdmission(context, expectedGatewayProfileUid, admitted)
            currentCoroutineContext().ensureActive()
            dispatched = true
            // Graph status is a revision-pinned read even though its transport uses POST.
            // A lost status response cannot change the remote index. Retry once only
            // for a transport IOException, never an index, cancel, TLS or HTTP failure.
            val result = try {
                client.graph(frozenConfig, payload)
            } catch (interrupted: java.io.IOException) {
                if (action != "status" || interrupted is javax.net.ssl.SSLException) throw interrupted
                // A suspended read may outlive an account, branch or permission change.
                // Reconfirm the original owner before allowing one bounded new read.
                verifyAdmission(context, expectedGatewayProfileUid, admitted)
                currentCoroutineContext().ensureActive()
                client.graph(frozenConfig, payload)
            }
            currentCoroutineContext().ensureActive()
            verifyResult(context, result)
            if (action == "index") {
                if (background || cancelJob) {
                    val job = result["index_job"] as? JsonObject
                    if (job?.text("id").isNullOrBlank() || job?.text("state").isNullOrBlank()) throw GatewayGraphIntegrityException("GRAPH_JOB_ACK_MISSING", "The gateway did not confirm its saved indexing job. Check the saved status before continuing.")
                    if (controlJob && job?.text("id") != payload.text("job_id")) throw GatewayGraphIntegrityException("GRAPH_JOB_CHANGED", "The indexing reply belongs to a different saved job. Check its status before continuing.")
                    if (background && !cancelJob && attempt != job?.text("request_id") && (job?.get("accepted_request_ids") as? JsonArray).orEmpty().none { (it as? JsonPrimitive)?.contentOrNull == attempt }) throw GatewayGraphIntegrityException("GRAPH_JOB_ACK_MISSING", "The saved job has not acknowledged this request. Check its status before continuing.")
                } else {
                    check(result["has_more"] in setOf(JsonPrimitive(true), JsonPrimitive(false))) { "The gateway did not confirm the index batch outcome. Check the saved index before continuing." }
                    check(result["has_more"] != JsonPrimitive(true) || !result.text("index_cursor").isNullOrBlank()) { "The gateway did not return the next indexing cursor. Check the saved index before continuing." }
                }
            }
            try {
                verifyAdmission(context, expectedGatewayProfileUid, admitted)
            } catch (changed: GatewayGraphAdmissionException) {
                // The POST already ran; this is no longer a pre-dispatch rejection.
                throw IllegalStateException(changed.message, changed)
            }
            val confirmed = attempt?.let { retained.confirm(it) }
            val observed = if (action == "status") retained.reconcileObserved(result, requireAttemptIdentity = expectedAttempt != null) else retained.observation()
            verifyAdmission(context, expectedGatewayProfileUid, admitted)
            requireSharing()
            GatewayGraphResponse(
                JsonObject(result - "confirmed_sync_request_id"),
                action,
                admitted.gateway.uid,
                admitted.gateway.name,
                observed.unresolved,
                durableJobs,
                modelAccess,
                observed.attemptHash ?: confirmed ?: observed.confirmedAttemptHash ?: previouslyConfirmed,
                confirmed ?: observed.confirmedAttemptHash ?: previouslyConfirmed
            )
        } catch (cancelled: CancellationException) {
            if (!dispatched) attempt?.let { token -> withContext(NonCancellable) { checkpoint?.confirm(token, executed = false) } }
            throw cancelled
        } catch (failure: Exception) {
            // This typed prerequisite rejection is returned before graph execution.
            // Other HTTP failures can follow a saved batch and remain uncertain.
            if (!dispatched || failure is GatewayGraphUnavailableException) {
                attempt?.let { token -> withContext(NonCancellable) { checkpoint?.confirm(token, executed = false) } }
            }
            val unavailable = when {
                failure is GatewayGraphUnavailableException -> failure.atRevision(context)
                failure is java.io.IOException && failure !is javax.net.ssl.SSLException -> GatewayGraphUnavailableException("The selected gateway graph connection is unavailable.", listOf(GatewayGraphPrerequisite.TRANSPORT_UNAVAILABLE)).atRevision(context)
                else -> failure
            }
            if (unavailable !== failure) unavailable.initCause(failure)
            val currentCheckpoint = checkpoint?.observation()
            val refusedPendingIndex = failure is GatewayGraphAdmissionException && failure.code == "GRAPH_INDEX_OUTCOME_UNKNOWN"
            val observed = if (refusedPendingIndex) observedCheckpoint else currentCheckpoint
            val unknown = observed?.unresolved == true
            val dispatchedAttemptHash = attempt?.let { checkpoint?.attemptHash(it) }
            // The prior checkpoint may have been confirmed and replaced during this call.
            // Never pair a newer checkpoint's unknown flag with the dispatched attempt's token.
            val attemptHash = if (unknown) observed?.attemptHash else dispatchedAttemptHash
            val currentIndexUnknown = dispatched && dispatchedAttemptHash != null && unknown && observed?.attemptHash == dispatchedAttemptHash
            val repair = when (unavailable) {
                is GatewayGraphUnavailableException -> unavailable.prerequisites.firstOrNull()?.nextAction
                is GatewayGraphAdmissionException -> unavailable.repairAction
                is GatewayAdmissionException -> when (unavailable.reason) {
                    GatewayAdmissionReason.ENGINEERING_GITHUB_REQUIRED -> "CONFIGURE_SELECTED_GATEWAY_HOST_GITHUB"
                    else -> "INSPECT_SELECTED_GATEWAY_READINESS"
                }
                is GatewayGraphIntegrityException -> "RECHECK_SELECTED_REVISION"
                is GatewayGraphResultException -> unavailable.repairAction
                is javax.net.ssl.SSLException -> "REVIEW_SELECTED_GATEWAY_CERTIFICATE"
                else -> null
            }
            throw GatewayGraphOperationException(
                unavailable,
                gateway?.uid ?: expectedGatewayProfileUid,
                gateway?.name,
                if (unknown) "CHECK_SAVED_INDEX" else repair,
                unknown,
                indexAttemptHash = attemptHash,
                currentIndexOutcomeUnknown = currentIndexUnknown
            )
        }
    }

    private data class Admission(val connection: ToolConnection, val profile: PlatformV2, val gateway: PlatformV2, val preferences: GitHubChatProfileOverride)

    private suspend fun admission(context: GitHubConversationContext, expectedGatewayProfileUid: String?, requirePersistentMemory: Boolean = true): Admission {
        val connection = connections.getConnection(context.connectionUid)
            ?: throw GatewayGraphAdmissionException("PERMISSION_REVOKED", "The selected GitHub connection was removed. Reconnect or explicitly select an authorized connection.", "RESTORE_SELECTED_GITHUB_CONNECTION")
        val profiles = settings.fetchPlatformV2s()
        val selected = profiles.singleOrNull { it.uid == context.mainProfileUid && it.enabled }
            ?: throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "The selected AI profile is unavailable. Restore it before opening the repository map.", "RESTORE_SELECTED_AI_PROFILE")
        val originalFeatures = settings.getFeatureSettings()
        val effectiveProfile = GitHubChatPolicy.platform(selected, originalFeatures, context)
        if (effectiveProfile.disableAllTools || effectiveProfile.disableRemoteTools) throw GatewayGraphAdmissionException("PERMISSION_DENIED", "Tools are disabled for the selected profile.")
        val features = GitHubChatPolicy.features(originalFeatures, selected.uid, context)
        val preferences = features.githubProfileOverrides[selected.uid] ?: GitHubChatProfileOverride()
        if (!features.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.GITHUB) || !features.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.connection(context.connectionUid)) || !preferences.githubTools) {
            throw GatewayGraphAdmissionException("PERMISSION_DENIED", "The GitHub plugin or tools are disabled for the selected profile.")
        }
        if (requirePersistentMemory && preferences.engineeringMemory == GitHubEngineeringMemoryMode.SESSION_ONLY) throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "Enable persistent engineering memory for the selected profile to use its repository graph.", "ENABLE_ENGINEERING_MEMORY")
        if (connection.type != "GITHUB") throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "The selected connection is not GitHub.")
        val gateway = engineeringGatewayProfile(profiles, preferences.engineeringGatewayProfileUid)
            ?: throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "Select an enabled private engineering gateway in GitHub settings. The AI connection remains separate.", "SELECT_CONFIGURED_ENGINEERING_SURFACE")
        if (expectedGatewayProfileUid != null && gateway.uid != expectedGatewayProfileUid) throw GatewayGraphAdmissionException("GRAPH_SELECTION_CHANGED", "The selected engineering gateway changed. Reopen the repository map to check that host.", "RECHECK_SELECTED_GATEWAY")
        return Admission(connection, selected, gateway, preferences)
    }

    private suspend fun verifyAdmission(context: GitHubConversationContext, expectedGatewayProfileUid: String?, admitted: Admission) {
        if (admission(context, expectedGatewayProfileUid) != admitted) throw GatewayGraphAdmissionException("GRAPH_SELECTION_CHANGED", "The graph connection or permissions changed. Reopen the repository map.", "RECHECK_SELECTED_GATEWAY")
    }

    companion object {
        internal fun boundRequest(context: GitHubConversationContext, arguments: JsonObject): JsonObject {
            require(arguments.toString().length <= 64_000) { "Graph request exceeds its inspection limit." }
            require(context.commitSha.matches(Regex("[a-fA-F0-9]{40}"))) { "Graph qualification requires the exact selected repository commit SHA." }
            val pinned = mapOf(
                "repository" to JsonPrimitive("${context.owner}/${context.repo}"),
                "branch" to JsonPrimitive(context.ref),
                "commit_sha" to JsonPrimitive(context.commitSha.lowercase(java.util.Locale.ROOT))
            )
            pinned.forEach { (name, value) ->
                val supplied = arguments[name]
                require(supplied == null || supplied is JsonPrimitive && supplied.isString && supplied.content.equals(value.content, ignoreCase = name != "branch")) {
                    "Graph request must use the selected repository revision."
                }
            }
            return JsonObject(arguments + pinned)
        }

        private fun verifyResult(context: GitHubConversationContext, result: JsonObject) {
            val metadata = result["graph"] as? JsonObject ?: result
            if (!(
                    "${context.owner}/${context.repo}".equals(metadata.text("repository"), ignoreCase = true) &&
                        context.commitSha.equals(metadata.text("commit_sha"), ignoreCase = true) &&
                        (metadata["branch"] == null || metadata.text("branch") == context.ref)
                    )
            ) {
                throw GatewayGraphIntegrityException("GRAPH_REVISION_MISMATCH", "The gateway graph response does not match the selected repository revision. Check the original host before continuing.")
            }
            if (result.text("status") in setOf("RESULT_ITEM_EXCEEDS_BUDGET", "RESULT_EXCEEDS_BUDGET")) {
                throw GatewayGraphResultException("GRAPH_RESULT_BUDGET_TOO_SMALL", "This source result exceeds the available response size. Search for a narrower symbol or source path, or inspect that source directly.", "NARROW_GRAPH_QUERY")
            }
        }

        private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    }
}
