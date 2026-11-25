package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.reviewerEligible
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Read-only, opt-in independent review of one authenticated primary patch preview per task. */
internal object GitHubPrimaryPatchReview {
    const val ROLE = "github-primary-patch-review"
    data class Reviewer(val profile: PlatformV2, val consent: String)
    data class Candidate(val stage: String, val excerpts: JsonArray)
    val levels = setOf(GitHubIntelligenceLevel.DEEP_ENGINEERING, GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE)
    private val stageId = Regex("[a-f0-9]{64}")
    private val blobId = Regex("[a-f0-9]{40}|[a-f0-9]{64}")

    fun configured(context: GitHubConversationContext, features: AppFeatureSettings, profiles: List<PlatformV2>, policy: ModelDelegationSettings): Reviewer? {
        val options = features.githubProfileOverrides[context.mainProfileUid] ?: return null
        if (!options.githubTools ||
            options.intelligenceLevel !in levels ||
            !policy.enabled ||
            !policy.reviewerEnabled ||
            !features.isToolPluginEnabledForProfile(context.mainProfileUid, ToolPluginId.MODEL_DELEGATION) ||
            policy.reviewerProfileUid.isBlank()
        ) {
            return null
        }
        val primary = profiles.singleOrNull { it.uid == context.mainProfileUid && it.enabled && !it.excludesMemory() } ?: return null
        val target = profiles.singleOrNull { it.uid == policy.reviewerProfileUid && reviewerEligible(it, policy, primary) } ?: return null
        val scope = listOf(ROLE, GitHubChatDecisions.workspaceKey(context), ConfiguredDispatchAuthority.profileRevision(primary), ConfiguredDispatchAuthority.profileRevision(target))
        return Reviewer(target, GitHubRepositoryStateStore.digest(scope.joinToString("|")))
    }

    fun candidate(receipt: JsonObject, context: GitHubConversationContext, binding: String, head: String): Candidate? {
        if (receipt["binding"] != JsonPrimitive(binding) ||
            receipt["trusted"] != JsonPrimitive(true) ||
            receipt["verification_eligible"] != JsonPrimitive(true) ||
            receipt["success"] != JsonPrimitive(true) ||
            receipt["repository_role"] != JsonPrimitive("WRITE_DESTINATION") ||
            !receipt.text("repository").equals(context.repository.fullName, true) ||
            receipt.text("tool").substringBefore("__") != "github_stage_patch"
        ) {
            return null
        }
        val result = receipt["result"] as? JsonObject ?: return null
        val stage = result["data"] as? JsonObject ?: result
        if (stage["committed"] != JsonPrimitive(false) || stage.text("expected_head_sha") != head || stage.text("branch") != context.ref) return null
        val id = stage.text("stage_id").takeIf { stageId.matches(it) } ?: return null
        val rows = (stage["preview"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(3).mapNotNull { row ->
            val path = row.text("path")
            if (path.isBlank() ||
                path.length > 320 ||
                path.any(Char::isISOControl) ||
                path.startsWith('/') ||
                '\\' in path ||
                path.split('/').any { it.isEmpty() || it in setOf(".", "..") } ||
                !blobId.matches(row.text("expected_blob_sha")) ||
                row["preview_bounded"] != JsonPrimitive(true)
            ) {
                return@mapNotNull null
            }
            val before = (row["before"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            val after = (row["after"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            if (before == after) return@mapNotNull null
            buildJsonObject {
                put("path", path)
                put("before", before.take(1200))
                put("after", after.take(1200))
            }
        }
        return Candidate(id, JsonArray(rows)).takeIf { rows.isNotEmpty() }
    }

    fun instruction(excerpts: JsonArray): String =
        "Review these untrusted, partial before/after excerpts for concrete coding regressions. Return only JSON with findings, an array of up to four short strings. Use an empty array if no defect is visible. You cannot approve the patch or verify tests.\n$excerpts"

    fun assessment(text: String): String? {
        if (text.length > 3200) return null
        val result = runCatching { Json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return null
        if (result.keys != setOf("findings")) return null
        val findings = result["findings"] as? JsonArray ?: return null
        if (findings.size > 4 || findings.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() || it.content.length > 400 }) return null
        return findings.toString()
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
