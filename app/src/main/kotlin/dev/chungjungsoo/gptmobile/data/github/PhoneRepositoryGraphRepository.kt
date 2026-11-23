package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Revision-bound graph reads use the phone's existing GitHub authorization and source cache. */
@Singleton
class PhoneRepositoryGraphRepository internal constructor(
    private val settings: SettingRepository,
    private val connections: ToolConnectionRepository,
    private val authentication: GitHubPublicAuthCoordinator,
    private val state: GitHubRepositoryStateStore,
    private val indexOperationTimeoutMs: Long = 20_000,
    private val clientFactory: (PhoneGraphClientConfiguration) -> GitHubWorkspaceClient
) {
    @Inject constructor(
        settings: SettingRepository,
        connections: ToolConnectionRepository,
        authentication: GitHubPublicAuthCoordinator,
        state: GitHubRepositoryStateStore
    ) : this(settings, connections, authentication, state, clientFactory = { config -> config.createClient() })

    private val operations = Mutex()

    internal suspend fun executeForMap(
        context: GitHubConversationContext,
        arguments: JsonObject,
        forModel: Boolean = false
    ): GatewayGraphResponse {
        if (arguments["action"] != JsonPrimitive("index")) return executeAdmittedForMap(context, arguments, forModel)
        // This phone-only deadline includes mutex queueing, authentication and the
        // current access check. Gateway indexing and notes never enter this path.
        return GitHubReadWorkBudget(indexOperationTimeoutMs).run { executeAdmittedForMap(context, arguments, forModel) }
            ?: throw GatewayGraphOperationException(
                GatewayGraphResultException("READ_DEADLINE", "The foreground phone index reached its read deadline. Completed immutable source is retained; read current phone status before continuing.", "READ_PHONE_INDEX_STATUS"),
                "",
                "On this phone",
                "READ_PHONE_INDEX_STATUS",
                false,
                targetRevision = context.commitSha
            )
    }

    private suspend fun executeAdmittedForMap(
        context: GitHubConversationContext,
        arguments: JsonObject,
        forModel: Boolean
    ): GatewayGraphResponse = operations.withLock {
        try {
            require(context.commitSha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))) { "Resolve the repository revision before opening its map." }
            require(arguments.keys.none { it in setOf("repository", "owner", "repo", "branch", "commit_sha", "ref") }) {
                "Repository map arguments cannot replace the selected repository or revision."
            }
            val admitted = admission(context)
            val initialToken = token(admitted.connection)
            val identity = identity(admitted.connection)
            verifyAdmission(context, admitted, identity)
            currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.PhoneIndexInterruption.AttemptKey]?.admit(context, identity, arguments)
            val credentials: suspend () -> String = {
                verifyAdmission(context, admitted, identity)
                token(requireNotNull(connections.getConnection(context.connectionUid)))
                    .also { verifyAdmission(context, admitted, identity) }
            }
            val config = PhoneGraphClientConfiguration(initialToken, identity, admitted.connection, admitted.features, context, state, credentials)
            val client = clientFactory(config)
            // Credential and repository authorization precede cached source reads too.
            // The immutable tree and blobs remain reusable after this current access check.
            val repository = client.execute(
                "get_repository",
                buildJsonObject {
                    put("owner", context.owner)
                    put("repo", context.repo)
                }
            ).jsonObject
            val observedName = (repository["full_name"] as? JsonPrimitive)?.contentOrNull
            if (observedName == null || !observedName.equals(context.repository.fullName, true)) {
                throw GatewayGraphIntegrityException("PHONE_GRAPH_REPOSITORY_CHANGED", "GitHub did not confirm the selected repository identity.")
            }
            verifyAdmission(context, admitted, identity)
            val result = client.repositoryMap(
                context.owner,
                context.repo,
                JsonObject(arguments + mapOf("ref" to JsonPrimitive(context.ref), "commit_sha" to JsonPrimitive(context.commitSha)))
            )
            currentCoroutineContext().ensureActive()
            verifyAdmission(context, admitted, identity)
            val graph = result["graph"] as? JsonObject ?: result
            if (!(graph["repository"] as? JsonPrimitive)?.contentOrNull.equals(context.repository.fullName, true) ||
                !(graph["commit_sha"] as? JsonPrimitive)?.contentOrNull.equals(context.commitSha, true) ||
                (graph["branch"] as? JsonPrimitive)?.contentOrNull?.let { it != context.ref } == true
            ) {
                throw GatewayGraphIntegrityException("GRAPH_REVISION_MISMATCH", "The phone map does not match the selected repository revision.")
            }
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull ?: "status"
            GatewayGraphResponse(
                JsonObject(result + ("map_source" to JsonPrimitive("phone"))),
                action,
                "",
                "On this phone",
                durableIndexJobs = false,
                modelAccess = if (forModel) "NATIVE_GITHUB_AUTHORIZED" else "LOCAL_GITHUB_MAP"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw GatewayGraphOperationException(
                failure,
                "",
                "On this phone",
                when {
                    failure.isLocalPrivacyPolicyRejection() -> "REVIEW_PRIVACY_MODE"
                    failure is GatewayGraphAdmissionException -> failure.repairAction
                    failure is GatewayGraphResultException -> failure.repairAction
                    else -> null
                },
                false,
                targetRevision = context.commitSha
            )
        }
    }

    private data class Admission(val connection: ToolConnection, val profile: PlatformV2, val features: AppFeatureSettings)

    private suspend fun admission(context: GitHubConversationContext): Admission {
        val connection = connections.getConnection(context.connectionUid)
            ?: throw GatewayGraphAdmissionException("PERMISSION_REVOKED", "The selected GitHub connection was removed.", "RESTORE_SELECTED_GITHUB_CONNECTION")
        if (connection.type != "GITHUB") throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "Select a GitHub connection.")
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == context.mainProfileUid && it.enabled }
            ?: throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "The selected AI profile is unavailable.", "RESTORE_SELECTED_AI_PROFILE")
        val original = settings.getFeatureSettings()
        val effective = GitHubChatPolicy.platform(profile, original, context)
        val features = GitHubChatPolicy.features(original, profile.uid, context)
        if (effective.disableAllTools ||
            effective.disableRemoteTools ||
            !features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.GITHUB) ||
            !features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.connection(context.connectionUid)) ||
            GitHubChatPolicy.settings(original, profile.uid, context)?.githubTools == false
        ) {
            throw GatewayGraphAdmissionException("PERMISSION_DENIED", "GitHub reads are disabled for the selected profile.")
        }
        // This is the same direct source route as native GitHub reads. It neither
        // changes privacy transport policy nor requires a private inference host.
        return Admission(connection, profile, features)
    }

    private suspend fun token(connection: ToolConnection): String =
        if (connection.secretRef != null) authentication.accessToken(connection) else ""

    private suspend fun identity(connection: ToolConnection): String =
        if (connection.secretRef == null) {
            "anonymous:${connection.connectionUid}:${GitHubApiOrigins.fromEndpoint(connection.endpointUrl).hostIdentity}"
        } else {
            authentication.durableIdentity(connection.connectionUid)
        }

    private suspend fun verifyAdmission(context: GitHubConversationContext, admitted: Admission, identity: String) {
        val current = admission(context)
        // Same-account token refresh can update metadata; account or policy changes cannot.
        val currentConnection = current.connection.copy(createdAt = admitted.connection.createdAt, updatedAt = admitted.connection.updatedAt)
        if (currentConnection != admitted.connection ||
            current.profile != admitted.profile ||
            current.features.githubBlobCache != admitted.features.githubBlobCache ||
            identity(current.connection) != identity
        ) {
            throw GatewayGraphAdmissionException("GRAPH_SELECTION_CHANGED", "The GitHub account, profile or permissions changed. Reopen the repository map.", "RECHECK_SELECTED_GITHUB_CONNECTION")
        }
    }
}

/** Kept separate so transport tests can use the real client with an isolated mock engine. */
internal class PhoneGraphClientConfiguration(
    private val token: String,
    private val identity: String,
    private val connection: ToolConnection,
    private val features: AppFeatureSettings,
    private val context: GitHubConversationContext,
    private val state: GitHubRepositoryStateStore,
    private val credentialProvider: suspend () -> String
) {
    fun createClient(http: io.ktor.client.HttpClient? = null): GitHubWorkspaceClient {
        val conversation = "phone-map:${context.mainProfileUid}"
        val task = "phone-map:${GitHubRepositoryStateStore.digest("${context.owner.lowercase()}/${context.repo.lowercase()}|${context.ref}|${context.commitSha}")}"
        return if (http == null) {
            GitHubWorkspaceClient(token, conditionalReads = false, blobCache = features.githubBlobCache, connectionUid = connection.connectionUid, conversationId = conversation, taskId = task, stateStore = state, durableIdentity = identity, credentialProvider = credentialProvider, apiOrigins = GitHubApiOrigins.fromEndpoint(connection.endpointUrl))
        } else {
            GitHubWorkspaceClient(token, client = http, conditionalReads = false, blobCache = features.githubBlobCache, connectionUid = connection.connectionUid, conversationId = conversation, taskId = task, stateStore = state, durableIdentity = identity, credentialProvider = credentialProvider, apiOrigins = GitHubApiOrigins.fromEndpoint(connection.endpointUrl))
        }
    }

    override fun toString(): String = "Phone repository map transport (credentials redacted)"
}
