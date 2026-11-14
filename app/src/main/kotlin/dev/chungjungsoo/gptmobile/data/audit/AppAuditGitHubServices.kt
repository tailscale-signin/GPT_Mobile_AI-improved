package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceStore
import dev.chungjungsoo.gptmobile.data.github.normalizeGitHubRepository
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Uses the selected workspace scope and current credential without refreshing or changing an account. */
internal class AppAuditGitHubServices @Inject constructor(
    private val connections: ToolConnectionRepository,
    private val workspaces: GitHubWorkspaceStore,
    private val vault: SecretVault,
    private val authorization: GitHubPublicAuthCoordinator,
    private val settings: SettingRepository
) {
    suspend fun inventory(): AppAuditGitHubInventory {
        val configured = connections.listConnections().filter { it.type == ToolConnectionType.GITHUB }.sortedBy { it.connectionUid }
        return AppAuditGitHubInventory(
            configured.take(AppAuditGitHubCatalog.MAX_CONNECTIONS).map { target(snapshot(it)) },
            (configured.size - AppAuditGitHubCatalog.MAX_CONNECTIONS).coerceIn(0, 4096)
        )
    }

    suspend fun target(key: String): AppAuditGitHubTarget? {
        if (!key.startsWith("connection:")) return null
        val connection = connections.getConnection(key.removePrefix("connection:"))?.takeIf { it.type == ToolConnectionType.GITHUB } ?: return null
        return target(snapshot(connection))
    }

    private fun target(saved: Snapshot) = AppAuditGitHubTarget(
        key = "connection:${saved.connection.connectionUid}",
        revision = saved.revision,
        repositorySelected = saved.repository != null,
        prerequisite = saved.prerequisite,
        reasonCode = saved.reason,
        read = { check, beforeDispatch ->
            val token = requireNotNull(saved.token)
            val client = GitHubWorkspaceClient(
                token, conditionalReads = false, blobCache = false, freshnessSeconds = 0,
                connectionUid = saved.connection.connectionUid, conversationId = "app-audit", persistentState = false,
                apiOrigins = GitHubApiOrigins.fromEndpoint(saved.connection.endpointUrl),
                credentialProvider = {
                    val connection = connections.getConnection(saved.connection.connectionUid) ?: throw AppAuditGitHubConfigurationChanged()
                    val current = snapshot(connection)
                    if (current.revision != saved.revision || current.prerequisite != AppAuditPrerequisite.READY) throw AppAuditGitHubConfigurationChanged()
                    requireNotNull(current.token)
                }
            )
            appAuditGitHubRead(check, saved.repository, saved.accountId, client, beforeDispatch)
        }
    )

    private suspend fun snapshot(connection: ToolConnection): Snapshot {
        val features = settings.getFeatureSettings()
        val enabled = features.isToolPluginEnabled(ToolPluginId.GITHUB) && features.isToolPluginEnabled(ToolPluginId.connection(connection.connectionUid))
        val selection = workspaces.get(connection.connectionUid)
        val repository = selection?.takeIf {
            runCatching { normalizeGitHubRepository(it.owner, it.repo) == (it.owner to it.repo) }.getOrDefault(false) &&
                it.ref.isNotBlank() &&
                it.ref.length <= 512 &&
                it.ref.none(Char::isISOControl)
        }
        var unreadableCredential = false
        val bytes = try {
            connection.secretRef?.let { vault.read(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unreadableCredential = true
            null
        }
        val token = try {
            bytes?.takeIf { it.size in 1..32_768 }?.decodeToString()?.trim()?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        } finally {
            bytes?.fill(0)
        }
        val session = try {
            authorization.session(connection.connectionUid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unreadableCredential = true
            null
        }
        val origins = runCatching { GitHubApiOrigins.fromEndpoint(connection.endpointUrl) }.getOrNull()
        val credentialReady = !unreadableCredential && appAuditGitHubCredentialReady(connection, token, session, System.currentTimeMillis())
        val reason = when {
            !enabled -> "GITHUB_AUDIT_DISABLED"
            origins == null || selection != null && repository == null -> "GITHUB_AUDIT_SCOPE_INVALID"
            !credentialReady -> "GITHUB_AUDIT_AUTHORIZATION_REQUIRED"
            GitHubSessionCaches.authenticationForCredential(requireNotNull(token), requireNotNull(origins).hostIdentity).get() -> "GITHUB_AUDIT_AUTHORIZATION_REQUIRED"
            GitHubSessionCaches.rateLimitsForCredential(requireNotNull(token), requireNotNull(origins).hostIdentity).shouldBackOff() -> "GITHUB_AUDIT_RATE_LIMITED"
            else -> null
        }
        val revision = appAuditHash(
            listOf(
                connection.connectionUid, connection.type, connection.endpointUrl, connection.authType, connection.oauthClientId,
                connection.toolPolicy, connection.approvedReadTools, connection.secretRef, token?.let(::appAuditHash), enabled,
                selection?.owner, selection?.repo, selection?.ref, session?.account?.id, session?.fingerprint,
                session?.clientId, session?.expiresAt, session?.refreshState, session?.verifyAfterRestore, unreadableCredential
            ).joinToString("\u0000")
        )
        return Snapshot(connection, repository, token, session?.account?.id, revision, if (reason == null) AppAuditPrerequisite.READY else AppAuditPrerequisite.PERMISSION_REQUIRED, reason)
    }

    private class Snapshot(
        val connection: ToolConnection,
        val repository: GitHubRepositoryContext?,
        val token: String?,
        val accountId: String?,
        val revision: String,
        val prerequisite: AppAuditPrerequisite,
        val reason: String?
    ) {
        override fun toString() = "GitHub audit scope (credentials and repository omitted)"
    }
}

internal fun appAuditGitHubCredentialReady(
    connection: ToolConnection,
    token: String?,
    session: GitHubPublicAuthCoordinator.Session?,
    now: Long
): Boolean {
    if (connection.type != ToolConnectionType.GITHUB || token.isNullOrBlank()) return false
    if (connection.authType !in setOf(ToolConnectionAuthType.OAUTH, ToolConnectionAuthType.BEARER, ToolConnectionAuthType.API_KEY)) return false
    if (session == null) return connection.authType != ToolConnectionAuthType.OAUTH
    if (session.account.id.isBlank() || session.fingerprint != GitHubPublicAuthCoordinator.fingerprint(token) || session.verifyAfterRestore || session.refreshState != "READY") return false
    if (session.expiresAt != null && (session.expiresAt <= now || session.expiresAt - now <= 60_000)) return false
    return if (session.refreshToken != null || connection.authType == ToolConnectionAuthType.OAUTH) {
        connection.authType == ToolConnectionAuthType.OAUTH && connection.oauthClientId == session.clientId
    } else {
        true
    }
}
