package dev.chungjungsoo.gptmobile.data.research

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/** Observed workspace status and display settings are not a new recovery action. */
internal object ResearchResumePolicy {
    fun revision(configuredPolicyRevision: String, chat: JsonObject): String {
        val github = chat["github"] as? JsonObject
        val policy = if (github == null) chat else JsonObject(chat + ("github" to JsonObject(github - observationFields)))
        val bytes = "$configuredPolicyRevision:$policy".toByteArray(Charsets.UTF_8)
        return "dispatch-v2:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Upgrade only with current proof of every field that the legacy action captured. */
    fun mayMigrate(previous: String, current: String, currentLegacyRevision: String?): Boolean =
        previous.matches(Regex("[a-f0-9]{64}")) &&
            previous == currentLegacyRevision &&
            current.matches(Regex("dispatch-v2:[a-f0-9]{64}"))

    private val observationFields = setOf("commitSha", "stagedFileCount", "recentCommits", "observedAtMillis")
}
