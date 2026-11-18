package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The phone retains a recovery handle; the selected host owns dispatch and supervision. */
@Singleton
class GatewayCopilotOperations @Inject constructor(
    private val settings: SettingRepository,
    private val client: GatewayEngineeringClient,
    private val state: GitHubRepositoryStateStore
) {
    internal suspend fun execute(profileUid: String, gatewayProfileUid: String, scope: String, action: String, arguments: JsonObject, allowCreate: suspend () -> Boolean): JsonObject {
        currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
        val profile = settings.engineeringGateway(profileUid)
        check(profile.uid == gatewayProfileUid) { "The selected Copilot Gateway changed. Restore the original controller for saved tasks." }
        check(settings.getFeatureSettings().githubProfileOverrides[profileUid]?.copilotExecutionOwner == GitHubCopilotExecutionOwner.GATEWAY) {
            "The Copilot controller changed. Resolve tools again; existing tasks retain their original controller."
        }
        val config = profile.engineeringConfig()
        val capability = client.copilotCapabilities(config)
        check(
            capability.text("schema") == SCHEMA &&
                capability["supported"] == JsonPrimitive(true) &&
                capability.text("execution_owner") == "GITHUB_CLOUD" &&
                capability.text("orchestration_owner") == "GATEWAY"
        ) { "This Gateway does not support durable Copilot cloud tasks." }
        val actorId = capability["actor_id"]?.takeUnless { it == JsonNull }?.let {
            requireNotNull((it as? JsonPrimitive)?.takeUnless { actor -> actor.isString }?.longOrNull?.takeIf { actor -> actor > 0 }) {
                "The Gateway did not verify a valid GitHub user account."
            }
        }
        val identity = GatewayScopeIdentity(capability.text("gateway_id"), capability.text("principal_id"))
        requireGatewayIdentity(identity.gatewayId, identity.principalId)
        val boundConfig = config.withGatewayIdentity(identity)
        val binding = buildJsonObject {
            put("gateway_id", identity.gatewayId)
            put("principal_id", identity.principalId)
            put("profile_uid", profile.uid)
            put("endpoint_sha256", GitHubRepositoryStateStore.digest(profile.apiUrl.orEmpty()))
        }
        val repository = "${arguments.text("owner")}/${arguments.text("repo")}".also {
            require(it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Choose the original GitHub repository." }
        }
        val namespace = "$scope:gateway-copilot-tasks"
        fun claimKey(request: JsonObject) = key("payload:" + GitHubExecutionJournal.canonical(JsonObject(request - setOf("request_id", "expected_base_sha", "expected_head_sha"))))
        suspend fun retainObservation(request: JsonObject, requestAlias: String, receipt: JsonObject) {
            val observed = state.update(namespace, claimKey(request)) { prior ->
                val saved = requireNotNull(prior) { "The original Copilot claim is unavailable." }
                check(saved["binding"] == binding && saved["request"] == request) { "The saved Copilot task changed." }
                JsonObject(saved + mapOf("dispatch_state" to JsonPrimitive("OBSERVED"), "receipt" to receipt))
            }
            state.update(namespace, key("request:$requestAlias")) { prior ->
                check(prior?.get("binding") == binding && prior["request"] == request) { "The saved Copilot request changed." }
                observed
            }
        }
        suspend fun validateRoute() {
            currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
            val live = settings.engineeringGateway(profileUid)
            check(live.uid == profile.uid && live.apiUrl == profile.apiUrl && live.token == profile.token) { "The Gateway connection changed before dispatch." }
            check(settings.getFeatureSettings().githubProfileOverrides[profileUid]?.copilotExecutionOwner == GitHubCopilotExecutionOwner.GATEWAY) { "The Copilot controller changed before dispatch." }
        }
        fun verify(receipt: JsonObject, request: JsonObject? = null) {
            check(
                receipt.text("schema") == SCHEMA &&
                    receipt.text("execution_owner") == "GITHUB_CLOUD" &&
                    receipt.text("orchestration_owner") == "GATEWAY" &&
                    receipt.text("gateway_id") == identity.gatewayId &&
                    receipt.text("principal_id") == identity.principalId &&
                    receipt.text("repository").equals(repository, true) &&
                    receipt.text("gateway_job_id").matches(JOB_ID)
            ) { "The Gateway returned an unrelated Copilot receipt. Reconcile the original task." }
            if (actorId != null) check(receipt["github_actor_id"] == JsonPrimitive(actorId)) { "The Copilot receipt belongs to another GitHub account." }
            if (request != null) {
                check(receipt["request_id"] == request["request_id"] && receipt["github_actor_id"] == request["expected_actor_id"]) { "The Copilot receipt belongs to another request or GitHub account." }
                check(receipt["base_ref"] == request["base_ref"] && receipt["expected_base_sha"] == request["expected_base_sha"]) { "The Copilot task base changed." }
                check(listOf("head_ref", "expected_head_sha", "model", "custom_agent", "create_pull_request").all { receipt[it] == request[it] }) { "The Copilot task options changed." }
            }
        }
        fun verifyRetained(saved: JsonObject) {
            check(saved["binding"] == binding) { "Restore the original Gateway and account for this saved Copilot task." }
            val request = requireNotNull(saved["request"] as? JsonObject)
            check(request.text("repository").equals(repository, true)) { "Restore the original Copilot task repository." }
            if (actorId != null) check(JsonPrimitive(actorId) == request["expected_actor_id"]) { "The Gateway GitHub account changed. Restore the original account." }
        }
        suspend fun reconcile(request: JsonObject, jobId: String? = null): JsonObject {
            validateRoute()
            return try {
                val receipt = if (jobId != null) {
                    client.copilotTask(boundConfig, jobId)
                } else {
                    val result = client.copilotTasks(boundConfig, repository, request.text("request_id"))
                    check(result.text("schema") == SCHEMA) { "The Gateway task list is invalid." }
                    val tasks = result["tasks"] as? JsonArray
                    requireNotNull(tasks?.filterIsInstance<JsonObject>()?.singleOrNull { it["request_id"] == request["request_id"] }) { "The saved request has no verified Gateway receipt yet." }
                }
                receipt.also {
                    verify(it, request)
                    if (jobId != null) check(it.text("gateway_job_id") == jobId) { "The Gateway returned another task." }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw GitHubExecutionBlocked(reconciliationRequired(request.text("request_id"), jobId))
            }
        }
        if (action == "copilot_tasks") {
            fun pageArgument(name: String, default: Int, maximum: Int): Int = arguments[name]?.let {
                requireNotNull((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull?.takeIf { value -> value in 1..maximum }) {
                    "$name must be an integer between 1 and $maximum."
                }
            } ?: default
            val page = pageArgument("page", 1, 1000)
            val perPage = pageArgument("per_page", 30, 100)
            validateRoute()
            val result = client.copilotTasks(boundConfig, repository, page = page, perPage = perPage)
            check(result.text("schema") == SCHEMA) { "The Gateway task list is invalid." }
            val tasks = requireNotNull(result["tasks"] as? JsonArray) { "The Gateway task list is invalid." }
            val hasMore = requireNotNull((result["has_more"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) { "The Gateway task page is incomplete." }
            check(result["page"] == JsonPrimitive(page) && result["per_page"] == JsonPrimitive(perPage) && result["complete"] == JsonPrimitive(!hasMore) && tasks.size <= perPage) {
                "The Gateway returned another task page."
            }
            tasks.forEach { verify(requireNotNull(it as? JsonObject)) }
            return JsonObject(
                result + mapOf(
                    "execution_owner" to JsonPrimitive("GITHUB_CLOUD"),
                    "orchestration_owner" to JsonPrimitive("GATEWAY"),
                    "gateway_id" to JsonPrimitive(identity.gatewayId),
                    "principal_id" to JsonPrimitive(identity.principalId)
                )
            )
        }
        if (action == "copilot_task_status") {
            val requestId = arguments.text("request_id")
            val jobId = arguments.text("gateway_job_id").takeIf(String::isNotBlank)
            require(arguments.text("task_id").isBlank() && (requestId.isNotBlank() != (jobId != null))) { "Use exactly one saved request_id or gateway_job_id to inspect this Gateway task." }
            if (requestId.isNotBlank()) {
                val saved = requireNotNull(state.read(namespace, key("request:$requestId"))) { "The original Copilot request is unavailable. List the selected Gateway's saved tasks to recover its Gateway job ID." }
                verifyRetained(saved)
                val receipt = saved["receipt"] as? JsonObject
                val original = requireNotNull(saved["request"] as? JsonObject)
                return reconcile(original, receipt?.text("gateway_job_id")?.takeIf(String::isNotBlank)).also {
                    retainObservation(original, requestId, it)
                }
            }
            require(jobId != null && jobId.matches(JOB_ID)) { "Use the saved gateway_job_id or request_id to inspect this Gateway task. An upstream task_id is not a Gateway job ID." }
            validateRoute()
            return client.copilotTask(boundConfig, jobId).also {
                verify(it)
                check(it.text("gateway_job_id") == jobId) { "The Gateway returned another task." }
            }
        }
        require(action == "copilot_create_task") { "Choose a supported Copilot task action." }
        val requestId = arguments.text("request_id")
        require(requestId.matches(REQUEST_ID)) { "Provide a stable request_id for Copilot task recovery." }
        val actor = checkNotNull(actorId) { "Configure and verify the Gateway's GitHub user account before submitting cloud work." }
        arguments.text("expected_actor_id").takeIf(String::isNotBlank)?.let {
            check(it == actor.toString()) { "The selected Gateway GitHub actor differs from the approved account." }
        }
        val payload = buildJsonObject {
            put("repository", repository)
            put("request_id", requestId)
            listOf("prompt", "base_ref", "expected_base_sha", "head_ref", "expected_head_sha", "model", "custom_agent", "create_pull_request").forEach { name -> arguments[name]?.let { put(name, it) } }
            if (arguments["create_pull_request"] == null) put("create_pull_request", false)
            put("expected_actor_id", actor)
            put("execution_owner", "GATEWAY")
            put("copilot_enabled", true)
            put("conversation_copilot_enabled", true)
        }
        val retained = state.update(namespace, claimKey(payload)) { prior ->
            if (prior != null) {
                verifyRetained(prior)
                prior
            } else {
                check(capability["enabled"] == JsonPrimitive(true) && capability["configured"] == JsonPrimitive(true)) { "Enable Copilot cloud tasks in the selected Gateway's GitHub settings. The task API preview requires Copilot Business or Enterprise and Agent tasks permission." }
                buildJsonObject {
                    put("binding", binding)
                    put("request", payload)
                    put("dispatch_state", "PREPARED")
                }
            }
        }
        // Alias every explicit request before network dispatch; a lost response is recoverable by GET.
        state.update(namespace, key("request:$requestId")) { prior ->
            if (prior != null) {
                check(prior["binding"] == retained["binding"] && prior["request"] == retained["request"]) { "This request_id already identifies a different Copilot task." }
                prior
            } else {
                retained
            }
        }
        validateRoute()
        check(allowCreate()) { "Copilot consent was revoked before Gateway dispatch." }
        var dispatch = false
        val claimed = state.update(namespace, claimKey(payload)) { prior ->
            val saved = requireNotNull(prior)
            verifyRetained(saved)
            if (saved.text("dispatch_state") == "PREPARED") {
                dispatch = true
                JsonObject(saved + ("dispatch_state" to JsonPrimitive("DISPATCH_ATTEMPTED")))
            } else {
                saved
            }
        }
        val original = requireNotNull(claimed["request"] as? JsonObject)
        val result = if (dispatch) {
            try {
                client.copilotCreate(boundConfig, original).also { verify(it, original) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: GatewayEngineeringRequestRejected) {
                throw rejected
            } catch (_: Exception) {
                throw GitHubExecutionBlocked(reconciliationRequired(original.text("request_id")))
            }
        } else {
            reconcile(original, (claimed["receipt"] as? JsonObject)?.text("gateway_job_id")?.takeIf(String::isNotBlank))
        }
        retainObservation(original, requestId, result)
        return result
    }

    private fun reconciliationRequired(requestId: String, jobId: String? = null) = buildJsonObject {
        put("schema", SCHEMA)
        put("status", "RECONCILE_REQUIRED")
        put("request_id", requestId)
        jobId?.let { put("gateway_job_id", it) }
        put("execution_owner", "GITHUB_CLOUD")
        put("orchestration_owner", "GATEWAY")
        put("remote_state_verified", false)
        put("message", "The original Gateway task may still be running. Restore its controller and account, then check its saved request. Do not submit another task.")
        put(
            "next_action",
            buildJsonObject {
                put("action", "copilot_task_status")
                put("request_id", requestId)
            }
        )
    }

    private fun key(value: String) = "execution:" + GitHubRepositoryStateStore.digest(value)
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        const val SCHEMA = "github.copilot-tasks.v1"
        val JOB_ID = Regex("cp_[a-f0-9]{32}")
        val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    }
}
