package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.github.GitHubAuthorizationIdentity
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject

/** Uses the explicitly selected saved GitHub account; inference remains in the common profile executor. */
@Singleton
class BenchmarkPhoneGitHubExecutor @Inject constructor(
    private val connections: ToolConnectionRepository,
    private val auth: GitHubPublicAuthCoordinator,
    private val state: GitHubRepositoryStateStore
) {
    private val admissionLocks = ConcurrentHashMap<String, Mutex>()

    internal suspend fun open(profileUid: String, plan: BenchmarkEngineeringPlan): BenchmarkEngineeringTransport {
        require(plan.valid() && profileUid in plan.profileUids && plan.lane == BenchmarkEngineeringLane.LIVE_GITHUB && plan.externalTransport == BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS) { "PHONE_BENCHMARK_PLAN_INVALID" }
        val selected = requireNotNull(plan.githubConnectionUid) { "PHONE_BENCHMARK_CONNECTION_REQUIRED" }
        val connection = requireNotNull(connections.getConnection(selected)) { "PHONE_BENCHMARK_CONNECTION_REMOVED" }
        require(connection.type == ToolConnectionType.GITHUB && connection.toolPolicy in setOf("ASK_WRITES", "TRUSTED")) { "PHONE_BENCHMARK_WRITE_POLICY_REQUIRED" }
        val credential = auth.accessToken(connection)
        require(credential.isNotBlank()) { "PHONE_BENCHMARK_CREDENTIAL_REQUIRED" }
        val account = auth.durableIdentity(selected)
        val permission = GitHubAuthorizationIdentity.permission(connection.authType, connection.toolPolicy, connection.approvedReadTools, connection.endpointUrl)
        val repository = requireNotNull(plan.repositoryFullName)
        val scope = "phone-benchmark:" + benchmarkHash(listOf(account, selected, plan.id, repository, plan.baseSha).joinToString("\u0000"))
        val workspace = GitHubWorkspaceClient(
            credential,
            conditionalReads = false,
            connectionUid = selected,
            conversationId = "benchmark:${plan.id}",
            taskId = "phone-benchmark:${plan.id}",
            stateStore = state,
            durableIdentity = account,
            legacyCredentialIdentities = auth.legacyCredentialIdentities(selected),
            credentialProvider = {
                val current = requireNotNull(connections.getConnection(selected)) { "PHONE_BENCHMARK_CONNECTION_REMOVED" }
                check(current.type == connection.type && current.authType == connection.authType && current.endpointUrl == connection.endpointUrl && current.oauthClientId == connection.oauthClientId && current.toolPolicy == connection.toolPolicy && current.approvedReadTools == connection.approvedReadTools) { "PHONE_BENCHMARK_CONNECTION_CHANGED" }
                val fresh = auth.accessToken(current)
                check(auth.durableIdentity(selected) == account) { "PHONE_BENCHMARK_ACCOUNT_CHANGED" }
                fresh
            },
            apiOrigins = GitHubApiOrigins.fromEndpoint(connection.endpointUrl)
        )
        val io = object : BenchmarkPhoneGitHubIo {
            override var requestCount = 0
                private set
            override suspend fun request(path: String, body: JsonObject?): JsonObject {
                requestCount++
                return try {
                    workspace.phoneBenchmarkRequest(repository, path, body)
                } catch (rejected: GitHubRequestRejected) {
                    if (body != null && (rejected.status == 0 || rejected.status in 400..499 && rejected.status != 408)) {
                        throw BenchmarkPhoneMutationRejected()
                    }
                    throw rejected
                }
            }
            override suspend fun artifact(id: Long, digest: String): String {
                requestCount++
                return workspace.phoneBenchmarkArtifact(repository, id, digest)
            }
        }
        val journal = object : BenchmarkPhoneGitHubJournal {
            override suspend fun read(key: String): JsonObject? = state.read(scope, key)
            override suspend fun update(key: String, transform: (JsonObject?) -> JsonObject): JsonObject = state.update(scope, key, transform)
        }
        return BenchmarkPhoneGitHubTransport.open(
            plan,
            io,
            journal,
            admissionLocks.getOrPut(scope) { Mutex() },
            benchmarkHash(listOf(account, selected, permission, connection.oauthClientId.orEmpty(), repository, plan.baseSha).joinToString("\u0000")),
            profileUid
        )
    }
}

internal interface BenchmarkPhoneGitHubIo {
    val requestCount: Int
    suspend fun request(path: String, body: JsonObject? = null): JsonObject
    suspend fun artifact(id: Long, digest: String): String
}

internal interface BenchmarkPhoneGitHubJournal {
    suspend fun read(key: String): JsonObject?
    suspend fun update(key: String, transform: (JsonObject?) -> JsonObject): JsonObject
}

/** A typed local admission denial or observed rejecting response; never a timeout/message heuristic. */
internal class BenchmarkPhoneMutationRejected : IllegalStateException("PHONE_BENCHMARK_MUTATION_REJECTED")
