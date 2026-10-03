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
    fun allows(connection: ToolConnection, tool: String): Boolean = preferences.getBoolean(providerKey(connection), false) || preferences.getBoolean(key(connection, tool), false)
    fun allow(connection: ToolConnection, tool: String) {
        check(preferences.edit().putBoolean(key(connection, tool), true).putString("label:" + key(connection, tool), "${connection.name} · $tool · permanent").commit())
    }
    fun allowProvider(connection: ToolConnection) {
        check(preferences.edit().putBoolean(providerKey(connection), true).putString("label:" + providerKey(connection), "${connection.name} · all tools · permanent").commit())
    }
    fun allowScoped(connection: ToolConnection, tool: String, scope: ScopedToolGrant, now: Long = System.currentTimeMillis()) {
        require(scope.chatId > 0 && scope.schemaHash.isNotBlank())
        // Resource arguments can include signed URLs or private paths. Persist only their digest.
        check(preferences.edit().putLong(scopedKey(connection, tool, scope), now + 3_600_000L).putString("label:" + scopedKey(connection, tool, scope), "${connection.name} · $tool · chat ${scope.chatId} · scope ${scope.key().take(12)}").commit())
    }
    fun allowsScoped(connection: ToolConnection, tool: String, scope: ScopedToolGrant, now: Long = System.currentTimeMillis()): Boolean =
        preferences.getLong(scopedKey(connection, tool, scope), 0L) > now

    private fun scopedKey(connection: ToolConnection, tool: String, scope: ScopedToolGrant) = key(connection, tool) + ":scoped:" + scope.key() + ":" + connection.updatedAt

    fun observeCatalog(connectionUid: String, fingerprint: String): Boolean {
        val key = "catalog:$connectionUid"
        val previous = preferences.getString(key, null)
        val changed = previous != null && previous != fingerprint
        if (changed) revoke(connectionUid)
        check(preferences.edit().putString(key, fingerprint).commit())
        return changed
    }
    fun grants(): List<Pair<String, String>> = preferences.all.entries.filter { !it.key.startsWith("label:") && !it.key.startsWith("catalog:") && (it.value == true || (it.value is Long && (it.value as Long) > System.currentTimeMillis())) }.map { (key, value) ->
        key to ((preferences.getString("label:$key", null) ?: "Saved grant · ${key.substringBefore(':')}") + if (value is Long) " · expires ${java.util.Date(value)}" else "")
    }
    fun revokeGrant(key: String) {
        check(preferences.edit().remove(key).remove("label:$key").commit())
    }
    fun revoke(connectionUid: String) {
        val prefix = "$connectionUid:"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) || it.startsWith("label:$prefix") }.forEach(editor::remove)
        check(editor.commit())
    }
    private fun providerKey(connection: ToolConnection): String {
        val identity = "${connection.endpointUrl}|${connection.authType}|${connection.oauthClientId}|*"
        return "${connection.connectionUid}:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun key(connection: ToolConnection, tool: String): String {
        val identity = "${connection.endpointUrl}|${connection.authType}|${connection.oauthClientId}|$tool"
        return "${connection.connectionUid}:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
