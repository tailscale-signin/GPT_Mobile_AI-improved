package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Restore destinations by stable conversation identity, never by coincidental numeric ID. */
internal object GitHubBackupPreferences {
    private const val KEY = ConversationCombinedPortable.FEATURE_KEY
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun restore(
        next: Map<String, BackupValue>,
        current: Map<String, BackupValue>,
        incoming: Map<String, BackupValue>,
        snapshot: ConversationCombinedPortableSnapshot?,
        restoringChats: Boolean,
        restoreGlobal: Boolean,
        existingChatIds: Set<Int>,
        connections: Set<String>,
        profiles: Set<String>
    ): Map<String, BackupValue> {
        fun root(values: Map<String, BackupValue>) = values[KEY]?.value?.let { json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())
        val base = root(next)
        val old = root(current)
        val saved = root(incoming)
        val identities = (base["conversationIdentities"] as? JsonObject).orEmpty()
        val targetByIdentity = identities.entries.filter { it.key.toIntOrNull() in existingChatIds }
            .groupBy { (it.value as? JsonPrimitive)?.contentOrNull }.filterValues { it.size == 1 }
            .mapValues { it.value.single().key }
        val sourceIdentity = snapshot?.conversations.orEmpty().associate { it.sourceChatId.toString() to it.identity }
        val workspaces = if (restoringChats) mutableMapOf() else (old["conversationGitHub"] as? JsonObject).orEmpty().filterKeys { it.toIntOrNull() in existingChatIds }.toMutableMap()
        (saved["conversationGitHub"] as? JsonObject).orEmpty().forEach { (source, value) ->
            val target = if (restoringChats && source.toIntOrNull() in existingChatIds) source else sourceIdentity[source]?.let(targetByIdentity::get)
            if (target == null) return@forEach
            val context = runCatching { json.decodeFromJsonElement(GitHubConversationContext.serializer(), value) }.getOrNull() ?: return@forEach
            if (context.connectionUid !in connections || context.mainProfileUid !in profiles) return@forEach
            workspaces[target] = json.encodeToJsonElement(GitHubConversationContext.serializer(), context.copy(autoApproveTools = false, stagedFileCount = null, recentCommits = emptyList(), observedAtMillis = null))
        }
        val globals = if (restoreGlobal) saved else old
        val jobs = mutableMapOf<String, GatewayEngineeringBinding>()
        fun restoreJobs(root: JsonObject, remap: Boolean) {
            (root["gatewayEngineeringJobs"] as? JsonArray).orEmpty().forEach { value ->
                val binding = runCatching { json.decodeFromJsonElement(GatewayEngineeringBinding.serializer(), value) }.getOrNull() ?: return@forEach
                val source = binding.chatId.toString()
                val target = if (!remap || restoringChats) binding.chatId.takeIf { it in existingChatIds } else sourceIdentity[source]?.let(targetByIdentity::get)?.toIntOrNull()
                val context = target?.let { workspaces[it.toString()] }?.let { runCatching { json.decodeFromJsonElement(GitHubConversationContext.serializer(), it) }.getOrNull() }
                if (target == null || binding.profileUid !in profiles || binding.principalId == null || context == null || "${context.owner}/${context.repo}" != binding.request.repository) return@forEach
                jobs[binding.request.requestId] = binding.copy(localChatId = target, job = binding.job?.copy(mergeReady = false, approvalExpiresAt = null))
            }
        }
        if (!restoringChats) restoreJobs(old, false)
        restoreJobs(saved, true)
        val mainProfiles = (globals["githubMainProfiles"] as? JsonObject).orEmpty().filter { (connection, profile) -> connection in connections && (profile as? JsonPrimitive)?.contentOrNull in profiles }
        val prepareIndex = (globals["githubPrepareIndex"] as? JsonArray).orEmpty().filter { (it as? JsonPrimitive)?.contentOrNull in connections }
        val overrides = (globals["githubProfileOverrides"] as? JsonObject).orEmpty().filterKeys { it in profiles }
        val defaultProfile = globals["githubDefaultProfileUid"]?.takeIf { (it as? JsonPrimitive)?.contentOrNull in profiles } ?: kotlinx.serialization.json.JsonNull
        val updated = JsonObject(base + mapOf("gatewayEngineeringJobs" to JsonArray(jobs.values.take(64).map { json.encodeToJsonElement(GatewayEngineeringBinding.serializer(), it) }), "githubProfileOverrides" to JsonObject(overrides), "githubDefaultProfileUid" to defaultProfile, "conversationGitHub" to JsonObject(workspaces), "githubMainProfiles" to JsonObject(mainProfiles), "githubPrepareIndex" to JsonArray(prepareIndex)))
        return next + (KEY to BackupValue("string", updated.toString()))
    }
}
