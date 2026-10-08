package dev.chungjungsoo.gptmobile.data.amazon

import android.util.AtomicFile
import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface AmazonBudgetStore {
    suspend fun read(): AmazonBudgetState
    suspend fun update(change: (AmazonBudgetState) -> AmazonBudgetState): AmazonBudgetState
}

/** The legacy file stays in noBackupFilesDir. The committed singleton row marks import completion. */
internal class AmazonRoomBudgetStore(private val database: ChatDatabaseV2, private val legacy: File) : AmazonBudgetStore {
    override suspend fun read(): AmazonBudgetState = update { it }

    override suspend fun update(change: (AmazonBudgetState) -> AmazonBudgetState): AmazonBudgetState = database.withTransaction {
        val existing = database.amazonDao().budget()
        val current = if (existing == null) {
            withContext(Dispatchers.IO) { readLegacyBudget(legacy) }
        } else {
            decodeBudget(existing.stateJson)
        }
        val next = change(current)
        validateBudget(next)
        database.amazonDao().saveBudget(AmazonBudgetEntity(stateJson = Json.encodeToString(next)))
        next
    }
}

/** Kept for transport/legacy tests; production injects the shared Room store. */
internal class AmazonFileBudgetStore(private val file: File) : AmazonBudgetStore {
    override suspend fun read(): AmazonBudgetState = withContext(Dispatchers.IO) { readLegacyBudget(file) }

    override suspend fun update(change: (AmazonBudgetState) -> AmazonBudgetState): AmazonBudgetState = withContext(Dispatchers.IO) {
        val next = change(readLegacyBudget(file))
        validateBudget(next)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(Json.encodeToString(next).encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
        next
    }
}

internal fun readLegacyBudget(file: File): AmazonBudgetState {
    if (!file.exists() && !File(file.path + ".bak").exists()) return AmazonBudgetState()
    return try {
        AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 16_384)
                output.write(buffer, 0, count)
            }
            decodeBudget(output.toByteArray().decodeToString())
        }
    } catch (_: Exception) {
        throw ledgerUnavailable()
    }
}

private fun decodeBudget(json: String): AmazonBudgetState = try {
    Json.decodeFromString<AmazonBudgetState>(json).also(::validateBudget)
} catch (_: Exception) {
    throw ledgerUnavailable()
}

private fun validateBudget(value: AmazonBudgetState) {
    require(value.usageCount in 0..100)
    require(value.usageDay.isEmpty() || runCatching { LocalDate.parse(value.usageDay) }.isSuccess)
    require(value.nextRequestAt >= 0)
    require(value.marketplaceCooldowns.size <= AmazonFreeMarket.entries.size)
    require(value.marketplaceCooldowns.all { (market, until) -> AmazonFreeMarket.fromDomain(market) != null && until >= 0 })
}

private fun ledgerUnavailable() = AmazonReadException(AmazonReadError.STORAGE_ERROR, "The local Amazon request ledger could not be loaded. No lookup was sent.")
