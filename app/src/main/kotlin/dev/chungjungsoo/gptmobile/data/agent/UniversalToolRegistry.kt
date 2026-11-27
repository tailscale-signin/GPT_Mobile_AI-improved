package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One persistent host inventory over the existing native, Gateway and MCP resolvers.
 * Saved declarations accelerate local preparation; only freshly resolved executors authorize calls.
 */
@Singleton
class UniversalToolRegistry @Inject constructor(private val store: GitHubRepositoryStateStore) {
    internal data class Snapshot(val digest: String, val toolCount: Int, val reusedSchemas: Int, val persisted: Boolean)

    /** Startup restores local schema costs only, never cached grants, executors or provider sessions. */
    internal suspend fun warm(scope: String) {
        val saved = read(GitHubRepositoryStateStore.digest("tool-registry.v1:$scope")) ?: return
        val tools = saved["tools"] as? JsonArray ?: return
        for (value in tools) {
            val descriptor = value as? JsonObject ?: continue
            val key = (descriptor["wire_digest"] as? JsonPrimitive)?.contentOrNull ?: continue
            val tokens = (descriptor["schema_tokens"] as? JsonPrimitive)?.intOrNull ?: continue
            if (key.matches(Regex("[a-f0-9]{64}")) && tokens in 0..4_000_000) rememberCost(key, tokens)
        }
    }

    internal suspend fun prepare(
        scope: String,
        authority: String,
        definitions: List<AgentToolDefinition>,
        sourceIds: Map<String, String?>,
        persistent: Boolean,
        now: Long = System.currentTimeMillis()
    ): Snapshot = withContext(Dispatchers.Default) {
        val visible = definitions.filter { it.modelVisible }.sortedBy { it.name }
        require(visible.map { it.name }.distinct().size == visible.size) { "Ambiguous tool registry identity" }
        val identity = GitHubRepositoryStateStore.digest("tool-registry.v1:$scope")
        // Authority is opaque, scoped to this profile/model/configuration, and contains no credential bytes.
        val previous = if (persistent) read(identity) else null
        val retained = previous?.takeIf {
            it["authority"] == JsonPrimitive(authority) &&
                (it["observed_at"] as? JsonPrimitive)?.longOrNull?.let { at -> now - at in 0..MAX_AGE_MILLIS } == true
        }?.get("tools") as? JsonArray
        val old = retained.orEmpty().mapNotNull { it as? JsonObject }.associateBy { (it["name"] as? JsonPrimitive)?.contentOrNull }
        var reused = 0
        val entries = visible.map { definition ->
            val key = wireIdentity(definition)
            val saved = old[definition.name]?.takeIf { it["wire_digest"] == JsonPrimitive(key) }
            val cost = (saved?.get("schema_tokens") as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..4_000_000 }
            if (cost != null) {
                rememberCost(key, cost)
                reused++
            }
            buildJsonObject {
                put("id", GitHubRepositoryStateStore.digest("${sourceIds[definition.name].orEmpty()}:${definition.name}"))
                put("name", definition.name)
                put("description", definition.description)
                put("schema_digest", ToolContractFingerprint.definition(definition))
                put("schema_version", "${definition.contractVersion.major}.${definition.contractVersion.minor}")
                put("wire_digest", key)
                put("schema_tokens", cost ?: schemaTokens(definition))
                put("input_schema", definition.inputSchema)
                put("contract_schema", definition.contractSchema)
                put("capabilities", JsonArray(definition.capabilities.map { it.name }.sorted().map(::JsonPrimitive)))
                put("category", definition.discoveryPresetId ?: definition.capabilities.firstOrNull()?.name ?: "utility")
                put("dependencies", JsonArray(definition.dependsOn.sorted().map(::JsonPrimitive)))
                put("provider_id", sourceIds[definition.name] ?: "native")
                put("execution_location", if (sourceIds[definition.name] == null) "native" else "connection")
                put("enabled_at_observation", true)
                put("authorized_at_observation", true)
                put("availability", "RECHECK_AT_DISPATCH")
                put("model_compatibility", "RECHECK_AT_PROVIDER_ADMISSION")
                put("permissions", "CURRENT_RESOLVER_AND_EXECUTOR_POLICY")
                put("retry_safety", "EXECUTOR_POLICY_REQUIRED")
            }
        }
        val digest = GitHubRepositoryStateStore.digest(JsonArray(entries).toString())
        var persisted = previous?.get("digest") == JsonPrimitive(digest) && previous?.get("authority") == JsonPrimitive(authority) && retained != null
        if (persistent && !persisted) {
            val snapshot = buildJsonObject {
                put("schema", 1)
                put("authority", authority)
                put("digest", digest)
                put("observed_at", now)
                put("tools", JsonArray(entries))
                put("execution_authority", false)
            }
            try {
                store.write(identity, "universal-tool-registry", snapshot)
                persisted = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A full/corrupt cache cannot disable the fresh, authorized inventory.
            }
        }
        Snapshot(digest, visible.size, reused, persisted)
    }

    private suspend fun read(identity: String): JsonObject? = try {
        store.read(identity, "universal-tool-registry")?.takeIf {
            it["schema"] == JsonPrimitive(1) &&
                it["tools"] is JsonArray &&
                it["digest"] == JsonPrimitive(GitHubRepositoryStateStore.digest(it.getValue("tools").toString()))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_AGE_MILLIS = 24 * 60 * 60_000L
        private const val MAX_COSTS = 2048
        private val costs = LinkedHashMap<String, Int>(64, 0.75f, true)

        private fun wireIdentity(definition: AgentToolDefinition): String = GitHubRepositoryStateStore.digest(
            buildJsonObject {
                put("name", definition.name)
                put("description", definition.description)
                put("schema", definition.inputSchema)
            }.toString()
        )

        @Synchronized private fun rememberCost(key: String, tokens: Int) {
            costs[key] = tokens
            while (costs.size > MAX_COSTS) costs.remove(costs.keys.first())
        }

        /** Exact wire bytes are the key; a projection or description edit invalidates its estimate. */
        @Synchronized internal fun schemaTokens(definition: AgentToolDefinition): Int {
            val key = wireIdentity(definition)
            return costs[key] ?: ContextBudgetService.toolSchemaTokens(definition).also { rememberCost(key, it) }
        }
    }
}
