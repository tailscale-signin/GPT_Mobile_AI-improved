package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only unresolved load identities are retained; saved state never attests model readiness. */
@Serializable
internal data class SavedGatewayModelPreparation(
    val version: Int = 1,
    val configurationRevision: String,
    val model: String,
    val operationId: String,
    val updatedAtMillis: Long,
    val receiptState: String? = null
)

internal class GatewayModelPreparationStorageException : IllegalStateException("The saved model preparation could not be read or written.")

@Singleton
class GatewayModelPreparationStore @Inject constructor(private val vault: SecretVault) {
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun read(identity: String, model: String): SavedGatewayModelPreparation? = storage {
        val bytes = vault.read(reference(identity)) ?: return@storage null
        try {
            check(bytes.size <= 4096) { "The saved model preparation is invalid; retain the gateway operation and recheck its storage." }
            json.decodeFromString<SavedGatewayModelPreparation>(bytes.decodeToString()).also {
                check(it.version == 1 && it.configurationRevision == identity && it.model == model && validOperation(it.operationId) && it.updatedAtMillis >= 0) {
                    "The saved model preparation does not match this exact selection; recheck its storage."
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    internal suspend fun write(record: SavedGatewayModelPreparation) = storage {
        require(record.model.isNotBlank() && record.model.length <= 512 && validOperation(record.operationId))
        val bytes = json.encodeToString(record).encodeToByteArray()
        try {
            check(bytes.size <= 4096)
            vault.put(reference(record.configurationRevision), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    internal suspend fun clear(identity: String) = storage { vault.delete(reference(identity)) }

    private suspend fun <T> storage(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GatewayModelPreparationStorageException()
    }

    private fun reference(identity: String): String {
        require(identity.length == 64 && identity.all { it in '0'..'9' || it in 'a'..'f' })
        return "gateway_model_preparation_$identity"
    }

    private fun validOperation(value: String) = value.length in 1..128 && value.all { it.isLetterOrDigit() && it.code < 128 || it in "_-" }
}
