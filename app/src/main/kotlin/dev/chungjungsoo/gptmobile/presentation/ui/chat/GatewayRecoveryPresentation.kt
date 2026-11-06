package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class GatewayRecoveryPresentation(
    val stage: String,
    val reason: String?,
    val profileUid: String,
    val requestedModel: String,
    val observedModel: String,
    val executionIdentity: String,
    val hardware: String,
    val nextAction: String,
    val canReconcileRequest: Boolean,
    val canCancel: Boolean
)

internal fun gatewayRecoveryPresentation(binding: GatewayEngineeringBinding): GatewayRecoveryPresentation {
    val job = binding.job
    val reason = job?.blockedReason?.string("code")
    val identity = job?.latestExecutionReceipt?.string("identity_status") ?: "UNAVAILABLE"
    val canReconcile = job == null && binding.executionAdmission != null && binding.executionSelection != null
    val next = when {
        job == null && !canReconcile -> "The saved request has no verified execution binding. Keep its evidence and explicitly select a profile before starting new work."
        job == null -> "Reconcile this saved request using its original route and request identity before considering another submission."
        reason?.contains("UNCERTAIN", true) == true || reason?.contains("UNKNOWN_WRITE", true) == true || reason?.contains("UNKNOWN_OUTCOME", true) == true -> "Inspect the retained operation receipt and reconcile the remote result before retrying a write."
        reason?.contains("PRIVACY", true) == true -> "Review the privacy decision for this retained job. Its saved output remains available."
        reason?.contains("BUDGET", true) == true || reason?.contains("CAPACITY", true) == true -> "Review the exhausted budget or unavailable capacity. The selected model and route remain unchanged."
        reason?.contains("AUTH", true) == true || reason?.contains("CONNECTION", true) == true -> "Repair the original connection, then check this saved job without submitting it again."
        reason?.contains("BINDING", true) == true || reason?.contains("MODEL", true) == true || reason?.contains("PROFILE", true) == true -> "Restore the exact selected profile and model, or explicitly admit new work. This job cannot silently change its execution identity."
        job.mergeReady -> "Review the exact revision and retained checks before approving the merge."
        job.terminal -> "Inspect the saved output and evidence. Transport completion does not establish every requested acceptance test passed."
        job.state == "NEEDS_INPUT" -> "Inspect the exact blocked reason and retained evidence. Only the supported action for that state may continue the job."
        else -> "Check saved progress on the original route; the job keeps its confirmed progress."
    }
    return GatewayRecoveryPresentation(
        stage = job?.state ?: "SUBMISSION_UNCONFIRMED",
        reason = reason,
        profileUid = job?.executionBinding?.string("profile_uid") ?: binding.profileUid,
        requestedModel = job?.executionBinding?.string("requested_model_id") ?: binding.request.model,
        observedModel = job?.latestExecutionReceipt?.string("observed_model_id") ?: "Not measured",
        executionIdentity = identity,
        hardware = hardwareEvidence(job?.latestExecutionReceipt),
        nextAction = next,
        canReconcileRequest = canReconcile,
        canCancel = job != null && !job.terminal
    )
}

private fun hardwareEvidence(receipt: JsonObject?): String {
    val hardware = (receipt?.get("hardware") ?: receipt?.get("observed_hardware")) as? JsonObject ?: return "Not measured"
    if (hardware.string("status") == "NOT_MEASURED") return "Not measured"
    val labels = mapOf("accelerator" to "Accelerator", "offloaded_layers" to "Offloaded layers", "model_file_digest" to "Model file digest", "memory" to "Memory")
    return labels.mapNotNull { (key, label) ->
        val observation = hardware[key] as? JsonObject ?: return@mapNotNull null
        if (observation.string("origin") != "PROVIDER_REPORTED") return@mapNotNull null
        val value = observation["value"] ?: return@mapNotNull null
        val text = if (value is JsonPrimitive) value.content else value.toString()
        text.takeIf { it.isNotBlank() && it != "null" }?.let { "$label: ${it.take(256)} (provider reported)" }
    }.joinToString(" · ").ifEmpty { "Not measured" }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
