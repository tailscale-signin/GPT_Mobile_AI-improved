package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Closed differences explain a refused recovery action without exposing task, profile or evidence values. */
internal object ResearchRecoveryEvidence {
    const val FEATURE = "RESEARCH_RECOVERY_EVIDENCE_V1"
    const val CHANGED_FIELDS = "research_recovery_changed_fields"
    const val RESOLUTION = "research_recovery_resolution"
    const val SAVED_ACTION_PRESERVED = "research_recovery_saved_action_preserved"
    const val REVIEW_AND_START_NEW_ACTION = "REVIEW_CURRENT_SETTINGS_AND_START_NEW_ACTION"
    val fields = setOf(CHANGED_FIELDS, RESOLUTION, SAVED_ACTION_PRESERVED)
    val changes = linkedMapOf(
        "TASK_ID" to "accepted task",
        "ROOT_WORK_ID" to "saved task scope",
        "ACTION_ID" to "recovery action",
        "REQUESTED_STAGE" to "requested stage",
        "DESTINATION_PROFILE" to "destination profile",
        "POLICY_REVISION" to "authorized settings",
        "CHECKPOINT_ID" to "displayed checkpoint",
        "EVIDENCE_REVISION" to "available evidence revision"
    )

    fun changed(previous: ResearchResumeRequest, requested: ResearchResumeRequest, policyMigration: Boolean): JsonObject = buildJsonObject {
        val observed = buildList {
            if (previous.taskId != requested.taskId) add("TASK_ID")
            if (previous.rootWorkId != requested.rootWorkId) add("ROOT_WORK_ID")
            if (previous.actionId != requested.actionId) add("ACTION_ID")
            if (previous.requestedStage != requested.requestedStage) add("REQUESTED_STAGE")
            if (previous.destinationProfileUid != requested.destinationProfileUid) add("DESTINATION_PROFILE")
            if (previous.policyRevision != requested.policyRevision && !policyMigration) add("POLICY_REVISION")
            if (previous.checkpointId != requested.checkpointId) add("CHECKPOINT_ID")
            if (previous.evidenceRevision != requested.evidenceRevision) add("EVIDENCE_REVISION")
        }
        if (observed.isNotEmpty()) put(CHANGED_FIELDS, JsonArray(observed.map(::JsonPrimitive)))
        put(RESOLUTION, REVIEW_AND_START_NEW_ACTION)
        put(SAVED_ACTION_PRESERVED, true)
    }

    fun validValue(key: String, value: JsonElement): JsonElement? = when (key) {
        CHANGED_FIELDS -> (value as? JsonArray)?.takeIf { items ->
            items.size in 1..changes.size && items.distinct().size == items.size && items.all { it is JsonPrimitive && it.isString && it.content in changes }
        }
        RESOLUTION -> value.takeIf { it == JsonPrimitive(REVIEW_AND_START_NEW_ACTION) }
        SAVED_ACTION_PRESERVED -> value.takeIf { it == JsonPrimitive(true) }
        else -> null
    }

    fun retained(details: JsonObject): JsonObject = buildJsonObject {
        fields.forEach { key -> details[key]?.let { validValue(key, it) }?.let { put(key, it) } }
    }

    fun message(details: JsonObject): String {
        val changed = (details[CHANGED_FIELDS]?.let { validValue(CHANGED_FIELDS, it) } as? JsonArray).orEmpty()
        val blocking = changed.map { (it as JsonPrimitive).content }.filterNot { it in setOf("CHECKPOINT_ID", "EVIDENCE_REVISION") }
        val reason = blocking.mapNotNull(changes::get).joinToString(", ").takeIf { it.isNotEmpty() } ?: "the accepted recovery settings"
        return "The saved research action cannot resume because $reason changed. Review the current settings and choose a new recovery action. The original action, checkpoint and completed evidence remain retained."
    }
}
