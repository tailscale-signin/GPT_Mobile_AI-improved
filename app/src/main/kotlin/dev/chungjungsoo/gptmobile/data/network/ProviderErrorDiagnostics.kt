package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.redactLogMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Bounded error-only details. Never log request bodies, arbitrary metadata, or credential echoes. */
internal fun providerErrorDetails(body: String, fallback: String, credential: String? = null): String {
    val envelope = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = envelope?.get("error") as? JsonObject ?: envelope
    val templateFailure = dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.find(body)?.takeIf { it.code == "BACKEND_TEMPLATE_INVALID" }
    val metadata = error?.get("metadata") as? JsonObject
    fun JsonObject?.value(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    val details = listOfNotNull(
        templateFailure?.message ?: error.value("message") ?: fallback,
        error.value("code")?.let { "code=$it" },
        error.value("failed_stage")?.let { "stage=$it" },
        error.value("job_id")?.let { "job=$it" },
        error.value("request_id")?.let { "request=$it" },
        error.value("dispatch_state")?.let { "dispatch=$it" },
        error.value("retryable")?.let { "retryable=$it" },
        metadata.value("provider_name")?.let { "provider=$it" },
        (envelope.value("id") ?: metadata.value("generation_id"))?.let { "generation=$it" },
        metadata.value("raw")?.takeIf { templateFailure == null }?.let { raw ->
            val nested = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("data:").trim()) as? JsonObject }.getOrNull()
            val nestedError = nested?.get("error") as? JsonObject ?: nested
            // Only the upstream message, not an arbitrary response body (which can echo prompts).
            listOfNotNull(
                nestedError.value("message"),
                // Some upstreams put the actionable ceiling in param. Accept only a
                // numeric token-limit diagnostic, never arbitrary echoed parameters.
                nestedError.value("param")?.takeIf { param ->
                    Regex("(?i)^max[_ ](?:completion[_ ])?tokens.{0,80}(?:too large|exceed)").containsMatchIn(param) &&
                        Regex("(?i)(?:completion|output) tokens").containsMatchIn(param)
                }
            ).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "upstream=$it" }
        }
    ).joinToString(" · ")
    var redacted = details
    credential?.split(',')?.map(String::trim)?.filter { it.isNotBlank() }?.forEach { secret ->
        redacted = redacted.replace(secret, "[redacted]")
    }
    return redactLogMessage(redacted).replace('\n', ' ').replace('\r', ' ').take(1600)
}

/** Allowlisted gateway context survives HTTP and SSE parsing without retaining request bodies. */
internal fun structuredProviderFailure(body: String, httpStatus: Int? = null): JsonObject? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = root?.get("error") as? JsonObject
    val fields = error.orEmpty().filter { (key, value) ->
        key in setOf(
            "code", "failed_stage", "request_id", "job_id", "dispatch_state", "retryable", "dispatched", "inference_dispatched",
            "http_status", "backend_status", "backend_error_code", "backend_error_type", "origin", "failureCode", "cause_code", "backend_error_digest",
            "terminal_status", "terminal_status_code", "status_code", "result_available", "recovery_code", "recovery_http_status",
            "active_job_id", "active_job_kind", "active_job_state", "conversation_id", "conversation_revision", "recovery_action", "retryable_without_new_input"
        ) &&
            value is JsonPrimitive &&
            value.content.length <= 256
    }.toMutableMap()
    val pending = error?.get("pending_client_tool_call_ids") as? kotlinx.serialization.json.JsonArray
    if (pending != null && pending.size <= 128 && pending.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 256 && it.content.none(Char::isISOControl) }) {
        fields["pending_client_tool_call_ids"] = pending
    }
    val actions = error?.get("allowed_recovery_actions") as? JsonArray
    val knownActions = setOf("open_active_job", "return_client_tool_results", "wait_for_active_job", "queue_instruction", "resolve_active_job")
    if (actions != null && actions.size <= knownActions.size && actions.all { it is JsonPrimitive && it.isString && it.content in knownActions }) {
        fields["allowed_recovery_actions"] = actions
    }
    val selectionFields = setOf("profile_uid", "profile_revision", "provider_connection_id", "provider_connection_revision", "provider_kind", "configured_endpoint", "requested_model_id", "generation_parameters", "privacy_scope", "tool_owner", "required_capability_policy", "credential_reference", "credential_account_identity", "approved_alias_mapping")
    for (key in listOf("missing_fields", "invalid_fields")) {
        val names = error?.get(key) as? JsonArray
        if (names != null && names.size <= selectionFields.size && names.all { it is JsonPrimitive && it.isString && it.content in selectionFields }) fields[key] = names
    }
    (error?.get("reasonCode") as? JsonPrimitive)?.takeIf {
        it.isString && it.content in setOf("SELECTION_NOT_OBJECT", "SELECTION_SCHEMA_INVALID", "SELECTION_IDENTITY_INVALID", "SELECTION_CREDENTIAL_REFERENCE_INVALID")
    }?.let { fields["reasonCode"] = it }
    (error?.get("unknown_field_count") as? JsonPrimitive)?.takeIf { !it.isString && it.intOrNull?.let { count -> count in 0..98_304 } == true }
        ?.let { fields["unknown_field_count"] = it }
    boundedRepairUsage(error?.get("gateway_recovery") as? JsonObject)?.let { fields["gateway_recovery"] = it }
    httpStatus?.takeIf { it in 100..599 }?.let { fields["http_status"] = JsonPrimitive(it) }
    dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.find(
        (error?.get("code") as? JsonPrimitive)?.contentOrNull,
        (error?.get("message") as? JsonPrimitive)?.contentOrNull
    )?.let { contract ->
        fields["code"] = JsonPrimitive(contract.code)
        fields["retryable"] = JsonPrimitive(false)
        fields.putIfAbsent("failed_stage", JsonPrimitive(contract.phase))
    }
    if (error?.get("command_outcome_unknown") == JsonPrimitive(true)) {
        // A command identity collision is not proof that the original command
        // was unsent. Reconcile it instead of replaying a rejected new turn.
        fields["code"] = JsonPrimitive("GATEWAY_OUTCOME_UNKNOWN")
        fields["failed_stage"] = JsonPrimitive("gateway_dispatch")
        fields["retryable"] = JsonPrimitive(false)
        fields["requires_reconciliation"] = JsonPrimitive(true)
        fields["request_replay_allowed"] = JsonPrimitive(false)
        fields.remove("dispatched")
        fields.remove("dispatch_state")
    }
    return fields.takeIf { it.isNotEmpty() }?.let(::JsonObject)
}

/** Keep exact measured attempts separately; missing usage is never an estimated total. */
private fun boundedRepairUsage(recovery: JsonObject?): JsonObject? {
    if (recovery?.get("usage_origin") != JsonPrimitive("PROVIDER_REPORTED") || recovery["usage_estimated"] != JsonPrimitive(false)) return null
    val observed = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
        "usage_origin" to JsonPrimitive("PROVIDER_REPORTED"),
        "usage_estimated" to JsonPrimitive(false)
    )
    for (key in listOf("attempts", "failed_attempt", "output_allowance")) {
        val value = (recovery[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (value != null && value >= 0 && (key == "output_allowance" || value <= 8)) observed[key] = JsonPrimitive(value)
    }
    (recovery["failed_attempt_usage_available"] as? JsonPrimitive)?.takeIf { it == JsonPrimitive(true) || it == JsonPrimitive(false) }
        ?.let { observed["failed_attempt_usage_available"] = it }
    for ((key, names) in mapOf("attempt_usage" to setOf("prompt_tokens", "completion_tokens", "total_tokens"), "attempt_backend_tokens" to setOf("prompt_n", "predicted_n"))) {
        val attempts = recovery[key] as? JsonArray ?: continue
        if (attempts.size > 8 || attempts.any { it !is JsonObject }) continue
        observed[key] = JsonArray(
            attempts.map { attempt ->
                JsonObject(
                    (attempt as JsonObject).filter { (name, value) ->
                        name in names && value is JsonPrimitive && !value.isString && value.longOrNull?.let { it >= 0 } == true
                    }
                )
            }
        )
    }
    return JsonObject(observed)
}
