package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemory
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal enum class RepositoryContextAvailability {
    DISABLED,
    WINDOW_TOO_SMALL,
    ACCESS_UNAVAILABLE,
    ACCESS_CHANGED,
    DEADLINE,
    FAILED,
    NO_CACHED_TREE,
    NO_MATCH,
    PATHS_ONLY,
    REFERENCES,
    SOURCE
}

internal data class PreparedRepositoryContext(
    val availability: RepositoryContextAvailability,
    val text: String = "",
    val reusedTree: Boolean? = null,
    val reusedPacket: Boolean = false
) {
    val nextAction: String get() = when (availability) {
        RepositoryContextAvailability.NO_CACHED_TREE, RepositoryContextAvailability.NO_MATCH,
        RepositoryContextAvailability.FAILED, RepositoryContextAvailability.DEADLINE -> "Use the selected repository's authorized reader for a targeted lookup if source is needed."
        else -> ""
    }
}

/** Optional fresh repository reference context; personal/project document recall stays separate. */
@Singleton
class GitHubTaskContextPreparation @Inject constructor(
    private val maps: PhoneRepositoryGraphRepository,
    private val semantic: LocalSemanticMemory,
    private val connections: ToolConnectionRepository
) {
    internal suspend fun prepare(
        context: GitHubConversationContext,
        query: String,
        variant: GitHubContextVariant,
        contextTokens: Int,
        tools: List<ResolvedAgentTool>,
        memoryMode: GitHubEngineeringMemoryMode,
        availableTokens: Int = minOf(4000, contextTokens / 2)
    ): PreparedRepositoryContext {
        if (variant == GitHubContextVariant.CURRENT) return PreparedRepositoryContext(RepositoryContextAvailability.DISABLED)
        if (availableTokens < 256 || contextTokens < 2048 || contextTokens < 8192 && variant == GitHubContextVariant.REFERENCE) return PreparedRepositoryContext(RepositoryContextAvailability.WINDOW_TOO_SMALL)
        if (!GitHubEngineeringMemoryPreparation.eligible(context, tools)) return PreparedRepositoryContext(RepositoryContextAvailability.ACCESS_UNAVAILABLE)
        val connection = connections.getConnection(context.connectionUid) ?: return PreparedRepositoryContext(RepositoryContextAvailability.ACCESS_UNAVAILABLE)
        val compact = contextTokens < 8192
        val ranker = GitHubTaskContextRanking(fallbackReason = "SESSION_ONLY_MEMORY_POLICY".takeIf { memoryMode == GitHubEngineeringMemoryMode.SESSION_ONLY }) { task, records, account ->
            if (memoryMode == GitHubEngineeringMemoryMode.SESSION_ONLY) {
                null
            } else {
                withTimeoutOrNull(750) {
                    semantic.rankEngineering(task, semanticScope(context, connection, account), records)
                }
            }
        }
        val receipt = try {
            withTimeoutOrNull(if (compact) 1500L else 8000L) {
                withContext(ranker) {
                    maps.executeForMap(
                        context,
                        buildJsonObject {
                            if (compact) {
                                put("action", "cached_path_hints")
                                put("query", query.take(2048))
                            } else {
                                put("action", "task_context_bundle")
                                put("query", query.take(6000))
                                put("variant", variant.name)
                                put("budget_bytes", (contextTokens / 2).coerceIn(4096, 12_000))
                                put("preparation_budget_ms", 5000)
                            }
                        },
                        forModel = true
                    ).result
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayGraphOperationException) {
            val availability = when {
                failure.cause is GatewayGraphAdmissionException || failure.repairAction == "REVIEW_PRIVACY_MODE" -> RepositoryContextAvailability.ACCESS_UNAVAILABLE
                failure.failureCode.contains("DEADLINE") -> RepositoryContextAvailability.DEADLINE
                else -> RepositoryContextAvailability.FAILED
            }
            return PreparedRepositoryContext(availability)
        } ?: return PreparedRepositoryContext(RepositoryContextAvailability.DEADLINE)
        if (connections.getConnection(context.connectionUid) != connection) return PreparedRepositoryContext(RepositoryContextAvailability.ACCESS_CHANGED)
        if (!(receipt["source_sha"] as? JsonPrimitive)?.contentOrNull.equals(context.commitSha, ignoreCase = true)) {
            return PreparedRepositoryContext(RepositoryContextAvailability.FAILED)
        }
        val paths = receipt["source_read_candidates"] as? JsonArray
        val reusedTree = (receipt["tree_reused"] as? JsonPrimitive)?.booleanOrNull
        val window = receipt["source_window"] as? JsonObject
        val availability = when {
            window != null || !(receipt["selected_ranges"] as? JsonArray).isNullOrEmpty() -> RepositoryContextAvailability.SOURCE
            !(receipt["documents"] as? JsonArray).isNullOrEmpty() -> RepositoryContextAvailability.REFERENCES
            !paths.isNullOrEmpty() -> RepositoryContextAvailability.PATHS_ONLY
            compact && reusedTree == false -> RepositoryContextAvailability.NO_CACHED_TREE
            else -> RepositoryContextAvailability.NO_MATCH
        }
        if (availability !in setOf(RepositoryContextAvailability.SOURCE, RepositoryContextAvailability.REFERENCES, RepositoryContextAvailability.PATHS_ONLY)) {
            return PreparedRepositoryContext(availability, reusedTree = reusedTree, reusedPacket = receipt["cache_status"] == JsonPrimitive("PINNED_PACKET_REUSED"))
        }
        val projection = GitHubTaskContextProjection.render(context.repository.fullName, context.ref, context.commitSha, receipt, availableTokens)
        val admittedAvailability = when {
            projection.source -> RepositoryContextAvailability.SOURCE
            projection.documents -> RepositoryContextAvailability.REFERENCES
            projection.candidates -> RepositoryContextAvailability.PATHS_ONLY
            else -> RepositoryContextAvailability.WINDOW_TOO_SMALL
        }
        return PreparedRepositoryContext(admittedAvailability, projection.text, reusedTree, receipt["cache_status"] == JsonPrimitive("PINNED_PACKET_REUSED"))
    }

    companion object {
        /** Only immutable repository source records use this namespace. The encoder keys each blob/range separately. */
        internal fun semanticScope(context: GitHubConversationContext, connection: ToolConnection, accountIdentity: String): String {
            require(accountIdentity.isNotBlank() && connection.connectionUid == context.connectionUid)
            val scope = buildJsonObject {
                put("account", accountIdentity)
                put("connection", connection.connectionUid)
                put("connection_revision", connection.updatedAt)
                put("permission", GitHubAuthorizationIdentity.permission(connection.authType, connection.toolPolicy, connection.approvedReadTools, connection.endpointUrl))
                put("repository", context.repository.fullName.lowercase())
                put("parser", PhoneRepositoryGraph.PARSER_VERSION)
                put("bundle", GitHubTaskContextBundle.VERSION)
            }
            return "github:source-ranges:" + GitHubRepositoryStateStore.digest(scope.toString())
        }
    }
}
