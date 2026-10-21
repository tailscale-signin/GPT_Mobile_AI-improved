package dev.chungjungsoo.gptmobile.presentation.ui.setting

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Keep the other collection's position while paging one collection at the same PR revision. */
internal fun retainGitHubPullReviewSections(previous: JsonObject?, incoming: JsonObject): JsonObject {
    fun JsonObject.pull() = get("pull_request") as? JsonObject
    fun JsonObject.sha(side: String) = (pull()?.get(side) as? JsonObject)?.get("sha")
    if (previous == null || previous["expected_head"] != incoming["expected_head"] || previous.pull()?.get("number") != incoming.pull()?.get("number") || previous.sha("base") != incoming.sha("base")) return incoming
    val retained = incoming.toMutableMap()
    if ((incoming["files_included"] as? JsonPrimitive)?.booleanOrNull == false) {
        for (key in listOf("files", "files_returned", "page", "files_truncated", "has_more", "files_continuation")) {
            previous[key]?.let { retained[key] = it }
        }
    }
    if ((incoming["checks_included"] as? JsonPrimitive)?.booleanOrNull == false) {
        for (key in listOf("checks", "checks_continuation")) previous[key]?.let { retained[key] = it }
    }
    return JsonObject(retained)
}
