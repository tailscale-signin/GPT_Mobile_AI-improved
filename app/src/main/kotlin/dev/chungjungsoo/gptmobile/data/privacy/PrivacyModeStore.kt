package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class UnverifiedServiceTransport : IllegalStateException("$CODE: Choose a verified text profile. Your conversation is saved locally.") {
    companion object {
        const val CODE = "PRIVACY_SERVICE_UNVERIFIED"
    }
}

/** Categories are enabled by default, independently of the OFF-by-default master switch. */
enum class PrivacyCategory(val title: String, val description: String) {
    IDENTITIES("Names & identities", "People, aliases and personal identifiers"),
    CONTACTS("Contact details", "Email, telephone numbers and usernames"),
    LOCATIONS("Addresses & locations", "Private addresses and location coordinates"),
    HEALTH("Health & family", "Health and sensitive personal context"),
    FINANCES("Financial details", "Contracts, amounts and financial accounts"),
    DOCUMENT_IDS("Identification numbers", "Government, employee and account identifiers"),
    CONFIDENTIAL_WORK("Clients & projects", "Confidential companies, projects and documents"),
    PRIVATE_DATES("Private dates", "Appointments, milestones and confidential deadlines"),
    SECRETS("Credentials & secrets", "Passwords, API keys, tokens and certificates"),
    NETWORK("Device & network", "IP addresses, device identifiers and local paths"),
    CUSTOM("Custom private terms", "Terms explicitly marked private by the user")
}

data class PrivacyModeState(
    val enabled: Boolean = false,
    val categories: Set<PrivacyCategory> = PrivacyCategory.entries.toSet(),
    val revision: Long = 0,
    val protectionRevision: Long = revision
) {
    fun protects(category: PrivacyCategory): Boolean = enabled && category in categories
}

/**
 * App-wide local settings. No personal values or replacement mappings are stored here.
 * Other parts of the pipeline MUST separately enforce outbound redaction and ZDR admission.
 */
@Singleton
class PrivacyModeStore @Inject constructor(
    @ApplicationContext context: Context,
    internal val verifier: PrivacyProviderVerifier,
    private val vault: SecretVault
) {
    private val privateTermsMutex = Mutex()

    internal suspend fun privateTerms(): List<String> = privateTermsMutex.withLock {
        vault.read("privacy_custom_terms")?.let { bytes ->
            try {
                bytes.decodeToString().lines().filter(String::isNotBlank)
            } finally {
                bytes.fill(0)
            }
        }.orEmpty()
    }

    internal suspend fun savePrivateTerms(text: String) {
        require(text.length <= 8192)
        privateTermsMutex.withLock {
            // Invalidate earlier masks before a write, including a write with an uncertain outcome.
            updateRevision()
            val bytes = text.encodeToByteArray()
            try {
                vault.put("privacy_custom_terms", bytes)
            } finally {
                bytes.fill(0)
                // A snapshot taken while this write was pending cannot authorize a later dispatch.
                updateRevision()
            }
        }
    }

    companion object {
        @Volatile private var application: Context? = null

        @Volatile private var requestedPolicy: PrivacyModeState? = null
        fun initialize(context: Context) {
            application = context.applicationContext
            requestedPolicy = null
        }

        /** The feature switch is availability, not authority to change any conversation. */
        fun active(): Boolean = ConversationPrivacyContext.currentOrNull()?.enabled == true

        /** Read-only diagnostic observation; unknown is not silently reported as disabled. */
        fun auditPolicySnapshot(): PrivacyModeState? {
            requestedPolicy?.let { return it }
            val preferences = application?.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) ?: return null
            repeat(3) {
                val revision = preferences.getLong("privacy_revision", 0)
                val enabled = preferences.getBoolean("privacy_mode_enabled", false)
                val categories = PrivacyCategory.entries.filterTo(mutableSetOf()) {
                    preferences.getBoolean("privacy_category_${it.name}", true)
                }
                if (preferences.getLong("privacy_revision", 0) == revision) {
                    // A newly queued choice also supersedes a consistent older disk observation.
                    return requestedPolicy ?: PrivacyModeState(enabled, categories, revision, preferences.getLong("privacy_protection_revision", revision))
                }
            }
            return null
        }

        fun requireUnprotectedTransport() {
            if (active()) throw UnverifiedServiceTransport()
        }
    }

    private val preferences = context.getSharedPreferences("device_privacy", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val stateLock = Any()

    @Volatile private var requested: PrivacyModeState? = null
    private val writer = PrivacySettingsWriter(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        persist = { persistLatest() },
        onResult = { change, saved ->
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                "PrivacyMode",
                "PRIVACY_SETTINGS_SAVE · control=${change.key} · saved=$saved · enabled=${snapshot().enabled}",
                if (saved) "I" else "W"
            )
        }
    )
    val updates = writer.state

    /** UI intent takes effect before disk I/O; readers never wait for the persistence monitor. */
    fun snapshot(): PrivacyModeState = requested ?: readConsistent()

    /** Availability changes do not invalidate a private chat's prepared mask or unlock it. */
    fun effectiveSnapshot(privacyLocked: Boolean = active()): PrivacyModeState {
        val settings = snapshot()
        return settings.copy(enabled = privacyLocked, revision = settings.protectionRevision)
    }

    private fun readConsistent(): PrivacyModeState {
        while (true) {
            val revision = preferences.getLong("privacy_revision", 0)
            val observed = read()
            if (revision == observed.revision && revision == preferences.getLong("privacy_revision", 0)) return observed
        }
    }

    fun requestEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            publish(protectionChanged = false) { it.copy(enabled = enabled) }
            writer.submit(PrivacySettingChange.Enabled(enabled))
        }
    }

    fun requestCategory(category: PrivacyCategory, protected: Boolean) {
        synchronized(stateLock) {
            publish { it.copy(categories = if (protected) it.categories + category else it.categories - category) }
            writer.submit(PrivacySettingChange.Category(category, protected))
        }
    }

    fun retrySaving() = writer.retry()

    fun setEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            if (snapshot().enabled == enabled && requested == null) return
            publish(protectionChanged = false) { it.copy(enabled = enabled) }
        }
        persistLatest()
    }

    fun setCategory(category: PrivacyCategory, protected: Boolean) {
        synchronized(stateLock) {
            if ((category in snapshot().categories) == protected && requested == null) return
            publish { it.copy(categories = if (protected) it.categories + category else it.categories - category) }
        }
        persistLatest()
    }

    private fun updateRevision() {
        synchronized(stateLock) { publish { it } }
        persistLatest()
    }

    /** Caller holds only the short state lock, never the disk-write monitor. */
    private fun publish(protectionChanged: Boolean = true, change: (PrivacyModeState) -> PrivacyModeState) {
        val current = snapshot()
        val next = change(current).copy(
            revision = Math.incrementExact(current.revision),
            protectionRevision = if (protectionChanged) Math.incrementExact(current.protectionRevision) else current.protectionRevision
        )
        // Transport checks and diagnostics share the complete effective policy before disk I/O.
        requestedPolicy = next
        requested = next
        mutable.value = next
    }

    @Synchronized
    private fun persistLatest() {
        val target = snapshot()
        val editor = preferences.edit()
            .putLong("privacy_revision", target.revision)
            .putLong("privacy_protection_revision", target.protectionRevision)
            .putBoolean("privacy_mode_enabled", target.enabled)
        PrivacyCategory.entries.forEach { editor.putBoolean("privacy_category_${it.name}", it in target.categories) }
        check(editor.commit()) { "Privacy settings could not be saved." }
        synchronized(stateLock) {
            // A completed older write cannot undo a newer tap or revive its authorization.
            if (requested == target) {
                requested = null
                requestedPolicy = null
                mutable.value = readConsistent()
            }
        }
    }

    private fun read(): PrivacyModeState = PrivacyModeState(
        enabled = preferences.getBoolean("privacy_mode_enabled", false),
        categories = PrivacyCategory.entries.filterTo(mutableSetOf()) {
            preferences.getBoolean("privacy_category_${it.name}", true)
        },
        revision = preferences.getLong("privacy_revision", 0),
        protectionRevision = preferences.getLong("privacy_protection_revision", preferences.getLong("privacy_revision", 0))
    )
}
