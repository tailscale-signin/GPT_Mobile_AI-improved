package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encrypted, process-death-safe authorization. It never executes engineering writes. */
@Singleton
class GitHubPublicAuthCoordinator internal constructor(
    private val connections: ToolConnectionRepository,
    private val vault: SecretVault,
    private val configuration: GitHubPublicAuthConfiguration,
    private val clock: () -> Long = System::currentTimeMillis,
    private val clientFactory: () -> GitHubPublicAuthClient = { GitHubPublicAuthClient(http, configuration.brokerOrigin, configuration.callbackUri, configuration.clientId, clock) },
    private val accountIdentity: suspend (String) -> Account = { token ->
        val value = GitHubWorkspaceClient(token).execute("get_account", buildJsonObject {}).jsonObject
        Account(value.getValue("id").jsonPrimitive.content, value.getValue("login").jsonPrimitive.content, value["avatar_url"]?.jsonPrimitive?.content)
    },
    private val deviceRefresh: suspend (String, String) -> GitHubOAuthCredential = { clientId, refreshToken -> GitHubDeviceAuthClient(http).refresh(clientId, refreshToken) }
) {
    @Inject constructor(connections: ToolConnectionRepository, vault: SecretVault) : this(connections, vault, GitHubPublicAuthConfiguration.project())

    @Serializable
    data class Account(val id: String, val login: String, val avatarUrl: String? = null)

    @Serializable
    private data class PendingRecord(val authorization: GitHubPublicAuthClient.Pending, val connection: ToolConnection) {
        override fun toString() = "Pending GitHub sign-in (redacted)"
    }

    @Serializable
    internal data class Session(
        val account: Account,
        val fingerprint: String,
        val clientId: String,
        val refreshToken: String? = null,
        val expiresAt: Long? = null,
        val refreshExpiresAt: Long? = null,
        val refreshState: String = "READY",
        val refreshOperationId: String? = null,
        val refreshMethod: String = "BROKER",
        val verifyAfterRestore: Boolean = false
    ) {
        override fun toString() = "Encrypted GitHub session (credentials redacted)"
    }

    private val pendingMutex = Mutex()
    private val credentialMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun credentialMutex(uid: String) = credentialMutexes.computeIfAbsent(uid) { Mutex() }

    fun canAuthorize(connection: ToolConnection): Boolean = configuration.configured && connection.type == "GITHUB" && connection.authType == "OAUTH" && (connection.oauthClientId.isNullOrBlank() || connection.oauthClientId == configuration.clientId)
    fun acceptsCallback(value: String) = configuration.acceptsCallback(value)

    suspend fun begin(connectionUid: String): String = pendingMutex.withLock {
        val existing = read(PENDING_REFERENCE, PendingRecord.serializer())
        check(existing == null || existing.authorization.expiresAt <= clock()) { "Complete or cancel the pending GitHub sign-in before starting another." }
        val connection = requireNotNull(connections.getConnection(connectionUid)) { "GitHub connection was removed." }
        check(canAuthorize(connection)) { "Public GitHub sign-in is not configured for this connection." }
        clientFactory().use { client ->
            val start = client.begin(connectionUid)
            check(connections.getConnection(connectionUid) == connection) { "GitHub connection changed. Start sign-in again." }
            put(PENDING_REFERENCE, PendingRecord(start.pending, connection), PendingRecord.serializer())
            start.authorizationUri
        }
    }

    suspend fun pendingConnectionUid(): String? = pendingMutex.withLock {
        val pending = read(PENDING_REFERENCE, PendingRecord.serializer()) ?: return@withLock null
        if (pending.authorization.expiresAt <= clock() || pending.authorization.clientId != configuration.clientId || pending.authorization.brokerOrigin != configuration.brokerOrigin || pending.authorization.callbackUri != configuration.callbackUri || connections.getConnection(pending.connection.connectionUid) != pending.connection) {
            vault.delete(PENDING_REFERENCE)
            return@withLock null
        }
        pending.connection.connectionUid
    }

    suspend fun cancel() = pendingMutex.withLock { vault.delete(PENDING_REFERENCE) }

    suspend fun disconnect(connectionUid: String) = pendingMutex.withLock {
        credentialMutex(connectionUid).withLock {
            dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock mutation@{
                val connection = connections.getConnection(connectionUid)?.takeIf { it.type == "GITHUB" } ?: return@mutation
                connections.upsertConnection(connection, clearCredential = true)
                vault.delete(sessionReference(connectionUid))
            }
            if (read(PENDING_REFERENCE, PendingRecord.serializer())?.connection?.connectionUid == connectionUid) vault.delete(PENDING_REFERENCE)
        }
    }

    suspend fun connectedAccount(connectionUid: String): Account? {
        val connection = connections.getConnection(connectionUid)?.takeIf { it.type == "GITHUB" } ?: return null
        val reference = connection.secretRef ?: return null
        val bytes = vault.read(reference) ?: return null
        return try {
            session(connectionUid)?.takeIf { it.fingerprint == fingerprint(bytes.decodeToString().trim()) }?.account
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun complete(callback: String): Account = pendingMutex.withLock {
        val pending = read(PENDING_REFERENCE, PendingRecord.serializer()) ?: error("GitHub sign-in is no longer pending. Start again.")
        val connection = pending.connection
        check(connections.getConnection(connection.connectionUid) == connection) { "GitHub connection changed. Start sign-in again." }
        clientFactory().use { client ->
            try {
                client.validateCallback(pending.authorization, callback)
            } catch (denied: GitHubPublicAuthClient.ConsentDenied) {
                vault.delete(PENDING_REFERENCE)
                throw denied
            }
            // Wrong-state callbacks cannot consume a legitimate transaction. A valid
            // callback is consumed before network I/O and cannot be replayed after death.
            vault.delete(PENDING_REFERENCE)
            val credential = client.complete(pending.authorization, callback)
            credentialMutex(connection.connectionUid).withLock {
                val account = verifyAccount(connection, credential.accessToken)
                save(connection, credential, account)
                account
            }
        }
    }

    internal suspend fun completeDevice(connection: ToolConnection, clientId: String, credential: GitHubOAuthCredential): Account = credentialMutex(connection.connectionUid).withLock {
        check(connection.type == "GITHUB" && connection.authType == "OAUTH" && connections.getConnection(connection.connectionUid) == connection && GitHubDeviceAuthClient.validClientId(clientId)) { "GitHub connection changed. Start sign-in again." }
        val account = verifyAccount(connection, credential.accessToken)
        save(connection, credential, account, clientId, "DEVICE")
        account
    }

    private suspend fun verifyAccount(connection: ToolConnection, token: String): Account {
        val account = accountIdentity(token)
        require(account.id.matches(Regex("[1-9][0-9]{0,19}")) && account.login.matches(Regex("[A-Za-z0-9-]{1,39}"))) { "GitHub returned an invalid account identity." }
        val previous = session(connection.connectionUid)
        val binding = read(bindingReference(connection.connectionUid), Account.serializer()) ?: previous?.account
        check(binding == null || binding.id == account.id) { "This connection belongs to another GitHub account. Add a separate connection to keep repository access isolated." }
        if (connection.secretRef != null && binding == null) {
            val bytes = vault.read(connection.secretRef) ?: error("Create a new GitHub connection; the old account identity cannot be verified.")
            val previousAccount = try {
                accountIdentity(bytes.decodeToString().trim())
            } finally {
                bytes.fill(0)
            }
            check(previousAccount.id == account.id) { "This connection belongs to another GitHub account. Add a separate connection." }
        }
        return account
    }

    internal suspend fun session(connectionUid: String): Session? = read(sessionReference(connectionUid), Session.serializer())

    /** A refresh interrupted after dispatch is never replayed automatically. */
    suspend fun accessToken(connection: ToolConnection): String = credentialMutex(connection.connectionUid).withLock {
        val reference = connection.secretRef ?: error("Connect GitHub in Plugins/Tools before using private repositories or writes.")
        val bytes = vault.read(reference) ?: error("GitHub credential is missing. Reconnect GitHub.")
        val currentToken = try {
            bytes.decodeToString().trim()
        } finally {
            bytes.fill(0)
        }
        val session = session(connection.connectionUid) ?: run {
            val binding = read(bindingReference(connection.connectionUid), Account.serializer())
            if (binding != null) {
                val account = accountIdentity(currentToken)
                check(account.id == binding.id) { "This GitHub connection belongs to another account. Add a separate connection." }
                put(sessionReference(connection.connectionUid), Session(account, fingerprint(currentToken), configuration.clientId), Session.serializer())
            }
            return@withLock currentToken
        }
        check(session.fingerprint == fingerprint(currentToken)) { "GitHub credentials changed. Reconnect to verify this account." }
        if (session.refreshToken != null || connection.authType == "OAUTH") {
            check(connection.authType == "OAUTH" && connection.oauthClientId == session.clientId) { "GitHub App configuration changed. Reconnect to verify this client and account." }
        }
        val rejected = GitHubSessionCaches.authenticationForCredential(currentToken).get()
        if (!rejected && (session.expiresAt == null || clock() + 60_000 < session.expiresAt)) {
            if (session.verifyAfterRestore) {
                val account = accountIdentity(currentToken)
                check(account.id == session.account.id) { "Restored GitHub credentials belong to another account. Reconnect GitHub." }
                put(bindingReference(connection.connectionUid), account, Account.serializer())
                put(sessionReference(connection.connectionUid), session.copy(verifyAfterRestore = false), Session.serializer())
            }
            return@withLock currentToken
        }
        val canRefresh = when (session.refreshMethod) {
            "DEVICE" -> GitHubDeviceAuthClient.validClientId(session.clientId)
            "BROKER" -> configuration.configured && session.clientId == configuration.clientId
            else -> false
        }
        check(canRefresh && session.refreshState == "READY" && session.refreshToken != null && (session.refreshExpiresAt == null || clock() < session.refreshExpiresAt)) { "GitHub authorization expired or refresh needs reconciliation. Reconnect GitHub." }
        val operationId = java.util.UUID.randomUUID().toString()
        val rotating = session.copy(refreshState = "REFRESHING", refreshOperationId = operationId)
        put(sessionReference(connection.connectionUid), rotating, Session.serializer())
        try {
            val rotated = if (session.refreshMethod == "DEVICE") {
                deviceRefresh(session.clientId, session.refreshToken)
            } else {
                clientFactory().use { it.rotate(operationId, session.refreshToken) }
            }
            val account = accountIdentity(rotated.accessToken)
            check(account.id == session.account.id) { "GitHub refresh changed account identity. Reconnect GitHub." }
            save(connection, rotated, account, session.clientId, session.refreshMethod)
            rotated.accessToken
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                if (connections.getConnection(connection.connectionUid) == connection && session(connection.connectionUid)?.refreshOperationId == operationId) {
                    put(sessionReference(connection.connectionUid), rotating.copy(refreshState = "REAUTH_REQUIRED"), Session.serializer())
                }
            }
            if (failure is CancellationException) throw failure
            error("GitHub credential refresh could not be verified. Reconnect GitHub; no engineering operation was repeated.")
        }
    }

    private suspend fun save(connection: ToolConnection, credential: GitHubOAuthCredential, account: Account, clientId: String = configuration.clientId, refreshMethod: String = "BROKER") = dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations.mutex.withLock {
        check(connections.getConnection(connection.connectionUid) == connection) { "GitHub connection changed before credentials could be saved. Reconnect." }
        put(bindingReference(connection.connectionUid), account, Account.serializer())
        val bytes = credential.accessToken.encodeToByteArray()
        try {
            connections.upsertConnection(connection.copy(oauthClientId = clientId), credential = bytes)
        } finally {
            bytes.fill(0)
        }
        put(sessionReference(connection.connectionUid), Session(account, fingerprint(credential.accessToken), clientId, credential.refreshToken, credential.expiresAt, credential.refreshExpiresAt, refreshMethod = refreshMethod), Session.serializer())
    }

    private suspend fun <T> read(reference: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val bytes = vault.read(reference) ?: return null
        try {
            check(bytes.size <= 32_768) { "GitHub authorization record is invalid. Reconnect." }
            return runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrElse { error("GitHub authorization record could not be read. Reconnect GitHub.") }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun <T> put(reference: String, value: T, serializer: kotlinx.serialization.KSerializer<T>) {
        val bytes = json.encodeToString(serializer, value).encodeToByteArray()
        try {
            vault.put(reference, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        private val http by lazy {
            HttpClient(OkHttp) {
                followRedirects = false
                expectSuccess = false
                engine { config { retryOnConnectionFailure(false) } }
            }
        }
        private val json = Json { ignoreUnknownKeys = false }
        internal const val PENDING_REFERENCE = "github-public-auth-pending"
        internal fun sessionReference(uid: String) = "github-oauth-session-" + fingerprint(uid)
        internal fun bindingReference(uid: String) = "github-account-binding-" + fingerprint(uid)
        internal fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
