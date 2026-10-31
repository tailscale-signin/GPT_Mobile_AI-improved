package dev.chungjungsoo.gptmobile.data.backup

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
        val mainProfiles = (globals["githubMainProfiles"] as? JsonObject).orEmpty().filter { (connection, profile) -> connection in connections && (profile as? JsonPrimitive)?.contentOrNull in profiles }
        val prepareIndex = (globals["githubPrepareIndex"] as? JsonArray).orEmpty().filter { (it as? JsonPrimitive)?.contentOrNull in connections }
        val updated = JsonObject(base + mapOf("conversationGitHub" to JsonObject(workspaces), "githubMainProfiles" to JsonObject(mainProfiles), "githubPrepareIndex" to JsonArray(prepareIndex)))
        return next + (KEY to BackupValue("string", updated.toString()))
    }
}
