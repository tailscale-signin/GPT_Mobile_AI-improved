package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.repository.mcpOAuthPendingSecretRef
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Singleton
class McpOAuthCoordinator @Inject constructor(
    private val oauthClient: McpOAuthClient,
    private val connectionRepository: ToolConnectionRepository,
    private val secretVault: SecretVault,
    private val mcpClientManager: McpClientManager
) {
    private val credentialLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun credentialMutex(uid: String) = credentialLocks.computeIfAbsent(uid) { Mutex() }
    private val rejectedCredentials = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val refreshBackoff = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    suspend fun begin(connectionUid: String, redirectUri: String): String = credentialMutex(connectionUid).withLock {
        val connection = requireOAuthConnection(connectionUid)
        val discovery = oauthClient.discover(
            resourceUrl = dev.chungjungsoo.gptmobile.data.security.EndpointSecrets.resolve(connection, secretVault),
            allowCleartext = connection.allowCleartext
        )
        val start = oauthClient.beginAuthorization(discovery, redirectUri, connection.oauthClientId)
        if (start.pending.clientId != connection.oauthClientId) {
            persistConnection(connection.copy(oauthClientId = start.pending.clientId), expected = connection)
        }
        storePending(start.pending.copy(connectionUid = connectionUid))
        start.authorizationUri
    }

    suspend fun complete(connectionUid: String, callbackUri: String) = credentialMutex(connectionUid).withLock {
        val connection = requireOAuthConnection(connectionUid)
        val pendingRef = mcpOAuthPendingSecretRef(connectionUid)
        val pending = readSecret<McpOAuthPending>(pendingRef)
            ?: throw McpOAuthException("OAuth authorization is no longer pending.")
        if (pending.connectionUid != connectionUid) throw McpOAuthException("OAuth pending connection did not match.")
        val credential = oauthClient.completeAuthorization(pending, callbackUri)
        check(requireOAuthConnection(connectionUid) == connection) { "MCP connection changed during authorization; reconnect with the current configuration." }
        persistConnection(
            connection.copy(oauthClientId = credential.clientId),
            credential = NetworkClient.json.encodeToString(credential).encodeToByteArray(),
            expected = connection
        )
        rejectedCredentials.remove(connectionUid)
        refreshBackoff.remove(connectionUid)
        secretVault.delete(pendingRef)
        mcpClientManager.close(connectionUid)
        mcpClientManager.resetHealth(connectionUid)
    }

    suspend fun authorizationHeader(
        connection: ToolConnection,
        forceRefresh: Boolean = false,
        rejectedAuthorizationHeader: String? = null
    ): String {
        val credentialMutex = credentialMutex(connection.connectionUid)
        credentialMutex.lock()
        try {
            require(connection.authType == ToolConnectionAuthType.OAUTH) { "OAuth connection required." }
            val secretRef = connection.secretRef ?: throw McpOAuthException("MCP OAuth connection is not authorized. Reconnect this service in Plugins/Tools.", requiresReauthorization = true)
            var credential = readSecret<McpOAuthCredential>(secretRef)
                ?: throw McpOAuthException("MCP OAuth credential is missing. Reconnect this service in Plugins/Tools.", requiresReauthorization = true)
            if (credential.requiresReauthorization) {
                throw McpOAuthException("OAuth authorization is no longer valid. Reconnect this service in Plugins/Tools.", requiresReauthorization = true)
            }
            val fingerprint = dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository.digest(NetworkClient.json.encodeToString(credential))
            if (rejectedCredentials[connection.connectionUid] == fingerprint) {
                throw McpOAuthException("OAuth authorization is no longer valid. Reconnect this service in Plugins/Tools.", requiresReauthorization = true)
            }
            rejectedCredentials.remove(connection.connectionUid)
            val currentHeader = "${credential.tokenType} ${credential.accessToken}"
            val isExpired = credential.expiresAtEpochSeconds?.let { it <= nowEpochSeconds() + REFRESH_SKEW_SECONDS } == true
            val shouldRefreshRejected = forceRefresh && (rejectedAuthorizationHeader == null || rejectedAuthorizationHeader == currentHeader)
            if (isExpired || shouldRefreshRejected) {
                refreshBackoff[connection.connectionUid]?.takeIf { it.first == fingerprint && it.second > System.currentTimeMillis() }?.let { throw McpBackoffException(connection.connectionUid, it.second) }
                credential = try {
                    oauthClient.refresh(credential)
                } catch (error: McpOAuthException) {
                    if (error.requiresReauthorization && requireOAuthConnection(connection.connectionUid) == connection) {
                        if (rejectedCredentials.size >= 64) rejectedCredentials.remove(rejectedCredentials.keys.first())
                        rejectedCredentials[connection.connectionUid] = fingerprint
                        // Store the rejection with the encrypted credential so app restarts
                        // do not replay an invalid refresh grant indefinitely.
                        persistConnection(
                            connection,
                            credential = NetworkClient.json.encodeToString(credential.copy(requiresReauthorization = true)).encodeToByteArray(),
                            expected = connection
                        )
                    }
                    if (!error.requiresReauthorization) refreshBackoff[connection.connectionUid] = fingerprint to (System.currentTimeMillis() + 30_000)
                    throw error
                }
                val live = requireOAuthConnection(connection.connectionUid)
                check(live == connection) { "MCP connection changed during OAuth refresh; retry using the current configuration." }
                refreshBackoff.remove(connection.connectionUid)
                persistConnection(
                    connection.copy(oauthClientId = credential.clientId),
                    credential = NetworkClient.json.encodeToString(credential).encodeToByteArray(),
                    expected = connection
                )
                mcpClientManager.close(connection.connectionUid)
                mcpClientManager.resetHealth(connection.connectionUid)
            }
            return "${credential.tokenType} ${credential.accessToken}"
        } finally {
            credentialMutex.unlock()
        }
    }

    private suspend fun requireOAuthConnection(connectionUid: String): ToolConnection {
        val connection = connectionRepository.getConnection(connectionUid)
            ?: throw McpOAuthException("MCP connection was not found.")
        if (connection.type != ToolConnectionType.MCP || connection.authType != ToolConnectionAuthType.OAUTH) {
            throw McpOAuthException("Connection is not configured for MCP OAuth.")
        }
        return connection
    }

    private suspend fun persistConnection(
        connection: ToolConnection,
        credential: ByteArray? = null,
        expected: ToolConnection
    ) = dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
        check(connectionRepository.getConnection(connection.connectionUid) == expected) { "MCP connection changed before OAuth credentials could be saved. Reconnect using the current configuration." }
        connectionRepository.upsertConnection(connection, credential = credential)
    }

    private suspend fun storePending(pending: McpOAuthPending) {
        val bytes = NetworkClient.json.encodeToString(pending).encodeToByteArray()
        try {
            secretVault.put(mcpOAuthPendingSecretRef(requireNotNull(pending.connectionUid)), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend inline fun <reified T> readSecret(secretRef: String): T? {
        val bytes = secretVault.read(secretRef) ?: return null
        return try {
            NetworkClient.json.decodeFromString<T>(bytes.decodeToString())
        } catch (error: Exception) {
            throw McpOAuthException("MCP OAuth credential is invalid.", error)
        } finally {
            bytes.fill(0)
        }
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val REFRESH_SKEW_SECONDS = 60
    }
}

fun mcpOAuthRedirectUri(connectionUid: String): String {
    require(MCP_CONNECTION_UID_PATTERN.matches(connectionUid)) { "MCP connection ID is invalid." }
    return "$MCP_OAUTH_SCHEME://$MCP_OAUTH_HOST/mcp/$connectionUid"
}

fun mcpOAuthConnectionUid(callbackUri: String): String? = runCatching { URI(callbackUri) }
    .getOrNull()
    ?.takeIf { it.scheme == MCP_OAUTH_SCHEME && it.host == MCP_OAUTH_HOST && it.path?.startsWith("/mcp/") == true }
    ?.path
    ?.substringAfterLast('/')
    ?.takeIf(MCP_CONNECTION_UID_PATTERN::matches)

fun isMcpOAuthCallbackUri(callbackUri: String?): Boolean = callbackUri?.let(::mcpOAuthConnectionUid) != null

const val MCP_OAUTH_SCHEME = "dev.chungjungsoo.gptmobile"
const val MCP_OAUTH_HOST = "oauth"

private val MCP_CONNECTION_UID_PATTERN = Regex("[A-Za-z0-9_-]{1,96}")
