package dev.chungjungsoo.gptmobile.data.permissions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the explicit data-sharing unlock for free-model tools.
 *
 * New acknowledgements apply to this tool across all models. Older profile grants
 * remain valid for their original profile until the user grants or revokes the tool.
 */
@Singleton
class FreeModelToolConsentStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences("free_model_tool_consent_v1", Context.MODE_PRIVATE)

    fun isGranted(profileUid: String, toolId: String): Boolean =
        preferences.getBoolean(globalKey(toolId), false) || preferences.getBoolean(key(profileUid, toolId), false)

    @Suppress("UNUSED_PARAMETER")
    fun grant(profileUid: String, toolId: String) {
        check(preferences.edit().putBoolean(globalKey(toolId), true).putString("tool:" + globalKey(toolId), toolId).commit()) {
            "Could not save free-model tool permission."
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun revoke(profileUid: String, toolId: String) {
        val digest = key("", toolId).substringAfter(':')
        val editor = preferences.edit()
        preferences.all.keys.filter { it.endsWith(":$digest") }.forEach(editor::remove)
        check(editor.commit()) {
            "Could not revoke free-model tool permission."
        }
    }

    fun revokeProfile(profileUid: String) {
        val prefix = "$profileUid:"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        check(editor.commit()) { "Could not reset free-model tool permissions." }
    }

    fun revokeConnection(connectionUid: String) {
        val tools = preferences.all.filter { it.key.startsWith("tool:") }
            .values.filterIsInstance<String>().filter { it.startsWith("$connectionUid:") }
        tools.forEach { revoke("", it) }
    }

    private fun globalKey(toolId: String) = key("all-models", toolId)

    private fun key(profileUid: String, toolId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toolId.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$profileUid:$digest"
    }
}
