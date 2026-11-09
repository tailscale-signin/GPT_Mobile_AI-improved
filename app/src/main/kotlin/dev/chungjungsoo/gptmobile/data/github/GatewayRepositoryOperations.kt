package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A host transfer keeps its original gateway and principal even across app restarts. */
@Singleton
class GatewayRepositoryOperations @Inject constructor(
    private val settings: SettingRepository,
    private val client: GatewayEngineeringClient,
    private val state: GitHubRepositoryStateStore
) {
    internal suspend fun execute(profileUid: String, gatewayProfileUid: String, scope: String, arguments: JsonObject): JsonObject {
        currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
        val profile = settings.engineeringGateway(profileUid)
        check(profile.uid == gatewayProfileUid) { "The selected repository-transfer gateway changed. Resolve the tools again before continuing." }
        val config = profile.engineeringConfig()
        val capability = client.capabilities(config)
        val identity = GatewayScopeIdentity(requireNotNull(capability.gatewayId), requireNotNull(capability.principalId))
        requireGatewayIdentity(identity.gatewayId, identity.principalId)
        val action = arguments.text("action")
        if (action == "repository_lifecycle_capabilities") {
            return buildJsonObject {
                put("history_import", capability.repositoryOperations?.supports("import_repository") == true)
                put("gateway_clone", capability.repositoryOperations?.supports("clone_repository") == true)
            }
        }
        val bound = buildJsonObject {
            put("gateway_id", identity.gatewayId)
            put("principal_id", identity.principalId)
            put("profile_uid", gatewayProfileUid)
            put("endpoint_sha256", GitHubRepositoryStateStore.digest(profile.apiUrl.orEmpty()))
        }
        val namespace = "$scope:gateway-repository-operations"
        val requestedOperationId = arguments.text("operation_id")
        suspend fun validateRoute() {
            currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
            val current = settings.engineeringGateway(profileUid)
            check(current.uid == profile.uid && current.apiUrl == profile.apiUrl && current.token == profile.token) { "The gateway connection changed before dispatch." }
        }
        if (action == "repository_lifecycle_status") {
            require(requestedOperationId.matches(OPERATION_ID)) { "Choose the retained repository operation identity." }
            val retained = state.read(namespace, key("receipt:$requestedOperationId"))
            check(retained?.get("binding") == bound) { "Restore the original gateway and account for this retained repository operation." }
            val request = requireNotNull(retained["request"] as? JsonObject) { "The original repository request is unavailable. Reconcile it on its gateway." }
            val digest = repositoryOperationDigest(identity.principalId, request)
            check(requestedOperationId == operationId(digest)) { "The retained repository operation does not match its original request." }
            validateRoute()
            val result = verifiedReceipt(request, digest) { client.repositoryOperationStatus(config.withGatewayIdentity(identity), requestedOperationId) }
            state.update(namespace, key("request:" + GitHubExecutionJournal.canonical(request))) { prior ->
                check(prior?.get("binding") == bound && prior["request"] == request && prior.text("request_digest") == digest) { "The original repository request is unavailable. Reconcile it on its gateway." }
                observed(prior)
            }
            return result
        }
        require(action in setOf("clone_repository", "import_repository")) { "Choose a supported repository operation." }
        val payload = buildJsonObject {
            put("operation", action)
            listOf("source_owner", "source_repo", "source_ref", "source_commit_sha", "expected_actor_id").forEach { name -> arguments[name]?.let { put(name, it) } }
            if (action == "import_repository") listOf("destination_owner", "destination_repo", "owner_type", "visibility", "copy_mode").forEach { name -> arguments[name]?.let { put(name, it) } }
        }
        val digest = repositoryOperationDigest(identity.principalId, payload)
        val expectedOperationId = operationId(digest)
        val requestKey = key("request:" + GitHubExecutionJournal.canonical(payload))
        fun prepared() = buildJsonObject {
            put("binding", bound)
            put("request", payload)
            put("request_digest", digest)
            put("operation_id", expectedOperationId)
            put("status", "RUNNING")
            put("dispatch_state", "PREPARED")
        }
        fun verifyRetained(prior: JsonObject) {
            check(prior["binding"] == bound && prior["request"] == payload && prior.text("request_digest") == digest && prior.text("operation_id") == expectedOperationId) {
                "A repository transfer already belongs to another gateway, account or request. Restore it and reconcile the retained operation."
            }
        }
        fun requireCapability() {
            check(capability.repositoryOperations?.supports(action) == true) { "This gateway has not qualified $action. Configure its Git and GitHub connection, then refresh capabilities; no transfer was dispatched." }
        }
        // Both protected records precede dispatch. The exact request gives a lost
        // response a known GET identity; a route/account change cannot create again.
        val retained = state.update(namespace, requestKey) { prior ->
            if (prior != null) {
                verifyRetained(prior)
                prior
            } else {
                requireCapability()
                prepared()
            }
        }
        state.update(namespace, key("receipt:$expectedOperationId")) { prior ->
            prior?.also(::verifyRetained) ?: JsonObject(prepared() + ("status" to JsonPrimitive("RETAINED")))
        }
        validateRoute()
        var dispatch = false
        if (retained.text("dispatch_state") in setOf("PREPARED", "REJECTED")) {
            requireCapability()
            state.update(namespace, requestKey) { prior ->
                val original = requireNotNull(prior)
                verifyRetained(original)
                if (original.text("dispatch_state") in setOf("PREPARED", "REJECTED")) {
                    dispatch = true
                    JsonObject(original.filterKeys { it != "rejection_http_status" } + mapOf("status" to JsonPrimitive("RUNNING"), "dispatch_state" to JsonPrimitive("DISPATCH_ATTEMPTED")))
                } else {
                    original
                }
            }
        }
        val result = if (dispatch) {
            try {
                verifiedReceipt(payload, digest) { client.repositoryOperation(config.withGatewayIdentity(identity), payload) }
            } catch (rejected: GatewayEngineeringRequestRejected) {
                // An explicit tool retry may repair a proven pre-admission rejection.
                // Transport loss, cancellation, 5xx and status 404 never clear a claim.
                if (!rejected.commandOutcomeUnknown) {
                    state.update(namespace, requestKey) { prior ->
                        val original = requireNotNull(prior)
                        verifyRetained(original)
                        if (original.text("dispatch_state") == "OBSERVED") original else JsonObject(original + mapOf("status" to JsonPrimitive("REJECTED"), "dispatch_state" to JsonPrimitive("REJECTED"), "rejection_http_status" to JsonPrimitive(rejected.httpStatus)))
                    }
                }
                throw rejected
            }
        } else {
            verifiedReceipt(payload, digest) { client.repositoryOperationStatus(config.withGatewayIdentity(identity), expectedOperationId) }
        }
        state.update(namespace, requestKey) { prior ->
            val original = requireNotNull(prior)
            verifyRetained(original)
            observed(original)
        }
        return result
    }

    private suspend fun verifiedReceipt(request: JsonObject, digest: String, fetch: suspend () -> JsonObject): JsonObject = try {
        fetch().also { verifyReceipt(request, digest, it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: GatewayEngineeringRequestRejected) {
        throw rejected
    } catch (_: Exception) {
        throw GitHubExecutionBlocked(
            buildJsonObject {
                put("schema", "github.repository-lifecycle.v1")
                put("status", "RECONCILE_REQUIRED")
                put("operation_id", operationId(digest))
                put("request_digest", digest)
                put("operation", requireNotNull(request["operation"]))
                put("remote_state_verified", false)
                put("message", "The original repository operation has no verified current receipt. Check its retained status on the original gateway and account before continuing.")
                put(
                    "next_action",
                    buildJsonObject {
                        put("action", "repository_lifecycle_status")
                        put("operation_id", operationId(digest))
                    }
                )
            }
        )
    }

    private fun verifyReceipt(request: JsonObject, digest: String, result: JsonObject) {
        check(result.text("request_digest") == digest && result.text("operation_id") == operationId(digest)) { "The gateway returned a different repository request. Reconcile the original retained operation." }
        check(
            result.text("schema") == "github.repository-lifecycle.v1" &&
                result["operation"] == request["operation"] &&
                result.text("source_repository").equals("${request.text("source_owner")}/${request.text("source_repo")}", true)
        ) { "The gateway returned an unrelated repository operation receipt." }
        if (request.text("operation") == "import_repository") {
            check(
                result.text("destination_repository").equals("${request.text("destination_owner")}/${request.text("destination_repo")}", true) &&
                    result["visibility"] == request["visibility"] &&
                    result["copy_mode"] == request["copy_mode"]
            ) { "The gateway repository destination or copy semantics changed." }
        }
        if (request["source_commit_sha"] != null && result["source_commit_sha"] != null) check(request["source_commit_sha"] == result["source_commit_sha"]) { "The gateway changed the pinned source revision." }
    }

    private fun operationId(digest: String) = "repoop_" + digest.take(32)
    private fun observed(original: JsonObject) = JsonObject(original.filterKeys { it != "rejection_http_status" } + mapOf("status" to JsonPrimitive("RETAINED"), "dispatch_state" to JsonPrimitive("OBSERVED")))
    private fun key(value: String) = "execution:" + GitHubRepositoryStateStore.digest(value)
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val OPERATION_ID = Regex("repoop_[a-f0-9]{32}")
    }
}

/** Matches the host's sorted, compact JSON with ensure_ascii=True, including Unicode principals. */
internal fun repositoryOperationDigest(principal: String, payload: JsonObject): String {
    val canonical = GitHubExecutionJournal.canonical(JsonArray(listOf(JsonPrimitive(principal), payload))).toString()
    val ascii = buildString {
        canonical.forEach { character ->
            if (character.code >= 127) append("\\u").append(character.code.toString(16).padStart(4, '0')) else append(character)
        }
    }
    return GitHubRepositoryStateStore.digest(ascii)
}
