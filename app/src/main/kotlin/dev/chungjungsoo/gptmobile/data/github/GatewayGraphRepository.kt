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

    suspend fun execute(context: GitHubConversationContext, arguments: JsonObject): JsonObject = try {
        executeForMap(context, arguments, refreshReadiness = false).result
    } catch (failure: GatewayGraphOperationException) {
        throw requireNotNull(failure.cause)
    }

    internal suspend fun executeForMap(
        context: GitHubConversationContext,
        arguments: JsonObject,
        expectedGatewayProfileUid: String? = null,
        refreshReadiness: Boolean = true
    ): GatewayGraphResponse = operations.withLock {
        var gateway: PlatformV2? = null
        var checkpoint: GatewayGraphIndexCheckpoint? = null
        var attempt: String? = null
        var dispatched = false
        try {
            val admitted = admission(context, expectedGatewayProfileUid)
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
            var payload = boundRequest(context, arguments)
            val action = payload.text("action") ?: error("Select a repository graph operation.")
            val cancelJob = action == "index" && payload.text("job_action") == "cancel"
            val durableJobs = capability.codeGraph?.durableIndexJobs == true
            if (payload["background"] == JsonPrimitive(true) && !durableJobs) payload = JsonObject(payload - "background")
            val background = action == "index" && payload["background"] == JsonPrimitive(true)
            val modelAccess = if (GitHubAutonomousEngineeringPolicy.supportsSavedJobs(admitted.profile)) "PRIVATE_PROFILE_ELIGIBLE" else "PRIVATE_PROFILE_REQUIRED"
            var previousStatus: JsonObject? = null
            verifyAdmission(context, expectedGatewayProfileUid, admitted)
            if (cancelJob) {
                if (!durableJobs) throw GatewayGraphAdmissionException("GRAPH_BACKGROUND_UNSUPPORTED", "Update this gateway to control saved index jobs.", "UPDATE_SELECTED_GATEWAY_GRAPH_PROTOCOL")
                val saved = client.graph(frozenConfig, boundRequest(context, buildJsonObject { put("action", "status") }))
                verifyResult(context, saved)
                val job = saved["index_job"] as? JsonObject
                if (payload.text("job_id").isNullOrBlank() || payload.text("job_id") != job?.text("id")) {
                    throw GatewayGraphIntegrityException("GRAPH_JOB_CHANGED", "The saved indexing job changed. Refresh its status before stopping it.")
                }
                val jobGeneration = (payload["job_generation"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (jobGeneration == null || jobGeneration < 1 || payload["job_generation"] != job?.get("generation")) throw GatewayGraphIntegrityException("GRAPH_JOB_CHANGED", "The saved indexing job was replaced. Refresh its status before stopping the current job.")
                verifyAdmission(context, expectedGatewayProfileUid, admitted)
            }
            if (action == "index" && !cancelJob && retained.unresolved()) {
                throw GatewayGraphAdmissionException("GRAPH_INDEX_OUTCOME_UNKNOWN", "The previous index request is unconfirmed. Check the saved index on this gateway before continuing.", "CHECK_SAVED_INDEX")
            }
            // A retained cursor is a server-side precondition. A fresh build first reads
            // the existing snapshot, so reopening the map never restarts saved work.
            if (action == "index" && !cancelJob && payload.text("index_cursor").isNullOrBlank()) {
                val saved = client.graph(frozenConfig, boundRequest(context, buildJsonObject { put("action", "status") }))
                previousStatus = saved
                verifyResult(context, saved)
                verifyAdmission(context, expectedGatewayProfileUid, admitted)
                if (saved["has_more"] == JsonPrimitive(false) && arguments["retry_failed"] != JsonPrimitive(true)) {
                    return@withLock GatewayGraphResponse(saved, "status", admitted.gateway.uid, admitted.gateway.name, durableIndexJobs = durableJobs, modelAccess = modelAccess)
                }
                if (saved["has_more"] == JsonPrimitive(true) && arguments["retry_failed"] != JsonPrimitive(true) && !background) {
                    val cursor = saved.text("index_cursor")?.takeIf(String::isNotBlank)
                        ?: error("The saved index has no continuation cursor. Check its status before continuing.")
                    payload = JsonObject(payload + ("index_cursor" to JsonPrimitive(cursor)))
                }
            }
            if (action == "index" && !cancelJob) {
                attempt = retained.begin(payload.text("index_cursor"), previousStatus)
                if (background) payload = JsonObject(payload + ("request_id" to JsonPrimitive(attempt)))
            }
            // Persistence and capability reads can suspend. Revalidate before the POST,
            // not only after a stale configuration could have changed host state.
            verifyAdmission(context, expectedGatewayProfileUid, admitted)
            currentCoroutineContext().ensureActive()
            dispatched = true
            val result = client.graph(frozenConfig, payload)
            currentCoroutineContext().ensureActive()
            verifyResult(context, result)
            if (action == "index") {
                if (background || cancelJob) {
                    val job = result["index_job"] as? JsonObject
                    if (job?.text("id").isNullOrBlank() || job?.text("state").isNullOrBlank()) throw GatewayGraphIntegrityException("GRAPH_JOB_ACK_MISSING", "The gateway did not confirm its saved indexing job. Check the saved status before continuing.")
                    if (cancelJob && job?.text("id") != payload.text("job_id")) throw GatewayGraphIntegrityException("GRAPH_JOB_CHANGED", "The indexing reply belongs to a different saved job. Check its status before continuing.")
                    if (background && attempt != job?.text("request_id") && (job?.get("accepted_request_ids") as? JsonArray).orEmpty().none { (it as? JsonPrimitive)?.contentOrNull == attempt }) throw GatewayGraphIntegrityException("GRAPH_JOB_ACK_MISSING", "The saved job has not acknowledged this request. Check its status before continuing.")
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
            attempt?.let { retained.confirm(it) }
            val unresolved = if (action == "status") retained.reconcile(result) else retained.unresolved()
            GatewayGraphResponse(result, action, admitted.gateway.uid, admitted.gateway.name, unresolved, durableJobs, modelAccess)
        } catch (cancelled: CancellationException) {
            if (!dispatched) attempt?.let { token -> withContext(NonCancellable) { checkpoint?.confirm(token) } }
            throw cancelled
        } catch (failure: Exception) {
            // This typed prerequisite rejection is returned before graph execution.
            // Other HTTP failures can follow a saved batch and remain uncertain.
            if (!dispatched || failure is GatewayGraphUnavailableException) {
                attempt?.let { token -> withContext(NonCancellable) { checkpoint?.confirm(token) } }
            }
            val unavailable = when {
                failure is GatewayGraphUnavailableException -> failure.atRevision(context)
                failure is java.io.IOException && failure !is javax.net.ssl.SSLException -> GatewayGraphUnavailableException("The selected gateway graph connection is unavailable.", listOf(GatewayGraphPrerequisite.TRANSPORT_UNAVAILABLE)).atRevision(context)
                else -> failure
            }
            val unknown = checkpoint?.unresolved() == true
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
            throw GatewayGraphOperationException(unavailable, gateway?.uid ?: expectedGatewayProfileUid, gateway?.name, if (unknown) "CHECK_SAVED_INDEX" else repair, unknown)
        }
    }

    private data class Admission(val connection: ToolConnection, val profile: PlatformV2, val gateway: PlatformV2, val preferences: GitHubChatProfileOverride)

    private suspend fun admission(context: GitHubConversationContext, expectedGatewayProfileUid: String?): Admission {
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
        if (preferences.engineeringMemory == GitHubEngineeringMemoryMode.SESSION_ONLY) throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "Enable persistent engineering memory for the selected profile to use its repository graph.", "ENABLE_ENGINEERING_MEMORY")
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
