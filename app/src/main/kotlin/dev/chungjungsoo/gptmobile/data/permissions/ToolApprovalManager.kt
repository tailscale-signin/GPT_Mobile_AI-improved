package dev.chungjungsoo.gptmobile.data.permissions

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

enum class ToolPolicy { READ_ONLY, ASK_WRITES, TRUSTED }

@Entity(tableName = "tool_approvals", indices = [Index("runId"), Index("state")])
data class ToolApproval(
    @PrimaryKey val id: String,
    val runId: String,
    val connection: String,
    val tool: String,
    val argumentHash: String,
    val argumentPreview: String = "",
    val state: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ToolApprovalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(value: ToolApproval): Long

    @Query("SELECT * FROM tool_approvals WHERE state = 'PENDING' ORDER BY createdAt")
    fun pending(): Flow<List<ToolApproval>>

    @Query("SELECT * FROM tool_approvals WHERE state IN ('PENDING', 'OUTCOME_UNKNOWN', 'INTERRUPTED') ORDER BY createdAt DESC LIMIT 100")
    fun attention(): Flow<List<ToolApproval>>

    @Query("SELECT * FROM tool_approvals WHERE id = :id")
    fun observe(id: String): Flow<ToolApproval?>

    @Query("UPDATE tool_approvals SET state = :state WHERE id = :id AND state = 'PENDING'")
    suspend fun decide(id: String, state: String)

    @Query("UPDATE tool_approvals SET state = 'EXECUTING' WHERE id = :id AND state = 'APPROVED'")
    suspend fun claim(id: String): Int

    @Query("SELECT a.state FROM tool_approvals a JOIN agent_runs previous ON previous.run_id = a.runId JOIN agent_runs target_run ON target_run.run_id = :runId WHERE previous.chat_id = target_run.chat_id AND previous.user_message_id = target_run.user_message_id AND a.connection = :connection AND a.tool = :tool AND a.argumentHash = :hash AND a.id != :excludeId AND a.state IN ('PENDING', 'APPROVED', 'EXECUTING', 'COMPLETED', 'OUTCOME_UNKNOWN', 'INTERRUPTED') LIMIT 1")
    suspend fun previousMatchingAction(runId: String, connection: String, tool: String, hash: String, excludeId: String = ""): String?

    @Query("UPDATE tool_approvals SET state = :state WHERE id = :id AND state = 'EXECUTING'")
    suspend fun finish(id: String, state: String)

    @Query("UPDATE tool_approvals SET state = 'INTERRUPTED' WHERE state IN ('PENDING', 'APPROVED', 'EXECUTING')")
    suspend fun interrupt()

    @Query("DELETE FROM tool_approvals WHERE state NOT IN ('PENDING', 'APPROVED', 'EXECUTING') AND createdAt < :cutoff AND runId NOT IN (SELECT run_id FROM agent_runs)")
    suspend fun prune(cutoff: Long)
}

/** User-owned permissions. Server readOnly annotations never grant authority. */
@Singleton
class ToolApprovalManager @Inject constructor(private val database: ChatDatabaseV2, private val connections: ToolConnectionRepository, private val trust: ToolTrustStore? = null, private val nativeMarketplace: dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry? = null, private val settings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository? = null) {
    private val dao = database.toolApprovalDao()
    private val submissionMutex = Mutex()
    private val requestConnections = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val requestScopes = java.util.concurrent.ConcurrentHashMap<String, ScopedToolGrant>()
    val pending = dao.pending()
    suspend fun recover() {
        dao.interrupt()
        dao.prune(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    }
    suspend fun decide(id: String, allow: Boolean) = dao.decide(id, if (allow) "APPROVED" else "DENIED")
    suspend fun allowInConversation(id: String) {
        val request = dao.observe(id).first() ?: return
        if (request.state != "PENDING") return
        if (request.requiresExactConfirmation) return
        val connection = requestConnections[id]?.let { connections.getConnection(it) } ?: return
        val scope = requestScopes[id] ?: return
        requireNotNull(trust).allowScoped(connection, request.tool, scope)
        dao.decide(id, "APPROVED")
    }
    suspend fun alwaysAllowProvider(id: String) {
        val request = dao.observe(id).first() ?: return
        if (request.state != "PENDING") return
        if (request.requiresExactConfirmation) return
        val connection = requestConnections[id]?.let { connections.getConnection(it) } ?: return
        requireNotNull(trust) { "Persistent tool permissions are unavailable." }.allowProvider(connection)
        approveWaiting(connection.connectionUid, id)
    }
    suspend fun alwaysAllow(id: String) {
        val request = dao.observe(id).first() ?: return
        if (request.state != "PENDING") return
        if (request.requiresExactConfirmation) return
        val connection = requestConnections[id]?.let { connections.getConnection(it) } ?: return
        requireNotNull(trust) { "Persistent tool permissions are unavailable." }.allow(connection, request.tool)
        approveWaiting(connection.connectionUid, id, request.tool)
    }
    private suspend fun approveWaiting(connectionUid: String, approvedId: String, tool: String? = null) {
        // All models share these grants. Release prompts already queued by parallel runs.
        submissionMutex.withLock {
            dao.decide(approvedId, "APPROVED")
            dao.pending().first().filter { request ->
                !request.requiresExactConfirmation && requestConnections[request.id] == connectionUid && (tool == null || request.tool == tool)
            }.forEach { request ->
                val previous = if (request.isReplayableGitHubRead) null else dao.previousMatchingAction(request.runId, request.connection, request.tool, request.argumentHash, request.id)
                dao.decide(request.id, if (previous == null) "APPROVED" else "DENIED")
            }
        }
    }
    suspend fun finish(runId: String, callId: String, success: Boolean) {
        val id = "$runId:$callId"
        val replayableRead = dao.observe(id).first()?.isReplayableGitHubRead == true
        dao.finish(
            id,
            if (success) {
                "COMPLETED"
            } else if (replayableRead) {
                "READ_FAILED"
            } else {
                "OUTCOME_UNKNOWN"
            }
        )
    }
    suspend fun authorizeOrThrow(connectionId: String, runId: String, callId: String, tool: String, arguments: JsonObject, schema: JsonObject? = null): Boolean {
        if (dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool) != null && connections.getConnection(connectionId)?.type == "GITHUB") {
            val native = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.nativeArguments(tool, arguments) ?: return false
            return authorizeOrThrow(connectionId, runId, callId, "github", native, schema)
        }
        if (authorize(connectionId, runId, callId, tool, arguments, schema)) return true
        val connection = connections.getConnection(connectionId)
        val current = dao.observe("$runId:$callId").first()
        val duplicate = current?.state in setOf("EXECUTING", "COMPLETED", "OUTCOME_UNKNOWN", "INTERRUPTED") ||
            (
                !(connection?.type == "GITHUB" && GitHubAuthorizationBoundary.isReplayableRead(tool, arguments)) &&
                    connection?.let { dao.previousMatchingAction(runId, it.name, tool, ScopedToolGrant.canonicalHash(arguments), "$runId:$callId") } != null
                )
        if (duplicate) throw ToolAuthorizationRejected("DUPLICATE_ACTION", "A matching action is already pending, dispatched or has an uncertain outcome. Inspect its saved receipt before repeating it; changing permission will not repeat it.")
        throw ToolAuthorizationRejected("AUTH_REQUIRED", "Tool permission was denied or is no longer available. Review this tool's source and destination grants before retrying.")
    }

    suspend fun authorize(connectionId: String, runId: String, callId: String, tool: String, arguments: JsonObject, schema: JsonObject? = null): Boolean {
        if (dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool) != null && connections.getConnection(connectionId)?.type == "GITHUB") {
            val native = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.nativeArguments(tool, arguments) ?: return false
            return authorize(connectionId, runId, callId, "github", native, schema)
        }
        val nativeEntry = dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.find(connectionId)
        if (nativeEntry != null && dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.supports(nativeEntry)) {
            // Explicitly enabled, compiled-in adapters expose only this reviewed read-only allowlist.
            if (tool !in dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.operations[nativeEntry.provider].orEmpty()) return false
            return try {
                nativeMarketplace?.configuration(nativeEntry) ?: return false
                dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.validate(nativeEntry.provider, tool, arguments)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        val connection = connections.getConnection(connectionId) ?: return false
        val github = GitHubAuthorizationBoundary.applies(connection)
        val workspace = if (connection.type == "GITHUB" && settings != null) {
            database.agentRunDao().getById(runId)?.let { run -> settings.getFeatureSettings().conversationGitHub[run.chatId] }
        } else {
            null
        }
        val workspaceApproved = workspace?.let { selected ->
            selected.autoApproveTools &&
                selected.connectionUid == connectionId &&
                listOf("owner" to selected.owner, "repo" to selected.repo).all { (key, expected) ->
                    (arguments[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.equals(expected, true) != false
                }
        } == true
        if (github && GitHubAuthorizationBoundary.requiresElevatedAuthorization(tool, arguments)) {
            throw ToolAuthorizationRejected("ELEVATED_AUTH_REQUIRED", "This GitHub administrative operation requires a separate elevated authorization and explicit confirmation. Ordinary repository permissions and remembered tool grants cannot authorize it. No request was sent.")
        }
        if (connection.type !in setOf("MCP", "AMAZON_SERPAPI") && tool != "github") return connection.type != "GITHUB"
        val policy = runCatching { ToolPolicy.valueOf(connection.toolPolicy) }.getOrDefault(ToolPolicy.ASK_WRITES)
        val githubRead = tool != "github" ||
            GitHubAuthorizationBoundary.isReplayableRead(tool, arguments) ||
            (arguments["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "stage_patch"
        val replayableRead = connection.type == "GITHUB" && GitHubAuthorizationBoundary.isReplayableRead(tool, arguments)
        val branchSelection = github && GitHubAuthorizationBoundary.isBranchSelection(tool, arguments)
        val readOnly = githubRead && tool in connection.approvedReadTools.lines().map(String::trim)
        if (!workspaceApproved && policy == ToolPolicy.READ_ONLY && !readOnly) return false
        if (readOnly && !branchSelection) return true
        val exactApproval = github && !workspaceApproved && (branchSelection || !(if (tool == "github") githubRead else readOnly))
        val scope = schema?.let { definition -> database.agentRunDao().getById(runId)?.let { ScopedToolGrant.from(it.chatId, definition, arguments) } }
        val id = "$runId:$callId"
        scope?.let { requestScopes[id] = it }
        requestConnections[id] = connectionId
        val hash = ScopedToolGrant.canonicalHash(arguments)
        val inserted = submissionMutex.withLock {
            val previous = if (replayableRead) null else dao.previousMatchingAction(runId, connection.name, tool, hash)
            val trusted = !exactApproval && (workspaceApproved || policy == ToolPolicy.TRUSTED || trust?.allows(connection, tool) == true)
            // Permanent permission must never prompt again. Keep the duplicate-action
            // guard: already-dispatched writes in this turn are rejected, not repeated.
            if ((trusted || exactApproval) && previous != null) return@withLock -1L
            val request = ToolApproval(
                id,
                runId,
                connection.name,
                tool,
                hash,
                argumentPreview = (
                    if (exactApproval) {
                        GitHubAuthorizationBoundary.EXACT_APPROVAL_PREFIX
                    } else if (replayableRead) {
                        GitHubAuthorizationBoundary.READ_APPROVAL_PREFIX
                    } else {
                        ""
                    }
                    ) + (scope?.let { "Conversation #${it.chatId}" + it.resource.takeIf(String::isNotBlank)?.let { resource -> " · $resource" }.orEmpty() + "\n" }.orEmpty()) + (previous?.let { "A matching action in this turn has status $it. Check its effect before approving a repeat.\n" }.orEmpty()) +
                    dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.arguments(arguments),
                state = if (!exactApproval && (workspaceApproved || policy == ToolPolicy.TRUSTED || trust?.allows(connection, tool) == true || scope?.let { trust?.allowsScoped(connection, tool, it) } == true) && previous == null) "APPROVED" else "PENDING"
            )
            dao.insert(request)
        }
        if (inserted == -1L) {
            requestConnections.remove(id)
            requestScopes.remove(id)
            return false
        }
        return try {
            val decision = dao.observe(id).first { it == null || it.state != "PENDING" }
            decision?.state == "APPROVED" && dao.claim(id) == 1
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.decide(id, "CANCELED") }
            throw cancelled
        } finally {
            requestConnections.remove(id)
            requestScopes.remove(id)
        }
    }
}
