package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val revision: Long = 0
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
        fun initialize(context: Context) {
            application = context.applicationContext
        }
        fun active(): Boolean = application?.getSharedPreferences("device_privacy", Context.MODE_PRIVATE)
            ?.getBoolean("privacy_mode_enabled", false) == true

        fun requireUnprotectedTransport() {
            check(!active()) { "PRIVACY_SERVICE_UNVERIFIED: Choose a verified text profile. Your conversation is saved locally." }
        }
    }

    private val preferences = context.getSharedPreferences("device_privacy", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    @Synchronized
    fun snapshot(): PrivacyModeState = read()

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (read().enabled == enabled) return
        updateRevision { putBoolean("privacy_mode_enabled", enabled) }
    }

    @Synchronized
    fun setCategory(category: PrivacyCategory, protected: Boolean) {
        if ((category in read().categories) == protected) return
        updateRevision { putBoolean("privacy_category_${category.name}", protected) }
    }

    @Synchronized
    private fun updateRevision(change: android.content.SharedPreferences.Editor.() -> Unit = {}) {
        val next = Math.incrementExact(preferences.getLong("privacy_revision", 0))
        val editor = preferences.edit().putLong("privacy_revision", next).apply(change)
        val saved = try {
            editor.commit()
        } finally {
            // SharedPreferences updates its in-memory state even if persistence reports failure.
            mutable.value = read()
        }
        check(saved) { "Privacy settings could not be saved." }
    }

    private fun read(): PrivacyModeState = PrivacyModeState(
        enabled = preferences.getBoolean("privacy_mode_enabled", false),
        categories = PrivacyCategory.entries.filterTo(mutableSetOf()) {
            preferences.getBoolean("privacy_category_${it.name}", true)
        },
        revision = preferences.getLong("privacy_revision", 0)
    )
}
