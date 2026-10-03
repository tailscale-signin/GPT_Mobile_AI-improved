package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Reserve the reviewer before choosing workers, including automatic selection. */
internal fun reservedReviewer(config: ModelDelegationSettings, profiles: List<PlatformV2>, source: PlatformV2): PlatformV2? {
    if (!config.reviewerEnabled) return null
    if (config.reviewerProfileUid.isNotBlank()) return profiles.firstOrNull { it.uid == config.reviewerProfileUid }
    val worker = profiles.firstOrNull { it.uid == config.targetProfileUid }
        ?: profiles.firstOrNull { reviewerEligible(it, config, source) }
    return profiles.firstOrNull { reviewerEligible(it, config, source) && !sameDelegationModel(it, worker) }
}

internal fun reviewerEligible(candidate: PlatformV2, config: ModelDelegationSettings, source: PlatformV2): Boolean =
    candidate.enabled &&
        candidate.uid != source.uid &&
        candidate.model.isNotBlank() &&
        !candidate.excludesMemory() &&
        (config.remoteWorkersAllowed() || candidate.isPrivateDestination()) &&
        !(source.compatibleType == ClientType.LITERT_LM && candidate.compatibleType == ClientType.LITERT_LM)

internal fun sameDelegationModel(candidate: PlatformV2, reserved: PlatformV2?): Boolean = reserved != null &&
    (candidate.uid == reserved.uid || candidate.model.isNotBlank() && candidate.model.trim().equals(reserved.model.trim(), ignoreCase = true))

internal data class ReviewerAssessment(val score: Int, val verdict: String, val issues: List<String>, val corrections: String?)

/** Accept a single JSON object, optionally fenced. Prose, missing fields and coerced scores are invalid. */
internal fun parseReviewerAssessment(raw: String): ReviewerAssessment? {
    return runCatching {
        var text = raw.trim()
        if (text.substringBefore('\n').trim().lowercase() in setOf("```json", "```")) {
            if (!text.endsWith("```")) return null
            text = text.substringAfter('\n').removeSuffix("```").trim()
        }
        val obj = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val scoreValue = obj["review_score"] as? JsonPrimitive ?: return null
        val score = scoreValue.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..100 } ?: return null
        val verdict = (obj["verdict"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it in setOf("PASS", "CORRECTED", "REJECT") } ?: return null
        val issues = (obj["issues"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: return null
        } ?: return null
        if (issues.size > 10 || issues.any { it.length > 1000 }) return null
        val correction = obj["corrections"] as? JsonPrimitive ?: return null
        val corrections = if (correction == kotlinx.serialization.json.JsonNull) null else correction.takeIf { it.isString }?.content ?: return null
        if (verdict == "CORRECTED" && corrections.isNullOrBlank()) return null
        if (corrections != null && corrections.length > 12000) return null
        ReviewerAssessment(score, verdict, issues, corrections)
    }.getOrNull()
}
