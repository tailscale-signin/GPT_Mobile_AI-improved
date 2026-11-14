package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Immutable comparisons from the existing selected-profile check, without retaining settings or identifiers. */
internal class GitHubProfileSelectionRejected(
    val profileMatches: Boolean,
    val registeredProfilePresent: Boolean,
    val modelMatches: Boolean?,
    val requestedProfileHash: String,
    val requestedModelHash: String,
    val selectedProfileHash: String,
    val registeredModelHash: String?
) : IllegalStateException(
    when {
        !profileMatches -> "$ERROR_CODE: This request uses a different AI profile from the one selected in GitHub settings. Review the selected profile before resuming."
        !registeredProfilePresent -> "$ERROR_CODE: The requested GitHub profile is not registered. Select an available AI profile in GitHub settings before resuming."
        else -> "$ERROR_CODE: This request's model differs from the model saved for its selected AI profile. Review the profile and model before resuming."
    }
) {
    val errorCode: String = ERROR_CODE

    fun failureFields(): JsonObject = buildJsonObject {
        put("github_selection_profile_match", profileMatches)
        put("github_selection_registered_profile", registeredProfilePresent)
        modelMatches?.let { put("github_selection_model_match", it) }
        put("profile_hash", requestedProfileHash)
        put("model_hash", requestedModelHash)
        put("github_selection_profile_hash", selectedProfileHash)
        registeredModelHash?.let { put("github_selection_registered_model_hash", it) }
    }

    companion object {
        const val ERROR_CODE = "GITHUB_PROFILE_LOCKED"
    }
}
