package dev.chungjungsoo.gptmobile.data.permissions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A grant is scoped to a tool and connection destination, not a mutable display name. */
@Singleton
class ToolTrustStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("tool_always_allow", Context.MODE_PRIVATE)
    private fun scopes(connection: ToolConnection, key: String) = listOf("connection:${connection.connectionUid}", "grant:$key")
    private fun allowed(connection: ToolConnection, key: String, read: () -> Boolean): Boolean {
        val snapshot = revocations.snapshot(scopes(connection, key)) ?: return false
        return read() && revocations.current(snapshot)
    }
    internal data class Approval(val connection: ToolConnection, val snapshot: ToolGrantRevocations.Snapshot)
    internal fun captureApproval(connection: ToolConnection, tool: String, scope: ScopedToolGrant? = null): Approval? {
        val scopes = scopes(connection, key(connection, tool)) + "grant:${providerKey(connection)}" + listOfNotNull(scope?.let { "grant:${scopedKey(connection, tool, it)}" })
        return revocations.snapshot(scopes)?.let { Approval(connection.copy(name = ""), it) }
    }
    internal fun isApprovalCurrent(approval: Approval, connection: ToolConnection? = null): Boolean =
        revocations.current(approval.snapshot) && (connection == null || approval.connection == connection.copy(name = ""))

    private fun grant(connection: ToolConnection, key: String, approval: Approval?, write: () -> Unit) {
        val snapshot = requireNotNull(revocations.snapshot(scopes(connection, key))) { "Tool permission revocation is still pending. Retry after it finishes." }
        synchronized(mutationLock) {
            check(revocations.current(snapshot)) { "Tool permission changed before the grant could be saved." }
            check(approval == null || isApprovalCurrent(approval, connection)) { "This tool approval predates a permission or account change. Request approval again." }
            write()
        }
    }
    fun allows(connection: ToolConnection, tool: String): Boolean =
        allowed(connection, providerKey(connection)) { preferences.getBoolean(providerKey(connection), false) } ||
            allowed(connection, key(connection, tool)) { preferences.getBoolean(key(connection, tool), false) }
    fun allow(connection: ToolConnection, tool: String) = allow(connection, tool, null)
    internal fun allow(connection: ToolConnection, tool: String, approval: Approval?) {
        grant(connection, key(connection, tool), approval) {
            check(preferences.edit().putBoolean(key(connection, tool), true).putString("label:" + key(connection, tool), "${connection.name} · $tool · all models · permanent").commit())
        }
    }
    fun allowProvider(connection: ToolConnection) = allowProvider(connection, null)
    internal fun allowProvider(connection: ToolConnection, approval: Approval?) {
        grant(connection, providerKey(connection), approval) {
            check(preferences.edit().putBoolean(providerKey(connection), true).putString("label:" + providerKey(connection), "${connection.name} · all tools · all models · permanent").commit())
        }
    }
    fun allowScoped(connection: ToolConnection, tool: String, scope: ScopedToolGrant, now: Long = System.currentTimeMillis()) = allowScoped(connection, tool, scope, null, now)
    internal fun allowScoped(connection: ToolConnection, tool: String, scope: ScopedToolGrant, approval: Approval?, now: Long = System.currentTimeMillis()) {
        require(scope.chatId > 0 && scope.schemaHash.isNotBlank())
        // Resource arguments can include signed URLs or private paths. Persist only their digest.
        grant(connection, scopedKey(connection, tool, scope), approval) {
            check(preferences.edit().putLong(scopedKey(connection, tool, scope), now + 3_600_000L).putString("label:" + scopedKey(connection, tool, scope), "${connection.name} · $tool · chat ${scope.chatId} · scope ${scope.key().take(12)}").commit())
        }
    }
    fun allowsScoped(connection: ToolConnection, tool: String, scope: ScopedToolGrant, now: Long = System.currentTimeMillis()): Boolean =
        allowed(connection, scopedKey(connection, tool, scope)) { preferences.getLong(scopedKey(connection, tool, scope), 0L) > now }

    private fun scopedKey(connection: ToolConnection, tool: String, scope: ScopedToolGrant) = key(connection, tool) + ":scoped:" + scope.key() + ":" + connection.updatedAt

    fun observeCatalog(connectionUid: String, fingerprint: String): Boolean {
        val key = "catalog:$connectionUid"
        val previous = preferences.getString(key, null)
        val changed = previous != null && previous != fingerprint
        check(preferences.edit().putString(key, fingerprint).commit())
        return changed
    }
    fun grants(): List<Pair<String, String>> = preferences.all.entries.filter { !it.key.startsWith("label:") && !it.key.startsWith("catalog:") && (it.value == true || (it.value is Long && (it.value as Long) > System.currentTimeMillis())) }.map { (key, value) ->
        key to ((preferences.getString("label:$key", null) ?: "Saved grant · ${key.substringBefore(':')}") + if (value is Long) " · expires ${java.util.Date(value)}" else "")
    }
    internal fun denyGrant(key: String) = revocations.begin("grant:$key")
    internal fun denyConnection(connectionUid: String) = revocations.begin("connection:$connectionUid")
    fun revokeGrant(key: String) = revokeGrant(key, denyGrant(key))
    internal fun revokeGrant(key: String, denial: ToolGrantRevocations.Ticket, release: Boolean = true) = synchronized(mutationLock) {
        require(denial.scope == "grant:$key")
        check(preferences.edit().remove(key).remove("label:$key").commit())
        if (release) revocations.complete(denial)
    }
    fun revoke(connectionUid: String) = revoke(connectionUid, denyConnection(connectionUid))
    internal fun revoke(connectionUid: String, denial: ToolGrantRevocations.Ticket, release: Boolean = true) = synchronized(mutationLock) {
        require(denial.scope == "connection:$connectionUid")
        val prefix = "$connectionUid:"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) || it.startsWith("label:$prefix") }.forEach(editor::remove)
        check(editor.commit())
        if (release) revocations.complete(denial)
    }
    internal fun finishRevocation(denial: ToolGrantRevocations.Ticket) = revocations.complete(denial)
    private fun providerKey(connection: ToolConnection): String {
        val identity = "${connection.endpointUrl}|${connection.authType}|${connection.oauthClientId}|*"
        return "${connection.connectionUid}:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun key(connection: ToolConnection, tool: String): String {
        val identity = "${connection.endpointUrl}|${connection.authType}|${connection.oauthClientId}|$tool"
        return "${connection.connectionUid}:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val revocations = ToolGrantRevocations()
        val mutationLock = Any()
    }
}
