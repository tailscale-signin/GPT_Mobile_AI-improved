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
 * V3 grants name recipients, categories, expiry and a data range. Legacy booleans are never authority.
 */
@Singleton
class FreeModelToolConsentStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences by lazy { context.getSharedPreferences("free_model_tool_consent_v2", Context.MODE_PRIVATE) }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Expiry is a permission change even without an explicit settings mutation. */
    @Synchronized
    fun revision(): Long {
        val now = System.currentTimeMillis()
        val expired = preferences.all.filter { (key, value) ->
            key.startsWith("grant:") && (value !is String || runCatching { json.decodeFromString<PersonalDataGrant>(value).active(now) }.getOrDefault(false).not())
        }.keys
        if (expired.isNotEmpty()) {
            val editor = preferences.edit()
            expired.forEach(editor::remove)
            check(editor.putLong("revision", preferences.getLong("revision", 0) + 1).commit()) { "Could not expire personal-data permission." }
        }
        return preferences.getLong("revision", 0)
    }

    private fun scope(profile: PlatformV2) = "${profile.uid}|${ProviderPrivacyPolicy.scope(profile)}"

    fun isGranted(profile: PlatformV2, toolId: String, chatId: Int? = null): Boolean = grantFor(profile, toolId, chatId) != null

    fun grantFor(profile: PlatformV2, toolId: String, chatId: Int? = null): PersonalDataGrant? {
        if (!profile.enabled || profile.disableAllTools) return null
        val policy = ProviderPrivacyPolicy.forProfile(profile)?.takeIf { it.personalDataAllowed } ?: return null
        revision()
        val keys = listOfNotNull(chatId?.let { grantKey(profile, toolId, it) }, grantKey(profile, toolId, null))
        return keys.firstNotNullOfOrNull { storedKey ->
            preferences.getString(storedKey, null)?.let { raw -> runCatching { json.decodeFromString<PersonalDataGrant>(raw) }.getOrNull() }
                ?.takeIf { grant ->
                    grant.active(System.currentTimeMillis()) &&
                        grant.profileUid == profile.uid &&
                        grant.destinationScope == ProviderPrivacyPolicy.scope(profile) &&
                        grant.toolId == toolId &&
                        grant.recipients == policy.recipients &&
                        grant.categories.isNotEmpty() &&
                        personalDataCategories(toolId).containsAll(grant.categories) &&
                        (grant.chatId == null || grant.chatId == chatId)
                }
        }
    }

    @Synchronized
    fun grant(profile: PlatformV2, toolId: String, chatId: Int? = null, expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60_000L, categories: Set<PersonalDataCategory> = personalDataCategories(toolId), healthRangeDays: Int? = null, rangeStart: Long? = null, rangeEnd: Long? = null, downstreamRecipients: Set<String> = emptySet()) {
        val policy = ProviderPrivacyPolicy.forProfile(profile)
        check(policy?.personalDataAllowed == true) { "This route does not permit personal tools." }
        require(chatId == null || chatId > 0)
        val now = System.currentTimeMillis()
        require(expiresAt > now && expiresAt - now <= (if (chatId == null) 30L else 1L) * 24 * 60 * 60_000L) { "Conversation permission lasts at most 24 hours; profile permission lasts at most 30 days." }
        require(categories.isNotEmpty() && personalDataCategories(toolId).containsAll(categories)) { "The selected categories do not match this tool." }
        require(downstreamRecipients.isEmpty() || personalDataCategory(toolId) == PersonalDataCategory.PRECISE_LOCATION && downstreamRecipients == setOf(LOCATION_MAP_RECIPIENT)) { "A downstream recipient must be explicitly supported by this tool." }
        val allowedCategories = if (PersonalDataCategory.PRECISE_LOCATION in categories) categories + PersonalDataCategory.COARSE_LOCATION else categories
        val grant = PersonalDataGrant(
            profileUid = profile.uid, destinationScope = ProviderPrivacyPolicy.scope(profile),
            toolId = toolId, recipients = policy.recipients, categories = allowedCategories, chatId = chatId, issuedAt = now, expiresAt = expiresAt,
            rangeStart = rangeStart, rangeEnd = rangeEnd, healthRangeDays = healthRangeDays, downstreamRecipients = downstreamRecipients
        )
        require(grant.active(now)) { "Choose a valid data range before allowing this tool." }
        check(
            preferences.edit().putString(grantKey(profile, toolId, chatId), json.encodeToString(PersonalDataGrant.serializer(), grant))
                .putLong("revision", revision() + 1).commit()
        ) { "Could not save personal-data permission." }
    }

    private fun grantKey(profile: PlatformV2, toolId: String, chatId: Int?) = "grant:" + key(scope(profile) + "|chat=" + (chatId ?: "profile"), toolId)

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
        preferences.all.keys.filter { it.endsWith(":$digest") && (profileUid.isBlank() || it.removePrefix("tool:").removePrefix("grant:").let { owner -> owner.startsWith("$profileUid:") || owner.startsWith("$profileUid|") }) }.forEach(editor::remove)
        editor.putLong("revision", revision() + 1)
        check(editor.commit()) {
            "Could not revoke free-model tool permission."
        }
    }

    @Synchronized
    fun revokeProfile(profileUid: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.removePrefix("tool:").removePrefix("grant:").let { key -> key.startsWith("$profileUid:") || key.startsWith("$profileUid|") } }.forEach(editor::remove)
        editor.putLong("revision", revision() + 1)
        check(editor.commit()) { "Could not reset free-model tool permissions." }
    }

    fun revokeConnection(connectionUid: String) {
        val tools = preferences.all.filter { it.key.startsWith("tool:") }.values.filterIsInstance<String>() +
            preferences.all.filter { it.key.startsWith("grant:") }.values.filterIsInstance<String>().mapNotNull { raw ->
                runCatching { json.decodeFromString<PersonalDataGrant>(raw).toolId }.getOrNull()
            }
        tools.filter { it.startsWith("$connectionUid:") }.distinct().forEach { revoke("", it) }
    }

    /** Legacy entries have no stored tool label; keep their actual owner visible without guessing. */
    fun grants(): List<Pair<String, String>> {
        revision()
        return preferences.all.entries.mapNotNull { (key, value) ->
            if (key.startsWith("grant:") && value is String) {
                val grant = runCatching { json.decodeFromString<PersonalDataGrant>(value) }.getOrNull() ?: return@mapNotNull null
                key to "Profile ${grant.profileUid} · ${grant.toolId} · ${grant.categories.sortedBy { it.name }.joinToString()}${grant.healthRangeDays?.let { " · last $it calendar days" }.orEmpty()} · ${grant.chatId?.let { "Chat $it" } ?: "profile"} · expires ${java.time.Instant.ofEpochMilli(grant.expiresAt)}"
            } else if (value == true) {
                val owner = "Profile ${key.substringBefore('|').substringBefore(':')}"
                key to "$owner · ${preferences.getString("tool:$key", null) ?: "tool not recorded"} · legacy permission; review required"
            } else {
                null
            }
        }
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
