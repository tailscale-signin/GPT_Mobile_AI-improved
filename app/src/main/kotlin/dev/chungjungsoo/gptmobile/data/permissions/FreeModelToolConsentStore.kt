package dev.chungjungsoo.gptmobile.data.permissions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the explicit data-sharing unlock for free-model tools.
 *
 * V2 grants name a recipient scope. Legacy all-model grants are never authority.
 */
@Singleton
class FreeModelToolConsentStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences by lazy { context.getSharedPreferences("free_model_tool_consent_v2", Context.MODE_PRIVATE) }

    fun revision(): Long = preferences.getLong("revision", 0)

    private fun scope(profile: PlatformV2) = "${profile.uid}|${ProviderPrivacyPolicy.scope(profile)}"

    fun isGranted(profile: PlatformV2, toolId: String): Boolean =
        ProviderPrivacyPolicy.forProfile(profile)?.personalDataAllowed == true && isGranted(scope(profile), toolId)

    fun grant(profile: PlatformV2, toolId: String) {
        check(ProviderPrivacyPolicy.forProfile(profile)?.personalDataAllowed == true) { "This route does not permit personal tools." }
        grant(scope(profile), toolId)
    }

    fun isGranted(profileUid: String, toolId: String): Boolean =
        preferences.getBoolean(key(profileUid, toolId), false)

    @Synchronized
    fun grant(profileUid: String, toolId: String) {
        check(preferences.edit().putBoolean(key(profileUid, toolId), true).putString("tool:" + key(profileUid, toolId), toolId).putLong("revision", revision() + 1).commit()) {
            "Could not save free-model tool permission."
        }
    }

    @Synchronized
    fun revoke(profileUid: String, toolId: String) {
        val digest = key("", toolId).substringAfter(':')
        val editor = preferences.edit()
        preferences.all.keys.filter { it.endsWith(":$digest") && (profileUid.isBlank() || it.removePrefix("tool:").let { owner -> owner.startsWith("$profileUid:") || owner.startsWith("$profileUid|") }) }.forEach(editor::remove)
        editor.putLong("revision", revision() + 1)
        check(editor.commit()) {
            "Could not revoke free-model tool permission."
        }
    }

    @Synchronized
    fun revokeProfile(profileUid: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.removePrefix("tool:").let { key -> key.startsWith("$profileUid:") || key.startsWith("$profileUid|") } }.forEach(editor::remove)
        editor.putLong("revision", revision() + 1)
        check(editor.commit()) { "Could not reset free-model tool permissions." }
    }

    fun revokeConnection(connectionUid: String) {
        val tools = preferences.all.filter { it.key.startsWith("tool:") }
            .values.filterIsInstance<String>().filter { it.startsWith("$connectionUid:") }
        tools.forEach { revoke("", it) }
    }

    /** Legacy entries have no stored tool label; keep their actual owner visible without guessing. */
    fun grants(): List<Pair<String, String>> = preferences.all.entries.filter { it.value == true }.map { (key, _) ->
        val owner = "Profile ${key.substringBefore('|').substringBefore(':')}"
        key to "$owner · ${preferences.getString("tool:$key", null) ?: "tool not recorded"} · data-sharing acknowledgement"
    }

    @Synchronized
    fun revokeGrant(key: String) {
        check(preferences.edit().remove(key).remove("tool:$key").putLong("revision", revision() + 1).commit()) { "Could not revoke free-model tool permission." }
    }

    private fun key(profileUid: String, toolId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toolId.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$profileUid:$digest"
    }
}
