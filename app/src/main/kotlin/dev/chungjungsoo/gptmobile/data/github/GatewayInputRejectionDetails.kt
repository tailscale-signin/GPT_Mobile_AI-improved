package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Static repair vocabulary prevents remote error text or private field names entering diagnostics. */
internal object GatewayInputRejectionDetails {
    private val reasons = mapOf(
        "ENGINEERING_REQUEST_FIELDS_INVALID" to ("request" to "Unsupported request fields; update or correct the saved request."),
        "ENGINEERING_BUDGET_INVALID" to ("execution_budget" to "The execution budget is invalid; review its supported limits."),
        "ENGINEERING_BRANCH_INVALID" to ("branch" to "The selected branch is invalid; review the branch name."),
        "ENGINEERING_REPOSITORY_INVALID" to ("repository" to "Select a valid owner and repository."),
        "ENGINEERING_REVISION_INVALID" to ("expected_revision" to "Refresh the original conversation revision."),
        "ENGINEERING_REVISION_REQUIRED" to ("revision" to "Refresh the exact saved job before confirming."),
        "ENGINEERING_TASK_SCOPE_INVALID" to ("task_scope" to "Select a supported engineering task scope."),
        "ENGINEERING_TASK_SCOPE_REQUIRED" to ("task_scope" to "Update the app to send explicit implementation authority before starting work from the base branch."),
        "ENGINEERING_SOURCE_REVISION_REQUIRED" to ("source_sha" to "Refresh the selected branch to an exact source revision before starting implementation."),
        "ENGINEERING_SOURCE_REVISION_INVALID" to ("source_sha" to "Refresh the selected source revision; a full commit SHA is required."),
        "ENGINEERING_PROFILE_INVALID" to ("profile_id" to "Recheck the selected profile identity."),
        "ENGINEERING_MEMORY_MODE_INVALID" to ("memory_mode" to "Select a supported memory mode."),
        "ENGINEERING_MODE_INVALID" to ("execution_mode" to "Select a supported execution mode."),
        "ENGINEERING_BATCH_POLICY_INVALID" to ("batch_preferences" to "Review batch preferences and app/gateway policy compatibility."),
        "ENGINEERING_REQUEST_FORMAT_INVALID" to ("request" to "The gateway requires a structured engineering request."),
        "ENGINEERING_REQUEST_TOO_LARGE" to ("request" to "Reduce the request and keep large evidence separately.")
    )

    val reasonCodes: Set<String> = reasons.keys + "ENGINEERING_INPUT_OR_STATE_REJECTED"
    val fieldNames: Set<String> = reasons.values.map { it.first }.toSet() + "batch_policy_version"

    fun retained(error: JsonObject?): JsonObject? =
        error?.takeIf { (it["reasonCode"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content in reasonCodes }?.let(::from)

    fun from(error: JsonObject?): JsonObject {
        val code = (error?.get("reasonCode") as? JsonPrimitive)?.content?.takeIf { it in reasons } ?: "ENGINEERING_INPUT_OR_STATE_REJECTED"
        val allowedFields = if (code == "ENGINEERING_BATCH_POLICY_INVALID") setOf("batch_preferences", "batch_policy_version") else setOfNotNull(reasons[code]?.first)
        val fields = (error?.get("invalid_fields") as? JsonArray).orEmpty().take(16).mapNotNull {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString && value.content in allowedFields }?.content
        }.distinct()
        val stage = (error?.get("failed_stage") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in stages }
            ?: if (code in reasons) "request_validation" else "engineering_guard"
        return buildJsonObject {
            put("reasonCode", code)
            put("invalid_fields", JsonArray(fields.map(::JsonPrimitive)))
            put("failed_stage", stage)
        }
    }

    private val stages = setOf("engineering_guard", "request_validation", "provider_admission", "binding_admission", "message_admission", "conversation_admission", "execution_admission")

    fun stage(details: JsonObject): String = (details["failed_stage"] as? JsonPrimitive)?.content?.takeIf { it in stages } ?: "engineering_guard"

    fun message(details: JsonObject): String = reasons[(details["reasonCode"] as? JsonPrimitive)?.content]?.second
        ?: "The gateway rejected a request or state prerequisite. Retain the draft and inspect its exact settings; a conversation change has not been established."
}

class GatewayEngineeringInputRejected(
    status: Int,
    val diagnosticDetails: JsonObject,
    unknown: Boolean = false
) : GatewayEngineeringRequestRejected(
    status,
    GatewayInputRejectionDetails.message(diagnosticDetails),
    unknown,
    "engineering_state_or_input_changed",
    GatewayInputRejectionDetails.stage(diagnosticDetails)
) {
    override fun withCommandOutcomeUnknown(unknown: Boolean) =
        GatewayEngineeringInputRejected(httpStatus, diagnosticDetails, commandOutcomeUnknown || unknown)
}
