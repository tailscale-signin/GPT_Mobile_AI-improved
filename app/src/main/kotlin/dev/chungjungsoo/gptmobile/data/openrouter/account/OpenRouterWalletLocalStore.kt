package dev.chungjungsoo.gptmobile.data.openrouter.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Local account associations are explicit, never inferred from matching names or key hashes. */
@Serializable
data class OpenRouterManagementBinding(
    val connectionUid: String,
    val secretRef: String,
    val revision: String,
    val endpoint: String,
    val inferenceRevisions: Set<String>,
    val associated: Boolean = true
)

@Serializable
data class OpenRouterWalletObservation(
    val bindingRevision: String,
    val snapshot: SettingsHomeWalletSnapshot? = null,
    val failure: AccountFailure? = null,
    val cooldownUntil: Long = 0
)

@Serializable
data class OpenRouterWalletEvent(
    val id: String,
    val generationAlias: String?,
    val connectionUid: String,
    val bindingRevision: String,
    val activationRevision: Long,
    val state: String = "accepted"
)

@Serializable
data class OpenRouterWalletLocalState(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
    val activationRevision: Long = 0,
    val bindings: Map<String, OpenRouterManagementBinding> = emptyMap(),
    val observations: Map<String, OpenRouterWalletObservation> = emptyMap(),
    val events: Map<String, OpenRouterWalletEvent> = emptyMap()
)

/** Separate no-backup DataStore: atomic claims and caches cannot enter portable settings exports. */
@Singleton
class OpenRouterWalletLocalStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "openrouter_wallet.preferences_pb") }
    )

    private val key = stringPreferencesKey("wallet_v1")
    private val json = Json { ignoreUnknownKeys = true }
    val state = dataStore.data.map { decode(it[key]) }
    suspend fun snapshot(): OpenRouterWalletLocalState = state.first()
    private fun decode(value: String?) = value?.let { json.decodeFromString<OpenRouterWalletLocalState>(it) } ?: OpenRouterWalletLocalState()

    suspend fun update(transform: (OpenRouterWalletLocalState) -> OpenRouterWalletLocalState) {
        dataStore.edit { preferences -> preferences[key] = json.encodeToString(transform(decode(preferences[key]))) }
    }

    suspend fun setEnabled(enabled: Boolean) = update { current ->
        if (current.enabled == enabled) current else current.copy(enabled = enabled, activationRevision = current.activationRevision + 1)
    }

    suspend fun invalidateConnection(uid: String) = update { current ->
        current.copy(
            bindings = current.bindings.mapValues { (id, binding) -> if (id == uid) binding.copy(associated = false) else binding },
            observations = current.observations - uid
        )
    }

    suspend fun invalidateAssociations() = update { current ->
        current.copy(
            activationRevision = current.activationRevision + 1,
            bindings = current.bindings.mapValues { (_, binding) -> binding.copy(associated = false) },
            observations = emptyMap()
        )
    }

    suspend fun detach(uid: String): String? {
        var reference: String? = null
        update { current ->
            reference = current.bindings[uid]?.secretRef
            current.copy(bindings = current.bindings - uid, observations = current.observations - uid)
        }
        return reference
    }

    /** Startup abandons uncertain claims. Historical events are never put back on the queue. */
    suspend fun abandonUnfinished() = update { current ->
        current.copy(
            events = current.events.mapValues { (_, event) ->
                if (event.state in setOf("accepted", "claimed")) event.copy(state = "abandoned") else event
            }
        )
    }

    suspend fun accept(receipt: OpenRouterReceipt, now: Long = System.currentTimeMillis() / 1000): OpenRouterWalletEvent? {
        var accepted: OpenRouterWalletEvent? = null
        update { current ->
            val binding = current.bindings[receipt.context.connectionUid]
            val alias = receipt.generationId?.let { "${receipt.context.connectionUid}|${receipt.context.credentialRevision}|$it" }
            if (current.events.containsKey(receipt.localId) ||
                alias != null &&
                current.events.values.any { it.generationAlias == alias } ||
                current.events.size >= 100_000
            ) {
                return@update current
            }
            val valid = current.enabled &&
                binding?.associated == true &&
                binding.revision == receipt.context.accountBindingRevision &&
                receipt.context.credentialRevision in binding.inferenceRevisions &&
                receipt.context.endpoint == binding.endpoint &&
                (current.observations[receipt.context.connectionUid]?.cooldownUntil ?: 0) <= now
            val event = OpenRouterWalletEvent(
                receipt.localId,
                alias,
                receipt.context.connectionUid,
                receipt.context.accountBindingRevision.orEmpty(),
                current.activationRevision,
                if (valid) "accepted" else "skipped_scope_or_disabled"
            )
            if (valid) accepted = event
            current.copy(events = current.events + (event.id to event))
        }
        return accepted
    }

    suspend fun claim(event: OpenRouterWalletEvent, now: Long): OpenRouterManagementBinding? {
        var claimed: OpenRouterManagementBinding? = null
        update { current ->
            if (current.events[event.id]?.state != "accepted") return@update current
            val binding = current.bindings[event.connectionUid]
            val valid = current.enabled &&
                current.activationRevision == event.activationRevision &&
                binding?.associated == true &&
                binding.revision == event.bindingRevision &&
                (current.observations[event.connectionUid]?.cooldownUntil ?: 0) <= now
            if (valid) claimed = binding
            current.copy(events = current.events + (event.id to event.copy(state = if (valid) "claimed" else "skipped_scope_or_cooldown")))
        }
        return claimed
    }

    suspend fun publish(binding: OpenRouterManagementBinding, snapshot: SettingsHomeWalletSnapshot?, failure: AccountFailure?, cooldownUntil: Long = 0) = update { current ->
        if (current.bindings[binding.connectionUid] != binding || !binding.associated) return@update current
        val previous = current.observations[binding.connectionUid]?.takeIf { it.bindingRevision == binding.revision }
        current.copy(
            observations = current.observations + (
                binding.connectionUid to OpenRouterWalletObservation(
                    binding.revision,
                    snapshot ?: previous?.snapshot,
                    failure,
                    cooldownUntil
                )
                )
        )
    }

    suspend fun finish(event: OpenRouterWalletEvent, state: String) = update { current ->
        current.copy(events = current.events + (event.id to event.copy(state = state)))
    }
}
